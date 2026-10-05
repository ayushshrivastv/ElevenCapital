package com.elevencapital.app.data

import com.elevencapital.app.wallet.validatedBearerToken
import com.elevencapital.app.wallet.validatedTransferBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

internal fun interface PantaAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

internal data class PantaMarket(
    val marketId: String,
    val title: String,
    val description: String,
    val category: String,
    val phase: String,
    val status: String,
    val endTimeSeconds: Long?,
    val volumeUsdc: BigDecimal?,
    val yesPrice: BigDecimal?,
    val noPrice: BigDecimal?,
)

internal data class PantaPosition(
    val marketId: String,
    val side: String,
    val shares: BigDecimal,
    val phase: String,
    val claimable: Boolean,
    val outcome: String?,
)

internal interface PantaPredictionApi {
    suspend fun markets(userId: String): List<PantaMarket>
    suspend fun market(userId: String, marketId: String): PantaMarket
    suspend fun positions(userId: String, wallet: String): List<PantaPosition>
}

/** Panta API credentials remain on Eleven's backend; Android sends only its Privy session token. */
internal class PantaPredictionClient(
    baseUrl: String,
    private val accessToken: PantaAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    allowLoopbackHttp: Boolean = false,
) : PantaPredictionApi {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    override suspend fun markets(userId: String): List<PantaMarket> {
        val url = "$base/v1/panta/markets".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("status", "primary").build()
        val root = request(userId, Request.Builder().url(url).get())
        val items = root.optJSONArray("items") ?: throw IOException("Prediction markets are unavailable.")
        if (items.length() > 20) throw IOException("Prediction markets are unavailable.")
        return (0 until items.length()).map { index ->
            parseMarket(items.optJSONObject(index) ?: throw IOException("Prediction markets are unavailable."))
        }.also { rows ->
            if (rows.map(PantaMarket::marketId).toSet().size != rows.size)
                throw IOException("Prediction markets are unavailable.")
        }
    }

    override suspend fun market(userId: String, marketId: String): PantaMarket {
        requireAddress(marketId)
        val root = request(userId, Request.Builder().url("$base/v1/panta/markets/$marketId").get())
        return parseMarket(root).also {
            if (it.marketId != marketId) throw IOException("Prediction market details are unavailable.")
        }
    }

    override suspend fun positions(userId: String, wallet: String): List<PantaPosition> {
        requireAddress(wallet)
        val url = "$base/v1/panta/positions".toHttpUrl().newBuilder()
            .addQueryParameter("wallet", wallet).build()
        val root = request(userId, Request.Builder().url(url).get())
        if (root.optString("wallet") != wallet) throw IOException("Positions are unavailable.")
        val rows = root.optJSONArray("positions") ?: throw IOException("Positions are unavailable.")
        if (rows.length() > 200) throw IOException("Positions are unavailable.")
        return (0 until rows.length()).map { index ->
            val item = rows.optJSONObject(index) ?: throw IOException("Positions are unavailable.")
            val id = item.optString("marketId").also(::requireAddress)
            val side = item.optString("side").lowercase()
            if (side !in setOf("yes", "no")) throw IOException("Positions are unavailable.")
            PantaPosition(
                marketId = id,
                side = side,
                shares = positiveDecimal(item, "shares"),
                phase = item.optString("phase"),
                claimable = item.optBoolean("claimable"),
                outcome = optionalText(item, "outcome"),
            )
        }
    }

    private suspend fun request(userId: String, builder: Request.Builder): JSONObject {
        val token = try { validatedBearerToken(accessToken.freshToken(userId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IOException("Sign in again to view prediction markets.") }
        val request = builder.header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IOException("Prediction markets are unavailable. Try again."))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val root = response.use { received ->
                            if (received.code == 401 || received.code == 403)
                                throw IOException("Sign in again to view prediction markets.")
                            if (received.code !in 200..299)
                                throw IOException("Prediction markets are unavailable. Try again.")
                            if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json")
                                throw IOException("Prediction markets are unavailable.")
                            val body = received.body ?: throw IOException("Prediction markets are unavailable.")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > 256 * 1024)
                                        throw IOException("Prediction markets are unavailable.")
                                    output.write(buffer, 0, count)
                                }
                            }
                            JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(root)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is IOException) failure else IOException("Prediction markets are unavailable."))
                    }
                }
            })
        }
    }

    companion object {
        private val ADDRESS = Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")
        private fun requireAddress(value: String) {
            require(ADDRESS.matches(value)) { "Invalid Solana address." }
        }

        internal fun parseMarket(root: JSONObject): PantaMarket {
            val marketId = root.optString("marketId").also(::requireAddress)
            val title = root.optString("title").trim()
            if (title.isEmpty() || title.length > 500) throw IOException("Prediction market details are unavailable.")
            return PantaMarket(
                marketId = marketId,
                title = title,
                description = root.optString("description").take(4000),
                category = root.optString("category"),
                phase = root.optString("phase"),
                status = root.optString("status"),
                endTimeSeconds = root.optLong("endTime").takeIf { it > 0 },
                volumeUsdc = optionalDecimal(root, "volumeUsdc"),
                yesPrice = optionalPrice(root, "yesPrice"),
                noPrice = optionalPrice(root, "noPrice"),
            )
        }

        private fun optionalText(root: JSONObject, field: String): String? =
            root.opt(field)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

        private fun optionalDecimal(root: JSONObject, field: String): BigDecimal? =
            optionalText(root, field)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.precision() <= 30 }

        private fun positiveDecimal(root: JSONObject, field: String): BigDecimal =
            optionalDecimal(root, field) ?: throw IOException("Positions are unavailable.")

        private fun optionalPrice(root: JSONObject, field: String): BigDecimal? {
            val text = optionalText(root, field) ?: return null
            return text.toBigDecimalOrNull()?.takeIf {
                it.signum() >= 0 && it <= BigDecimal.ONE && it.precision() <= 30
            } ?: throw IOException("Prediction market prices are unavailable.")
        }
    }
}
