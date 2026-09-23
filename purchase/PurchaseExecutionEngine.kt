package com.elevencapital.app.purchase

import com.elevencapital.app.BuildConfig
import java.time.Instant
import kotlinx.coroutines.delay

internal interface PurchaseExecutionBackend {
    suspend fun commit(userId: String, quote: PurchaseQuote): Boolean
    suspend fun invoking(userId: String, quote: PurchaseQuote, action: PurchaseAction): Boolean
    suspend fun releaseDefinitelyNotInvoked(userId: String, quoteId: String, actionId: String): PurchaseStatus
    suspend fun submitted(userId: String, quote: PurchaseQuote, submission: PurchaseActionSubmission): PurchaseStatus
    suspend fun status(userId: String, quoteId: String): PurchaseStatus
}

internal interface PurchaseExecutionJournalStore {
    fun begin(quote: PurchaseQuote)
    fun markCommitted(quote: PurchaseQuote)
    fun markInvoking(quote: PurchaseQuote, action: PurchaseAction)
    fun markProviderInvoked(quote: PurchaseQuote, action: PurchaseAction)
    fun markProviderNotInvoked(quote: PurchaseQuote, action: PurchaseAction)
    fun markBroadcast(quote: PurchaseQuote, submission: PurchaseActionSubmission)
    fun markReported(quote: PurchaseQuote, action: PurchaseAction)
    fun finish(quoteId: String)
}

internal data class PurchaseExecutionProgress(
    val phase: PurchasePhase,
    val message: String,
    val status: PurchaseStatus? = null,
)

