package com.elevencapital.app.data

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.core.stock.StockId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

enum class WalletPortfolioStatus { OK, PARTIAL, UNAVAILABLE }
enum class WalletNetworkStatus { OK, UNAVAILABLE }

data class LiveWalletHolding(val stockId: StockId, val quantity: BigDecimal, val valueUsd: BigDecimal?)
data class LiveWalletTokenHolding(
    val chain: WalletChain,
    val assetId: String,
    val symbol: String,
    val quantity: BigDecimal,
    val valueUsd: BigDecimal?,
    val unitPriceUsd: BigDecimal?,
)
data class LiveWalletNetwork(val chain: WalletChain, val status: WalletNetworkStatus, val observedAt: Instant?)

/** A missing valuation is never an empty wallet. Only an OK observation exposes a total. */
data class LiveWalletPortfolio(
    val status: WalletPortfolioStatus,
    val balanceUsd: BigDecimal?,
    val holdings: List<LiveWalletHolding>,
    val holdingsComplete: Boolean,
    val unpricedAssets: Int,
    val networks: List<LiveWalletNetwork>,
    val receivedAt: Instant,
    val message: String?,
    val tokenHoldings: List<LiveWalletTokenHolding> = emptyList(),
)

class WalletPortfolioHttpException(val statusCode: Int) : IOException("Wallet balance service returned HTTP $statusCode.")

/** Public addresses stay in the POST body, never URL parameters, credentials, or diagnostics. */
class LiveWalletPortfolioClient internal constructor(
    baseUrl: String,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
) {
    private val base = baseUrl.trimEnd('/')
    private val finiteHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .retryOnConnectionFailure(true)
        .build()

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun portfolio(wallets: List<UserWallet>): LiveWalletPortfolio {
        // Existing Privy accounts may have multiple embedded wallets on each supported chain.
        require(wallets.size in 2..10 && wallets.map { it.chain }.toSet() == WalletChain.entries.toSet())
        require(wallets.groupBy { it.chain }.values.all { it.size in 1..5 })
        val identities = wallets.map { it.chain to if (it.chain == WalletChain.ETHEREUM) it.address.lowercase() else it.address }
        require(identities.toSet().size == wallets.size) { "Duplicate wallet address." }
        val accounts = JSONArray()
        wallets.sortedBy { it.chain.ordinal }.forEach { wallet ->
            require(when (wallet.chain) {
                WalletChain.SOLANA -> wallet.address.matches(Regex("[1-9A-HJ-NP-Za-km-z]{32,44}"))
                WalletChain.ETHEREUM -> wallet.address.matches(Regex("0x[0-9a-fA-F]{40}"))
            }) { "Invalid wallet address." }
            accounts.put(JSONObject().put("chain", wallet.chain.name).put("address", wallet.address))
        }
        val body = JSONObject().put("wallets", accounts).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(base + "/v1/wallet/portfolio")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(body).build()
        val response = request(request)
        return LiveWalletPortfolioParser.portfolio(response, now())
    }

    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = finiteHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (it.code != 200) throw WalletPortfolioHttpException(it.code)
                        require(it.header("Content-Type")?.substringBefore(';')?.trim() == "application/json") {
                            "Unexpected wallet balance format."
                        }
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= MAX_RESPONSE_BYTES) { "Wallet balance response too large." }
                        val bytes = body.byteStream().use { stream ->
                            val result = ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                require(result.size() + count <= MAX_RESPONSE_BYTES) { "Wallet balance response too large." }
                                result.write(chunk, 0, count)
                            }
                            result.toByteArray()
                        }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(json)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private companion object { const val MAX_RESPONSE_BYTES = 256 * 1024 }
}

/** Strict wire validation before private account data enters presentation state. */
object LiveWalletPortfolioParser {
    private val maximumAge = Duration.ofMinutes(5)
    private val clockSkew = Duration.ofSeconds(30)
    private val decimalPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    private val stockIdPattern = Regex("(?:backed|backpack|prestocks):[A-Za-z0-9._-]{1,160}")
    private val assetIdPattern = Regex("(?:SOLANA:(?:native|[1-9A-HJ-NP-Za-km-z]{32,44})|ETHEREUM:(?:native|0x[0-9a-f]{40}))")
    private val tokenSymbolPattern = Regex("[A-Z0-9]{1,12}")

