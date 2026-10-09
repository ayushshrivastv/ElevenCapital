package com.elevencapital.app.wallet

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
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

/**
 * Fetches a short-lived unsigned transfer from Eleven Capital's fixed backend. The client never
 * accepts RPC URLs, contracts, token decimals, or transaction identities from UI input.
 */
class TransferPreparationClient internal constructor(
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
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    suspend fun prepare(review: TransferReviewSnapshot): PreparedTransfer {
        val accessToken = validatedBearerToken(accessTokenProvider.freshToken(review.userId))
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", review.operationId.value)
            .put("walletId", review.walletId)
            .put("chain", review.networkId.wireChain())
            .put("sender", review.sender.value)
            .put("recipient", review.recipient.value)
            .put("assetId", review.assetId.wireId())
            .put("amount", review.displayAmount)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$base/v1/wallet/transfer/prepare")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        val source = execute(request)
        return PreparedTransferParser.parse(source, review, now())
    }

    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = finiteHttp.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    TransferPreparationException(
                        TransferPreparationFailureCode.NETWORK_UNAVAILABLE,
                        "The transfer service is unavailable. No transaction was sent.",
                        retryable = true,
                    ),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { received ->
                        val contentType = received.header("Content-Type")?.substringBefore(';')?.trim()
                        if (contentType != "application/json") protocolFailure()
                        val bytes = readBounded(received)
                        val json = try { parseSingleJsonObject(bytes) }
                        catch (_: JSONException) { protocolFailure() }
                        if (received.code != 200) throw serverFailure(received, json)
                        val cacheControl = received.header("Cache-Control").orEmpty().lowercase()
                        if ("no-store" !in cacheControl.split(',').map(String::trim)) protocolFailure()
                        json
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is TransferPreparationException) failure else preparationProtocolException(),
                    )
                }
            }
        })
    }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: protocolFailure()
        val declared = body.contentLength()
        if (declared > MAX_RESPONSE_BYTES) protocolFailure()
        return body.byteStream().use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(4_096)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) protocolFailure()
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun serverFailure(response: Response, json: JSONObject): TransferPreparationException {
        json.requireExactKeys(setOf("error", "message"))
        val rawCode = json.strictString("error", 64)
        // Validate the server's message shape but use only fixed local copy in the UI.
        json.strictString("message", 512)
        val code = when (rawCode) {
            "invalid_request" -> TransferPreparationFailureCode.INVALID_REQUEST
            "invalid_address" -> TransferPreparationFailureCode.INVALID_ADDRESS
            "unsafe_recipient" -> TransferPreparationFailureCode.UNSAFE_RECIPIENT
            "unsupported_asset" -> TransferPreparationFailureCode.UNSUPPORTED_ASSET
            "wrong_network" -> TransferPreparationFailureCode.WRONG_NETWORK
            "stale_chain_data" -> TransferPreparationFailureCode.STALE_CHAIN_DATA
            "insufficient_asset_balance" -> TransferPreparationFailureCode.INSUFFICIENT_ASSET_BALANCE
            "insufficient_fee_balance" -> TransferPreparationFailureCode.INSUFFICIENT_FEE_BALANCE
            "simulation_failed" -> TransferPreparationFailureCode.SIMULATION_FAILED
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
            else -> protocolFailure()
        }
        val allowedStatus = if (rawCode == "request_too_large") setOf(413) else when (code) {
            TransferPreparationFailureCode.INVALID_REQUEST -> setOf(400)
            TransferPreparationFailureCode.INVALID_ADDRESS -> setOf(400)
            TransferPreparationFailureCode.UNSAFE_RECIPIENT,
            TransferPreparationFailureCode.UNSUPPORTED_ASSET,
            TransferPreparationFailureCode.INSUFFICIENT_ASSET_BALANCE,
            TransferPreparationFailureCode.INSUFFICIENT_FEE_BALANCE,
            TransferPreparationFailureCode.SIMULATION_FAILED -> setOf(422)
            TransferPreparationFailureCode.RATE_LIMITED -> setOf(429)
            TransferPreparationFailureCode.AUTHENTICATION_REQUIRED -> setOf(401)
            TransferPreparationFailureCode.INTENT_CONFLICT -> setOf(409)
            TransferPreparationFailureCode.INTENT_NOT_FOUND,
            TransferPreparationFailureCode.INTENT_STATE_INVALID -> setOf(422)
            TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE -> setOf(503)
            TransferPreparationFailureCode.WRONG_NETWORK,
            TransferPreparationFailureCode.STALE_CHAIN_DATA,
            TransferPreparationFailureCode.RPC_UNAVAILABLE,
            TransferPreparationFailureCode.BUSY -> setOf(503)
            TransferPreparationFailureCode.SERVICE_ERROR -> setOf(500)
            else -> emptySet()
        }
        if (response.code !in allowedStatus) protocolFailure()
        val retryable = code in setOf(
            TransferPreparationFailureCode.STALE_CHAIN_DATA,
            TransferPreparationFailureCode.RPC_UNAVAILABLE,
            TransferPreparationFailureCode.BUSY,
            TransferPreparationFailureCode.RATE_LIMITED,
            TransferPreparationFailureCode.SERVICE_ERROR,
        )
        val userMessage = when (code) {
            TransferPreparationFailureCode.INVALID_REQUEST -> "Review the transfer details and try again."
            TransferPreparationFailureCode.INVALID_ADDRESS -> "The connected wallet or recipient address is invalid."
            TransferPreparationFailureCode.UNSAFE_RECIPIENT -> "This recipient cannot safely receive this transfer."
            TransferPreparationFailureCode.UNSUPPORTED_ASSET -> "Sending this asset is not available yet."
            TransferPreparationFailureCode.WRONG_NETWORK -> "The wallet service is connected to the wrong network."
            TransferPreparationFailureCode.STALE_CHAIN_DATA -> "Fresh network data is unavailable. Try again safely."
            TransferPreparationFailureCode.INSUFFICIENT_ASSET_BALANCE -> "The wallet does not have enough of this asset."
            TransferPreparationFailureCode.INSUFFICIENT_FEE_BALANCE -> "The wallet does not have enough native currency for the network fee."
            TransferPreparationFailureCode.SIMULATION_FAILED -> "The transfer could not be verified. Nothing was sent."
            TransferPreparationFailureCode.RPC_UNAVAILABLE -> "The blockchain network is unavailable. Nothing was sent."
            TransferPreparationFailureCode.BUSY -> "The transfer service is busy. Try again safely."
            TransferPreparationFailureCode.RATE_LIMITED -> "Too many transfer checks. Wait before trying again."
            TransferPreparationFailureCode.AUTHENTICATION_REQUIRED -> "Your wallet session expired. Sign in again."
            TransferPreparationFailureCode.INTENT_CONFLICT -> "This wallet has an unresolved transfer. Check History before sending again."
            TransferPreparationFailureCode.INTENT_NOT_FOUND -> "The reviewed transfer expired. Review it again."
            TransferPreparationFailureCode.INTENT_STATE_INVALID -> "This transfer can no longer be submitted. Review its status."
            TransferPreparationFailureCode.INTENT_STORAGE_UNAVAILABLE -> "Transfer safety storage is unavailable. Nothing was sent."
            TransferPreparationFailureCode.SERVICE_ERROR -> "The transfer service is unavailable. Nothing was sent."
            else -> "The transfer could not be prepared. Nothing was sent."
        }
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..3_600 }
        return TransferPreparationException(code, userMessage, retryable, response.code, retryAfter)
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 32 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Strict, independently testable decoder for the short-lived unsigned preparation. */
object PreparedTransferParser {
    private val integerPattern = Regex("(?:0|[1-9][0-9]*)")
    private val timestampPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
    private val maximumU64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private val maximumSignedLong = BigInteger.valueOf(Long.MAX_VALUE)
    // Independent client ceilings ensure a compromised or misconfigured backend cannot turn an
    // otherwise valid transaction into an economically unbounded network fee.
    private val maximumSolanaTransferFee = BigInteger("5000000")
    private val commonKeys = setOf(
        "schemaVersion", "operationId", "chain", "caip2", "asset", "sender", "recipient", "amount",
        "baseUnits", "balanceBaseUnits", "estimatedFeeBaseUnits", "maxFeeBaseUnits", "preparedAt", "expiresAt",
    )

