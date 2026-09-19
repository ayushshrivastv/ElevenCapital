package com.elevencapital.app.wallet

import android.content.Context
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable state for a one-shot wallet submission. [COMMITTING] and [RELEASING] are the only
 * hashless states that can be recovered automatically because Privy's provider has not broadcast.
 * A hashless [SUBMITTING] or [AMBIGUOUS] record remains blocked: after process death there is no
 * safe evidence that another transfer from the same wallet would not duplicate the first one.
 */
enum class TransferJournalState {
    COMMITTING,
    SUBMITTING,
    RELEASING,
    AMBIGUOUS,
    DEFINITELY_NOT_BROADCAST,
    BROADCAST,
    UNKNOWN,
    PENDING,
    CONFIRMED,
    FINALIZED,
    FAILED_NONFINAL,
    FAILED_FINALIZED,
}

@ConsistentCopyVisibility
data class TransferJournalRecord internal constructor(
    val review: TransferReviewSnapshot,
    val state: TransferJournalState,
    val transactionId: String?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(updatedAtEpochMillis >= 0)
        val needsHash = state in HASHED_STATES
        require(needsHash == (transactionId != null))
        transactionId?.let { require(canonicalJournalTransactionId(review.networkId, it) == it) }
    }

    val blocksNewSubmission: Boolean
        get() = transactionId == null && state in setOf(
            TransferJournalState.COMMITTING,
            TransferJournalState.SUBMITTING,
            TransferJournalState.RELEASING,
            TransferJournalState.AMBIGUOUS,
        )

    val needsServerRelease: Boolean
        get() = transactionId == null && state in setOf(
            TransferJournalState.COMMITTING,
            TransferJournalState.RELEASING,
        )

    val needsStatusPolling: Boolean
        get() = transactionId != null && state !in setOf(
            TransferJournalState.FINALIZED,
            TransferJournalState.FAILED_FINALIZED,
        )

    companion object {
        private val HASHED_STATES = setOf(
            TransferJournalState.BROADCAST,
            TransferJournalState.UNKNOWN,
            TransferJournalState.PENDING,
            TransferJournalState.CONFIRMED,
            TransferJournalState.FINALIZED,
            TransferJournalState.FAILED_NONFINAL,
            TransferJournalState.FAILED_FINALIZED,
        )
    }
}

internal interface TransferJournalStorage {
    fun read(): String?

    /** Must be a synchronous durable write. */
    fun write(value: String): Boolean
}

private class SharedPreferencesTransferJournalStorage(context: Context) : TransferJournalStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "eleven-transfer-journal-v1",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString(JOURNAL_KEY, null)

    override fun write(value: String): Boolean = preferences.edit().putString(JOURNAL_KEY, value).commit()

    private companion object { const val JOURNAL_KEY = "records" }
}

internal class TransferJournalPersistenceException : Exception()
internal class TransferJournalBlockedException : Exception()

/**
 * App-private transaction journal. It stores public transaction metadata only; Privy continues to
 * own all credentials and signing material. Every mutation commits synchronously before returning.
 */
