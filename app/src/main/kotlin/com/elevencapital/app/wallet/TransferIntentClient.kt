package com.elevencapital.app.wallet

import java.io.ByteArrayOutputStream
import java.io.IOException
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

/** Server-side cross-device idempotency gate. It never signs or broadcasts. */
internal class TransferIntentClient(
    baseUrl: String,
    private val accessTokenProvider: TransferAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    allowLoopbackHttp: Boolean = false,
) {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).cache(null).build()

    suspend fun commit(review: TransferReviewSnapshot) {
        mutate("commit", review, null, "committed")
    }

    suspend fun releaseDefinitelyNotBroadcast(review: TransferReviewSnapshot) {
        mutate("release", review, "provider_definitely_not_broadcast", "released")
    }

    private suspend fun mutate(path: String, review: TransferReviewSnapshot, reason: String?, expectedState: String) {
        val token = validatedBearerToken(accessTokenProvider.freshToken(review.userId))
        val payload = JSONObject()
            .put("schemaVersion", 1).put("operationId", review.operationId.value).put("walletId", review.walletId)
            .put("chain", review.networkId.statusWireChain()).put("sender", review.sender.value)
            .put("recipient", review.recipient.value).put("assetId", review.assetId.statusWireAssetId())
            .put("baseUnits", review.baseUnits)
            .also { if (reason != null) it.put("reason", reason) }
            .toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url("$base/v1/wallet/transfer/$path")
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token").post(payload).build()
        val result = execute(request)
        result.requireExactKeys(setOf("schemaVersion", "operationId", "state", "idempotent"))
        if (result.strictInteger("schemaVersion") != 1L ||
            result.strictString("operationId", 36) != review.operationId.value ||
            result.strictString("state", 16) != expectedState || result.opt("idempotent") !is Boolean) protocolFailure()
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TransferPreparationException(
                    TransferPreparationFailureCode.NETWORK_UNAVAILABLE,
                    "Transfer safety verification is unavailable. Nothing was sent.", true))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { received ->
                        if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") protocolFailure()
                        val body = received.body ?: protocolFailure()
                        if (body.contentLength() > MAX_RESPONSE_BYTES) protocolFailure()
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(2_048)
                        body.byteStream().use { stream ->
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_RESPONSE_BYTES) protocolFailure()
                                output.write(buffer, 0, count)
                            }
                        }
                        val json = parseSingleJsonObject(output.toByteArray())
                        if (received.code != 200) throw mutationFailure(received, json)
                        if (!received.header("Cache-Control").orEmpty().lowercase().split(',').map(String::trim).contains("no-store")) protocolFailure()
                        json
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is TransferPreparationException) failure else preparationProtocolException())
                }
            }
        })
    }

    private fun mutationFailure(response: Response, json: JSONObject): TransferPreparationException {
        json.requireExactKeys(setOf("error", "message")); val raw = json.strictString("error", 64); json.strictString("message", 512)
        val (code, valid, message) = when (raw) {
            "authentication_required", "invalid_access_token" -> Triple(TransferPreparationFailureCode.AUTHENTICATION_REQUIRED,
                response.code == 401, "Your wallet session expired. Sign in again.")
            "authentication_unavailable", "intent_storage_unavailable" -> Triple(TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE,
                response.code == 503, "Transfer safety storage is unavailable. Nothing was sent.")
            "intent_conflict", "unresolved_transfer" -> Triple(TransferPreparationFailureCode.INTENT_CONFLICT,
                response.code == 409, "This wallet has an unresolved transfer. Check History before sending again.")
            "intent_not_found" -> Triple(TransferPreparationFailureCode.INTENT_NOT_FOUND,
                response.code == 422, "The reviewed transfer expired. Review it again.")
            "intent_state_invalid" -> Triple(TransferPreparationFailureCode.INTENT_STATE_INVALID,
                response.code == 422, "This transfer can no longer be submitted. Check History.")
            "rate_limited" -> Triple(TransferPreparationFailureCode.RATE_LIMITED,
                response.code == 429, "Too many transfer checks. Wait before trying again.")
            "invalid_request" -> Triple(TransferPreparationFailureCode.INVALID_REQUEST,
                response.code == 400, "The reviewed transfer is invalid. Nothing was sent.")
            else -> protocolFailure()
        }
        if (!valid) protocolFailure()
        return TransferPreparationException(code, message, code == TransferPreparationFailureCode.RATE_LIMITED,
            response.code, response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..3_600 })
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1_024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
