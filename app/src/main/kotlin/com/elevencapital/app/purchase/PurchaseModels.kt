package com.elevencapital.app.purchase

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.json.JSONObject

/** Networks that the pinned Privy Android SDK can sign for in this release. */
enum class PurchaseNetwork(val wireName: String, val chainId: String, val walletChain: WalletChain) {
    ETHEREUM("ETHEREUM", "1", WalletChain.ETHEREUM),
    BASE("BASE", "8453", WalletChain.ETHEREUM),
    ARBITRUM("ARBITRUM", "42161", WalletChain.ETHEREUM),
    SOLANA("SOLANA", "1151111081099710", WalletChain.SOLANA),
    ;

    val displayName: String get() = when (this) {
        ETHEREUM -> "Ethereum"
        BASE -> "Base"
        ARBITRUM -> "Arbitrum"
        SOLANA -> "Solana"
    }

    companion object {
        fun parse(wireName: String, chainId: String): PurchaseNetwork =
            entries.singleOrNull { it.wireName == wireName && it.chainId == chainId }
                ?: throw purchaseProtocolFailure()
    }
}

data class PurchasePaymentAsset(
    val id: String,
    val symbol: String,
    val name: String,
    val network: PurchaseNetwork,
    val address: String,
    val decimals: Int,
    val balanceBaseUnits: BigInteger,
    val balance: BigDecimal,
    val usdValue: BigDecimal?,
    val enabled: Boolean,
    /** Provider-defined display units per raw token unit; one for ordinary fungible tokens. */
    val uiMultiplier: BigDecimal = BigDecimal.ONE,
) {
    init {
        require(id.isSafeProtocolId() && symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")))
        require(name.isSafeDisplayText(80) && decimals in 0..36)
        require(balanceBaseUnits.signum() >= 0 && balance.signum() >= 0)
        require(usdValue == null || usdValue.signum() >= 0)
        require(uiMultiplier.signum() > 0 && uiMultiplier.scale() <= 72 && uiMultiplier.precision() <= 96)
    }

    val label: String get() = "$symbol on ${network.displayName}"
}

data class PurchaseDestination(
    val id: String,
    val network: PurchaseNetwork,
    val address: String,
    val symbol: String,
    val decimals: Int,
    val enabled: Boolean,
    val uiMultiplier: BigDecimal = BigDecimal.ONE,
) {
    init {
        require(id.isSafeProtocolId() && symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")))
        require(decimals in 0..36)
        require(uiMultiplier.signum() > 0 && uiMultiplier.scale() <= 72 && uiMultiplier.precision() <= 96)
    }

    val label: String get() = "$symbol on ${network.displayName}"
}

data class PurchaseOptions(
    val stockId: String,
    val purchasable: Boolean,
    val reason: String?,
    val executionEnabled: Boolean,
    val executionReason: String?,
    val paymentAssets: List<PurchasePaymentAsset>,
    val destinations: List<PurchaseDestination>,
    val defaultPaymentAssetId: String?,
    val defaultDestinationId: String?,
) {
    init {
        require(stockId.isSafeStockId())
        require(reason == null || reason.isSafeDisplayText(240))
        require(executionReason == null || executionReason.isSafeDisplayText(240))
        if (executionEnabled) require(executionReason == null)
        require(paymentAssets.size <= 32 && paymentAssets.map { it.id }.toSet().size == paymentAssets.size)
        require(destinations.size <= 16 && destinations.map { it.id }.toSet().size == destinations.size)
        require(defaultPaymentAssetId == null || paymentAssets.any { it.id == defaultPaymentAssetId && it.enabled })
        require(defaultDestinationId == null || destinations.any { it.id == defaultDestinationId && it.enabled })
        if (purchasable) require(paymentAssets.any { it.enabled } && destinations.any { it.enabled })
    }
}

data class PurchaseQuoteBinding(
    val operationId: String,
    val userId: String,
    val stockId: String,
    val fromAssetId: String,
    val fromNetwork: PurchaseNetwork,
    val inputDecimals: Int,
    /** Null means Eleven chooses the best verified destination. */
    val requestedDestinationId: String?,
    val inputBaseUnits: BigInteger,
    val slippageBps: Int,
    val walletAddresses: Set<String>,
    val side: OrderSide = OrderSide.BUY,
    val inputUiMultiplier: BigDecimal = BigDecimal.ONE,
) {
    init {
        require(operationId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
        require(userId.isSafeOpaqueId(256) && stockId.isSafeStockId() && fromAssetId.isSafeProtocolId())
        require(requestedDestinationId == null || requestedDestinationId.isSafeProtocolId())
        require(inputDecimals in 0..36 && inputBaseUnits.signum() > 0 && slippageBps in 1..500 && walletAddresses.isNotEmpty())
        require(inputUiMultiplier.signum() > 0 && inputUiMultiplier.scale() <= 72 && inputUiMultiplier.precision() <= 96)
    }
}

sealed interface PurchaseAction {
    val id: String
    val index: Int
    val kind: PurchaseActionKind
    val network: PurchaseNetwork
    val walletAddress: String
}

enum class PurchaseActionKind { EVM_APPROVAL, EVM_ROUTE, SOLANA_ROUTE }

data class EvmPurchaseTransaction(
    val from: String,
    val to: String,
    val data: String,
    val value: String,
    val gas: String,
    val gasPrice: String?,
    val maxFeePerGas: String?,
    val maxPriorityFeePerGas: String?,
    val nonce: String?,
) {
    /** Raw server JSON is discarded; only the independently validated fields are serialized. */
    fun rpcJson(chainId: String): String = JSONObject()
        .put("chainId", "0x" + BigInteger(chainId).toString(16))
        .put("from", from)
        .put("to", to)
        .put("data", data)
        .put("value", value)
        .put("gasLimit", gas)
        .put("type", if (gasPrice != null) "0x0" else "0x2")
        .also { transaction ->
            gasPrice?.let { transaction.put("gasPrice", it) }
            maxFeePerGas?.let { transaction.put("maxFeePerGas", it) }
            maxPriorityFeePerGas?.let { transaction.put("maxPriorityFeePerGas", it) }
            nonce?.let { transaction.put("nonce", it) }
        }
        .toString()
}

data class EvmPurchaseAction(
    override val id: String,
    override val index: Int,
    override val kind: PurchaseActionKind,
    override val network: PurchaseNetwork,
    override val walletAddress: String,
    val transaction: EvmPurchaseTransaction,
) : PurchaseAction {
    init { require(network != PurchaseNetwork.SOLANA && kind != PurchaseActionKind.SOLANA_ROUTE) }
}

data class SolanaPurchaseAction(
    override val id: String,
    override val index: Int,
    override val kind: PurchaseActionKind,
    override val network: PurchaseNetwork,
    override val walletAddress: String,
    private val transactionBytes: ByteArray,
    val minContextSlot: Long?,
    /** Informational router freshness hint; Privy 0.15.0 does not accept it in SendOptions. */
    val lastValidBlockHeight: Long?,
) : PurchaseAction {
    init { require(network == PurchaseNetwork.SOLANA && kind == PurchaseActionKind.SOLANA_ROUTE && transactionBytes.size in 64..4_096) }
    fun unsignedTransactionBytes(): ByteArray = transactionBytes.copyOf()
}

data class PurchaseQuote(
    val id: String,
    val binding: PurchaseQuoteBinding,
    val destination: PurchaseDestination,
    val inputAmount: BigDecimal,
    val estimatedOutputAmount: BigDecimal,
    val executableUnitPriceUsd: BigDecimal?,
    val feesUsd: BigDecimal?,
    val priceImpactPercent: BigDecimal?,
    val slippageBps: Int,
    val minimumReceived: BigDecimal,
    val minimumReceivedBaseUnits: BigInteger,
    val expiresAt: Instant,
    val walletConfirmations: Int,
    val actions: List<PurchaseAction>,
    val executionEnabled: Boolean,
    val executionReason: String?,
) {
    init {
        require(id.isSafeProtocolId() && inputAmount.signum() > 0 && estimatedOutputAmount.signum() > 0)
        require(executableUnitPriceUsd == null || executableUnitPriceUsd.signum() > 0)
        require(feesUsd == null || feesUsd.signum() >= 0)
        require(priceImpactPercent == null || priceImpactPercent.abs() <= BigDecimal("100"))
        require(slippageBps in 1..500 && minimumReceived.signum() > 0 && minimumReceivedBaseUnits.signum() > 0)
        require(walletConfirmations in 1..4 && actions.size == walletConfirmations)
        require(actions.indices.all { actions[it].index == it } && actions.map { it.id }.toSet().size == actions.size)
        require(executionReason == null || executionReason.isSafeDisplayText(240))
        if (executionEnabled) require(executionReason == null)
    }

    fun requireUsable(now: Instant = Instant.now()): PurchaseQuote {
        if (!now.isBefore(expiresAt)) throw PurchaseException(
            PurchaseFailureCode.EXPIRED,
            "This route expired. Request a fresh quote before continuing.",
            retryable = true,
        )
        return this
    }
}

data class PurchaseActionSubmission(val actionId: String, val transactionId: String) {
    init {
        require(actionId.isSafeProtocolId())
        require(transactionId.length in 64..90 && transactionId.none(Char::isWhitespace) &&
            transactionId.none(Char::isISOControl))
    }
}

enum class PurchaseRouteState { COMMITTED, EXECUTING, COMPLETED, FAILED, EXPIRED }

data class PurchaseStatus(
    val quoteId: String,
    val state: PurchaseRouteState,
    val step: Int,
    val stepCount: Int,
    val transactionIds: List<String>,
    val receivedAmount: BigDecimal?,
    val message: String?,
    val updatedAt: Instant,
    val solanaTransactionSignature: String? = null,
) {
    init {
        require(quoteId.isSafeProtocolId() && stepCount in 1..32 && step in 0..stepCount)
        require(transactionIds.size <= 32 && transactionIds.toSet().size == transactionIds.size && transactionIds.all {
            it.length in 64..90 && it.none(Char::isWhitespace) && it.none(Char::isISOControl)
        })
        require(receivedAmount == null || receivedAmount.signum() >= 0)
        require(message == null || message.isSafeDisplayText(240))
        require(solanaTransactionSignature == null ||
            state == PurchaseRouteState.COMPLETED &&
            com.elevencapital.app.wallet.isCanonicalSolanaSignature(solanaTransactionSignature))
    }
}

enum class PurchaseFailureCode {
    INVALID_AMOUNT, INSUFFICIENT_BALANCE, NOT_PURCHASABLE, NO_ROUTE, EXPIRED,
    AUTHENTICATION_REQUIRED, RATE_LIMITED, SERVICE_UNAVAILABLE, PROTOCOL_ERROR,
    WALLET_REJECTED, SUBMISSION_UNCERTAIN, UNRESOLVED_PURCHASE,
}

class PurchaseException(
    val code: PurchaseFailureCode,
    val userMessage: String,
    val retryable: Boolean,
    val providerInvoked: Boolean = false,
) : Exception(userMessage) {
    init { require(userMessage.isSafeDisplayText(220)) }
}

enum class PurchasePhase {
    IDLE, LOADING_OPTIONS, ENTRY, QUOTING, REVIEW, COMMITTING, SIGNING, TRACKING, COMPLETE, FAILED, UNAVAILABLE,
}

data class PurchaseUiState(
    val stockId: String? = null,
    val phase: PurchasePhase = PurchasePhase.IDLE,
    val options: PurchaseOptions? = null,
    val selectedPaymentAssetId: String? = null,
    /** Null is Auto; the returned quote always contains the actual destination. */
    val selectedDestinationId: String? = null,
    val quote: PurchaseQuote? = null,
    val status: PurchaseStatus? = null,
    val message: String? = null,
    val ambiguousSubmission: Boolean = false,
    val side: OrderSide = OrderSide.BUY,
) {
    val selectedPaymentAsset: PurchasePaymentAsset?
        get() = options?.paymentAssets?.firstOrNull { it.id == selectedPaymentAssetId }
    val selectedDestination: PurchaseDestination?
        get() = selectedDestinationId?.let { id -> options?.destinations?.firstOrNull { it.id == id } }
    val busy: Boolean get() = phase in setOf(PurchasePhase.LOADING_OPTIONS, PurchasePhase.QUOTING,
        PurchasePhase.COMMITTING, PurchasePhase.SIGNING, PurchasePhase.TRACKING)
}

internal fun walletForNetwork(wallets: List<UserWallet>, network: PurchaseNetwork): UserWallet? =
    wallets.singleOrNull { it.chain == network.walletChain }

internal fun String.isSafeStockId(): Boolean {
    if (length !in 3..200 || count { it == ':' } != 1 || any { it.code !in 0x21..0x7e }) return false
    val provider = substringBefore(':')
    val assetId = substringAfter(':')
    return provider in setOf("backed", "backpack", "prestocks") && assetId.isNotEmpty() &&
        assetId.all { it.isLetterOrDigit() || it in "_.-" }
}
internal fun String.isSafeProtocolId(): Boolean = length in 1..160 && all { it.isLetterOrDigit() || it in "-_:." }
internal fun String.isSafeOpaqueId(maximum: Int): Boolean = length in 1..maximum &&
    none { it.isWhitespace() || it.isISOControl() }
internal fun String.isSafeDisplayText(maximum: Int): Boolean = length in 1..maximum && none(Char::isISOControl)

internal fun purchaseProtocolFailure(): Nothing = throw PurchaseException(
    PurchaseFailureCode.PROTOCOL_ERROR,
    "The purchase service returned data that could not be verified. Nothing was sent.",
    retryable = true,
)
