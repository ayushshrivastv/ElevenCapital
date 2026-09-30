package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.wallet.parseSingleJsonObject
import com.elevencapital.app.wallet.validatedBearerToken
import com.elevencapital.app.wallet.validatedTransferBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
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
import org.json.JSONException
import org.json.JSONObject

internal fun interface TokenLaunchAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

internal enum class TokenLaunchReleaseReason(val wireValue: String) {
    PROVIDER_NOT_INVOKED("provider_definitely_not_invoked"),
    SIGNED_TRANSACTION_NOT_SUBMITTED("signed_transaction_definitely_not_submitted"),
}

internal interface TokenLaunchBackend {
    suspend fun uploadMetadata(
        userId: String,
        metadata: TokenLaunchMetadataUpload,
    ): UploadedTokenMetadata = throw UnsupportedOperationException("Metadata upload is unavailable.")
    suspend fun prepare(request: TokenLaunchRequest): PreparedTokenLaunch
    suspend fun commit(prepared: PreparedTokenLaunch)
    suspend fun release(prepared: PreparedTokenLaunch, reason: TokenLaunchReleaseReason)
    suspend fun submit(prepared: PreparedTokenLaunch, walletSignedTransactionBase64: String): TokenLaunchSubmission
    suspend fun status(prepared: PreparedTokenLaunch): TokenLaunchStatus
}