    fun parse(source: JSONObject, review: TransferReviewSnapshot, now: Instant = Instant.now()): PreparedTransfer {
        try {
            val chain = source.strictString("chain", 16)
            if (chain != "SOLANA") protocolFailure()
            val chainKeys = setOf("observedSlot", "recentBlockhash", "lastValidBlockHeight", "transactionBase64", "encoding")
            source.requireExactKeys(commonKeys + chainKeys)
            if (source.strictInteger("schemaVersion") != 1L) protocolFailure()
            if (source.strictString("operationId", 36) != review.operationId.value) protocolFailure()
            if (chain != review.networkId.wireChain()) protocolFailure()
            if (source.strictString("caip2", 80) != review.networkCaip2) protocolFailure()

            val asset = WalletAssetRegistry.asset(review.assetId)
            parseAsset(source.strictObject("asset"), asset)
            if (source.strictString("sender", 44) != review.sender.value) protocolFailure()
            if (source.strictString("recipient", 44) != review.recipient.value) protocolFailure()
            if (source.strictString("amount", 160) != review.displayAmount) protocolFailure()
            val baseUnits = source.unsigned("baseUnits", asset.maximumBaseUnits())
            if (baseUnits.toString() != review.baseUnits) protocolFailure()
            val balance = source.unsigned("balanceBaseUnits", asset.maximumBaseUnits())
            if (balance < baseUnits) protocolFailure()
            val estimatedFee = source.unsigned("estimatedFeeBaseUnits", maximumU64)
            val maxFee = source.unsigned("maxFeeBaseUnits", maximumU64)
            if (estimatedFee.signum() <= 0 || maxFee < estimatedFee) protocolFailure()
            val preparedAt = source.timestamp("preparedAt")
            val expiresAt = source.timestamp("expiresAt")
            validateLifetime(preparedAt, expiresAt, now)

            return parseSolana(source, review, asset, balance, estimatedFee, maxFee, preparedAt, expiresAt)
        } catch (failure: TransferPreparationException) {
            throw failure
        } catch (_: Exception) {
            throw protocolFailure()
        }
    }

