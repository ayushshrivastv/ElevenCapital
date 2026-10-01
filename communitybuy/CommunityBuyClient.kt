package com.elevencapital.app.communitybuy

import com.elevencapital.app.wallet.parseSingleJsonObject
import com.elevencapital.app.wallet.validatedBearerToken
import com.elevencapital.app.wallet.validatedTransferBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal fun interface CommunityBuyAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

internal enum class CommunityBuyReleaseReason(val wireValue: String) {
    BEFORE_SIGNING("provider_definitely_not_invoked"),
    SIGNED_NOT_SUBMITTED("signed_transaction_definitely_not_submitted"),
}

internal interface CommunityBuyBackend {
    suspend fun prepare(request: CommunityBuyRequest): PreparedCommunityBuy
    suspend fun commit(prepared: PreparedCommunityBuy)
    suspend fun release(prepared: PreparedCommunityBuy, reason: CommunityBuyReleaseReason)
    suspend fun submit(prepared: PreparedCommunityBuy, signedBase64: String): String
    suspend fun status(prepared: PreparedCommunityBuy): CommunityBuyStatus
}

/** Authenticated, bounded, no-redirect mainnet DBC swap transport. */
internal class CommunityBuyClient(
    baseUrl: String,
    private val accessToken: CommunityBuyAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
    allowLoopbackHttp: Boolean = false,
) : CommunityBuyBackend {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cache(null).build()

    override suspend fun prepare(request: CommunityBuyRequest): PreparedCommunityBuy =
        CommunityBuyProtocol.preparation(post("prepare", request.userId, JSONObject()
            .put("schemaVersion", 1).put("operationId", request.operationId)
            .put("network", BUY_NETWORK).put("walletId", request.walletId)
            .put("walletAddress", request.walletAddress).put("mintAddress", request.mintAddress)
            .put("amountInLamports", request.amountInLamports.toString())
            .put("slippageBps", request.slippageBps)), request, now())

    override suspend fun commit(prepared: PreparedCommunityBuy) {
        CommunityBuyProtocol.mutation(post("commit", prepared.request.userId, identity(prepared)),
            prepared.request.operationId, "committed")
    }

    override suspend fun release(prepared: PreparedCommunityBuy, reason: CommunityBuyReleaseReason) {
        CommunityBuyProtocol.mutation(post("release", prepared.request.userId,
            identity(prepared).put("reason", reason.wireValue)),
            prepared.request.operationId, "released")
    }

    override suspend fun submit(prepared: PreparedCommunityBuy, signedBase64: String): String {
        val validatedSigned = CommunityBuyProtocol.walletSignedTransaction(signedBase64, prepared)
        val expectedSignature = CommunityBuyProtocol.signedTransactionId(validatedSigned, prepared)
        return CommunityBuyProtocol.submission(post("submit", prepared.request.userId,
            identity(prepared).put("walletSignedTransactionBase64", validatedSigned)),
            prepared, expectedSignature)
    }

    override suspend fun status(prepared: PreparedCommunityBuy): CommunityBuyStatus =
        CommunityBuyProtocol.status(post("status", prepared.request.userId, identity(prepared)), prepared)

    private fun identity(prepared: PreparedCommunityBuy) = JSONObject()
        .put("schemaVersion", 1).put("operationId", prepared.request.operationId)
        .put("walletId", prepared.request.walletId)
        .put("walletAddress", prepared.request.walletAddress)
        .put("transactionSha256", prepared.transactionSha256)

    private suspend fun post(path: String, userId: String, payload: JSONObject): JSONObject {
        val token = try { validatedBearerToken(accessToken.freshToken(userId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            throw CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                "Sign in with Privy before buying a token.")
        }
        val request = Request.Builder().url("$base/v1/community-buys/$path")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(CommunityBuyException(
                        CommunityBuyFailure.SERVICE_UNAVAILABLE,
                        "The mainnet buy service is unavailable. No purchase was made.",
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { received ->
                            if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                                throw protocolError()
                            }
                            val body = received.body ?: throw protocolError()
                            if (body.contentLength() > 65_536) throw protocolError()
                            val bytes = body.byteStream().use { stream ->
                                val out = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (out.size() + count > 65_536) throw protocolError()
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                            val json = parseSingleJsonObject(bytes)
                            if (received.code != 200) throw serverError(received.code, json)
                            if (!received.header("Cache-Control").orEmpty().lowercase().split(',')
                                    .map(String::trim).contains("no-store")) throw protocolError()
                            json
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is CommunityBuyException) failure else protocolError())
                    }
                }
            })
        }
    }

    private fun serverError(status: Int, json: JSONObject): CommunityBuyException {
        val error = json.optString("error")
        return when {
            status == 401 -> CommunityBuyException(CommunityBuyFailure.AUTHENTICATION_REQUIRED,
                "Your secure wallet session expired. Sign in again.")
            status == 402 || error.contains("insufficient") -> CommunityBuyException(
                CommunityBuyFailure.INSUFFICIENT_BALANCE, "Not enough SOL to cover this buy and fees.")
            status == 409 || error.contains("expired") -> CommunityBuyException(
                CommunityBuyFailure.EXPIRED, "This quote changed or expired. Review a new quote.")
            status in 400..499 -> CommunityBuyException(CommunityBuyFailure.INVALID_REQUEST,
                "This token cannot be bought on an active mainnet Meteora curve.")
            else -> CommunityBuyException(CommunityBuyFailure.SERVICE_UNAVAILABLE,
                "The mainnet buy service is unavailable. No purchase was confirmed.")
        }
    }

    private fun protocolError() = CommunityBuyException(CommunityBuyFailure.PROTOCOL_ERROR,
        "The buy service returned data that could not be verified. Nothing was signed.")
}
