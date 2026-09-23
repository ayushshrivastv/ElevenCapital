package com.elevencapital.app.purchase

import android.util.Base64
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.wallet.canonicalEthereumAddress
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.wallet.parseSingleJsonObject
import com.elevencapital.core.stock.flow.OrderSide
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

fun interface PurchaseAccessTokenProvider {
    suspend fun token(userId: String): String
}

data class PurchaseQuoteRequest(
    val operationId: String,
    val userId: String,
    val stockId: String,
    val fromAssetId: String,
    val fromNetwork: PurchaseNetwork,
    val inputDecimals: Int,
    val destinationId: String?,
    val amountBaseUnits: BigInteger,
    val slippageBps: Int,
    val wallets: List<UserWallet>,
    /** Options are independently verified before the quote and bind the destination metadata. */
    val destinations: List<PurchaseDestination>,
    val side: OrderSide = OrderSide.BUY,
    /** Internal binding only; the request still sends raw base units and no display metadata. */
    val inputUiMultiplier: BigDecimal = BigDecimal.ONE,
)

internal class PurchaseClient internal constructor(
    baseUrl: String,
    private val tokenProvider: PurchaseAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now,
    allowLoopbackHttp: Boolean = false,
) : PurchaseExecutionBackend {
    private val base = baseUrl.trimEnd('/')
    private val baseHttpUrl = base.toHttpUrl()
    private val finiteHttp = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .cache(null)
        .build()

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (allowLoopbackHttp && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun options(
        userId: String,
        stockId: String,
        wallets: List<UserWallet>,
        side: OrderSide = OrderSide.BUY,
    ): PurchaseOptions {
        require(stockId.isSafeStockId())
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("stockId", stockId)
            .put("side", side.name)
            .put("wallets", walletsJson(wallets))
        val json = post(userId, listOf("v1", "purchases", "options"), body)
        return PurchaseProtocol.options(json, stockId)
    }

    suspend fun quote(request: PurchaseQuoteRequest): PurchaseQuote {
        require(request.operationId.matches(UUID_V4))
        require(request.stockId.isSafeStockId() && request.fromAssetId.isSafeProtocolId())
        require(request.destinationId == null || request.destinationId.isSafeProtocolId())
        require(request.inputDecimals in 0..36)
        require(request.amountBaseUnits.signum() > 0 && request.amountBaseUnits.bitLength() <= 256)
        require(request.slippageBps in 1..500)
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", request.operationId)
            .put("stockId", request.stockId)
            .put("side", request.side.name)
            .put("fromAssetId", request.fromAssetId)
            .put("destinationId", request.destinationId ?: JSONObject.NULL)
            .put("amountBaseUnits", request.amountBaseUnits.toString())
            .put("slippageBps", request.slippageBps)
            .put("wallets", walletsJson(request.wallets))
        val binding = PurchaseQuoteBinding(
            operationId = request.operationId,
            userId = request.userId,
            stockId = request.stockId,
            fromAssetId = request.fromAssetId,
            fromNetwork = request.fromNetwork,
            inputDecimals = request.inputDecimals,
            requestedDestinationId = request.destinationId,
            inputBaseUnits = request.amountBaseUnits,
            slippageBps = request.slippageBps,
            walletAddresses = request.wallets.map { canonicalWalletAddress(it) }.toSet(),
            side = request.side,
            inputUiMultiplier = request.inputUiMultiplier,
        )
        return PurchaseProtocol.quote(post(request.userId, listOf("v1", "purchases", "quote"), body), binding,
            request.destinations, now())
    }

    override suspend fun commit(userId: String, quote: PurchaseQuote): Boolean {
        quote.requireServerExecutable()
        quote.requireUsable(now())
        val json = post(userId, listOf("v1", "purchases", quote.id, "commit"),
            JSONObject().put("schemaVersion", 1))
        return PurchaseProtocol.commit(json, quote.id)
    }

    override suspend fun invoking(userId: String, quote: PurchaseQuote, action: PurchaseAction): Boolean {
        quote.requireServerExecutable()
        quote.requireUsable(now())
        if (quote.actions.singleOrNull { it.id == action.id && it.index == action.index } == null) purchaseProtocolFailure()
        val json = post(userId, listOf("v1", "purchases", quote.id, "actions", action.id, "invoking"),
            JSONObject().put("schemaVersion", 1))
        return PurchaseProtocol.invoking(json, quote.id, action.id)
    }

    override suspend fun releaseDefinitelyNotInvoked(
        userId: String,
        quoteId: String,
        actionId: String,
    ): PurchaseStatus {
        if (!quoteId.isSafeProtocolId() || !actionId.isSafeProtocolId()) purchaseProtocolFailure()
        val body = JSONObject().put("schemaVersion", 1).put("reason", "provider_definitely_not_invoked")
        return PurchaseProtocol.status(post(userId,
            listOf("v1", "purchases", quoteId, "actions", actionId, "release"), body), quoteId)
    }

    override suspend fun submitted(
        userId: String,
        quote: PurchaseQuote,
        submission: PurchaseActionSubmission,
    ): PurchaseStatus {
        quote.requireServerExecutable()
        val action = quote.actions.singleOrNull { it.id == submission.actionId } ?: purchaseProtocolFailure()
        return submitted(userId, quote.id, action.id, submission.transactionId)
    }

    /** Restart recovery for a provider hash durably saved before its acknowledgement arrived. */
    suspend fun submitted(userId: String, quoteId: String, actionId: String, transactionId: String): PurchaseStatus {
        if (!quoteId.isSafeProtocolId() || !actionId.isSafeProtocolId() ||
            !(transactionId.matches(Regex("0x[0-9a-f]{64}")) || isCanonicalSolanaSignature(transactionId))) {
            purchaseProtocolFailure()
        }
        val body = JSONObject().put("schemaVersion", 1).put("transactionId", transactionId)
        val json = post(userId, listOf("v1", "purchases", quoteId, "actions", actionId, "submitted"), body)
        return PurchaseProtocol.status(json, quoteId).also { status ->
            if (transactionId !in status.transactionIds) purchaseProtocolFailure()
        }
    }

    override suspend fun status(userId: String, quoteId: String): PurchaseStatus {
        require(quoteId.isSafeProtocolId())
        return PurchaseProtocol.status(get(userId, listOf("v1", "purchases", quoteId, "status")), quoteId)
    }

    private suspend fun post(userId: String, path: List<String>, body: JSONObject): JSONObject =
        request(userId, Request.Builder().url(url(path))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)))

    private suspend fun get(userId: String, path: List<String>): JSONObject =
        request(userId, Request.Builder().url(url(path)).get())

    private fun url(path: List<String>) = baseHttpUrl.newBuilder().apply {
        path.forEach { segment ->
            require(segment.length in 1..180 && segment.none { it.isWhitespace() || it.isISOControl() })
            addPathSegment(segment)
        }
    }.build()

    private suspend fun request(userId: String, builder: Request.Builder): JSONObject {
        if (!userId.isSafeOpaqueId(256)) purchaseProtocolFailure()
        val accessToken = tokenProvider.token(userId)
        if (accessToken.length !in 32..8_192 || accessToken.count { it == '.' } != 2 ||
            accessToken.any { !(it.isLetterOrDigit() || it in "-_.~") }) {
            throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session expired. Sign in again.", retryable = false)
        }
        val request = builder.header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = finiteHttp.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        PurchaseException(PurchaseFailureCode.SERVICE_UNAVAILABLE,
                            "The route service is reconnecting. Nothing was sent.", retryable = true))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            val bytes = readBounded(it)
                            val json = try { parseSingleJsonObject(bytes) } catch (_: JSONException) { purchaseProtocolFailure() }
                            if (!it.isSuccessful) throw serverFailure(it.code, json)
                            if (it.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") purchaseProtocolFailure()
                            if (continuation.isActive) continuation.resume(json)
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: purchaseProtocolFailure()
        if (body.contentLength() > MAX_RESPONSE_BYTES) purchaseProtocolFailure()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_RESPONSE_BYTES) purchaseProtocolFailure()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun serverFailure(status: Int, json: JSONObject): PurchaseException {
        json.exactKeys(setOf("error", "message"))
        val code = json.text("error", 64)
        json.text("message", 512) // Validate, then deliberately discard server-authored UI text.
        val failure = when (code) {
            "authentication_required", "invalid_access_token" -> PurchaseFailureCode.AUTHENTICATION_REQUIRED
            "not_purchasable", "stock_not_purchasable", "unsupported_asset", "unsupported_destination",
            "asset_unavailable" -> PurchaseFailureCode.NOT_PURCHASABLE
            "no_route", "route_unavailable" -> PurchaseFailureCode.NO_ROUTE
            "insufficient_balance" -> PurchaseFailureCode.INSUFFICIENT_BALANCE
            "quote_expired" -> PurchaseFailureCode.EXPIRED
            "rate_limited", "quote_capacity" -> PurchaseFailureCode.RATE_LIMITED
            "unresolved_purchase", "operation_conflict", "intent_state_invalid", "quote_conflict",
            "quote_state_invalid", "action_out_of_order", "quote_not_found" -> PurchaseFailureCode.UNRESOLVED_PURCHASE
            "authentication_unavailable", "busy", "router_unavailable", "unsafe_router_response",
            "service_unavailable", "internal_error", "purchase_storage_unavailable" ->
                PurchaseFailureCode.SERVICE_UNAVAILABLE
            "invalid_purchase", "invalid_request" -> PurchaseFailureCode.PROTOCOL_ERROR
            else -> purchaseProtocolFailure()
        }
        val validStatus = when (failure) {
            PurchaseFailureCode.AUTHENTICATION_REQUIRED -> status == 401
            PurchaseFailureCode.NOT_PURCHASABLE, PurchaseFailureCode.NO_ROUTE,
            PurchaseFailureCode.INSUFFICIENT_BALANCE, PurchaseFailureCode.EXPIRED -> status == 422
            PurchaseFailureCode.RATE_LIMITED -> status == 429
            PurchaseFailureCode.UNRESOLVED_PURCHASE -> status == 409 || status == 422
            PurchaseFailureCode.SERVICE_UNAVAILABLE -> status in 500..503
            PurchaseFailureCode.PROTOCOL_ERROR -> status == 400 || status == 422
            else -> false
        }
        if (!validStatus) purchaseProtocolFailure()
        val message = when (failure) {
            PurchaseFailureCode.AUTHENTICATION_REQUIRED -> "Your secure wallet session expired. Sign in again."
            PurchaseFailureCode.NOT_PURCHASABLE -> "This listing is not available for wallet purchase."
            PurchaseFailureCode.NO_ROUTE -> "No verified route is currently available for this payment asset."
            PurchaseFailureCode.INSUFFICIENT_BALANCE -> "The selected wallet balance is not enough for this order."
            PurchaseFailureCode.EXPIRED -> "This route expired. Request a fresh quote before continuing."
            PurchaseFailureCode.RATE_LIMITED -> "Route requests are busy. Wait a moment and try again."
            PurchaseFailureCode.UNRESOLVED_PURCHASE -> "This wallet has an order in progress. Check its status before trying again."
            PurchaseFailureCode.SERVICE_UNAVAILABLE -> "The route service is unavailable. Nothing was sent."
            PurchaseFailureCode.PROTOCOL_ERROR -> "The reviewed route is no longer valid. Nothing else was submitted."
            else -> "The order could not continue. Nothing was sent."
        }
        return PurchaseException(failure, message, failure in setOf(PurchaseFailureCode.NO_ROUTE,
            PurchaseFailureCode.EXPIRED, PurchaseFailureCode.RATE_LIMITED, PurchaseFailureCode.SERVICE_UNAVAILABLE))
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

private fun PurchaseQuote.requireServerExecutable() {
    if (!executionEnabled) throw PurchaseException(
        PurchaseFailureCode.NOT_PURCHASABLE,
        executionReason ?: "This reviewed route is not executable. No funds were moved.",
        retryable = false,
    )
}

object PurchaseProtocol {
    private val decimal = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,18})?")
    private val unsigned = Regex("(?:0|[1-9][0-9]*)")
    private val hexQuantity = Regex("0x(?:0|[1-9a-f][0-9a-f]*)")
    private val hexData = Regex("0x(?:[0-9a-f]{2})*")
    private val evmAddress = Regex("0x[0-9a-fA-F]{40}")
    private val maximumU256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE)

    fun options(source: JSONObject, requestedStockId: String): PurchaseOptions = guarded {
        source.exactKeys(setOf("schemaVersion", "stockId", "purchasable", "reason", "paymentAssets",
            "destinations", "defaultPaymentAssetId", "defaultDestinationId", "executionEnabled", "executionReason"))
        source.version()
        val stockId = source.text("stockId", 200)
        if (stockId != requestedStockId || !stockId.isSafeStockId()) purchaseProtocolFailure()
        val assets = source.array("paymentAssets").objects(8).map(::paymentAsset)
        val destinations = source.array("destinations").objects(4).map(::destination)
        PurchaseOptions(stockId, source.boolean("purchasable"), source.nullableText("reason", 240),
            source.boolean("executionEnabled"), source.nullableText("executionReason", 240), assets,
            destinations, source.nullableText("defaultPaymentAssetId", 160),
            source.nullableText("defaultDestinationId", 160))
    }

    fun quote(
        source: JSONObject,
        binding: PurchaseQuoteBinding,
        knownDestinations: List<PurchaseDestination>,
        now: Instant,
    ): PurchaseQuote = guarded {
        source.exactKeys(setOf("schemaVersion", "quoteId", "operationId", "stockId", "fromAssetId", "destinationId",
            "inputAmount", "inputBaseUnits", "estimatedOutputAmount", "estimatedOutputBaseUnits",
            "executableUnitPriceUsd", "feesUsd", "priceImpactPercent", "slippageBps", "minimumReceived",
            "minimumReceivedBaseUnits", "expiresAt", "walletConfirmations", "actions",
            "executionEnabled", "executionReason"), setOf("outputDecimals", "outputUiMultiplier"))
        source.version()
        if (source.text("operationId", 36) != binding.operationId || source.text("stockId", 200) != binding.stockId ||
            source.text("fromAssetId", 160) != binding.fromAssetId ||
            source.unsigned("inputBaseUnits", maximumU256) != binding.inputBaseUnits) purchaseProtocolFailure()
        val destinationId = source.text("destinationId", 160)
        if (binding.requestedDestinationId != null && destinationId != binding.requestedDestinationId) purchaseProtocolFailure()
        val destination = knownDestinations.singleOrNull { it.id == destinationId && it.enabled }
            ?: purchaseProtocolFailure()
        val actions = source.array("actions").objects(4).mapIndexed { expected, value -> action(value, expected, binding) }
        val expiresAt = source.instant("expiresAt")
        if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(30 * 60))) purchaseProtocolFailure()
        val inputAmount = source.positiveDecimal("inputAmount")
        if (inputAmount.compareTo(displayAmountForRaw(binding.inputBaseUnits, binding.inputDecimals,
                binding.inputUiMultiplier)) != 0) purchaseProtocolFailure()
        val hasOutputDecimals = source.has("outputDecimals")
        val hasOutputMultiplier = source.has("outputUiMultiplier")
        if (hasOutputDecimals != hasOutputMultiplier) purchaseProtocolFailure()
        val outputDecimals = if (hasOutputDecimals) source.tokenDecimals("outputDecimals") else destination.decimals
        val outputUiMultiplier = if (hasOutputMultiplier) source.positiveDecimal("outputUiMultiplier") else BigDecimal.ONE
        if (outputDecimals != destination.decimals ||
            outputUiMultiplier.compareTo(destination.uiMultiplier) != 0) purchaseProtocolFailure()
        val outputAmount = source.positiveDecimal("estimatedOutputAmount")
        val outputBaseUnits = source.unsigned("estimatedOutputBaseUnits", maximumU256)
        if (outputBaseUnits.signum() <= 0) purchaseProtocolFailure()
        if (outputAmount.compareTo(displayAmountForRaw(outputBaseUnits, outputDecimals,
                outputUiMultiplier)) != 0) purchaseProtocolFailure()
        val minimumReceived = source.positiveDecimal("minimumReceived")
        val minimumReceivedBaseUnits = source.unsigned("minimumReceivedBaseUnits", maximumU256)
        if (minimumReceived.compareTo(displayAmountForRaw(minimumReceivedBaseUnits, outputDecimals,
                outputUiMultiplier)) != 0) purchaseProtocolFailure()
        val slippageBps = source.integer("slippageBps").toInt()
        if (slippageBps != binding.slippageBps) purchaseProtocolFailure()
        PurchaseQuote(
            id = source.text("quoteId", 160), binding = binding,
            destination = destination,
            inputAmount = inputAmount,
            estimatedOutputAmount = outputAmount,
            executableUnitPriceUsd = source.nullableDecimal("executableUnitPriceUsd", positive = true),
            feesUsd = source.nullableDecimal("feesUsd", positive = false),
            priceImpactPercent = source.nullableDecimal("priceImpactPercent", positive = false),
            slippageBps = slippageBps,
            minimumReceived = minimumReceived,
            minimumReceivedBaseUnits = minimumReceivedBaseUnits,
            expiresAt = expiresAt,
            walletConfirmations = source.integer("walletConfirmations").toInt(), actions = actions,
            executionEnabled = source.boolean("executionEnabled"),
            executionReason = source.nullableText("executionReason", 240),
        ).also {
            // Output amount is parsed even when only its display form is rendered; minimum cannot exceed it.
            if (it.minimumReceivedBaseUnits > outputBaseUnits) purchaseProtocolFailure()
        }
    }

    fun commit(source: JSONObject, quoteId: String): Boolean = guarded {
        source.exactKeys(setOf("schemaVersion", "quoteId", "state", "idempotent"))
        source.version()
        if (source.text("quoteId", 160) != quoteId || source.text("state", 16) != "COMMITTED") purchaseProtocolFailure()
        source.boolean("idempotent")
    }

    fun invoking(source: JSONObject, quoteId: String, actionId: String): Boolean = guarded {
        source.exactKeys(setOf("schemaVersion", "quoteId", "actionId", "state", "idempotent"))
        source.version()
        if (source.text("quoteId", 160) != quoteId || source.text("actionId", 160) != actionId ||
            source.text("state", 16) != "INVOKING") purchaseProtocolFailure()
        source.boolean("idempotent")
    }

    fun status(source: JSONObject, quoteId: String): PurchaseStatus = guarded {
        source.exactKeys(setOf("schemaVersion", "quoteId", "state", "step", "stepCount", "transactionIds",
            "receivedAmount", "message", "updatedAt"), setOf("solanaTransactionSignature"))
        source.version()
        if (source.text("quoteId", 160) != quoteId) purchaseProtocolFailure()
        val state = try { PurchaseRouteState.valueOf(source.text("state", 16)) } catch (_: Exception) { purchaseProtocolFailure() }
        val transactions = source.array("transactionIds").strings(32, 90)
        transactions.forEach { transaction ->
            if (!(transaction.matches(Regex("0x[0-9a-f]{64}")) || isCanonicalSolanaSignature(transaction))) purchaseProtocolFailure()
        }
        val solanaTransactionSignature = if (source.has("solanaTransactionSignature"))
            source.nullableText("solanaTransactionSignature", 90) else null
        if (solanaTransactionSignature != null &&
            (state != PurchaseRouteState.COMPLETED || !isCanonicalSolanaSignature(solanaTransactionSignature))) {
            purchaseProtocolFailure()
        }
        PurchaseStatus(quoteId, state, source.integer("step").toInt(), source.integer("stepCount").toInt(),
            transactions, source.nullableDecimal("receivedAmount", positive = false),
            source.nullableText("message", 240), source.instant("updatedAt"), solanaTransactionSignature)
    }

    private fun paymentAsset(source: JSONObject): PurchasePaymentAsset {
        source.exactKeys(setOf("id", "symbol", "name", "network", "chainId", "address", "decimals",
            "balanceBaseUnits", "balance", "usdValue", "enabled"), setOf("uiMultiplier"))
        val network = PurchaseNetwork.parse(source.text("network", 16), source.text("chainId", 24))
        val decimals = source.tokenDecimals("decimals")
        val baseUnits = source.unsigned("balanceBaseUnits", maximumU256)
        val balance = source.nonNegativeDecimal("balance")
        val uiMultiplier = if (source.has("uiMultiplier")) source.positiveDecimal("uiMultiplier") else BigDecimal.ONE
        if (balance.compareTo(displayAmountForRaw(baseUnits, decimals, uiMultiplier)) != 0) purchaseProtocolFailure()
        val address = source.text("address", 64)
        validateAddress(network, address, allowNative = true)
        return PurchasePaymentAsset(source.text("id", 160), source.text("symbol", 30), source.text("name", 80),
            network, address, decimals, baseUnits, balance, source.nullableDecimal("usdValue", positive = false),
            source.boolean("enabled"), uiMultiplier).also { asset ->
                // Privy 0.15.0 provisions EVM and Solana wallets only; Bitcoin is never presented as executable.
                if (asset.symbol == "BTC" && asset.enabled) purchaseProtocolFailure()
            }
    }

    private fun destination(source: JSONObject): PurchaseDestination {
        source.exactKeys(setOf("id", "network", "chainId", "address", "symbol", "decimals", "enabled"),
            setOf("uiMultiplier"))
        val network = PurchaseNetwork.parse(source.text("network", 16), source.text("chainId", 24))
        val address = source.text("address", 64)
        validateAddress(network, address, allowNative = false)
        return PurchaseDestination(source.text("id", 180), network, address, source.text("symbol", 30),
            source.tokenDecimals("decimals"), source.boolean("enabled"),
            if (source.has("uiMultiplier")) source.positiveDecimal("uiMultiplier") else BigDecimal.ONE)
    }

    private fun action(source: JSONObject, expectedIndex: Int, binding: PurchaseQuoteBinding): PurchaseAction {
        val type = source.text("type", 32)
        val common = setOf("id", "index", "type", "network", "chainId", "walletAddress")
        val network = PurchaseNetwork.parse(source.text("network", 16), source.text("chainId", 24))
        val wallet = source.text("walletAddress", 64)
        val canonical = validateAddress(network, wallet, allowNative = false)
        if (network != binding.fromNetwork || canonical !in binding.walletAddresses ||
            source.integer("index").toInt() != expectedIndex) purchaseProtocolFailure()
        return when (type) {
            "EVM_APPROVAL", "EVM_ROUTE" -> {
                source.exactKeys(common + "transaction")
                if (network == PurchaseNetwork.SOLANA) purchaseProtocolFailure()
                EvmPurchaseAction(source.text("id", 160), expectedIndex, PurchaseActionKind.valueOf(type), network, canonical,
                    evmTransaction(source.obj("transaction"), canonical))
            }
            "SOLANA_ROUTE" -> {
                source.exactKeys(common + setOf("transactionBase64", "minContextSlot", "lastValidBlockHeight"))
                if (network != PurchaseNetwork.SOLANA) purchaseProtocolFailure()
                val encoded = source.text("transactionBase64", 8_192)
                val decoded = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: Exception) { purchaseProtocolFailure() }
                if (Base64.encodeToString(decoded, Base64.NO_WRAP) != encoded) purchaseProtocolFailure()
                SolanaPurchaseAction(source.text("id", 160), expectedIndex, PurchaseActionKind.SOLANA_ROUTE, network, canonical, decoded,
                    source.nullableUnsignedLongString("minContextSlot"),
                    source.nullableUnsignedLongString("lastValidBlockHeight"))
            }
            else -> purchaseProtocolFailure()
        }
    }

    private fun evmTransaction(source: JSONObject, expectedFrom: String): EvmPurchaseTransaction {
        source.exactKeys(setOf("from", "to", "data", "value", "gas", "gasPrice", "maxFeePerGas",
            "maxPriorityFeePerGas", "nonce"))
        val from = validateAddress(PurchaseNetwork.ETHEREUM, source.text("from", 42), allowNative = false)
        val to = validateAddress(PurchaseNetwork.ETHEREUM, source.text("to", 42), allowNative = false)
        if (from != expectedFrom || to.equals(ZERO_ADDRESS, ignoreCase = true)) purchaseProtocolFailure()
        val data = source.text("data", 131_074).lowercase()
        if (!hexData.matches(data)) purchaseProtocolFailure()
        val value = source.hex("value", maximumU256)
        val gas = source.hex("gas", BigInteger("5000000"))
        if (BigInteger(gas.substring(2), 16).signum() <= 0) purchaseProtocolFailure()
        val gasPrice = source.nullableHex("gasPrice", MAX_FEE_PER_GAS)
        val maxFee = source.nullableHex("maxFeePerGas", MAX_FEE_PER_GAS)
        val priorityFee = source.nullableHex("maxPriorityFeePerGas", MAX_PRIORITY_FEE)
        if ((gasPrice == null) == (maxFee == null) || (maxFee != null && priorityFee == null)) purchaseProtocolFailure()
        val selectedFee = BigInteger((gasPrice ?: maxFee!!).substring(2), 16)
        if (selectedFee.multiply(BigInteger(gas.substring(2), 16)) > MAX_TOTAL_FEE) purchaseProtocolFailure()
        return EvmPurchaseTransaction(from, to, data, value, gas, gasPrice, maxFee, priorityFee,
            source.nullableHex("nonce", BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)))
    }

    private fun validateAddress(network: PurchaseNetwork, raw: String, allowNative: Boolean): String = when (network) {
        PurchaseNetwork.SOLANA -> {
            if (allowNative && raw == "11111111111111111111111111111111") raw
            else canonicalSolanaPublicKey(raw) ?: purchaseProtocolFailure()
        }
        else -> {
            if (!evmAddress.matches(raw)) purchaseProtocolFailure()
            val canonical = canonicalEthereumAddress(raw) ?: purchaseProtocolFailure()
            if (!allowNative && canonical.equals(ZERO_ADDRESS, ignoreCase = true)) purchaseProtocolFailure()
            canonical
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (failure: PurchaseException) { throw failure }
    catch (_: Exception) { purchaseProtocolFailure() }

    private val MAX_FEE_PER_GAS = BigInteger("2000000000000")
    private val MAX_PRIORITY_FEE = BigInteger("10000000000")
    private val MAX_TOTAL_FEE = BigInteger("100000000000000000") // 0.1 native EVM asset
    private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
}

private fun walletsJson(wallets: List<UserWallet>): JSONArray {
    if (wallets.size != 2 || wallets.map { it.chain }.toSet() != WalletChain.entries.toSet()) purchaseProtocolFailure()
    val values = JSONArray()
    wallets.sortedBy { it.chain.ordinal }.forEach { wallet ->
        canonicalWalletAddress(wallet)
        values.put(JSONObject().put("chain", wallet.chain.name).put("address", wallet.address))
    }
    return values
}

private fun canonicalWalletAddress(wallet: UserWallet): String = when (wallet.chain) {
    WalletChain.ETHEREUM -> canonicalEthereumAddress(wallet.address) ?: purchaseProtocolFailure()
    WalletChain.SOLANA -> canonicalSolanaPublicKey(wallet.address) ?: purchaseProtocolFailure()
}

private fun JSONObject.version() { if (integer("schemaVersion") != 1L) purchaseProtocolFailure() }
private fun JSONObject.exactKeys(required: Set<String>, optional: Set<String> = emptySet()) {
    val actual = keys().asSequence().toSet()
    if (!actual.containsAll(required) || actual.any { it !in required && it !in optional }) purchaseProtocolFailure()
}
private fun JSONObject.text(key: String, maximum: Int): String {
    val value = get(key)
    if (value !is String || value.isEmpty() || value.length > maximum || value.any(Char::isISOControl)) purchaseProtocolFailure()
    return value
}
private fun JSONObject.nullableText(key: String, maximum: Int): String? = when (val value = get(key)) {
    JSONObject.NULL -> null
    is String -> value.takeIf { it.isNotEmpty() && it.length <= maximum && it.none(Char::isISOControl) }
        ?: purchaseProtocolFailure()
    else -> purchaseProtocolFailure()
}
private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: purchaseProtocolFailure()
private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    if (value !is Int && value !is Long) purchaseProtocolFailure()
    return (value as Number).toLong().takeIf { it >= 0 } ?: purchaseProtocolFailure()
}
private fun JSONObject.tokenDecimals(key: String): Int = integer(key).takeIf { it in 0..36 }?.toInt()
    ?: purchaseProtocolFailure()
