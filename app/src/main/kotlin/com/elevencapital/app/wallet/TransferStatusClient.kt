package com.elevencapital.app.wallet

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import java.time.format.DateTimeParseException
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

private val statusUnsignedPattern = Regex("(?:0|[1-9][0-9]*)")
private val statusEthereumHashPattern = Regex("0x[0-9a-f]{64}")
private val statusEthereumInputHashPattern = Regex("0x[0-9a-fA-F]{64}")
private val statusTimestampPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
private const val statusBase58Alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** Read-only mainnet confirmation tracking. This class never signs, sends, or retries a transaction. */
class TransferStatusClient internal constructor(
    baseUrl: String,
    private val accessTokenProvider: TransferAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
    allowLoopbackHttp: Boolean = false,
) {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val finiteHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .cache(null)
        .build()

    suspend fun status(prepared: PreparedTransfer, transactionId: String): TransferStatusObservation =
        status(prepared.review, transactionId)

    suspend fun status(review: TransferReviewSnapshot, transactionId: String): TransferStatusObservation {
        val accessToken = validatedBearerToken(accessTokenProvider.freshToken(review.userId))
        val canonicalId = canonicalTransactionId(review.networkId, transactionId)
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", review.operationId.value)
            .put("walletId", review.walletId)
            .put("chain", review.networkId.statusWireChain())
            .put("sender", review.sender.value)
            .put("recipient", review.recipient.value)
            .put("assetId", review.assetId.statusWireAssetId())
            .put("baseUnits", review.baseUnits)
            .put("transactionId", canonicalId)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url("$base/v1/wallet/transfer/status")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        return TransferStatusParser.parse(execute(request), review, canonicalId, now())
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = finiteHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    TransferPreparationException(
                        TransferPreparationFailureCode.NETWORK_UNAVAILABLE,
                        "Transaction status is unavailable. Do not send the transfer again.",
                        retryable = true,
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { received ->
                        if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                            statusProtocolFailure()
                        }
                        val bytes = readBounded(received)
                        val json = try { parseSingleJsonObject(bytes) }
                        catch (_: JSONException) { statusProtocolFailure() }
                        if (received.code != 200) throw statusServerFailure(received, json)
                        val cacheControl = received.header("Cache-Control").orEmpty().lowercase()
                        if ("no-store" !in cacheControl.split(',').map(String::trim)) statusProtocolFailure()
                        json
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is TransferPreparationException) failure else statusProtocolException(),
                    )
                }
            }
        })
    }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: statusProtocolFailure()
        if (body.contentLength() > MAX_RESPONSE_BYTES) statusProtocolFailure()
        return body.byteStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) statusProtocolFailure()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun statusServerFailure(response: Response, json: JSONObject): TransferPreparationException {
        json.statusExactKeys(setOf("error", "message"))
        val rawCode = json.statusString("error", 64)
        json.statusString("message", 512)
        val code = when (rawCode) {
            "invalid_request" -> TransferPreparationFailureCode.INVALID_REQUEST
            "invalid_address" -> TransferPreparationFailureCode.INVALID_ADDRESS
            "transaction_mismatch" -> TransferPreparationFailureCode.TRANSACTION_MISMATCH
            "wrong_network" -> TransferPreparationFailureCode.WRONG_NETWORK
            "stale_chain_data" -> TransferPreparationFailureCode.STALE_CHAIN_DATA
            "rpc_unavailable" -> TransferPreparationFailureCode.RPC_UNAVAILABLE
            "busy" -> TransferPreparationFailureCode.BUSY
            "rate_limited" -> TransferPreparationFailureCode.RATE_LIMITED
            "authentication_required", "invalid_access_token" -> TransferPreparationFailureCode.AUTHENTICATION_REQUIRED
            "authentication_unavailable", "intent_storage_unavailable" -> TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE
            "intent_conflict", "unresolved_transfer" -> TransferPreparationFailureCode.INTENT_CONFLICT
            "intent_not_found" -> TransferPreparationFailureCode.INTENT_NOT_FOUND
            "intent_state_invalid" -> TransferPreparationFailureCode.INTENT_STATE_INVALID
            "internal_error" -> TransferPreparationFailureCode.SERVICE_ERROR
            "request_too_large" -> TransferPreparationFailureCode.INVALID_REQUEST
            else -> statusProtocolFailure()
        }
        val validStatus = if (rawCode == "request_too_large") response.code == 413 else when (code) {
            TransferPreparationFailureCode.INVALID_REQUEST -> response.code == 400
            TransferPreparationFailureCode.INVALID_ADDRESS -> response.code == 400
            TransferPreparationFailureCode.TRANSACTION_MISMATCH -> response.code == 422
            TransferPreparationFailureCode.RATE_LIMITED -> response.code == 429
            TransferPreparationFailureCode.AUTHENTICATION_REQUIRED -> response.code == 401
            TransferPreparationFailureCode.INTENT_CONFLICT -> response.code == 409
            TransferPreparationFailureCode.INTENT_NOT_FOUND,
            TransferPreparationFailureCode.INTENT_STATE_INVALID -> response.code == 422
            TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE -> response.code == 503
            TransferPreparationFailureCode.WRONG_NETWORK,
            TransferPreparationFailureCode.STALE_CHAIN_DATA,
            TransferPreparationFailureCode.RPC_UNAVAILABLE,
            TransferPreparationFailureCode.BUSY -> response.code == 503
            TransferPreparationFailureCode.SERVICE_ERROR -> response.code == 500
            else -> false
        }
        if (!validStatus) statusProtocolFailure()
        val retryable = code in setOf(
            TransferPreparationFailureCode.STALE_CHAIN_DATA,
            TransferPreparationFailureCode.RPC_UNAVAILABLE,
            TransferPreparationFailureCode.BUSY,
            TransferPreparationFailureCode.RATE_LIMITED,
            TransferPreparationFailureCode.SERVICE_ERROR,
        )
        val message = when (code) {
            TransferPreparationFailureCode.INVALID_REQUEST -> "Transaction status details are invalid. Do not send again."
            TransferPreparationFailureCode.INVALID_ADDRESS -> "The connected wallet cannot be verified. Do not send again."
            TransferPreparationFailureCode.TRANSACTION_MISMATCH -> "This transaction does not belong to the connected wallet."
            TransferPreparationFailureCode.WRONG_NETWORK -> "The status service is connected to the wrong network."
            TransferPreparationFailureCode.STALE_CHAIN_DATA -> "Fresh transaction status is unavailable. Do not send again."
            TransferPreparationFailureCode.RPC_UNAVAILABLE -> "The blockchain status is unavailable. Do not send again."
            TransferPreparationFailureCode.BUSY -> "Transaction status is busy. Check again shortly."
            TransferPreparationFailureCode.RATE_LIMITED -> "Transaction status was checked too often. Wait and check again."
            TransferPreparationFailureCode.AUTHENTICATION_REQUIRED -> "Your wallet session expired. Sign in again before checking status."
            TransferPreparationFailureCode.INTENT_CONFLICT -> "Transaction status does not match the committed transfer. Do not send again."
            TransferPreparationFailureCode.INTENT_NOT_FOUND -> "The committed transfer could not be found. Do not send again."
            TransferPreparationFailureCode.INTENT_STATE_INVALID -> "This transfer is not ready for a status check. Do not send again."
            TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE -> "Transfer safety storage is unavailable. Do not send again."
            TransferPreparationFailureCode.SERVICE_ERROR -> "Transaction status is unavailable. Do not send again."
            else -> "Transaction status could not be verified. Do not send again."
        }
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..3_600 }
        return TransferPreparationException(code, message, retryable, response.code, retryAfter)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

