package com.elevencapital.app.wallet

import java.math.BigInteger
import java.time.Instant
import org.json.JSONObject

/**
 * A server-prepared transaction that is still unsigned. Every field is bound to the immutable
 * review snapshot that produced it; signing code must re-check [requireUsable] immediately before
 * asking Privy to sign.
 */
sealed class PreparedTransfer protected constructor(
    open val review: TransferReviewSnapshot,
    open val asset: WalletAsset,
    open val assetBalanceBaseUnits: BigInteger,
    open val estimatedFeeBaseUnits: BigInteger,
    open val maxFeeBaseUnits: BigInteger,
    open val preparedAt: Instant,
    open val expiresAt: Instant,
) {
    fun requireUsable(now: Instant = Instant.now()): PreparedTransfer {
        if (!now.isBefore(expiresAt)) {
            throw TransferPreparationException(
                code = TransferPreparationFailureCode.EXPIRED,
                userMessage = "The network quote expired. Review the transfer again.",
                retryable = true,
            )
        }
        return this
    }
}

/** Exact EIP-1559 or legacy transaction object accepted by Privy's Ethereum provider. */
class EthereumTransactionRequest internal constructor(
    val chainId: String,
    val from: String,
    val to: String,
    val nonce: String,
    val gas: String,
    val value: String,
    val data: String,
    val type: String,
    val gasPrice: String?,
    val maxFeePerGas: String?,
    val maxPriorityFeePerGas: String?,
) {
    /** Serialization is intentionally rebuilt from validated fields; raw server JSON is discarded. */
    fun rpcJson(): String = JSONObject()
        .put("chainId", chainId)
        .put("from", from)
        .put("to", to)
        .put("nonce", nonce)
        // Privy's Android eth_sendTransaction helper names this quantity gasLimit.
        .put("gasLimit", gas)
        .put("value", value)
        .put("data", data)
        .put("type", type)
        .also { transaction ->
            gasPrice?.let { transaction.put("gasPrice", it) }
            maxFeePerGas?.let { transaction.put("maxFeePerGas", it) }
            maxPriorityFeePerGas?.let { transaction.put("maxPriorityFeePerGas", it) }
        }
        .toString()
}

class EthereumPreparedTransfer internal constructor(
    override val review: TransferReviewSnapshot,
    override val asset: WalletAsset,
    override val assetBalanceBaseUnits: BigInteger,
    override val estimatedFeeBaseUnits: BigInteger,
    override val maxFeeBaseUnits: BigInteger,
    override val preparedAt: Instant,
    override val expiresAt: Instant,
    val observedBlock: BigInteger,
    val transaction: EthereumTransactionRequest,
) : PreparedTransfer(review, asset, assetBalanceBaseUnits, estimatedFeeBaseUnits, maxFeeBaseUnits,
    preparedAt, expiresAt)

class SolanaPreparedTransfer internal constructor(
    override val review: TransferReviewSnapshot,
    override val asset: WalletAsset,
    override val assetBalanceBaseUnits: BigInteger,
    override val estimatedFeeBaseUnits: BigInteger,
    override val maxFeeBaseUnits: BigInteger,
    override val preparedAt: Instant,
    override val expiresAt: Instant,
    val observedSlot: BigInteger,
    val recentBlockhash: String,
    val lastValidBlockHeight: BigInteger,
    transactionBytes: ByteArray,
) : PreparedTransfer(review, asset, assetBalanceBaseUnits, estimatedFeeBaseUnits, maxFeeBaseUnits,
    preparedAt, expiresAt) {
    private val immutableTransactionBytes = transactionBytes.copyOf()

    /** Each signing attempt receives its own copy so callers cannot mutate review state. */
    fun unsignedTransactionBytes(): ByteArray = immutableTransactionBytes.copyOf()
}

enum class TransferPreparationFailureCode {
    INVALID_REQUEST,
    INVALID_ADDRESS,
    UNSAFE_RECIPIENT,
    UNSUPPORTED_ASSET,
    WRONG_NETWORK,
    STALE_CHAIN_DATA,
    INSUFFICIENT_ASSET_BALANCE,
    INSUFFICIENT_FEE_BALANCE,
    SIMULATION_FAILED,
    TRANSACTION_MISMATCH,
    RPC_UNAVAILABLE,
    BUSY,
    RATE_LIMITED,
    AUTHENTICATION_REQUIRED,
    INTENT_CONFLICT,
    INTENT_NOT_FOUND,
    INTENT_STATE_INVALID,
    INTENT_STORAGE_UNAVAILABLE,
    SERVICE_ERROR,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
    EXPIRED,
}

/** Contains only bounded display-safe metadata; upstream bodies and submitted addresses are omitted. */
class TransferPreparationException internal constructor(
    val code: TransferPreparationFailureCode,
    val userMessage: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
) : Exception(userMessage) {
    init {
        require(userMessage.isNotBlank() && userMessage.length <= 160 && userMessage.none(Char::isISOControl))
        require(statusCode == null || statusCode in 400..599)
        require(retryAfterSeconds == null || retryAfterSeconds in 0..3_600)
    }
}
