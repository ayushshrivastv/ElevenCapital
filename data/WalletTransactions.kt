package com.elevencapital.app.data

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.wallet.canonicalEthereumAddress
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.wallet.isSolanaPublicKey
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale
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

enum class WalletActivityStatus { OK, PARTIAL, UNAVAILABLE }
enum class WalletActivityChain(val displayName: String) {
    SOLANA("Solana"), SOLANA_DEVNET("Solana Devnet"), ARBITRUM("Arbitrum"), ETHEREUM("Ethereum"),
}
enum class WalletTransactionDirection { RECEIVE, SEND }
enum class WalletUsdBasis { CURRENT_SPOT }

data class WalletActivityWallet(val chain: WalletActivityChain, val address: String)

data class WalletTransaction(
    val id: String,
    val chain: WalletActivityChain,
    val transactionId: String,
    val timestamp: Instant,
    val direction: WalletTransactionDirection,
    val assetSymbol: String,
    val amount: BigDecimal,
    val valueUsd: BigDecimal?,
    val usdBasis: WalletUsdBasis?,
    val counterparty: String?,
)

data class WalletTransactionsSnapshot(
    val wallets: List<WalletActivityWallet>,
    val status: WalletActivityStatus,
    val observedAt: Instant,
    val transactions: List<WalletTransaction>,
)

enum class CompletedPurchaseSide { BUY, SELL }
enum class PurchaseUsdBasis { QUOTE_ESTIMATE, STABLECOIN_EXACT }

data class CompletedPurchaseTransaction(
    val id: String,
    val transactionId: String,
    val relatedTransactionIds: List<String>,
    val timestamp: Instant,
    val timestampBasis: String,
    val side: CompletedPurchaseSide?,
    val stockId: String,
    val fromAssetId: String,
    val inputAmount: BigDecimal,
    val receivedAmount: BigDecimal?,
    val valueUsd: BigDecimal?,
    val usdBasis: PurchaseUsdBasis?,
)

data class CompletedPurchasesSnapshot(
    val observedAt: Instant,
    val hasMore: Boolean,
    val transactions: List<CompletedPurchaseTransaction>,
)

/** The two SDK wallet addresses are the only identities sent to the public read-only activity API. */
fun activityWallets(wallets: List<UserWallet>): List<WalletActivityWallet> = buildList {
    wallets.firstOrNull { it.chain == WalletChain.SOLANA }?.let { wallet ->
        if (isSolanaPublicKey(wallet.address)) add(WalletActivityWallet(WalletActivityChain.SOLANA, wallet.address))
    }
    wallets.firstOrNull { it.chain == WalletChain.ETHEREUM }?.let { wallet ->
        val address = canonicalEthereumAddress(wallet.address)?.lowercase(Locale.ROOT)
        if (address != null) {
            add(WalletActivityWallet(WalletActivityChain.ARBITRUM, address))
            add(WalletActivityWallet(WalletActivityChain.ETHEREUM, address))
        }
    }
}

/** Strictly accepts activity belonging to the exact verified addresses requested by this session. */
object WalletTransactionsParser {
    fun parse(json: JSONObject, requested: List<WalletActivityWallet>, now: Instant = Instant.now()): WalletTransactionsSnapshot {
        require(json.keys().asSequence().toSet() == setOf("schemaVersion", "scope", "wallets", "status", "observedAt", "transactions"))
        require(json.get("schemaVersion") == 1 && json.get("scope") == "wallet-transactions")
        val echoed = json.getJSONArray("wallets").let { rows ->
            require(rows.length() <= 3)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.keys().asSequence().toSet() == setOf("chain", "address"))
                val chain = WalletActivityChain.valueOf(requiredString(row, "chain"))
                val address = validatedAddress(chain, requiredString(row, "address"))
                WalletActivityWallet(chain, address)
            }
        }
        require(echoed == requested && echoed.distinctBy(WalletActivityWallet::chain).size == echoed.size) {
            "Wallet activity belongs to a different wallet session."
        }
        val status = when (requiredString(json, "status")) {
            "ok" -> WalletActivityStatus.OK
            "partial" -> WalletActivityStatus.PARTIAL
            "unavailable" -> WalletActivityStatus.UNAVAILABLE
            else -> error("Unknown wallet activity status")
        }
        val observedAt = recentInstant(requiredString(json, "observedAt"), now)
        val rows = json.getJSONArray("transactions")
        require(rows.length() <= 128)
        val transactions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "chain", "transactionId", "timestamp", "direction",
                "assetSymbol", "amount", "valueUsd", "usdBasis", "counterparty"))
            val chain = WalletActivityChain.valueOf(requiredString(row, "chain"))
            require(echoed.any { it.chain == chain })
            val id = safeId(requiredString(row, "id"))
            val transactionId = transactionId(requiredString(row, "transactionId"), chain)
            val timestamp = pastInstant(requiredString(row, "timestamp"), now)
            val direction = WalletTransactionDirection.valueOf(requiredString(row, "direction"))
            val symbol = requiredString(row, "assetSymbol")
            require(symbol.length in 1..30 && symbol.all { it.isLetterOrDigit() || it in "._-…" })
            val amount = decimal(requiredString(row, "amount"), positive = true)
            val usd = nullableDecimal(row, "valueUsd")
            val usdBasis = when (nullableString(row, "usdBasis")) {
                null -> null
                "current_spot" -> WalletUsdBasis.CURRENT_SPOT
                else -> error("Unknown USD valuation basis")
            }
            require((usd == null) == (usdBasis == null))
            val counterparty = nullableString(row, "counterparty")?.let { validatedAddress(chain, it) }
            WalletTransaction(id, chain, transactionId, timestamp, direction, symbol, amount, usd, usdBasis, counterparty)
        }
        require(transactions.map(WalletTransaction::id).toSet().size == transactions.size)
        return WalletTransactionsSnapshot(echoed, status, observedAt, transactions)
    }
}