/** Testable ordering core. No UI path can bypass commit/invoking or retry a provider call. */
internal class PurchaseExecutionEngine(
    private val backend: PurchaseExecutionBackend,
    private val journal: PurchaseExecutionJournalStore,
    private val broadcaster: PurchaseActionBroadcaster,
    private val sessionIsCurrent: (String) -> Boolean,
    private val now: () -> Instant = Instant::now,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    /** Unit tests can exercise ordering; production always receives the compiled constant. */
    private val compiledExecutionEnabled: Boolean = BuildConfig.PURCHASE_EXECUTION_ENABLED,
) {
    suspend fun execute(
        userId: String,
        quote: PurchaseQuote,
        sourceNetwork: PurchaseNetwork,
        onProgress: (PurchaseExecutionProgress) -> Unit,
    ): PurchaseStatus {
        requireExecutable(quote)
        if (quote.binding.fromNetwork != sourceNetwork || quote.actions.any { it.network != sourceNetwork }) {
            purchaseProtocolFailure()
        }
        requireCurrentSession(userId)
        quote.requireUsable(now())
        journal.begin(quote)
        onProgress(PurchaseExecutionProgress(PurchasePhase.COMMITTING, "Securing the reviewed route…"))
        requireExecutable(quote)
        backend.commit(userId, quote)
        journal.markCommitted(quote)
        var latest: PurchaseStatus? = null
        for (action in quote.actions) {
            requireCurrentSession(userId)
            quote.requireUsable(now())
            onProgress(PurchaseExecutionProgress(PurchasePhase.SIGNING,
                "Wallet confirmation ${action.index + 1} of ${quote.walletConfirmations}", latest))
            journal.markInvoking(quote, action)
            requireExecutable(quote)
            backend.invoking(userId, quote, action)
            journal.markProviderInvoked(quote, action)
            val submission = try {
                requireExecutable(quote)
                broadcaster.broadcast(userId, action)
            } catch (failure: PurchaseException) {
                if (!failure.providerInvoked) {
                    journal.markProviderNotInvoked(quote, action)
                    try {
                        backend.releaseDefinitelyNotInvoked(userId, quote.id, action.id)
                        journal.finish(quote.id)
                    } catch (_: Exception) {
                        // RELEASING remains durable and restart recovery repeats only the release.
                    }
                }
                throw failure
            } catch (_: Exception) {
                throw PurchaseException(PurchaseFailureCode.SUBMISSION_UNCERTAIN,
                    "The wallet result is uncertain. Eleven will only check status and will not submit again.",
                    false, providerInvoked = true)
            }
            journal.markBroadcast(quote, submission)
            requireExecutable(quote)
            latest = backend.submitted(userId, quote, submission).requireBoundTo(quote)
            if (submission.transactionId !in latest.transactionIds) purchaseProtocolFailure()
            journal.markReported(quote, action)
            if (action.index < quote.actions.lastIndex) {
                latest = awaitReady(userId, quote, action, latest, onProgress)
            }
        }
        return requireNotNull(latest)
    }

    private fun requireExecutable(quote: PurchaseQuote) {
        if (!compiledExecutionEnabled || !quote.executionEnabled) {
            throw PurchaseException(
                PurchaseFailureCode.NOT_PURCHASABLE,
                quote.executionReason
                    ?: "This reviewed route is not executable. No funds were moved.",
                retryable = false,
            )
        }
    }

    private fun requireCurrentSession(userId: String) {
        if (!sessionIsCurrent(userId)) throw PurchaseException(
            PurchaseFailureCode.AUTHENTICATION_REQUIRED,
            "Your secure wallet session changed. Check the purchase status before retrying.",
            retryable = false,
        )
    }

    private suspend fun awaitReady(
        userId: String,
        quote: PurchaseQuote,
        action: PurchaseAction,
        initial: PurchaseStatus,
        onProgress: (PurchaseExecutionProgress) -> Unit,
    ): PurchaseStatus {
        var status = initial
        var delayMillis = 1_500L
        while (status.state in setOf(PurchaseRouteState.COMMITTED, PurchaseRouteState.EXECUTING) &&
            status.step < action.index + 1) {
            onProgress(PurchaseExecutionProgress(PurchasePhase.TRACKING,
                "Confirming wallet action ${action.index + 1} before continuing…", status))
            wait(delayMillis)
            requireExecutable(quote)
            status = backend.status(userId, quote.id).requireBoundTo(quote)
            delayMillis = (delayMillis * 2).coerceAtMost(8_000)
        }
        if (status.state == PurchaseRouteState.FAILED || status.state == PurchaseRouteState.EXPIRED) {
            journal.finish(quote.id)
            throw PurchaseException(if (status.state == PurchaseRouteState.EXPIRED) PurchaseFailureCode.EXPIRED
                else PurchaseFailureCode.SERVICE_UNAVAILABLE,
                status.message ?: "The approved route did not become ready. No further action was submitted.", false)
        }
        if (status.state == PurchaseRouteState.COMPLETED || status.step < action.index + 1) {
            throw PurchaseException(PurchaseFailureCode.UNRESOLVED_PURCHASE,
                "The route state changed before the next wallet action. Nothing else was submitted.", false)
        }
        return status
    }

    private fun PurchaseStatus.requireBoundTo(quote: PurchaseQuote): PurchaseStatus {
        if (quoteId != quote.id || stepCount != quote.actions.size || transactionIds.size > quote.actions.size) {
            purchaseProtocolFailure()
        }
        return this
    }
}

internal enum class PurchaseRecoveryDirective { STATUS_THEN_CLEAR, RELEASE_INVOCATION, REPORT_SAVED_HASH, STATUS_ONLY }

internal fun recoveryDirective(record: PurchaseJournalRecord): PurchaseRecoveryDirective = when (record.phase) {
    PurchaseJournalPhase.PLANNED, PurchaseJournalPhase.COMMITTED -> PurchaseRecoveryDirective.STATUS_THEN_CLEAR
    PurchaseJournalPhase.INVOKING, PurchaseJournalPhase.RELEASING -> PurchaseRecoveryDirective.RELEASE_INVOCATION
    PurchaseJournalPhase.BROADCAST -> PurchaseRecoveryDirective.REPORT_SAVED_HASH
    PurchaseJournalPhase.PROVIDER_INVOKED, PurchaseJournalPhase.TRACKING -> PurchaseRecoveryDirective.STATUS_ONLY
}

/** Returns only an already-durable provider identifier; it never constructs or invokes an action. */
internal fun savedSubmissionForRecovery(record: PurchaseJournalRecord): PurchaseActionSubmission? {
    if (record.phase != PurchaseJournalPhase.BROADCAST) return null
    val actionId = record.actionId ?: return null
    val transactionId = record.transactionIds.lastOrNull() ?: return null
    return PurchaseActionSubmission(actionId, transactionId)
}