    private fun parseAsset(source: JSONObject, expected: WalletAsset) {
        source.requireExactKeys(setOf("id", "symbol", "kind", "address", "decimals"))
        if (source.strictString("id", 32) != expected.id.wireId()) protocolFailure()
        if (source.strictString("symbol", 16) != expected.symbol) protocolFailure()
        val expectedKind = if (expected.kind == WalletAssetKind.NATIVE) "native" else "spl"
        if (source.strictString("kind", 16) != expectedKind) protocolFailure()
        val rawAddress = source.nullableStrictString("address", 64)
        if (expected.contractOrMint == null) {
            if (rawAddress != null) protocolFailure()
        } else {
            val canonical = rawAddress?.let(::canonicalSolanaPublicKey) ?: protocolFailure()
            if (canonical != canonicalSolanaPublicKey(expected.contractOrMint)) protocolFailure()
        }
        if (source.strictInteger("decimals") != expected.decimals.toLong()) protocolFailure()
    }

    private fun parseSolana(
        source: JSONObject,
        review: TransferReviewSnapshot,
        asset: WalletAsset,
        balance: BigInteger,
        estimatedFee: BigInteger,
        maxFee: BigInteger,
        preparedAt: Instant,
        expiresAt: Instant,
    ): SolanaPreparedTransfer {
        if (asset !== WalletAssetRegistry.solanaSol || source.strictString("encoding", 16) != "base64") protocolFailure()
        // Privy's SendOptions.minContextSlot is a signed Kotlin Long.
        val observedSlot = source.unsigned("observedSlot", maximumSignedLong)
        val lastValidBlockHeight = source.unsigned("lastValidBlockHeight", maximumU64)
        if (observedSlot.signum() <= 0 || lastValidBlockHeight.signum() <= 0 || estimatedFee != maxFee ||
            maxFee > maximumSolanaTransferFee) protocolFailure()
        val recentBlockhash = source.strictString("recentBlockhash", 44)
        if (canonicalSolanaPublicKey(recentBlockhash) != recentBlockhash) protocolFailure()
        val encoded = source.strictString("transactionBase64", 2_000)
        val transactionBytes = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { protocolFailure() }
        if (transactionBytes.size !in 150..1_232 || Base64.getEncoder().encodeToString(transactionBytes) != encoded) protocolFailure()
        val decoded = decodeSolanaSystemTransfer(transactionBytes)
        if (decoded.sender != review.sender.value || decoded.recipient != review.recipient.value ||
            decoded.recentBlockhash != recentBlockhash || decoded.baseUnits != review.baseUnits) protocolFailure()
        if (balance < BigInteger(review.baseUnits) + maxFee) protocolFailure()
        return SolanaPreparedTransfer(review, asset, balance, estimatedFee, maxFee, preparedAt, expiresAt,
            observedSlot, recentBlockhash, lastValidBlockHeight, transactionBytes)
    }

