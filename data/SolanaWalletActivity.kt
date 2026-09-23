package com.elevencapital.app.data

import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.wallet.isSolanaPublicKey
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
import org.json.JSONObject

enum class SolanaActivityStatus { OK, PARTIAL, UNAVAILABLE }
enum class SolanaActivityDirection { RECEIVE, SEND }

data class SolanaWalletTransaction(
    val signature: String,
    val timestamp: Instant,
    val direction: SolanaActivityDirection,
    val assetSymbol: String,
    val mint: String?,
    val amount: BigDecimal,
    /** Current spot USD estimate. Null when no fresh public price exists. */
    val valueUsd: BigDecimal?,
    val counterparty: String?,
)

/** The response echoes the requested public address; the caller must bind it to the verified Privy wallet. */
data class SolanaWalletActivity(
    val walletAddress: String,
    val status: SolanaActivityStatus,
    val observedAt: Instant,
    val transactions: List<SolanaWalletTransaction>,
)

class SolanaWalletActivityHttpException(val statusCode: Int) : IOException("Wallet activity returned HTTP $statusCode.")

/** Read-only chain history. It never signs, broadcasts, or stores wallet addresses. */
class SolanaWalletActivityClient internal constructor(
    baseUrl: String,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
) {
    private val base = baseUrl.trimEnd('/')
    private val finiteHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun activity(walletAddress: String): SolanaWalletActivity {
        require(isSolanaPublicKey(walletAddress)) { "Invalid Solana wallet address." }
        val body = JSONObject().put("walletAddress", walletAddress).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$base/v1/wallet/activity")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .post(body).build()
        return SolanaWalletActivityParser.activity(execute(request), walletAddress, now())
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = finiteHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code != 200) throw SolanaWalletActivityHttpException(it.code)
                        require(it.header("Content-Type")?.substringBefore(';')?.trim() == "application/json")
                        val stream = requireNotNull(it.body)
                        require(stream.contentLength() <= MAX_RESPONSE_BYTES)
                        val bytes = stream.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val chunk = ByteArray(8_192)
                            while (true) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                require(output.size() + count <= MAX_RESPONSE_BYTES)
                                output.write(chunk, 0, count)
                            }
                            output.toByteArray()
                        }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private companion object { const val MAX_RESPONSE_BYTES = 128 * 1024 }
}

/** Rejects malformed or mismatched responses before displaying financial activity. */
object SolanaWalletActivityParser {
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val amountPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")

    fun activity(source: JSONObject, requestedAddress: String, now: Instant = Instant.now()): SolanaWalletActivity {
        require(source.keys().asSequence().toSet() == setOf("schemaVersion", "scope", "walletAddress", "status", "observedAt", "transactions"))
        require(source.get("schemaVersion") == 1)
        require(source.string("scope") == "solana-wallet-activity")
        val address = source.string("walletAddress")
        require(isSolanaPublicKey(address) && address == requestedAddress) { "Wallet activity belongs to another address." }
        val status = when (source.string("status")) {
            "ok" -> SolanaActivityStatus.OK
            "partial" -> SolanaActivityStatus.PARTIAL
            "unavailable" -> SolanaActivityStatus.UNAVAILABLE
            else -> throw IllegalArgumentException("Unknown activity state.")
        }
        val observedAt = Instant.parse(source.string("observedAt"))
        require(!observedAt.isAfter(now.plusSeconds(60)) && !observedAt.isBefore(now.minus(Duration.ofMinutes(5))))
        val rows = source.getJSONArray("transactions")
        require(rows.length() <= 64)
        val transactions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("signature", "timestamp", "direction", "assetSymbol", "mint", "amount", "valueUsd", "counterparty"))
            val signature = row.string("signature")
            require(isCanonicalSolanaSignature(signature))
            val timestamp = Instant.parse(row.string("timestamp"))
            require(!timestamp.isAfter(now.plusSeconds(60)))
            val direction = when (row.string("direction")) {
                "RECEIVE" -> SolanaActivityDirection.RECEIVE
                "SEND" -> SolanaActivityDirection.SEND
                else -> throw IllegalArgumentException("Unknown transfer direction.")
            }
            val mint = row.nullableString("mint")
            require(mint == null || isSolanaPublicKey(mint))
            val symbol = row.string("assetSymbol")
            require(symbol == when (mint) {
                null -> "SOL"
                USDC_MINT -> "USDC"
                else -> "${mint.take(4)}…${mint.takeLast(4)}"
            })
            val rawAmount = row.string("amount")
            require(rawAmount.length <= 100 && amountPattern.matches(rawAmount))
            val amount = BigDecimal(rawAmount)
            require(amount.signum() > 0)
            val rawValueUsd = row.nullableString("valueUsd")
            require(rawValueUsd == null || (rawValueUsd.length <= 120 && amountPattern.matches(rawValueUsd)))
            val valueUsd = rawValueUsd?.let(::BigDecimal)
            require(valueUsd == null || valueUsd.signum() >= 0)
            val counterparty = row.nullableString("counterparty")
            require(counterparty == null || isSolanaPublicKey(counterparty))
            SolanaWalletTransaction(signature, timestamp, direction, symbol, mint, amount, valueUsd, counterparty)
        }
        require(transactions.map { it.signature to it.mint }.toSet().size == transactions.size)
        require(status != SolanaActivityStatus.UNAVAILABLE || transactions.isEmpty())
        return SolanaWalletActivity(address, status, observedAt, transactions)
    }

    private fun JSONObject.string(key: String): String {
        val value = get(key)
        require(value is String)
        return value
    }

    private fun JSONObject.nullableString(key: String): String? {
        val value = get(key)
        if (value === JSONObject.NULL) return null
        require(value is String)
        return value
    }
}