/** Authenticated, bounded, no-redirect transport for the dedicated mainnet launch protocol. */
internal class TokenLaunchClient(
    baseUrl: String,
    private val accessTokenProvider: TokenLaunchAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
    allowLoopbackHttp: Boolean = false,
) : TokenLaunchBackend {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    override suspend fun uploadMetadata(
        userId: String,
        metadata: TokenLaunchMetadataUpload,
    ): UploadedTokenMetadata {
        val body = JSONObject()
            .put("name", metadata.name)
            .put("symbol", metadata.symbol)
            .put("description", metadata.description)
            .put("imageBase64", metadata.artwork.base64)
            .put("imageMediaType", metadata.artwork.mediaType)
        metadata.websiteUrl?.let { body.put("websiteUrl", it) }
        metadata.xUrl?.let { body.put("xUrl", it) }
        metadata.telegramUrl?.let { body.put("telegramUrl", it) }
        return parseUploadedTokenMetadata(
            post("metadata", userId, body, submissionBoundary = false),
        )
    }

    override suspend fun prepare(request: TokenLaunchRequest): PreparedTokenLaunch {
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", request.operationId)
            .put("network", TOKEN_LAUNCH_NETWORK)
            .put("walletId", request.walletId)
            .put("walletAddress", request.walletAddress)
            .put("name", request.name)
            .put("symbol", request.symbol)
            .put("metadataUri", request.metadataUri)
            .put("totalTokenSupply", request.totalTokenSupply)
            .put("feeBps", request.feeBps)
            .put("migrationThresholdSol", request.migrationThresholdSol)
            .put("percentageSupplyOnMigration", request.percentageSupplyOnMigration)
        return TokenLaunchProtocol.preparation(
            post("prepare", request.userId, body, submissionBoundary = false),
            request,
            now(),
        )
    }

    override suspend fun commit(prepared: PreparedTokenLaunch) {
        val result = post("commit", prepared.request.userId, identity(prepared), submissionBoundary = false)
        TokenLaunchProtocol.mutation(result, prepared.request.operationId, "committed")
    }

    override suspend fun release(prepared: PreparedTokenLaunch, reason: TokenLaunchReleaseReason) {
        val body = identity(prepared).put("reason", reason.wireValue)
        val result = post("release", prepared.request.userId, body, submissionBoundary = false)
        TokenLaunchProtocol.mutation(result, prepared.request.operationId, "released")
    }

    override suspend fun submit(
        prepared: PreparedTokenLaunch,
        walletSignedTransactionBase64: String,
    ): TokenLaunchSubmission {
        val checked = TokenLaunchProtocol.walletSignedTransaction(walletSignedTransactionBase64, prepared)
        val result = post(
            "submit",
            prepared.request.userId,
            identity(prepared).put("walletSignedTransactionBase64", checked),
            submissionBoundary = true,
        )
        return TokenLaunchProtocol.submission(result, prepared)
    }

    override suspend fun status(prepared: PreparedTokenLaunch): TokenLaunchStatus = TokenLaunchProtocol.status(
        post("status", prepared.request.userId, identity(prepared), submissionBoundary = false),
        prepared,
    )

    private fun identity(prepared: PreparedTokenLaunch): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("operationId", prepared.request.operationId)
        .put("walletId", prepared.request.walletId)
        .put("walletAddress", prepared.request.walletAddress)
        .put("transactionSha256", prepared.transactionSha256)

    private suspend fun post(
        path: String,
        userId: String,
        payload: JSONObject,
        submissionBoundary: Boolean,
    ): JSONObject {
        val token = try {
            validatedBearerToken(accessTokenProvider.freshToken(userId))
        } catch (failure: TokenLaunchException) {
            throw failure
        } catch (_: Exception) {
            throw TokenLaunchException(
                TokenLaunchFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session expired. Sign in again.",
                retryable = false,
                submissionMayHaveReachedServer = false,
                statusCode = 401,
            )
        }
        val request = Request.Builder()
            .url("$base/v1/token-launches/$path")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(request, submissionBoundary)
    }

    private suspend fun execute(request: Request, submissionBoundary: Boolean): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(TokenLaunchException(
                        TokenLaunchFailureCode.NETWORK_UNAVAILABLE,
                        if (submissionBoundary) {
                            "The launch result is uncertain. Eleven will check its status and will not submit it again."
                        } else {
                            "The token launch service is unavailable. Nothing was submitted."
                        },
                        retryable = !submissionBoundary,
                        submissionMayHaveReachedServer = submissionBoundary,
                    ))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { received ->
                            if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                                protocolFailure()
                            }
                            val bytes = readBounded(received)
                            val json = try {
                                parseSingleJsonObject(bytes)
                            } catch (_: JSONException) {
                                protocolFailure()
                            }
                            if (received.code != 200) throw serverFailure(received.code, json, submissionBoundary)
                            val noStore = received.header("Cache-Control").orEmpty().lowercase()
                                .split(',').map(String::trim).contains("no-store")
                            if (!noStore) protocolFailure()
                            json
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (failure is TokenLaunchException) failure else protocolFailureException(),
                        )
                    }
                }
            })
        }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: protocolFailure()
        if (body.contentLength() > MAX_RESPONSE_BYTES) protocolFailure()
        return body.byteStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) protocolFailure()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    internal fun serverFailure(status: Int, json: JSONObject, submissionBoundary: Boolean): TokenLaunchException {
        if (json.keys().asSequence().toSet() != setOf("error", "message")) protocolFailure()
        val raw = json.get("error") as? String ?: protocolFailure()
        val upstreamMessage = json.get("message") as? String ?: protocolFailure()
        if (raw.length !in 1..64 || !raw.matches(Regex("[a-z0-9_]+")) ||
            upstreamMessage.length !in 1..512 || upstreamMessage.any(Char::isISOControl)) protocolFailure()
        val code = when (raw) {
            "invalid_request", "request_too_large", "invalid_launch" -> TokenLaunchFailureCode.INVALID_REQUEST
            "authentication_required", "invalid_access_token" -> TokenLaunchFailureCode.AUTHENTICATION_REQUIRED
            "wrong_network" -> TokenLaunchFailureCode.WRONG_NETWORK
            "insufficient_balance", "insufficient_fee_balance", "insufficient_mainnet_balance" ->
                TokenLaunchFailureCode.INSUFFICIENT_BALANCE
            "launch_cost_too_high" -> TokenLaunchFailureCode.COST_TOO_HIGH
            "simulation_failed" -> TokenLaunchFailureCode.SIMULATION_FAILED
            "signed_transaction_invalid" -> TokenLaunchFailureCode.SIGNED_TRANSACTION_INVALID
            "blockhash_expired" -> TokenLaunchFailureCode.EXPIRED
            "stale_chain_data" -> TokenLaunchFailureCode.STALE_CHAIN_DATA
            "launch_conflict", "unresolved_launch", "intent_conflict" -> TokenLaunchFailureCode.CONFLICT
            "launch_not_found", "intent_not_found" -> TokenLaunchFailureCode.NOT_FOUND
            "launch_state_invalid", "intent_state_invalid" -> TokenLaunchFailureCode.STATE_INVALID
            "launch_storage_unavailable", "intent_storage_unavailable" ->
                TokenLaunchFailureCode.STORAGE_UNAVAILABLE
            "authentication_unavailable" -> TokenLaunchFailureCode.SERVICE_UNAVAILABLE
            "metadata_unavailable" -> TokenLaunchFailureCode.METADATA_UNAVAILABLE
            "rate_limited" -> TokenLaunchFailureCode.RATE_LIMITED
            "rpc_unavailable", "mainnet_rpc_unavailable", "internal_error" ->
                TokenLaunchFailureCode.SERVICE_UNAVAILABLE
            else -> protocolFailure()
        }
        val validStatus = when (code) {
            TokenLaunchFailureCode.AUTHENTICATION_REQUIRED -> status == 401
            TokenLaunchFailureCode.CONFLICT -> status == 409
            TokenLaunchFailureCode.RATE_LIMITED -> status == 429
            TokenLaunchFailureCode.STORAGE_UNAVAILABLE, TokenLaunchFailureCode.SERVICE_UNAVAILABLE,
            TokenLaunchFailureCode.METADATA_UNAVAILABLE, TokenLaunchFailureCode.WRONG_NETWORK,
            TokenLaunchFailureCode.STALE_CHAIN_DATA -> status in 500..503
            TokenLaunchFailureCode.INVALID_REQUEST -> status in setOf(400, 413, 422)
            TokenLaunchFailureCode.INSUFFICIENT_BALANCE, TokenLaunchFailureCode.COST_TOO_HIGH,
            TokenLaunchFailureCode.SIMULATION_FAILED, TokenLaunchFailureCode.SIGNED_TRANSACTION_INVALID,
            TokenLaunchFailureCode.EXPIRED, TokenLaunchFailureCode.NOT_FOUND,
            TokenLaunchFailureCode.STATE_INVALID -> status == 422
            else -> false
        }
        if (!validStatus) protocolFailure()
        val message = when (code) {
            TokenLaunchFailureCode.INVALID_REQUEST -> "Review the token details and prepare the launch again."
            TokenLaunchFailureCode.AUTHENTICATION_REQUIRED -> "Your secure wallet session expired. Sign in again."
            TokenLaunchFailureCode.WRONG_NETWORK -> "The launch service is not connected to Solana Mainnet."
            TokenLaunchFailureCode.INSUFFICIENT_BALANCE -> "This wallet needs at least 0.05 SOL for the Mainnet launch."
            TokenLaunchFailureCode.COST_TOO_HIGH -> "This launch could debit more than the 0.05 SOL safety limit."
            TokenLaunchFailureCode.SIMULATION_FAILED -> "The Meteora launch transaction could not be verified."
            TokenLaunchFailureCode.SIGNED_TRANSACTION_INVALID ->
                "The wallet signature did not match this launch. Nothing was broadcast."
            TokenLaunchFailureCode.EXPIRED -> "The Solana blockhash expired. Prepare the launch again."
            TokenLaunchFailureCode.STALE_CHAIN_DATA -> "Fresh Solana Mainnet data is unavailable. Prepare the launch again."
            TokenLaunchFailureCode.CONFLICT -> "This wallet already has a token launch in progress."
            TokenLaunchFailureCode.NOT_FOUND -> "This launch preparation expired or no longer exists."
            TokenLaunchFailureCode.STATE_INVALID -> "This launch can no longer be submitted. Check its status."
            TokenLaunchFailureCode.STORAGE_UNAVAILABLE -> "Launch safety storage is unavailable. Nothing was submitted."
            TokenLaunchFailureCode.METADATA_UNAVAILABLE ->
                "Token details couldn't be uploaded. Nothing was submitted. Try again in a moment."
            TokenLaunchFailureCode.RATE_LIMITED -> "Launch requests are busy. Wait a moment and try again."
            TokenLaunchFailureCode.SERVICE_UNAVAILABLE -> "The token launch service is unavailable. Nothing was submitted."
            else -> "The token launch could not continue."
        }
        return TokenLaunchException(
            code,
            message,
            retryable = code in setOf(
                TokenLaunchFailureCode.STALE_CHAIN_DATA,
                TokenLaunchFailureCode.RATE_LIMITED,
                TokenLaunchFailureCode.METADATA_UNAVAILABLE,
                TokenLaunchFailureCode.SERVICE_UNAVAILABLE,
            ) && !submissionBoundary,
            submissionMayHaveReachedServer = submissionBoundary,
            statusCode = status,
        )
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 32 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun protocolFailureException() = TokenLaunchException(
    TokenLaunchFailureCode.PROTOCOL_ERROR,
    "The launch service returned data that could not be verified. Nothing was submitted.",
    retryable = false,
)