    fun portfolio(source: JSONObject, now: Instant = Instant.now()): LiveWalletPortfolio {
        require(source.integer("schemaVersion", 1) == 1)
        require(source.string("scope") == "supported-wallet-assets" && source.string("currency") == "USD")
        val status = when (source.string("status")) {
            "ok" -> WalletPortfolioStatus.OK
            "partial" -> WalletPortfolioStatus.PARTIAL
            "unavailable" -> WalletPortfolioStatus.UNAVAILABLE
            else -> throw IllegalArgumentException("Unknown wallet balance status.")
        }
        val receivedAt = timestamp(source.string("receivedAt"), now)
        val networkRows = source.getJSONArray("networks")
        require(networkRows.length() == WalletChain.entries.size)
        val networks = (0 until networkRows.length()).map { index ->
            val network = networkRows.getJSONObject(index)
            val chain = WalletChain.valueOf(network.string("chain"))
            val networkStatus = when (network.string("status")) {
                "ok" -> WalletNetworkStatus.OK
                "unavailable" -> WalletNetworkStatus.UNAVAILABLE
                else -> throw IllegalArgumentException("Unknown wallet network status.")
            }
            val observedAt = network.nullableString("observedAt")?.let { timestamp(it, now) }
            require((networkStatus == WalletNetworkStatus.OK) == (observedAt != null))
            require(observedAt == null || !observedAt.isAfter(receivedAt.plus(clockSkew)))
            LiveWalletNetwork(chain, networkStatus, observedAt)
        }
        require(networks.map { it.chain }.toSet() == WalletChain.entries.toSet())
        val rows = source.getJSONArray("holdings")
        require(rows.length() <= 512)
        val holdings = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.string("stockId")
            require(stockIdPattern.matches(id))
            val quantity = requireNotNull(row.decimal("quantity"))
            require(quantity.signum() > 0) { "Only positive stock holdings are permitted." }
            LiveWalletHolding(StockId(id), quantity, row.decimal("valueUsd"))
        }
        require(holdings.map { it.stockId }.toSet().size == holdings.size) { "Duplicate stock holding." }
        val tokenRows = source.getJSONArray("tokenHoldings")
        require(tokenRows.length() <= 200)
        val tokenHoldings = (0 until tokenRows.length()).map { index ->
            val row = tokenRows.getJSONObject(index)
            val chain = WalletChain.valueOf(row.string("chain"))
            val assetId = row.string("assetId")
            val symbol = row.string("symbol")
            require(assetIdPattern.matches(assetId) && assetId.startsWith("${chain.name}:") && tokenSymbolPattern.matches(symbol))
            val quantity = requireNotNull(row.decimal("quantity"))
            require(quantity.signum() > 0) { "Only positive token holdings are permitted." }
            val value = row.decimal("valueUsd")
            val price = row.decimal("unitPriceUsd")
            require((value == null) == (price == null))
            require(price == null || (price.signum() > 0 && quantity.multiply(price).compareTo(requireNotNull(value)) == 0)) {
                "Token valuation must match observed unit price."
            }
            LiveWalletTokenHolding(chain, assetId, symbol, quantity, value, price)
        }
        require(tokenHoldings.map { it.assetId }.toSet().size == tokenHoldings.size) { "Duplicate token holding." }
        val balance = source.decimal("balanceUsd")
        val holdingsComplete = source.get("holdingsComplete").also { require(it is Boolean) } as Boolean
        require(!holdingsComplete || networks.all { it.status == WalletNetworkStatus.OK })
        val unpricedAssets = source.integer("unpricedAssets", 10_000)
        val message = source.nullableString("message")
        require(message == null || (message.isNotBlank() && message.length <= 512 && message.none { it.isISOControl() }))
        if (status == WalletPortfolioStatus.OK) {
            require(balance != null && holdingsComplete && unpricedAssets == 0 && networks.all { it.status == WalletNetworkStatus.OK })
            require(holdings.all { it.valueUsd != null } && tokenHoldings.all { it.valueUsd != null })
            val stockTotal = holdings.fold(BigDecimal.ZERO) { total, holding -> total + requireNotNull(holding.valueUsd) }
            val tokenTotal = tokenHoldings.fold(BigDecimal.ZERO) { total, holding -> total + requireNotNull(holding.valueUsd) }
            require(balance.compareTo(stockTotal + tokenTotal) == 0) { "Total must equal valued holdings." }
        } else {
            require(balance == null) { "An incomplete wallet balance must not be reported as zero." }
        }
        return LiveWalletPortfolio(status, balance, holdings, holdingsComplete, unpricedAssets, networks, receivedAt, message, tokenHoldings)
    }

    private fun timestamp(value: String, now: Instant): Instant {
        require(value.length in 20..40)
        val parsed = Instant.parse(value)
        require(!parsed.isAfter(now.plus(clockSkew)) && !parsed.isBefore(now.minus(maximumAge))) {
            "Wallet observation timestamp is not current."
        }
        return parsed
    }

    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String) { "Expected a string field." }
        return value
    }

    private fun JSONObject.nullableString(key: String): String? {
        val value = get(key)
        if (value === JSONObject.NULL) return null
        require(value is String) { "Expected a nullable string field." }
        return value
    }

    private fun JSONObject.decimal(key: String): BigDecimal? {
        val value = nullableString(key) ?: return null
        require(value.length <= 100 && decimalPattern.matches(value)) { "Expected a nonnegative exact decimal string." }
        return BigDecimal(value)
    }

    private fun JSONObject.integer(key: String, maximum: Int): Int {
        val value = get(key)
        require(value is Int || value is Long) { "Expected an integer field." }
        val number = (value as Number).toLong()
        require(number in 0..maximum.toLong())
        return number.toInt()
    }
}