class TransferJournal internal constructor(
    private val storage: TransferJournalStorage,
    private val nowMillis: () -> Long,
) {
    constructor(context: Context) : this(
        SharedPreferencesTransferJournalStorage(context),
        System::currentTimeMillis,
    )

    private val lock = Any()
    private val restored = runCatching { TransferJournalCodec.decode(storage.read()) }
        .getOrElse { DecodedJournal(emptyList(), corrupted = true) }
    private var records = restored.records
    private var corrupted = restored.corrupted
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    /** Corruption is fail-closed because it may have hidden a pre-broadcast SUBMITTING record. */
    val storageHealthy: Boolean get() = synchronized(lock) { !corrupted }

    fun recordsForVerifiedUser(userId: String): List<TransferJournalRecord> {
        require(safeJournalIdentity(userId))
        return synchronized(lock) {
            records.asSequence().filter { it.review.userId == userId }
                .sortedWith(compareByDescending<TransferJournalRecord> { it.updatedAtEpochMillis }
                    .thenByDescending { it.review.createdAtEpochMillis })
                .toList()
        }
    }

    fun hasHashlessUnresolved(userId: String, walletId: String): Boolean {
        require(safeJournalIdentity(userId) && safeJournalIdentity(walletId))
        return synchronized(lock) {
            corrupted || records.any {
                it.review.userId == userId && it.review.walletId == walletId && it.blocksNewSubmission
            }
        }
    }

    internal fun recordsNeedingStatus(userId: String): List<TransferJournalRecord> =
        recordsForVerifiedUser(userId).filter(TransferJournalRecord::needsStatusPolling)

    /** Must commit before the server can acquire its cross-device submission lock. */
    internal fun beginCommitting(review: TransferReviewSnapshot) {
        mutate { current ->
            if (current.any {
                    it.review.userId == review.userId && it.review.walletId == review.walletId &&
                        it.blocksNewSubmission
                }) {
                throw TransferJournalBlockedException()
            }
            if (current.any { it.review.operationId == review.operationId }) {
                throw TransferJournalBlockedException()
            }
            current + TransferJournalRecord(
                review = review,
                state = TransferJournalState.COMMITTING,
                transactionId = null,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    /** Must commit immediately before the first provider signing/broadcast call. */
    internal fun beginSubmitting(review: TransferReviewSnapshot) {
        replace(review) { existing ->
            if (existing.state != TransferJournalState.COMMITTING || existing.transactionId != null) {
                throw TransferJournalBlockedException()
            }
            existing.copy(
                state = TransferJournalState.SUBMITTING,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    internal fun markProviderFailure(review: TransferReviewSnapshot, definitelyNotBroadcast: Boolean) {
        replace(review) { existing ->
            if (existing.transactionId != null) existing else existing.copy(
                // A definite provider rejection still needs the durable server lock released.
                // Persist that fact first so process death cannot strand the lock.
                state = if (definitelyNotBroadcast) TransferJournalState.RELEASING
                else TransferJournalState.AMBIGUOUS,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    internal fun recordsNeedingServerRelease(userId: String): List<TransferJournalRecord> =
        recordsForVerifiedUser(userId).filter(TransferJournalRecord::needsServerRelease)

    internal fun needsServerRelease(review: TransferReviewSnapshot): Boolean = synchronized(lock) {
        records.firstOrNull { it.review.operationId == review.operationId }
            ?.takeIf { sameJournalReview(it.review, review) }
            ?.needsServerRelease == true
    }

    /** Records a locally certain non-broadcast outcome when the server gate fails before Privy. */
    internal fun recordDefinitelyNotBroadcast(review: TransferReviewSnapshot) {
        mutate { current ->
            val existing = current.firstOrNull { it.review.operationId == review.operationId }
            if (existing != null) {
                if (!sameJournalReview(existing.review, review) || existing.transactionId != null) throw IllegalArgumentException()
                current.map { record -> if (record.review.operationId == review.operationId) record.copy(
                    state = TransferJournalState.DEFINITELY_NOT_BROADCAST,
                    updatedAtEpochMillis = safeNow(),
                ) else record }
            } else current + TransferJournalRecord(
                review = review,
                state = TransferJournalState.DEFINITELY_NOT_BROADCAST,
                transactionId = null,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    internal fun markBroadcast(review: TransferReviewSnapshot, transactionId: String) {
        val canonical = canonicalJournalTransactionId(review.networkId, transactionId)
        replace(review) { existing ->
            require(existing.state != TransferJournalState.DEFINITELY_NOT_BROADCAST)
            if (existing.transactionId != null) {
                require(existing.transactionId == canonical)
                return@replace existing
            }
            existing.copy(
                state = TransferJournalState.BROADCAST,
                transactionId = canonical,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    internal fun markObservation(observation: TransferStatusObservation) {
        replace(observation.review) { existing ->
            if (existing.transactionId != observation.transactionId) throw IllegalArgumentException()
            if (existing.state in TERMINAL_STATES) return@replace existing
            val incoming = when (observation.status) {
                TransferLifecycleStatus.UNKNOWN -> TransferJournalState.UNKNOWN
                TransferLifecycleStatus.PENDING -> TransferJournalState.PENDING
                TransferLifecycleStatus.CONFIRMED -> TransferJournalState.CONFIRMED
                TransferLifecycleStatus.FINALIZED -> TransferJournalState.FINALIZED
                TransferLifecycleStatus.FAILED -> if (observation.isFinalized) {
                    TransferJournalState.FAILED_FINALIZED
                } else {
                    TransferJournalState.FAILED_NONFINAL
                }
            }
            // Non-final chain inclusion can be reorganized. Preserve only definitive finality;
            // otherwise accept a changed verified observation even when it moves backward.
            if (incoming == existing.state) existing else existing.copy(
                state = incoming,
                updatedAtEpochMillis = safeNow(),
            )
        }
    }

    private fun replace(
        review: TransferReviewSnapshot,
        update: (TransferJournalRecord) -> TransferJournalRecord,
    ) {
        mutate { current ->
            val index = current.indexOfFirst { it.review.operationId == review.operationId }
            if (index < 0) throw IllegalArgumentException()
            val existing = current[index]
            if (!sameJournalReview(existing.review, review)) throw IllegalArgumentException()
            current.toMutableList().also { it[index] = update(existing) }
        }
    }

    private fun mutate(update: (List<TransferJournalRecord>) -> List<TransferJournalRecord>) {
        synchronized(lock) {
            if (corrupted) throw TransferJournalPersistenceException()
            val candidate = retainJournalRecords(update(records))
            if (candidate == records) return
            val encoded = TransferJournalCodec.encode(candidate)
            if (!storage.write(encoded)) throw TransferJournalPersistenceException()
            records = candidate
            mutableRevision.value = mutableRevision.value + 1
        }
    }

    private fun safeNow(): Long = nowMillis().takeIf { it >= 0 } ?: throw TransferJournalPersistenceException()

    private companion object {
        val TERMINAL_STATES = setOf(
            TransferJournalState.FINALIZED,
            TransferJournalState.FAILED_FINALIZED,
        )
    }
}

private fun retainJournalRecords(source: List<TransferJournalRecord>): List<TransferJournalRecord> {
    val unique = LinkedHashMap<String, TransferJournalRecord>()
    source.forEach { record -> unique[record.review.operationId.value] = record }
    val all = unique.values.toList()
    if (all.size <= MAX_JOURNAL_RECORDS) return all
    val protected = all.filter { it.blocksNewSubmission || it.needsStatusPolling }
    if (protected.size > MAX_JOURNAL_RECORDS) throw TransferJournalPersistenceException()
    val retainedResolved = all.asSequence().filterNot(protected::contains)
        .sortedByDescending(TransferJournalRecord::updatedAtEpochMillis)
        .take(MAX_JOURNAL_RECORDS - protected.size)
        .toList()
    return protected + retainedResolved
}

private fun sameJournalReview(first: TransferReviewSnapshot, second: TransferReviewSnapshot): Boolean =
    first.operationId == second.operationId && first.userId == second.userId &&
        first.walletId == second.walletId && first.sender == second.sender &&
        first.recipient == second.recipient && first.assetId == second.assetId &&
        first.displayAmount == second.displayAmount && first.baseUnits == second.baseUnits &&
        first.createdAtEpochMillis == second.createdAtEpochMillis

private fun safeJournalIdentity(value: String): Boolean =
    value.length in 1..200 && value.none(Char::isWhitespace) && value.none(Char::isISOControl)

private fun canonicalJournalTransactionId(network: WalletNetworkId, raw: String): String = when (network) {
    WalletNetworkId.ETHEREUM_MAINNET -> raw.lowercase().takeIf {
        Regex("0x[0-9a-f]{64}").matches(it)
    }
    WalletNetworkId.SOLANA_MAINNET -> raw.takeIf(::isCanonicalSolanaSignature)
} ?: throw IllegalArgumentException()

private data class DecodedJournal(val records: List<TransferJournalRecord>, val corrupted: Boolean)

private object TransferJournalCodec {
    private const val HEADER = "ELEVEN_TRANSFER_JOURNAL_V1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(records: List<TransferJournalRecord>): String = buildString {
        append(HEADER)
        records.forEach { record ->
            val review = record.review
            val fields = listOf(
                review.operationId.value,
                review.userId,
                review.walletId,
                review.sender.value,
                review.recipient.value,
                review.assetId.name,
                review.displayAmount,
                review.baseUnits,
                review.decimals.toString(),
                review.createdAtEpochMillis.toString(),
                record.state.name,
                record.transactionId.orEmpty(),
                record.updatedAtEpochMillis.toString(),
            )
            append('\n')
            append(fields.joinToString(".") { field ->
                encoder.encodeToString(field.toByteArray(Charsets.UTF_8))
            })
        }
        if (length > MAX_JOURNAL_BYTES) throw TransferJournalPersistenceException()
    }

    fun decode(raw: String?): DecodedJournal {
        if (raw == null) return DecodedJournal(emptyList(), corrupted = false)
        if (raw.length > MAX_JOURNAL_BYTES) return DecodedJournal(emptyList(), corrupted = true)
        val lines = raw.lineSequence().toList()
        if (lines.firstOrNull() != HEADER || lines.size - 1 > MAX_JOURNAL_RECORDS) {
            return DecodedJournal(emptyList(), corrupted = true)
        }
        val decoded = lines.drop(1).map(::decodeRecord)
        return DecodedJournal(decoded.filterNotNull(), corrupted = decoded.any { it == null })
    }

    private fun decodeRecord(line: String): TransferJournalRecord? = runCatching {
        if (line.length !in 1..MAX_RECORD_BYTES) throw IllegalArgumentException()
        val encoded = line.split('.')
        if (encoded.size != 13) throw IllegalArgumentException()
        val fields = encoded.map { token ->
            if (token.length > 600) throw IllegalArgumentException()
            decoder.decode(token).toString(Charsets.UTF_8).also {
                if (it.length > 400 || it.any(Char::isISOControl)) throw IllegalArgumentException()
            }
        }
        val operationId = TransferOperationId.fromPersisted(fields[0])
        val userId = fields[1].also { if (!safeJournalIdentity(it)) throw IllegalArgumentException() }
        val walletId = fields[2].also { if (!safeJournalIdentity(it)) throw IllegalArgumentException() }
        val asset = WalletAssetRegistry.asset(WalletAssetId.valueOf(fields[5]))
        val sender = ValidatedWalletAddress.wallet(asset.network.id, fields[3])
        val recipient = ValidatedWalletAddress.recipient(asset.network.id, fields[4])
        if (sender == recipient) throw IllegalArgumentException()
        val amount = TransferAmount.parse(fields[6], asset)
        if (fields[7] != amount.baseUnits.toString() || fields[8].toInt() != asset.decimals) {
            throw IllegalArgumentException()
        }
        val createdAt = fields[9].toLong().also { if (it < 0) throw IllegalArgumentException() }
        val review = TransferReviewSnapshot(
            operationId = operationId,
            userId = userId,
            walletId = walletId,
            sender = sender,
            recipient = recipient,
            assetId = asset.id,
            assetSymbol = asset.symbol,
            networkId = asset.network.id,
            networkCaip2 = asset.network.caip2,
            contractOrMint = asset.contractOrMint,
            displayAmount = amount.displayAmount,
            baseUnits = amount.baseUnits.toString(),
            decimals = asset.decimals,
            createdAtEpochMillis = createdAt,
        )
        TransferJournalRecord(
            review = review,
            state = TransferJournalState.valueOf(fields[10]),
            transactionId = fields[11].ifEmpty { null },
            updatedAtEpochMillis = fields[12].toLong(),
        )
    }.getOrNull()
}

private const val MAX_JOURNAL_RECORDS = 256
private const val MAX_JOURNAL_BYTES = 256 * 1024
private const val MAX_RECORD_BYTES = 4 * 1024