/** Parses only completed, authenticated trade history; no quote or unconfirmed route becomes activity. */
object CompletedPurchasesParser {
    fun parse(json: JSONObject, now: Instant = Instant.now()): CompletedPurchasesSnapshot {
        require(json.keys().asSequence().toSet() == setOf("schemaVersion", "scope", "observedAt", "hasMore", "transactions"))
        require(json.get("schemaVersion") == 1 && json.get("scope") == "completed-purchases")
        val observedAt = recentInstant(requiredString(json, "observedAt"), now)
        val hasMore = json.get("hasMore").also { require(it is Boolean) } as Boolean
        val rows = json.getJSONArray("transactions")
        require(rows.length() <= 128)
        val transactions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id", "transactionId", "relatedTransactionIds", "timestamp",
                "timestampBasis", "side", "stockId", "fromAssetId", "inputAmount", "receivedAmount", "valueUsd", "usdBasis"))
            val id = safeId(requiredString(row, "id"))
            val transactionId = anyTransactionId(requiredString(row, "transactionId"))
            val related = row.getJSONArray("relatedTransactionIds").let { items ->
                require(items.length() <= 8)
                (0 until items.length()).map { anyTransactionId(items.get(it).also { value -> require(value is String) } as String) }
            }
            require(related.distinct().size == related.size)
            val timestamp = pastInstant(requiredString(row, "timestamp"), now)
            val timestampBasis = requiredString(row, "timestampBasis")
            require(timestampBasis == "completion_observed")
            val side = nullableString(row, "side")?.let(CompletedPurchaseSide::valueOf)
            val stockId = requiredString(row, "stockId")
            require(STOCK_ID.matches(stockId))
            val fromAssetId = requiredString(row, "fromAssetId")
            require(fromAssetId.length in 5..100 && fromAssetId.all { it.isLetterOrDigit() || it in "_:.-" })
            val inputAmount = decimal(requiredString(row, "inputAmount"), positive = true)
            val receivedAmount = nullableDecimal(row, "receivedAmount")
            val usd = nullableDecimal(row, "valueUsd")
            val usdBasis = when (nullableString(row, "usdBasis")) {
                null -> null
                "quote_estimate" -> PurchaseUsdBasis.QUOTE_ESTIMATE
                "stablecoin_exact" -> PurchaseUsdBasis.STABLECOIN_EXACT
                else -> error("Unknown trade USD valuation basis")
            }
            require((usd == null) == (usdBasis == null))
            CompletedPurchaseTransaction(id, transactionId, related, timestamp, timestampBasis, side,
                stockId, fromAssetId, inputAmount, receivedAmount, usd, usdBasis)
        }
        require(transactions.map(CompletedPurchaseTransaction::id).toSet().size == transactions.size)
        return CompletedPurchasesSnapshot(observedAt, hasMore, transactions)
    }
}

private val STOCK_ID = Regex("(?:backed|backpack|prestocks):[A-Za-z0-9._-]+")
private val DECIMAL = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
private val EVM_TRANSACTION = Regex("0x[0-9a-fA-F]{64}")

