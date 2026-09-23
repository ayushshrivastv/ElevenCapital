package com.elevencapital.app.wallet

import com.elevencapital.app.auth.PrivyAuthController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Keeps backend verification and Privy signing as two explicit, user-separated steps. */
class WalletTransferCoordinator internal constructor(
    baseUrl: String,
    private val auth: PrivyAuthController,
    private val journal: TransferJournal,
    allowLoopbackHttp: Boolean = false,
) {
    private val tokenProvider = TransferAccessTokenProvider(auth::freshAccessToken)
    private val preparationClient = TransferPreparationClient(baseUrl, tokenProvider,
        allowLoopbackHttp = allowLoopbackHttp)
    private val statusClient = TransferStatusClient(baseUrl, tokenProvider,
        allowLoopbackHttp = allowLoopbackHttp)
    private val intentClient = TransferIntentClient(baseUrl, tokenProvider,
        allowLoopbackHttp = allowLoopbackHttp)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSubmissions = ActiveTransferOperations()

    val historyRevision: StateFlow<Long> get() = journal.revision
    val historyStorageHealthy: Boolean get() = journal.storageHealthy

    init {
        // Process recreation restores the app-private journal, then a verified Privy session
        // resumes read-only confirmation checks for identifiers already returned by the provider.
        // This loop never signs and never invokes submit.
        scope.launch {
            auth.state.map { state -> state.userId?.takeIf { state.authenticated } }
                .distinctUntilChanged()
                .collectLatest { userId ->
                    if (userId == null) return@collectLatest
                    while (currentCoroutineContext().isActive) {
                        val releases = journal.recordsNeedingServerRelease(userId).filterNot {
                            activeSubmissions.contains(it.review.operationId.value)
                        }
                        releases.forEach { record ->
                            try {
                                recoverDefinitelyUnsent(record.review)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Keep the durable release-pending state and retry automatically.
                            }
                        }
                        val pending = journal.recordsNeedingStatus(userId)
                        pending.forEach { record ->
                            try {
                                val transactionId = requireNotNull(record.transactionId)
                                journal.markObservation(statusClient.status(record.review, transactionId))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Unknown and transport failures are not proof of failure and can
                                // never trigger a replacement transaction.
                            }
                        }
                        delay(if (pending.isEmpty() && releases.isEmpty()) IDLE_STATUS_DELAY_MILLIS else ACTIVE_STATUS_DELAY_MILLIS)
                    }
                }
        }
    }

    suspend fun prepare(draft: TransferDraft): PreparedTransfer {
        val review = draft.review()
        if (journal.hasHashlessUnresolved(review.userId, review.walletId)) {
            throw TransferPreparationException(
                code = TransferPreparationFailureCode.BUSY,
                userMessage = "This wallet has an unresolved transfer. Check History before sending again.",
                retryable = false,
            )
        }
        return preparationClient.prepare(review)
    }

    suspend fun submit(prepared: PreparedTransfer): WalletSubmission {
        val operationId = prepared.review.operationId.value
        if (!activeSubmissions.begin(operationId)) {
            throw WalletSubmissionException(false,
                "This transfer is already being submitted. Check its status before trying again.")
        }
        return try {
            submitActive(prepared)
        } finally {
            activeSubmissions.end(operationId)
        }
    }

    private suspend fun submitActive(prepared: PreparedTransfer): WalletSubmission {
        try {
            // Persist the exact review before the server can acquire its cross-device lock. If the
            // process dies during commit, startup recovery can prove Privy's provider was never called.
            journal.beginCommitting(prepared.review)
        } catch (_: TransferJournalBlockedException) {
            throw WalletSubmissionException(true,
                "This wallet has an unresolved transfer. Check History before sending again.")
        } catch (_: TransferJournalPersistenceException) {
            throw WalletSubmissionException(true,
                "Transfer history could not be saved safely. Nothing was sent.")
        }
        // This cross-device durable commit must succeed before the journal advances to provider
        // submission or Privy is touched. A timeout is fail-closed because the backend may have committed.
        try {
            var lastFailure: TransferPreparationException? = null
            var committed = false
            for (attempt in 0..1) {
                try {
                    intentClient.commit(prepared.review)
                    committed = true
                    break
                } catch (failure: TransferPreparationException) {
                    lastFailure = failure
                    if (!failure.retryable || attempt == 1) break
                    delay(COMMIT_RETRY_DELAY_MILLIS)
                }
            }
            if (!committed) throw requireNotNull(lastFailure)
        } catch (failure: CancellationException) {
            cleanupUnsentServerIntent(prepared.review)
            throw failure
        } catch (failure: TransferPreparationException) {
            cleanupUnsentServerIntent(prepared.review)
            throw failure
        }
        return try {
            auth.broadcastPreparedTransfer(prepared)
        } catch (failure: WalletSubmissionException) {
            if (failure.definitelyNotBroadcast) {
                cleanupUnsentServerIntent(prepared.review)
            }
            throw failure
        } catch (failure: TransferPreparationException) {
            // A locally expired quote cannot reach Privy, so the server lock can be released safely.
            cleanupUnsentServerIntent(prepared.review)
            throw failure
        } catch (cancelled: CancellationException) {
            // Cancellation before provider submission leaves COMMITTING and is recoverable. Once
            // provider submission begins, PrivyAuthController converts cancellation to ambiguous.
            cleanupUnsentServerIntent(prepared.review)
            throw cancelled
        }
    }

    private suspend fun cleanupUnsentServerIntent(review: TransferReviewSnapshot) = withContext(NonCancellable) {
        if (!journal.needsServerRelease(review)) return@withContext
        try {
            intentClient.releaseDefinitelyNotBroadcast(review)
            journal.recordDefinitelyNotBroadcast(review)
        } catch (failure: TransferPreparationException) {
            // An absent intent is also definitive: no server lock or provider call exists. Every
            // transport/storage/unknown-state failure remains durable and is retried after restart.
            if (failure.code == TransferPreparationFailureCode.INTENT_NOT_FOUND) {
                runCatching { journal.recordDefinitelyNotBroadcast(review) }
            }
        }
    }

    private suspend fun recoverDefinitelyUnsent(review: TransferReviewSnapshot) {
        cleanupUnsentServerIntent(review)
    }

    suspend fun status(prepared: PreparedTransfer, submission: WalletSubmission): TransferStatusObservation {
        require(prepared.review.operationId == submission.operationId &&
            prepared.review.networkId == submission.networkId)
        // A prior disk failure can still recover once the visible screen has the validated ID.
        runCatching { journal.markBroadcast(prepared.review, submission.transactionId) }
        val observation = statusClient.status(prepared, submission.transactionId)
        runCatching { journal.markObservation(observation) }
        return observation
    }

    fun historyForVerifiedUser(userId: String): List<TransferJournalRecord> =
        journal.recordsForVerifiedUser(userId)

    private companion object {
        const val ACTIVE_STATUS_DELAY_MILLIS = 15_000L
        const val IDLE_STATUS_DELAY_MILLIS = 30_000L
        const val COMMIT_RETRY_DELAY_MILLIS = 250L
    }
}

internal class ActiveTransferOperations {
    private val operationIds = ConcurrentHashMap.newKeySet<String>()

    fun begin(operationId: String): Boolean = operationIds.add(operationId)
    fun end(operationId: String) { operationIds.remove(operationId) }
    fun contains(operationId: String): Boolean = operationId in operationIds
}