    private fun validateLifetime(preparedAt: Instant, expiresAt: Instant, now: Instant) {
        if (preparedAt.isAfter(now.plusSeconds(30)) || preparedAt.isBefore(now.minus(Duration.ofMinutes(2)))) protocolFailure()
        if (!expiresAt.isAfter(now) || !expiresAt.isAfter(preparedAt) || expiresAt.isAfter(preparedAt.plusSeconds(120))) {
            throw TransferPreparationException(
                TransferPreparationFailureCode.EXPIRED,
                "The network quote expired. Review the transfer again.",
                retryable = true,
            )
        }
    }

    private data class DecodedSolanaTransfer(
        val sender: String,
        val recipient: String,
        val recentBlockhash: String,
        val baseUnits: String,
    )

    private fun decodeSolanaSystemTransfer(raw: ByteArray): DecodedSolanaTransfer {
        var offset = 0
        fun byte(): Int {
            if (offset >= raw.size) protocolFailure()
            return raw[offset++].toInt() and 0xff
        }
        fun compact(): Int {
            // Every compact integer in this fixed System Program transfer is below 128.
            // Requiring its one-byte canonical form rejects alternate byte encodings.
            val next = byte()
            if (next and 0x80 != 0) protocolFailure()
            return next
        }
        fun bytes(count: Int): ByteArray {
            if (count < 0 || offset + count > raw.size) protocolFailure()
            return raw.copyOfRange(offset, offset + count).also { offset += count }
        }
        if (compact() != 1 || bytes(64).any { it != 0.toByte() }) protocolFailure()
        if (byte() != 1 || byte() != 0 || byte() != 1 || compact() != 3) protocolFailure()
        val keys = List(3) { encodeBase58(bytes(32)) }
        val blockhash = encodeBase58(bytes(32))
        if (compact() != 1 || byte() != 2 || compact() != 2 || byte() != 0 || byte() != 1 || compact() != 12) {
            protocolFailure()
        }
        val data = bytes(12)
        if (offset != raw.size || keys[2] != "11111111111111111111111111111111" ||
            data[0] != 2.toByte() || data.sliceArray(1..3).any { it != 0.toByte() }) protocolFailure()
        var amount = BigInteger.ZERO
        for (index in 7 downTo 0) amount = amount.shiftLeft(8).add(BigInteger.valueOf((data[index + 4].toInt() and 0xff).toLong()))
        return DecodedSolanaTransfer(keys[0], keys[1], blockhash, amount.toString())
    }

    private fun JSONObject.unsigned(key: String, maximum: BigInteger): BigInteger {
        val raw = strictString(key, 78)
        if (!integerPattern.matches(raw)) protocolFailure()
        val value = BigInteger(raw)
        if (value > maximum) protocolFailure()
        return value
    }

    private fun JSONObject.timestamp(key: String): Instant {
        val raw = strictString(key, 40)
        if (!timestampPattern.matches(raw)) protocolFailure()
        return try { Instant.parse(raw) } catch (_: DateTimeParseException) { protocolFailure() }
    }
}

private fun WalletNetworkId.wireChain(): String = "SOLANA"

private fun WalletAssetId.wireId(): String = when (this) {
    WalletAssetId.SOLANA_SOL -> "SOLANA:native"
    WalletAssetId.SOLANA_USDC -> "SOLANA:USDC"
}

private fun WalletAsset.maximumBaseUnits(): BigInteger = BigInteger.ONE.shiftLeft(baseUnitBits).subtract(BigInteger.ONE)

internal fun JSONObject.requireExactKeys(expected: Set<String>) {
    if (keys().asSequence().toSet() != expected) protocolFailure()
}

internal fun JSONObject.strictString(key: String, maximumLength: Int): String {
    val value = get(key)
    if (value !is String || value.isEmpty() || value.length > maximumLength || value.any(Char::isISOControl)) protocolFailure()
    return value
}

private fun JSONObject.nullableStrictString(key: String, maximumLength: Int): String? {
    val value = get(key)
    if (value === JSONObject.NULL) return null
    if (value !is String || value.isEmpty() || value.length > maximumLength || value.any(Char::isISOControl)) protocolFailure()
    return value
}

private fun JSONObject.strictObject(key: String): JSONObject = get(key) as? JSONObject ?: protocolFailure()

internal fun JSONObject.strictInteger(key: String): Long {
    val value = get(key)
    if (value !is Int && value !is Long) protocolFailure()
    return (value as Number).toLong()
}

internal fun preparationProtocolException() = TransferPreparationException(
    TransferPreparationFailureCode.PROTOCOL_ERROR,
    "The transfer service returned data that could not be verified. Nothing was sent.",
    retryable = true,
)

internal fun protocolFailure(): Nothing = throw preparationProtocolException()
