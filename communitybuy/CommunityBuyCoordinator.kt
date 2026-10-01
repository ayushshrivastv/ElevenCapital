package com.elevencapital.app.communitybuy

import android.content.Context
import com.elevencapital.app.auth.PrivyAuthController
import com.elevencapital.app.auth.PrivyRealExecutionSigner
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface CommunityBuySigner {
    suspend fun sign(prepared: PreparedCommunityBuy): String
}

/** A single quote can be signed and submitted once. After submit starts, only status is retried. */
class CommunityBuyCoordinator private constructor(
    private val backend: CommunityBuyBackend,
    private val signer: CommunityBuySigner,
    private val now: () -> Instant,
    private val wait: suspend (Long) -> Unit,
    private val pending: CommunityBuyPendingStore?,
    private val purchases: CommunityPurchasedTokenStore?,
    private val clusterVerifier: CommunityBuyClusterVerifier,
) {
    constructor(
        context: Context,
        baseUrl: String,
        auth: PrivyAuthController,
        allowLoopbackHttp: Boolean = false,
    ) : this(
        CommunityBuyClient(baseUrl, CommunityBuyAccessTokenProvider(
            PrivyRealExecutionSigner(auth)::freshTokenForCommunityBuy),
            allowLoopbackHttp = allowLoopbackHttp),
        CommunityBuySigner(PrivyRealExecutionSigner(auth)::signPreparedCommunityBuy), Instant::now, { delay(it) },
        CommunityBuyPendingStore(context), CommunityPurchasedTokenStore(context), CommunityMainnetVerifier(),
    )

    internal constructor(
        backend: CommunityBuyBackend,
        signer: CommunityBuySigner,
        now: () -> Instant,
        wait: suspend (Long) -> Unit,
        pending: CommunityBuyPendingStore? = null,
        purchases: CommunityPurchasedTokenStore? = null,
        clusterVerifier: CommunityBuyClusterVerifier = CommunityBuyClusterVerifier { },
        testOnly: Unit = Unit,
    ) : this(backend, signer, now, wait, pending, purchases, clusterVerifier) {
        @Suppress("UNUSED_VARIABLE") val ignored = testOnly
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val activeWallets = ConcurrentHashMap.newKeySet<String>()

    suspend fun prepare(request: CommunityBuyRequest): PreparedCommunityBuy {
        if (activeWallets.contains(walletKey(request))) throw CommunityBuyException(
            CommunityBuyFailure.SUBMISSION_UNCERTAIN,
            "Another buy is already being processed for this wallet. Check its status first.",
            submissionMayHaveReachedServer = true)
        if (pending?.load(request.userId, request.walletAddress) != null) throw CommunityBuyException(
            CommunityBuyFailure.SUBMISSION_UNCERTAIN,
            "Another buy may still be confirming. Check its status before starting a new buy.",
            submissionMayHaveReachedServer = true)
        val preparation = scope.async { backend.prepare(request) }
        return try { preparation.await() } catch (cancelled: CancellationException) {
            scope.launch { runCatching { releaseBeforeSubmit(preparation.await()) } }
            throw cancelled
        }
    }

    suspend fun execute(
        prepared: PreparedCommunityBuy,
        onProgress: (CommunityBuyProgress) -> Unit = {},
    ): CommunityBuyStatus {
        prepared.requireUsable(now())
        if (pending?.load(prepared.request.userId, prepared.request.walletAddress) != null) {
            throw CommunityBuyException(CommunityBuyFailure.SUBMISSION_UNCERTAIN,
                "Another buy may still be confirming. Check its status before starting a new buy.",
                submissionMayHaveReachedServer = true)
        }
        val walletKey = walletKey(prepared.request)
        if (!activeWallets.add(walletKey)) throw CommunityBuyException(
            CommunityBuyFailure.INVALID_REQUEST, "Another buy is already being processed for this wallet.")
        if (!active.add(prepared.request.operationId)) {
            activeWallets.remove(walletKey)
            throw CommunityBuyException(
                CommunityBuyFailure.INVALID_REQUEST, "This buy is already being submitted. Check its status.")
        }
        var handedToBackground = false
        var signingStarted = false
        try {
            notifyProgress(onProgress, CommunityBuyProgress.COMMITTING)
            backend.commit(prepared)
            prepared.requireUsable(now())
            clusterVerifier.verifyRecentBlockhash(prepared.recentBlockhash)
            prepared.requireUsable(now())
            notifyProgress(onProgress, CommunityBuyProgress.SIGNING)
            signingStarted = true
            val signed = CommunityBuyProtocol.walletSignedTransaction(signer.sign(prepared), prepared).also {
                currentCoroutineContext().ensureActive()
            }
            val expectedSignature = CommunityBuyProtocol.signedTransactionId(signed, prepared)
            prepared.requireUsable(now())
            currentCoroutineContext().ensureActive()
            if (pending != null && !pending.save(prepared, expectedSignature)) {
                throw CommunityBuyException(CommunityBuyFailure.SERVICE_UNAVAILABLE,
                    "Couldn't safely save this buy before submission. Nothing was submitted.")
            }
            notifyProgress(onProgress, CommunityBuyProgress.SUBMITTING)
            val reconciliation = scope.async {
                try { submitOnceAndConfirm(prepared, signed, expectedSignature, onProgress) }
                finally {
                    active.remove(prepared.request.operationId)
                    activeWallets.remove(walletKey)
                }
            }
            handedToBackground = true
            return reconciliation.await()
        } catch (failure: Exception) {
            if (!handedToBackground) {
                pending?.let { store -> runCatching { store.clear(PendingCommunityBuy.from(prepared)) } }
                releaseBeforeSubmit(prepared, signingStarted)
            }
            throw failure
        } finally {
            if (!handedToBackground) {
                active.remove(prepared.request.operationId)
                activeWallets.remove(walletKey)
            }
        }
    }

    suspend fun status(prepared: PreparedCommunityBuy): CommunityBuyStatus = backend.status(prepared)

    /** A reviewed quote can be abandoned safely until execute crosses the /submit boundary. */
    suspend fun releaseBeforeSigning(prepared: PreparedCommunityBuy) {
        if (active.contains(prepared.request.operationId)) return
        releaseBeforeSubmit(prepared)
    }

    fun purchasedTokens(userId: String, walletAddress: String): List<CommunityPurchasedToken> =
        purchases?.load(userId, walletAddress).orEmpty()

    fun hasPending(userId: String, walletAddress: String): Boolean =
        pending?.load(userId, walletAddress) != null

    /** On app restore, only a read-only status call is made; signed bytes are never persisted or resent. */
    suspend fun recoverPending(userId: String, walletAddress: String): CommunityBuyStatus? {
        val intent = pending?.load(userId, walletAddress) ?: return null
        val observed = backend.status(intent.forStatusOnly())
        if (observed.transactionId != null && observed.transactionId != intent.expectedSignature) {
            throw CommunityBuyException(CommunityBuyFailure.PROTOCOL_ERROR,
                "The previous buy service reported a different transaction. Do not buy again yet.",
                submissionMayHaveReachedServer = true)
        }
        reconcileReceipt(intent.forStatusOnly(), observed)
        return observed
    }

    private suspend fun submitOnceAndConfirm(
        prepared: PreparedCommunityBuy,
        signed: String,
        expectedSignature: String,
        onProgress: (CommunityBuyProgress) -> Unit,
    ): CommunityBuyStatus {
        var submittedSignature: String? = null
        try { submittedSignature = backend.submit(prepared, signed) } catch (_: Exception) {
            // /submit can have reached the backend. Never resend signed bytes.
        }
        if (submittedSignature != null && submittedSignature != expectedSignature) {
            throw CommunityBuyException(CommunityBuyFailure.PROTOCOL_ERROR,
                "The buy service returned a different transaction. Check its status before retrying.",
                submissionMayHaveReachedServer = true)
        }
        notifyProgress(onProgress, CommunityBuyProgress.CONFIRMING)
        val deadline = maxOf(now().plusSeconds(180), prepared.expiresAt.plusSeconds(45))
        var delayMillis = 800L
        while (now().isBefore(deadline)) {
            try {
                val observed = backend.status(prepared)
                if (observed.transactionId != null &&
                    (observed.transactionId != expectedSignature ||
                        (submittedSignature != null && observed.transactionId != submittedSignature))) {
                    throw CommunityBuyException(
                        CommunityBuyFailure.PROTOCOL_ERROR, "The buy service reported a different transaction.",
                        submissionMayHaveReachedServer = true)
                }
                if (observed.isVerifiedPurchase) {
                    reconcileReceipt(prepared, observed)
                    return observed
                }
                if (observed.state == CommunityBuyStatusState.FAILED ||
                    observed.state == CommunityBuyStatusState.EXPIRED) {
                    reconcileReceipt(prepared, observed)
                    throw CommunityBuyException(CommunityBuyFailure.BUY_FAILED,
                        "The buy was not confirmed on Solana Mainnet. No replacement was submitted.",
                        submissionMayHaveReachedServer = true)
                }
                // Await finalized delivery proof; current wallet balance may later be zero.
            } catch (failure: CommunityBuyException) {
                if (failure.code == CommunityBuyFailure.BUY_FAILED ||
                    failure.code == CommunityBuyFailure.PROTOCOL_ERROR) throw failure
            } catch (_: Exception) {
                // Status is read-only; it is safe to retry.
            }
            wait(delayMillis)
            delayMillis = (delayMillis * 2).coerceAtMost(5_000)
        }
        throw CommunityBuyException(CommunityBuyFailure.SUBMISSION_UNCERTAIN,
            "The buy may still be confirming. Check its status; do not buy again yet.",
            submissionMayHaveReachedServer = true)
    }

    private suspend fun releaseBeforeSubmit(prepared: PreparedCommunityBuy, signingStarted: Boolean = false) =
        withContext(NonCancellable) {
            val reason = if (signingStarted) CommunityBuyReleaseReason.SIGNED_NOT_SUBMITTED
                else CommunityBuyReleaseReason.BEFORE_SIGNING
            runCatching { backend.release(prepared, reason) }
        }

    private fun notifyProgress(callback: (CommunityBuyProgress) -> Unit, phase: CommunityBuyProgress) {
        runCatching { callback(phase) }
    }

    private fun walletKey(request: CommunityBuyRequest): String =
        "${request.userId}:${request.walletAddress}"

    private fun reconcileReceipt(prepared: PreparedCommunityBuy, status: CommunityBuyStatus) {
        val intent = PendingCommunityBuy.from(prepared)
        if (status.isVerifiedPurchase) {
            // If durable purchase saving fails, leave the pending intent to reconcile again.
            if (purchases?.save(prepared.request.userId, status) != false) pending?.clear(intent)
        } else if (status.state == CommunityBuyStatusState.FAILED ||
            status.state == CommunityBuyStatusState.EXPIRED) {
            pending?.clear(intent)
        }
    }
}