private fun JSONObject.nullableInteger(key: String): Long? = when (get(key)) {
    JSONObject.NULL -> null
    is Int, is Long -> integer(key)
    else -> purchaseProtocolFailure()
}
private fun JSONObject.nullableUnsignedLongString(key: String): Long? = when (get(key)) {
    JSONObject.NULL -> null
    is String -> {
        val raw = text(key, 20)
        if (!PurchaseProtocolUnsigned.matches(raw)) purchaseProtocolFailure()
        raw.toLongOrNull()?.takeIf { it >= 0 } ?: purchaseProtocolFailure()
    }
    else -> purchaseProtocolFailure()
}
private fun JSONObject.array(key: String): JSONArray = get(key) as? JSONArray ?: purchaseProtocolFailure()
private fun JSONObject.obj(key: String): JSONObject = get(key) as? JSONObject ?: purchaseProtocolFailure()
private fun JSONObject.nonNegativeDecimal(key: String): BigDecimal {
    val value = text(key, 96)
    if (!PurchaseProtocolDecimal.matches(value)) purchaseProtocolFailure()
    return BigDecimal(value)
}
private fun JSONObject.positiveDecimal(key: String): BigDecimal = nonNegativeDecimal(key).takeIf { it.signum() > 0 }
    ?: purchaseProtocolFailure()
