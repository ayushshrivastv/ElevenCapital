package com.elevencapital.app.tokenlaunch

import android.content.Context
import com.elevencapital.app.auth.PrivyAuthController
import com.elevencapital.app.auth.PrivyRealExecutionSigner
import com.elevencapital.app.communitybuy.CommunityMainnetVerifier
import java.time.Duration
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

internal fun interface TokenLaunchSigner {
    suspend fun sign(prepared: PreparedTokenLaunch): String
}

/**
 * Coordinates one reviewed launch. Signing only produces a signature; the backend verifies,
 * merges its two persisted partial signatures, simulates, and broadcasts the exact mainnet message.
 */
class TokenLaunchCoordinator private constructor(
    private val backend: TokenLaunchBackend,
    private val signer: TokenLaunchSigner,
    private val receipts: TokenLaunchReceiptSink,
    private val now: () -> Instant,
    private val pollingDelay: suspend (Long) -> Unit,
    private val artworkEncoder: TokenArtworkEncoder?,
    private val verifyMainnetBlockhash: suspend (String) -> Unit,
) {
    constructor(
        context: Context,
        baseUrl: String,
        auth: PrivyAuthController,
        receipts: TokenLaunchReceiptStore,
        allowLoopbackHttp: Boolean = false,
    ) : this(
        backend = TokenLaunchClient(
            baseUrl,
            TokenLaunchAccessTokenProvider(PrivyRealExecutionSigner(auth)::freshTokenForTokenLaunch),
            allowLoopbackHttp = allowLoopbackHttp,
        ),
        signer = TokenLaunchSigner(PrivyRealExecutionSigner(auth)::signPreparedTokenLaunch),
        receipts = receipts,
        now = Instant::now,
        pollingDelay = { delay(it) },
        artworkEncoder = AndroidTokenArtworkEncoder(context.contentResolver),
        verifyMainnetBlockhash = CommunityMainnetVerifier()::verifyRecentBlockhash,
    )

    internal constructor(
        backend: TokenLaunchBackend,
        signer: TokenLaunchSigner,
        receipts: TokenLaunchReceiptSink,
        now: () -> Instant,
        pollingDelay: suspend (Long) -> Unit,
        testOnly: Unit = Unit,
        artworkEncoder: TokenArtworkEncoder? = null,
        verifyMainnetBlockhash: suspend (String) -> Unit = {},
    ) : this(backend, signer, receipts, now, pollingDelay, artworkEncoder, verifyMainnetBlockhash) {
        @Suppress("UNUSED_VARIABLE") val ignored = testOnly
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeOperations = ConcurrentHashMap.newKeySet<String>()

    internal suspend fun uploadMetadata(
        userId: String,
        input: TokenLaunchMetadataInput,
    ): UploadedTokenMetadata {
        if (!safeIdentity(userId, 256)) throw TokenLaunchException(
            TokenLaunchFailureCode.AUTHENTICATION_REQUIRED,
            "Sign in with Privy before launching a token.",
            retryable = false,
        )
        val encoder = artworkEncoder ?: throw TokenLaunchException(
            TokenLaunchFailureCode.SERVICE_UNAVAILABLE,
            "Token artwork upload is unavailable. Nothing was submitted.",
            retryable = true,
        )
        val artwork = encoder.encode(input.imageUri)
        currentCoroutineContext().ensureActive()
        return backend.uploadMetadata(
            userId,
            TokenLaunchMetadataUpload(
                name = input.name,
                symbol = input.symbol,
                description = input.description,
                artwork = artwork,
                websiteUrl = input.websiteUrl,
                xUrl = input.xUrl,
                telegramUrl = input.telegramUrl,
            ),
        )
    }

    suspend fun prepare(request: TokenLaunchRequest): PreparedTokenLaunch {
        // The backend can persist PREPARED before the HTTP response reaches Android. Keep that
        // one request application-scoped so cancellation can release the exact returned hash
        // instead of stranding an unresolved operation until its blockhash expires.
        val preparation = scope.async { backend.prepare(request) }
        return try {
            preparation.await()
        } catch (cancelled: CancellationException) {
            scope.launch {
                try {
                    releaseBeforeSubmit(preparation.await(), TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                } catch (_: Exception) {
                    // Failed preparation or release leaves no client-side transaction to replay.
                }
            }
            throw cancelled
        }
    }

    /**
     * Commits, signs once, submits once, then polls read-only status. After `/submit` begins the
     * application scope owns reconciliation, so closing the Compose flow cannot trigger a resend.
     */
    suspend fun execute(
        prepared: PreparedTokenLaunch,
        onProgress: (TokenLaunchProgress) -> Unit = {},
    ): ConfirmedTokenLaunchReceipt {
        prepared.requireUsable(now())
        val operationId = prepared.request.operationId
        if (!activeOperations.add(operationId)) throw TokenLaunchException(
            TokenLaunchFailureCode.CONFLICT,
            "This token launch is already being submitted. Check its status instead.",
            retryable = false,
        )
        var handedToBackground = false
        try {
            notifyProgress(onProgress, TokenLaunchProgress.COMMITTING)
            try {
                backend.commit(prepared)
            } catch (cancelled: CancellationException) {
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                throw cancelled
            } catch (failure: Exception) {
                // Even an ambiguous commit is safe to release: the wallet provider was not invoked.
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                throw failure
            }

            try {
                verifyMainnetBlockhash(prepared.recentBlockhash)
            } catch (cancelled: CancellationException) {
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                throw cancelled
            } catch (_: Exception) {
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                throw TokenLaunchException(
                    TokenLaunchFailureCode.WRONG_NETWORK,
                    "Solana Mainnet could not be independently verified. Nothing was signed.",
                    retryable = true,
                )
            }
            try { prepared.requireUsable(now()) } catch (failure: TokenLaunchException) {
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED)
                throw failure
            }
            notifyProgress(onProgress, TokenLaunchProgress.SIGNING)
            val checked = try {
                val walletSigned = signer.sign(prepared)
                // Privy's non-cancellable bridge can return after its caller was cancelled. Never
                // turn that late signature into a backend submission after the flow has closed.
                currentCoroutineContext().ensureActive()
                TokenLaunchProtocol.walletSignedTransaction(walletSigned, prepared).also {
                    currentCoroutineContext().ensureActive()
                }
            } catch (cancelled: CancellationException) {
                // signTransaction never broadcasts. A cancelled UI must not continue to /submit.
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.SIGNED_TRANSACTION_NOT_SUBMITTED)
                throw cancelled
            } catch (failure: Exception) {
                // signTransaction cannot broadcast. Until /submit starts, every signer failure is
                // definitively safe to release, even if Privy's provider UI was invoked.
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.SIGNED_TRANSACTION_NOT_SUBMITTED)
                if (failure is TokenLaunchException) throw failure
                throw TokenLaunchException(
                    TokenLaunchFailureCode.SIGNING_FAILED,
                    "The wallet could not sign this launch. Nothing was submitted.",
                    retryable = true,
                    providerInvoked = true,
                )
            }
            val expectedTransactionId = TokenLaunchProtocol.signedTransactionId(checked, prepared)
            notifyProgress(onProgress, TokenLaunchProgress.SUBMITTING)
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                releaseBeforeSubmit(prepared, TokenLaunchReleaseReason.SIGNED_TRANSACTION_NOT_SUBMITTED)
                throw cancelled
            }

            val reconciliation = scope.async {
                try {
                    submitOnceAndConfirm(prepared, checked, expectedTransactionId, onProgress)
                } finally {
                    activeOperations.remove(operationId)
                }
            }
            handedToBackground = true
            return reconciliation.await()
        } finally {
            if (!handedToBackground) activeOperations.remove(operationId)
        }
    }

    /** Alias used by UI integration; it has the same one-shot semantics as [execute]. */
    suspend fun submit(
        prepared: PreparedTokenLaunch,
        onProgress: (TokenLaunchProgress) -> Unit = {},
    ): ConfirmedTokenLaunchReceipt = execute(prepared, onProgress)

    suspend fun status(prepared: PreparedTokenLaunch): TokenLaunchStatus = backend.status(prepared)

    suspend fun awaitTerminalStatus(
        prepared: PreparedTokenLaunch,
        expectedTransactionId: String,
    ): TokenLaunchStatus = pollUntilTerminal(prepared, expectedTransactionId)

    private suspend fun submitOnceAndConfirm(
        prepared: PreparedTokenLaunch,
        walletSigned: String,
        expectedTransactionId: String,
        onProgress: (TokenLaunchProgress) -> Unit,
    ): ConfirmedTokenLaunchReceipt {
        val submittedTransactionId = try {
            backend.submit(prepared, walletSigned).transactionId
        } catch (_: CancellationException) {
            // This application-owned job is not cancelled with the UI caller. A process shutdown
            // is reconciled by operation identity rather than ever resubmitting the signed bytes.
            null
        } catch (_: TokenLaunchException) {
            // Any /submit transport or response failure is ambiguous. Do not retry it; status is
            // the only safe next operation because the backend durably binds a transaction ID.
            null
        } catch (_: Exception) {
            // Unknown submission failures are ambiguous for the same reason.
            null
        }
        if (submittedTransactionId != null && submittedTransactionId != expectedTransactionId) protocolFailure()
        notifyProgress(onProgress, TokenLaunchProgress.CONFIRMING)
        val terminal = pollUntilTerminal(prepared, expectedTransactionId)
        return when (terminal.state) {
            TokenLaunchStatusState.CONFIRMED -> {
                if (!terminal.isFinalized) protocolFailure()
                val transactionId = terminal.transactionId ?: protocolFailure()
                val receipt = ConfirmedTokenLaunchReceipt(
                    userId = prepared.request.userId,
                    operationId = prepared.request.operationId,
                    walletAddress = prepared.request.walletAddress,
                    mintAddress = terminal.mintAddress,
                    configAddress = terminal.configAddress,
                    poolAddress = terminal.poolAddress,
                    transactionId = transactionId,
                    explorerUrl = terminal.explorerUrl,
                    confirmedAt = terminal.observedAt,
                    finalized = terminal.isFinalized,
                )
                // A disk failure cannot change an already confirmed public chain result.
                runCatching { receipts.save(receipt) }
                receipt
            }
            TokenLaunchStatusState.FAILED -> throw TokenLaunchException(
                TokenLaunchFailureCode.LAUNCH_FAILED,
                "The Meteora launch failed on Solana Mainnet. No replacement was submitted.",
                retryable = false,
                submissionMayHaveReachedServer = true,
            )
            TokenLaunchStatusState.EXPIRED -> throw TokenLaunchException(
                TokenLaunchFailureCode.EXPIRED,
                "The committed launch expired without confirmation. Check its status before preparing another.",
                retryable = false,
                submissionMayHaveReachedServer = true,
            )
            else -> throw TokenLaunchException(
                TokenLaunchFailureCode.SUBMISSION_UNCERTAIN,
                "The launch result is uncertain. Check its status; Eleven will not submit it again.",
                retryable = false,
                submissionMayHaveReachedServer = true,
            )
        }
    }

    private suspend fun pollUntilTerminal(
        prepared: PreparedTokenLaunch,
        expectedTransactionId: String?,
    ): TokenLaunchStatus {
        val deadline = maxOf(now().plus(MAXIMUM_STATUS_POLL), prepared.expiresAt.plusSeconds(45))
        var delayMillis = INITIAL_STATUS_DELAY_MILLIS
        var lastStatus: TokenLaunchStatus? = null
        while (now().isBefore(deadline)) {
            try {
                val observed = backend.status(prepared)
                if (expectedTransactionId != null && observed.transactionId != null &&
                    observed.transactionId != expectedTransactionId) protocolFailure()
                lastStatus = observed
                if (observed.terminal) {
                    if (observed.state == TokenLaunchStatusState.CONFIRMED &&
                        observed.transactionId != expectedTransactionId) protocolFailure()
                    return observed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: TokenLaunchException) {
                if (failure.code == TokenLaunchFailureCode.PROTOCOL_ERROR) throw failure
            } catch (_: Exception) {
                // Read-only status calls may be retried; they can never duplicate a launch.
            }
            pollingDelay(delayMillis)
            delayMillis = (delayMillis * 2).coerceAtMost(MAXIMUM_STATUS_DELAY_MILLIS)
        }
        return lastStatus ?: throw TokenLaunchException(
            TokenLaunchFailureCode.SUBMISSION_UNCERTAIN,
            "The launch is still being reconciled. Check its status again; do not submit another transaction.",
            retryable = false,
            submissionMayHaveReachedServer = true,
        )
    }

    private suspend fun releaseBeforeSubmit(prepared: PreparedTokenLaunch, reason: TokenLaunchReleaseReason) =
        withContext(NonCancellable) {
            runCatching { backend.release(prepared, reason) }
    }

    private fun notifyProgress(observer: (TokenLaunchProgress) -> Unit, progress: TokenLaunchProgress) {
        runCatching { observer(progress) }
    }

    private companion object {
        val MAXIMUM_STATUS_POLL: Duration = Duration.ofMinutes(2)
        // Status has its own 60/minute subject bucket; this remains comfortably below it.
        const val INITIAL_STATUS_DELAY_MILLIS = 2_000L
        const val MAXIMUM_STATUS_DELAY_MILLIS = 5_000L
    }
}
