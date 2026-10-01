package com.elevencapital.app.communitybuy

import com.elevencapital.app.tokenlaunch.METEORA_DBC_PROGRAM_ID
import com.elevencapital.app.tokenlaunch.WRAPPED_SOL_MINT
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A mainnet DBC swap, deliberately separate from the stock purchase route. */
data class CommunityBuyRequest(
    val operationId: String,
    val userId: String,
    val walletId: String,
    val walletAddress: String,
    val mintAddress: String,
    val amountInLamports: Long,
    val slippageBps: Int,
) {
    init {
        require(UUID.fromString(operationId).toString() == operationId)
        require(userId.isNotBlank() && userId.length <= 256 && userId.none(Char::isWhitespace))
        require(walletId.isNotBlank() && walletId.length <= 200 && walletId.none(Char::isWhitespace))
        require(canonicalSolanaPublicKey(walletAddress) == walletAddress)
        require(canonicalSolanaPublicKey(mintAddress) == mintAddress)
        require(mintAddress != WRAPPED_SOL_MINT)
        require(amountInLamports in 100_000L..1_000_000_000L)
        require(slippageBps in 1..500)
    }

    companion object {
        fun create(
            userId: String,
            walletId: String,
            walletAddress: String,
            mintAddress: String,
            amountInLamports: Long,
            slippageBps: Int = 100,
        ) = CommunityBuyRequest(
            UUID.randomUUID().toString().lowercase(Locale.US), userId, walletId,
            walletAddress, mintAddress, amountInLamports, slippageBps,
        )
    }
}

class PreparedCommunityBuy internal constructor(
    val request: CommunityBuyRequest,
    val poolAddress: String,
    val configAddress: String,
    val tokenDecimals: Int,
    val expectedAmountOut: BigInteger,
    val minimumAmountOut: BigInteger,
    val estimatedFeeLamports: Long,
    val maximumWalletDebitLamports: Long,
    val observedSlot: Long,
    val recentBlockhash: String,
    val lastValidBlockHeight: Long,
    val preparedAt: Instant,
    val expiresAt: Instant,
    val transactionSha256: String,
    transaction: ByteArray,
    message: ByteArray,
) {
    private val immutableTransaction = transaction.copyOf()
    private val immutableMessage = message.copyOf()

    fun unsignedTransactionBytes(): ByteArray = immutableTransaction.copyOf()
    internal fun messageBytes(): ByteArray = immutableMessage.copyOf()

    fun requireUsable(now: Instant = Instant.now()): PreparedCommunityBuy {
        if (!now.isBefore(expiresAt)) throw CommunityBuyException(
            CommunityBuyFailure.EXPIRED,
            "This quote expired. Review a fresh quote before signing.",
        )
        return this
    }
}

enum class CommunityBuyStatusState { COMMITTED, BROADCAST, CONFIRMED, FAILED, EXPIRED, UNKNOWN }

data class CommunityBuyStatus(
    val operationId: String,
    val state: CommunityBuyStatusState,
    val transactionId: String?,
    val isFinalized: Boolean,
    val message: String?,
    val observedAt: Instant,
    val walletAddress: String,
    val mintAddress: String,
    val poolAddress: String,
    val amountInLamports: Long,
    val tokenAmountReceived: BigInteger?,
    val walletTokenBalanceRaw: BigInteger?,
) {
    /** Historical delivery is distinct from today's wallet holding. */
    val isVerifiedPurchase: Boolean get() = state == CommunityBuyStatusState.CONFIRMED && isFinalized &&
        transactionId?.let(::isCanonicalSolanaSignature) == true &&
        (tokenAmountReceived ?: BigInteger.ZERO) > BigInteger.ZERO

    val isCurrentlyHeld: Boolean get() = isVerifiedPurchase &&
        (walletTokenBalanceRaw ?: BigInteger.ZERO) > BigInteger.ZERO

    val isTerminal: Boolean get() =
        (state == CommunityBuyStatusState.CONFIRMED && isFinalized) ||
            state == CommunityBuyStatusState.FAILED || state == CommunityBuyStatusState.EXPIRED
}

enum class CommunityBuyProgress { COMMITTING, SIGNING, SUBMITTING, CONFIRMING }
enum class CommunityBuyFailure { INVALID_REQUEST, AUTHENTICATION_REQUIRED, INSUFFICIENT_BALANCE, EXPIRED, SIGNING_FAILED, SERVICE_UNAVAILABLE, PROTOCOL_ERROR, SUBMISSION_UNCERTAIN, BUY_FAILED }

class CommunityBuyException(
    val code: CommunityBuyFailure,
    val userMessage: String,
    val submissionMayHaveReachedServer: Boolean = false,
) : Exception(userMessage)

internal const val BUY_NETWORK = "SOLANA_MAINNET"
internal const val BUY_DBC_PROGRAM = METEORA_DBC_PROGRAM_ID