private fun JSONObject.nullableDecimal(key: String, positive: Boolean): BigDecimal? = when (get(key)) {
    JSONObject.NULL -> null
    is String -> nonNegativeDecimal(key).takeIf { !positive || it.signum() > 0 } ?: purchaseProtocolFailure()
    else -> purchaseProtocolFailure()
}
private fun JSONObject.unsigned(key: String, maximum: BigInteger): BigInteger {
    val value = text(key, 96)
    if (!PurchaseProtocolUnsigned.matches(value)) purchaseProtocolFailure()
    return BigInteger(value).takeIf { it <= maximum } ?: purchaseProtocolFailure()
}
private fun JSONObject.hex(key: String, maximum: BigInteger): String {
    val value = text(key, 68).lowercase()
    if (!PurchaseProtocolHex.matches(value)) purchaseProtocolFailure()
    if (BigInteger(value.substring(2), 16) > maximum) purchaseProtocolFailure()
    return value
}
private fun JSONObject.nullableHex(key: String, maximum: BigInteger): String? = when (get(key)) {
    JSONObject.NULL -> null
    is String -> hex(key, maximum)
    else -> purchaseProtocolFailure()
}
private fun JSONObject.instant(key: String): Instant = try { Instant.parse(text(key, 40)) }
catch (_: DateTimeParseException) { purchaseProtocolFailure() }
private fun JSONArray.objects(maximum: Int): List<JSONObject> {
    if (length() > maximum) purchaseProtocolFailure()
    return (0 until length()).map { get(it) as? JSONObject ?: purchaseProtocolFailure() }
}
private fun JSONArray.strings(maximum: Int, maximumLength: Int): List<String> {
    if (length() > maximum) purchaseProtocolFailure()
    return (0 until length()).map { index ->
        (get(index) as? String)?.takeIf { it.isNotEmpty() && it.length <= maximumLength && it.none(Char::isISOControl) }
            ?: purchaseProtocolFailure()
    }
}

private val PurchaseProtocolDecimal = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,72})?")
private val PurchaseProtocolUnsigned = Regex("(?:0|[1-9][0-9]*)")
private val PurchaseProtocolHex = Regex("0x(?:0|[1-9a-f][0-9a-f]*)")

internal fun displayAmountForRaw(
    rawBaseUnits: BigInteger,
    decimals: Int,
    uiMultiplier: BigDecimal,
): BigDecimal {
    require(rawBaseUnits.signum() >= 0 && decimals in 0..36 && uiMultiplier.signum() > 0)
    return BigDecimal(rawBaseUnits).multiply(uiMultiplier)
        .setScale(0, RoundingMode.DOWN)
        .movePointLeft(decimals)
}

internal fun rawBaseUnitsForDisplay(
    displayAmount: BigDecimal,
    decimals: Int,
    uiMultiplier: BigDecimal,
): BigInteger {
    require(displayAmount.signum() >= 0 && decimals in 0..36 && uiMultiplier.signum() > 0)
    return displayAmount.movePointRight(decimals)
        .divide(uiMultiplier, 0, RoundingMode.DOWN)
        .toBigIntegerExact()
}