private fun decimal(raw: String, positive: Boolean = false): BigDecimal {
    require(raw.length <= 120 && DECIMAL.matches(raw))
    return BigDecimal(raw).also { require(!positive || it.signum() > 0) }
}

private fun nullableDecimal(row: JSONObject, key: String): BigDecimal? =
    nullableString(row, key)?.let { decimal(it) }

private fun nullableString(row: JSONObject, key: String): String? {
    val value = row.get(key)
    if (value === JSONObject.NULL) return null
    require(value is String)
    return value
}

private fun requiredString(row: JSONObject, key: String): String = row.get(key).let {
    require(it is String)
    it
}

private fun safeId(value: String): String {
    require(value.length in 1..200 && value.none { it.isWhitespace() || it.isISOControl() })
    return value
}

private fun validatedAddress(chain: WalletActivityChain, address: String): String = when (chain) {
    WalletActivityChain.SOLANA, WalletActivityChain.SOLANA_DEVNET -> address.also { require(isSolanaPublicKey(it)) }
    WalletActivityChain.ARBITRUM, WalletActivityChain.ETHEREUM -> address.also {
        require(canonicalEthereumAddress(it)?.lowercase(Locale.ROOT) == it)
    }
}

private fun transactionId(value: String, chain: WalletActivityChain): String = value.also {
    require(when (chain) {
        WalletActivityChain.SOLANA, WalletActivityChain.SOLANA_DEVNET -> isCanonicalSolanaSignature(it)
        WalletActivityChain.ARBITRUM, WalletActivityChain.ETHEREUM -> EVM_TRANSACTION.matches(it)
    })
}

private fun anyTransactionId(value: String): String = value.also {
    require(EVM_TRANSACTION.matches(it) || isCanonicalSolanaSignature(it))
}

private fun recentInstant(raw: String, now: Instant): Instant = Instant.parse(raw).also {
    require(!it.isAfter(now.plusSeconds(60)) && !it.isBefore(now.minus(Duration.ofMinutes(5))))
}

private fun pastInstant(raw: String, now: Instant): Instant = Instant.parse(raw).also {
    require(!it.isAfter(now.plusSeconds(60)))
}

class WalletTransactionsClient internal constructor(baseUrl: String, http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now) {
    private val transport = ActivityHttp(baseUrl, http)

    suspend fun activity(wallets: List<WalletActivityWallet>): WalletTransactionsSnapshot {
        require(wallets.isNotEmpty() && wallets.size <= 3 && wallets.map(WalletActivityWallet::chain).distinct().size == wallets.size)
        wallets.forEach { require(validatedAddress(it.chain, it.address) == it.address) }
        val body = JSONObject().put("wallets", JSONArray().apply {
            wallets.forEach { put(JSONObject().put("chain", it.chain.name).put("address", it.address)) }
        })
        val request = Request.Builder().url(transport.url("/v1/wallet/transactions"))
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        return WalletTransactionsParser.parse(transport.execute(request), wallets, now())
    }
}

class CompletedPurchasesClient internal constructor(baseUrl: String,
    private val accessToken: suspend (String) -> String, http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now) {
    private val transport = ActivityHttp(baseUrl, http)

    suspend fun activity(userId: String): CompletedPurchasesSnapshot {
        require(userId.length in 1..256 && userId.none { it.isWhitespace() || it.isISOControl() })
        val token = accessToken(userId)
        require(token.length in 32..8_192 && token.count { it == '.' } == 2 &&
            token.all { it.isLetterOrDigit() || it in "-_.~" })
        val request = Request.Builder().url(transport.url("/v1/purchase/activity"))
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token").get().build()
        return CompletedPurchasesParser.parse(transport.execute(request), now())
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal class ActivityHttp(baseUrl: String, http: OkHttpClient) {
    private val base = baseUrl.trimEnd('/')
    private val client = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS).writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).cache(null).build()

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    fun url(path: String): String = "$base$path"

    suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (it.code != 200) throw IOException("Activity returned HTTP ${it.code}.")
                        require(it.header("Content-Type")?.substringBefore(';')?.trim() == "application/json")
                        val body = requireNotNull(it.body)
                        require(body.contentLength() <= MAX_RESPONSE_BYTES)
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val chunk = ByteArray(8_192)
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                require(output.size() + count <= MAX_RESPONSE_BYTES)
                                output.write(chunk, 0, count)
                            }
                        }
                        JSONObject(output.toString(Charsets.UTF_8.name()))
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