object TransferStatusParser {
    private val maximumU64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val maximumLong = BigInteger.valueOf(Long.MAX_VALUE)
    private val commonKeys = setOf("schemaVersion", "operationId", "chain", "caip2", "transactionId", "sender",
        "senderVerified", "status", "confirmations", "isFinalized", "observedAt")

    fun parse(
        source: JSONObject,
        review: TransferReviewSnapshot,
        expectedTransactionId: String,
        now: Instant = Instant.now(),
    ): TransferStatusObservation {
        try {
            val canonicalId = canonicalTransactionId(review.networkId, expectedTransactionId)
            val chain = source.statusString("chain", 16)
            val chainKeys = when (chain) {
                "ETHEREUM" -> setOf("blockNumber", "blockHash", "blockTime", "finalizedBlockNumber", "failureCode")
                "SOLANA" -> setOf("observedSlot", "slot", "blockTime", "confirmationStatus", "failureCode")
                else -> statusProtocolFailure()
            }
            source.statusExactKeys(commonKeys + chainKeys)
            if (source.statusInteger("schemaVersion") != 1L || source.statusString("operationId", 36) != review.operationId.value ||
                chain != review.networkId.statusWireChain() || source.statusString("caip2", 80) != review.networkCaip2 ||
                source.statusString("transactionId", 88) != canonicalId || source.statusString("sender", 44) != review.sender.value) {
                statusProtocolFailure()
            }
            val status = when (source.statusString("status", 16)) {
                "unknown" -> TransferLifecycleStatus.UNKNOWN
                "pending" -> TransferLifecycleStatus.PENDING
                "confirmed" -> TransferLifecycleStatus.CONFIRMED
                "finalized" -> TransferLifecycleStatus.FINALIZED
                "failed" -> TransferLifecycleStatus.FAILED
                else -> statusProtocolFailure()
            }
            val senderVerified = source.statusNullableBoolean("senderVerified")
            if (senderVerified == false) statusProtocolFailure()
            val confirmations = source.statusNullableUnsigned("confirmations", maximumU64)
            val isFinalized = source.statusBoolean("isFinalized")
            val observedAt = source.statusTimestamp("observedAt")
            if (observedAt.isAfter(now.plusSeconds(30)) || observedAt.isBefore(now.minusSeconds(120))) statusProtocolFailure()
            return when (chain) {
                "ETHEREUM" -> parseEthereum(source, review, canonicalId, senderVerified, status, confirmations, isFinalized, observedAt)
                "SOLANA" -> parseSolana(source, review, canonicalId, senderVerified, status, confirmations, isFinalized, observedAt)
                else -> statusProtocolFailure()
            }
        } catch (failure: TransferPreparationException) {
            throw failure
        } catch (_: Exception) {
            throw statusProtocolFailure()
        }
    }

