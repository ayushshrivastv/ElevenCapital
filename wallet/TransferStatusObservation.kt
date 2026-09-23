package com.elevencapital.app.wallet

import java.math.BigInteger
import java.time.Instant

/** UNKNOWN means the chain observer has not found the identifier; it never means failed. */
enum class TransferLifecycleStatus { UNKNOWN, PENDING, CONFIRMED, FINALIZED, FAILED }

sealed class TransferStatusObservation protected constructor(
    open val review: TransferReviewSnapshot,
    open val transactionId: String,
    open val senderVerified: Boolean?,
    open val status: TransferLifecycleStatus,
    open val confirmations: BigInteger?,
    open val isFinalized: Boolean,
    open val observedAt: Instant,
) {
    val hasFailed: Boolean get() = status == TransferLifecycleStatus.FAILED

    /** A missing or timed-out observation must never trigger another broadcast automatically. */
    val permitsAutomaticResend: Boolean get() = false
}

enum class EthereumTransferFailure { EXECUTION_REVERTED }

class EthereumTransferStatus internal constructor(
    override val review: TransferReviewSnapshot,
    override val transactionId: String,
    override val senderVerified: Boolean?,
    override val status: TransferLifecycleStatus,
    override val confirmations: BigInteger?,
    override val isFinalized: Boolean,
    override val observedAt: Instant,
    val blockNumber: BigInteger?,
    val blockHash: String?,
    val blockTime: Instant?,
    val finalizedBlockNumber: BigInteger?,
    val failure: EthereumTransferFailure?,
) : TransferStatusObservation(review, transactionId, senderVerified, status, confirmations, isFinalized, observedAt)

enum class SolanaConfirmationStatus { PROCESSED, CONFIRMED, FINALIZED }
enum class SolanaTransferFailure { TRANSACTION_ERROR }

class SolanaTransferStatus internal constructor(
    override val review: TransferReviewSnapshot,
    override val transactionId: String,
    override val senderVerified: Boolean?,
    override val status: TransferLifecycleStatus,
    override val confirmations: BigInteger?,
    override val isFinalized: Boolean,
    override val observedAt: Instant,
    val observedSlot: Long,
    val slot: Long?,
    val blockTime: Instant?,
    val confirmationStatus: SolanaConfirmationStatus?,
    val failure: SolanaTransferFailure?,
) : TransferStatusObservation(review, transactionId, senderVerified, status, confirmations, isFinalized, observedAt)