    private fun parseEthereum(
        source: JSONObject,
        review: TransferReviewSnapshot,
        transactionId: String,
        senderVerified: Boolean?,
        status: TransferLifecycleStatus,
        confirmations: BigInteger?,
        isFinalized: Boolean,
        observedAt: Instant,
    ): EthereumTransferStatus {
        if (review.networkId != WalletNetworkId.ETHEREUM_MAINNET) statusProtocolFailure()
        val blockNumber = source.statusNullableUnsigned("blockNumber", maximumU64)
        val blockHash = source.statusNullableString("blockHash", 66)?.also {
            if (!statusEthereumHashPattern.matches(it)) statusProtocolFailure()
        }
        val blockTime = source.statusNullableTimestamp("blockTime")
        val finalizedBlock = source.statusNullableUnsigned("finalizedBlockNumber", maximumU64)
        val failure = when (source.statusNullableString("failureCode", 32)) {
            null -> null
            "execution_reverted" -> EthereumTransferFailure.EXECUTION_REVERTED
            else -> statusProtocolFailure()
        }
        val included = blockNumber != null && blockHash != null && blockTime != null && confirmations != null
        if ((status in setOf(TransferLifecycleStatus.CONFIRMED, TransferLifecycleStatus.FINALIZED,
                TransferLifecycleStatus.FAILED)) != included) statusProtocolFailure()
        if (blockTime != null && blockTime.isAfter(observedAt.plusSeconds(30))) statusProtocolFailure()
        when (status) {
            TransferLifecycleStatus.UNKNOWN -> if (senderVerified != null || confirmations != null || included ||
                finalizedBlock != null || failure != null || isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.PENDING -> if (senderVerified != true || confirmations != BigInteger.ZERO || included ||
                finalizedBlock != null || failure != null || isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.CONFIRMED -> if (senderVerified != true || confirmations!!.signum() <= 0 ||
                failure != null || isFinalized || (finalizedBlock != null && blockNumber!! <= finalizedBlock)) {
                statusProtocolFailure()
            }
            TransferLifecycleStatus.FINALIZED -> if (senderVerified != true || confirmations!!.signum() <= 0 ||
                failure != null || !isFinalized || finalizedBlock == null || blockNumber!! > finalizedBlock) statusProtocolFailure()
            TransferLifecycleStatus.FAILED -> {
                if (senderVerified != true || confirmations!!.signum() <= 0 || failure == null) statusProtocolFailure()
                val derivedFinality = finalizedBlock != null && blockNumber!! <= finalizedBlock
                if (isFinalized != derivedFinality) statusProtocolFailure()
            }
        }
        return EthereumTransferStatus(review, transactionId, senderVerified, status, confirmations, isFinalized,
            observedAt, blockNumber, blockHash, blockTime, finalizedBlock, failure)
    }

    private fun parseSolana(
        source: JSONObject,
        review: TransferReviewSnapshot,
        transactionId: String,
        senderVerified: Boolean?,
        status: TransferLifecycleStatus,
        confirmations: BigInteger?,
        isFinalized: Boolean,
        observedAt: Instant,
    ): SolanaTransferStatus {
        if (review.networkId != WalletNetworkId.SOLANA_MAINNET) statusProtocolFailure()
        val observedSlot = source.statusUnsigned("observedSlot", maximumLong)
        if (observedSlot.signum() <= 0) statusProtocolFailure()
        val slot = source.statusNullableUnsigned("slot", maximumLong)
        if (slot != null && (slot.signum() <= 0 || slot > observedSlot)) statusProtocolFailure()
        val blockTime = source.statusNullableTimestamp("blockTime")
        if (blockTime != null && blockTime.isAfter(observedAt.plusSeconds(30))) statusProtocolFailure()
        val confirmationStatus = when (source.statusNullableString("confirmationStatus", 16)) {
            null -> null
            "processed" -> SolanaConfirmationStatus.PROCESSED
            "confirmed" -> SolanaConfirmationStatus.CONFIRMED
            "finalized" -> SolanaConfirmationStatus.FINALIZED
            else -> statusProtocolFailure()
        }
        val failure = when (source.statusNullableString("failureCode", 32)) {
            null -> null
            "transaction_error" -> SolanaTransferFailure.TRANSACTION_ERROR
            else -> statusProtocolFailure()
        }
        when (status) {
            TransferLifecycleStatus.UNKNOWN -> if (slot != null || blockTime != null || confirmationStatus != null ||
                confirmations != null || senderVerified != null || failure != null || isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.PENDING -> if (slot == null || confirmationStatus != SolanaConfirmationStatus.PROCESSED ||
                confirmations == null || failure != null || isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.CONFIRMED -> if (senderVerified != true || slot == null || confirmationStatus != SolanaConfirmationStatus.CONFIRMED ||
                confirmations == null || failure != null || isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.FINALIZED -> if (senderVerified != true || slot == null || confirmationStatus != SolanaConfirmationStatus.FINALIZED ||
                failure != null || !isFinalized) statusProtocolFailure()
            TransferLifecycleStatus.FAILED -> if (senderVerified != true || slot == null || confirmationStatus == null || failure == null ||
                isFinalized != (confirmationStatus == SolanaConfirmationStatus.FINALIZED)) statusProtocolFailure()
        }
        if (confirmationStatus != null && confirmationStatus != SolanaConfirmationStatus.FINALIZED && confirmations == null) {
            statusProtocolFailure()
        }
        if (blockTime != null && senderVerified != true) statusProtocolFailure()
        return SolanaTransferStatus(review, transactionId, senderVerified, status, confirmations, isFinalized,
            observedAt, observedSlot.toLong(), slot?.toLong(), blockTime, confirmationStatus, failure)
    }
}

private fun canonicalTransactionId(network: WalletNetworkId, raw: String): String = when (network) {
    WalletNetworkId.ETHEREUM_MAINNET -> {
        if (!statusEthereumInputHashPattern.matches(raw)) statusProtocolFailure()
        raw.lowercase()
    }
    WalletNetworkId.SOLANA_MAINNET -> {
        val bytes = decodeBase58Signature(raw)
        if (bytes.all { it == 0.toByte() } || encodeBase58Any(bytes) != raw) statusProtocolFailure()
        raw
    }
}

private fun decodeBase58Signature(raw: String): ByteArray {
    if (raw.length !in 64..88 || raw.any { it !in statusBase58Alphabet }) statusProtocolFailure()
    var integer = BigInteger.ZERO
    raw.forEach { integer = integer * BigInteger.valueOf(58) + BigInteger.valueOf(statusBase58Alphabet.indexOf(it).toLong()) }
    val magnitude = if (integer == BigInteger.ZERO) byteArrayOf() else integer.toByteArray()
        .let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    val leading = raw.takeWhile { it == '1' }.length
    if (leading + magnitude.size != 64) statusProtocolFailure()
    return ByteArray(leading) + magnitude
}

private fun encodeBase58Any(raw: ByteArray): String {
    val leading = raw.takeWhile { it == 0.toByte() }.size
    var integer = BigInteger(1, raw)
    val encoded = StringBuilder()
    while (integer.signum() > 0) {
        val parts = integer.divideAndRemainder(BigInteger.valueOf(58))
        encoded.append(statusBase58Alphabet[parts[1].toInt()])
        integer = parts[0]
    }
    return "1".repeat(leading) + encoded.reverse().toString()
}

internal fun WalletNetworkId.statusWireChain(): String = when (this) {
    WalletNetworkId.ETHEREUM_MAINNET -> "ETHEREUM"
    WalletNetworkId.SOLANA_MAINNET -> "SOLANA"
}

internal fun WalletAssetId.statusWireAssetId(): String = when (this) {
    WalletAssetId.ETHEREUM_ETH -> "ETHEREUM:native"
    WalletAssetId.ETHEREUM_USDC -> "ETHEREUM:USDC"
    WalletAssetId.SOLANA_SOL -> "SOLANA:native"
    WalletAssetId.SOLANA_USDC -> "SOLANA:USDC"
}

private fun JSONObject.statusExactKeys(expected: Set<String>) {
    if (keys().asSequence().toSet() != expected) statusProtocolFailure()
}

private fun JSONObject.statusString(key: String, maximum: Int): String {
    val value = get(key)
    if (value !is String || value.isEmpty() || value.length > maximum || value.any(Char::isISOControl)) statusProtocolFailure()
    return value
}

private fun JSONObject.statusNullableString(key: String, maximum: Int): String? {
    val value = get(key)
    if (value === JSONObject.NULL) return null
    if (value !is String || value.isEmpty() || value.length > maximum || value.any(Char::isISOControl)) statusProtocolFailure()
    return value
}

private fun JSONObject.statusInteger(key: String): Long {
    val value = get(key)
    if (value !is Int && value !is Long) statusProtocolFailure()
    return (value as Number).toLong()
}

private fun JSONObject.statusBoolean(key: String): Boolean = get(key) as? Boolean ?: statusProtocolFailure()

private fun JSONObject.statusNullableBoolean(key: String): Boolean? {
    val value = get(key)
    if (value === JSONObject.NULL) return null
    return value as? Boolean ?: statusProtocolFailure()
}

private fun JSONObject.statusUnsigned(key: String, maximum: BigInteger): BigInteger {
    val raw = statusString(key, 78)
    if (!statusUnsignedPattern.matches(raw)) statusProtocolFailure()
    return BigInteger(raw).also { if (it > maximum) statusProtocolFailure() }
}

private fun JSONObject.statusNullableUnsigned(key: String, maximum: BigInteger): BigInteger? =
    statusNullableString(key, 78)?.let { raw ->
        if (!statusUnsignedPattern.matches(raw)) statusProtocolFailure()
        BigInteger(raw).also { if (it > maximum) statusProtocolFailure() }
    }

private fun JSONObject.statusTimestamp(key: String): Instant {
    val raw = statusString(key, 40)
    if (!statusTimestampPattern.matches(raw)) {
        statusProtocolFailure()
    }
    return try { Instant.parse(raw) } catch (_: DateTimeParseException) { statusProtocolFailure() }
}

private fun JSONObject.statusNullableTimestamp(key: String): Instant? =
    statusNullableString(key, 40)?.let { raw ->
        if (!statusTimestampPattern.matches(raw)) {
            statusProtocolFailure()
        }
        try { Instant.parse(raw) } catch (_: DateTimeParseException) { statusProtocolFailure() }
    }

private fun statusProtocolException() = TransferPreparationException(
    TransferPreparationFailureCode.PROTOCOL_ERROR,
    "Transaction status could not be verified. Do not send the transfer again.",
    retryable = true,
)

private fun statusProtocolFailure(): Nothing = throw statusProtocolException()
