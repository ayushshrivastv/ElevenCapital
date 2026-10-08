package com.elevencapital.app.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.wallet.PreparedTransfer
import com.elevencapital.app.wallet.SolanaPreparedTransfer
import com.elevencapital.app.wallet.TransferPreparationException
import com.elevencapital.app.wallet.TransferPreparationFailureCode
import com.elevencapital.app.wallet.TransferJournal
import com.elevencapital.app.wallet.TransferJournalBlockedException
import com.elevencapital.app.wallet.TransferJournalPersistenceException
import com.elevencapital.app.wallet.ValidatedWalletAddress
import com.elevencapital.app.wallet.WalletNetworkId
import com.elevencapital.app.wallet.WalletSubmission
import com.elevencapital.app.wallet.WalletSubmissionException
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.purchase.PurchaseAction
import com.elevencapital.app.purchase.PurchaseActionSubmission
import com.elevencapital.app.purchase.PurchaseException
import com.elevencapital.app.purchase.PurchaseFailureCode
import com.elevencapital.app.purchase.SolanaPurchaseAction
import io.privy.auth.AuthState
import io.privy.auth.LinkedAccount
import io.privy.auth.PrivyUser
import io.privy.auth.oAuth.OAuthProvider
import io.privy.logging.PrivyLogLevel
import io.privy.network.PrivyApiException
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import io.privy.wallet.solana.SendOptions
import io.privy.wallet.solana.SolanaCluster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** One application-lifetime session owner; Privy owns token persistence and wallet key management. */
class PrivyAuthController(
    context: Context,
    private val transferJournal: TransferJournal,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Only a restore-permission flag is persisted here; Privy owns all session credentials.
    private val sessionPreferences = context.applicationContext.getSharedPreferences("eleven-auth-lifecycle", Context.MODE_PRIVATE)
    private val sessionGate = AuthSessionGate(sessionPreferences.getBoolean("allowSessionRestore", true))
    // The explicit offline reference build must not restore accounts or create real wallets.
    private val configured = BuildConfig.PRIVY_APP_ID.isNotBlank() && BuildConfig.PRIVY_CLIENT_ID.isNotBlank()
    private val mutableState = MutableStateFlow(
        ElevenAuthState(configured, if (configured) AuthPhase.RESTORING else AuthPhase.UNCONFIGURED),
    )
    val state: StateFlow<ElevenAuthState> = mutableState.asStateFlow()

    private var walletJob: Job? = null
    private var loginJob: Job? = null
    private var sessionEpoch = 0L
    private var loggingOut = false
    private data class LogoutCleanup(val login: Job?, val message: String?, val restoreBlockSaved: Boolean)
    private var logoutCleanup: LogoutCleanup? = null
    private val transferMutex = Mutex()
    private val consumedTransferOperations = LinkedHashSet<String>()
    private val sdk: Privy? = if (!configured) null else try {
        Privy.init(
            context.applicationContext,
            PrivyConfig(
                appId = BuildConfig.PRIVY_APP_ID,
                appClientId = BuildConfig.PRIVY_CLIENT_ID,
                logLevel = PrivyLogLevel.NONE,
            ),
        )
    } catch (_: Exception) {
        mutableState.value = ElevenAuthState(true, AuthPhase.SIGNED_OUT, error = "Wallet sign-in could not start. Check the app configuration.")
        null
    }

    // The opt-in real token executor owns its own transaction checks and signing calls.
    // These accessors expose session state only inside this module; the installable UI APK
    // contains no token-launch or community-buy signer.
    internal val realExecutionPrivy: Privy? get() = sdk
    internal val realExecutionSigningMutex: Mutex get() = transferMutex
    internal fun realExecutionSessionMatches(userId: String): Boolean =
        !loggingOut && state.value.authenticated && state.value.userId == userId &&
            sessionGate.matchesVerifiedUser(userId)

    private val sessionRecovery = SessionRestoreRecovery(
        scope = scope,
        isCurrent = { sdk != null && !loggingOut && !sessionGate.isBlocked && !sessionGate.isSigningIn && restoringSession() },
        verify = {
            val privy = requireNotNull(sdk)
            // In the pinned SDK this requests restoration of an undetermined session; reading
            // getAuthState alone only waits for initialization and returns its current state.
            privy.onNetworkRestored()
            privy.getAuthState()
        },
        onObservation = ::accept,
        onRetryAvailable = ::setSessionRetryAvailable,
    )
    private val logoutRecovery = SessionRestoreRecovery(
        scope = scope,
        isCurrent = { sdk != null && loggingOut && sessionGate.isBlocked && logoutCleanup != null },
        verify = {
            val cleanup = requireNotNull(logoutCleanup)
            val privy = requireNotNull(sdk)
            // A cancelled browser operation must finish before SDK logout can clear its credentials.
            cleanup.login?.cancelAndJoin()
            privy.getAuthState()
            privy.logout()
        },
        onObservation = { finishLogout() },
        onRetryAvailable = ::setSessionRetryAvailable,
        attemptTimeoutMs = 12_000L,
        automaticDelaysMs = listOf(0L),
    )

    init {
        sdk?.let { privy ->
            scope.launch {
                privy.authState.collect { auth ->
                    if (loggingOut) return@collect
                    // The browser result, not a possibly late SDK event, completes explicit login.
                    if (sessionGate.isSigningIn) return@collect
                    if (sessionGate.isBlocked && auth is AuthState.Authenticated) {
                        clearSession()
                        return@collect
                    }
                    accept(auth)
                }
            }
            // A process killed during logout must not restore the former user's credentials.
            if (sessionGate.isBlocked) clearSession()
        }
    }

    /** Activity is checked but never retained; the SDK opens its own browser/redirect activity. */
    fun signIn(activity: Activity, provider: SocialLoginProvider) {
        if (activity.isFinishing || activity.isDestroyed || loginJob?.isCompleted == false || loggingOut) return
        if (state.value.authenticated) return
        if (provider == SocialLoginProvider.APPLE) {
            mutableState.value = state.value.copy(error = "Apple sign-in requires an additional supported authentication setup. Google sign-in is available once Privy is configured.")
            return
        }
        val privy = sdk ?: run {
            mutableState.value = state.value.copy(error = "Wallet sign-in is not configured yet.")
            return
        }
        sessionRecovery.stop()
        val attempt = sessionGate.beginSignIn()
        // A browser/redirect round trip may outlive this process. Keep restoration permitted so
        // Android process death cannot erase credentials that Privy successfully persisted.
        if (!setSessionRestoreAllowed(true)) {
            sessionGate.block()
            mutableState.value = ElevenAuthState(
                true,
                AuthPhase.SIGNED_OUT,
                error = "Sign-in could not safely start. Please try again.",
            )
            return
        }
        mutableState.value = ElevenAuthState(true, AuthPhase.SIGNING_IN)
        loginJob = scope.launch {
            try {
                checkNotNull(withTimeoutOrNull(6_000L) { privy.getAuthState() })
                val result = privy.oAuth.login(OAuthProvider.Google, "${BuildConfig.PRIVY_URL_SCHEME}://")
                ensureActive()
                val returnedUserId = result.getOrThrow().id
                // Authentication is verified by the SDK, not inferred from the browser callback.
                val verified = checkNotNull(withTimeoutOrNull(6_000L) { privy.getAuthState() })
                ensureActive()
                if (!sessionGate.isCurrentSignIn(attempt)) return@launch
                check(verified is AuthState.Authenticated && verified.user.id == returnedUserId && returnedUserId.isNotBlank())
                check(setSessionRestoreAllowed(true))
                check(sessionGate.completeSignIn(attempt, returnedUserId))
                accept(verified)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!loggingOut && sessionGate.isCurrentOperation(attempt)) {
                    // A transient network/verification error must never become a real logout.
                    // Reconcile the SDK once: a confirmed signed-out result may return to the
                    // login page, while any persisted/unverified state continues silently.
                    val observed = try {
                        withTimeoutOrNull(6_000L) { privy.getAuthState() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    when (observed) {
                        is AuthState.Authenticated -> {
                            if (sessionGate.completeSignIn(attempt, observed.user.id)) {
                                setSessionRestoreAllowed(true)
                                accept(observed)
                            }
                        }
                        AuthState.Unauthenticated -> {
                            sessionGate.block()
                            setSessionRestoreAllowed(false)
                            invalidateWalletWork()
                            mutableState.value = ElevenAuthState(
                                true,
                                AuthPhase.SIGNED_OUT,
                                error = "Sign-in did not complete. Please try again.",
                            )
                        }
                        else -> {
                            sessionGate.resumeRestore()
                            setSessionRestoreAllowed(true)
                            invalidateWalletWork()
                            mutableState.value = ElevenAuthState(true, AuthPhase.RESTORING)
                            if (observed != null) accept(observed) else sessionRecovery.start()
                        }
                    }
                }
            }
        }
    }

    fun logout() {
        clearSession()
    }

    /** A delayed deliberate recheck. It neither authenticates the user nor clears valid credentials. */
    fun retrySessionRestore() {
        if (!state.value.sessionRestoreRetryAvailable) return
        if (sessionGate.isBlocked) logoutRecovery.retry() else sessionRecovery.retry()
    }

    /** Requests a current user access JWT from Privy for one backend call; it is never cached. */
    internal suspend fun freshAccessToken(expectedUserId: String): String {
        val failure = {
            TransferPreparationException(
                TransferPreparationFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session expired. Sign in again.",
                retryable = false,
                statusCode = 401,
            )
        }
        val privy = sdk ?: throw failure()
        val current = state.value
        if (loggingOut || !current.authenticated || current.userId != expectedUserId ||
            !sessionGate.matchesVerifiedUser(expectedUserId)) throw failure()
        val auth = try { privy.getAuthState() } catch (_: Exception) { throw failure() }
        if (auth !is AuthState.Authenticated || auth.user.id != expectedUserId) throw failure()
        val user = try { privy.getUser() } catch (_: Exception) { null } ?: throw failure()
        if (user.id != expectedUserId) throw failure()
        val token = user.getAccessToken().getOrElse { throw failure() }
        if (token.length !in 32..8_192 || token.count { it == '.' } != 2 ||
            token.any { !(it.isLetterOrDigit() || it in "-_.~") }) throw failure()
        return token
    }

    internal suspend fun freshPurchaseAccessToken(expectedUserId: String): String = try {
        freshAccessToken(expectedUserId)
    } catch (_: TransferPreparationException) {
        throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
            "Your secure wallet session expired. Sign in again.", retryable = false)
    }

    /**
     * Broadcasts one exact action from a committed purchase route. The coordinator has already
     * durably recorded the provider boundary; any provider error is therefore treated as
     * potentially submitted and must be reconciled instead of automatically retried.
     */
    internal suspend fun broadcastPurchaseAction(
        expectedUserId: String,
        action: PurchaseAction,
    ): PurchaseActionSubmission = transferMutex.withLock {
        if (!BuildConfig.PURCHASE_EXECUTION_ENABLED) throw PurchaseException(
            PurchaseFailureCode.NOT_PURCHASABLE,
            "Live execution is not enabled yet. You can review routes without moving funds.",
            retryable = false,
            providerInvoked = false,
        )
        val privy = sdk ?: throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
            "Wallet signing is not configured on this device.", false)
        val current = state.value
        if (loggingOut || !current.authenticated || current.userId != expectedUserId ||
            !sessionGate.matchesVerifiedUser(expectedUserId)) {
            throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session changed. Check the purchase status before retrying.", false)
        }
        val verified = try { privy.getAuthState() } catch (_: Exception) { null }
        if (verified !is AuthState.Authenticated || verified.user.id != expectedUserId) {
            throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session changed. Check the purchase status before retrying.", false)
        }
        val user = try { privy.getUser() } catch (_: Exception) { null }
        if (user == null || user.id != expectedUserId) {
            throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
                "Your secure wallet session changed. Check the purchase status before retrying.", false)
        }

        try {
            if (action !is SolanaPurchaseAction) throw PurchaseException(
                PurchaseFailureCode.PROTOCOL_ERROR,
                "The purchase service returned an unsupported wallet action. Nothing was sent.",
                retryable = false,
                providerInvoked = false,
            )
            val expectedAddress = ValidatedWalletAddress.wallet(
                WalletNetworkId.SOLANA_MAINNET, action.walletAddress)
            val wallet = user.embeddedSolanaWallets.singleOrNull { candidate ->
                runCatching {
                    ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, candidate.address)
                }.getOrNull() == expectedAddress
            } ?: throw PurchaseException(PurchaseFailureCode.AUTHENTICATION_REQUIRED,
                "The reviewed wallet is no longer available. Check the purchase status before retrying.", false)
            val transactionId = withContext(NonCancellable) {
                wallet.provider.signAndSendTransaction(
                    transaction = action.unsignedTransactionBytes(),
                    cluster = SolanaCluster.MainNet,
                    rpcUrl = null,
                    sendOptions = SendOptions(
                        skipPreflight = false,
                        preflightCommitment = "confirmed",
                        maxRetries = 3,
                        minContextSlot = action.minContextSlot,
                    ),
                )
            }.getOrElse { throw purchaseProviderFailure() }
            if (!isCanonicalSolanaSignature(transactionId)) {
                throw PurchaseException(PurchaseFailureCode.SUBMISSION_UNCERTAIN,
                    "Privy returned an unexpected result. Eleven will check status and will not submit again.",
                    false, providerInvoked = true)
            }
            PurchaseActionSubmission(action.id, transactionId)
        } catch (failure: PurchaseException) {
            throw failure
        } catch (_: CancellationException) {
            throw PurchaseException(PurchaseFailureCode.SUBMISSION_UNCERTAIN,
                "The wallet result is uncertain. Eleven will check status and will not submit again.",
                false, providerInvoked = true)
        } catch (_: Exception) {
            throw PurchaseException(PurchaseFailureCode.SUBMISSION_UNCERTAIN,
                "The wallet result is uncertain. Eleven will check status and will not submit again.",
                false, providerInvoked = true)
        }
    }

    /**
     * The only bridge from a reviewed, server-verified payload to Privy's signing provider.
     * A preparation is one-shot, bound to one verified user and one exact SDK wallet ID/address.
     */
    suspend fun broadcastPreparedTransfer(prepared: PreparedTransfer): WalletSubmission = transferMutex.withLock {
        val privy = sdk ?: throw WalletSubmissionException(
            definitelyNotBroadcast = true,
            userMessage = "Wallet signing is not configured on this device.",
        )
        val review = try {
            prepared.requireUsable(Instant.now()).review
        } catch (_: TransferPreparationException) {
            throw WalletSubmissionException(true, "The network quote expired. Review the transfer again.")
        }
        val current = state.value
        if (loggingOut || !current.authenticated || current.userId != review.userId ||
            !sessionGate.matchesVerifiedUser(review.userId)) {
            throw WalletSubmissionException(true, "Your secure wallet session changed. Sign in and review again.")
        }
        val verified = try { privy.getAuthState() } catch (_: Exception) {
            throw WalletSubmissionException(true, "The secure wallet session could not be verified. Nothing was sent.")
        }
        if (verified !is AuthState.Authenticated || verified.user.id != review.userId) {
            throw WalletSubmissionException(true, "The secure wallet session changed. Nothing was sent.")
        }
        val user = try { privy.getUser() } catch (_: Exception) { null }
        if (user == null || user.id != review.userId) {
            throw WalletSubmissionException(true, "The secure wallet session could not be verified. Nothing was sent.")
        }
        if (review.operationId.value in consumedTransferOperations) {
            throw WalletSubmissionException(false,
                "This transfer was already submitted or checked. Check its status before doing anything else.")
        }

        var journalStarted = false
        var providerInvoked = false
        fun beginProviderSubmission() {
            try {
                // The coordinator already persisted COMMITTING before acquiring the backend lock.
                // Move to SUBMITTING only at the provider boundary; this is the point after which
                // process death can no longer prove that no broadcast occurred.
                transferJournal.beginSubmitting(review)
                journalStarted = true
            } catch (_: TransferJournalBlockedException) {
                throw WalletSubmissionException(true,
                    "This wallet has an unresolved transfer. Check History before sending again.")
            } catch (_: TransferJournalPersistenceException) {
                throw WalletSubmissionException(true,
                    "Transfer history could not be saved safely. Nothing was sent.")
            }
            consumedTransferOperations += review.operationId.value
            while (consumedTransferOperations.size > MAX_CONSUMED_TRANSFER_OPERATIONS) {
                consumedTransferOperations.remove(consumedTransferOperations.first())
            }
            providerInvoked = true
        }
        try {
            prepared.requireUsable(Instant.now())
            if (prepared !is SolanaPreparedTransfer || review.networkId != WalletNetworkId.SOLANA_MAINNET) {
                throw WalletSubmissionException(true, "The reviewed transfer is not a supported Solana transfer.")
            }
            val wallet = user.embeddedSolanaWallets.singleOrNull { candidate ->
                candidate.id == review.walletId &&
                    runCatching {
                        ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, candidate.address)
                    }.getOrNull() == review.sender
            } ?: throw WalletSubmissionException(true,
                "The reviewed Solana wallet is no longer available. Nothing was sent.")
            beginProviderSubmission()
            val transactionId = withContext(NonCancellable) {
                wallet.provider.signAndSendTransaction(
                    transaction = prepared.unsignedTransactionBytes(),
                    cluster = SolanaCluster.MainNet,
                    rpcUrl = null,
                    sendOptions = SendOptions(
                        skipPreflight = false,
                        preflightCommitment = "confirmed",
                        maxRetries = 3,
                        // TransferPreparationClient bounds this value to a positive signed Long.
                        minContextSlot = prepared.observedSlot.toLong(),
                    ),
                )
            }.getOrElse { throw providerSubmissionFailure(it) }
            if (!isCanonicalSolanaSignature(transactionId)) {
                throw WalletSubmissionException(false,
                    "Privy returned an unexpected transaction result. Check the wallet history before trying again.")
            }
            val submission = WalletSubmission(
                review.operationId,
                review.networkId,
                transactionId,
                System.currentTimeMillis(),
            )
            try {
                transferJournal.markBroadcast(review, transactionId)
            } catch (_: Exception) {
                // The provider returned a valid public identifier, so preserve it for the visible
                // status screen even if this device's durable storage failed at the same moment.
                throw WalletSubmissionException(
                    definitelyNotBroadcast = false,
                    userMessage = "The transfer was broadcast, but its history could not be saved. Keep the transaction identifier and do not resend.",
                    transactionId = transactionId,
                )
            }
            submission
        } catch (failure: WalletSubmissionException) {
            if (journalStarted) runCatching {
                transferJournal.markProviderFailure(review, failure.definitelyNotBroadcast)
            }
            throw failure
        } catch (failure: TransferPreparationException) {
            throw WalletSubmissionException(true, "The network quote expired. Review the transfer again.")
        } catch (_: CancellationException) {
            // Once the operation ID has been consumed, cancellation cannot prove that broadcast did
            // not happen: it may be delivered while the non-cancellable provider call is returning.
            // Classify it as uncertain so no caller can safely turn lifecycle cancellation into an
            // automatic retry of the same payment.
            if (journalStarted) runCatching { transferJournal.markProviderFailure(review, definitelyNotBroadcast = false) }
            throw WalletSubmissionException(
                definitelyNotBroadcast = !providerInvoked,
                userMessage = if (providerInvoked) {
                    "The transfer result is uncertain. Check its status before trying again."
                } else {
                    "The transfer was cancelled before Privy was asked to send it. Nothing was sent."
                },
            )
        } catch (_: Exception) {
            if (journalStarted) runCatching { transferJournal.markProviderFailure(review, definitelyNotBroadcast = false) }
            throw WalletSubmissionException(
                definitelyNotBroadcast = !providerInvoked,
                userMessage = if (providerInvoked) {
                    "The transfer result is uncertain. Check its status before trying again."
                } else {
                    "The wallet could not start this transfer. Nothing was sent."
                },
            )
        }
    }

    private fun clearSession(completionMessage: String? = null) {
        sdk ?: return
        if (loggingOut) return
        sessionRecovery.stop()
        loggingOut = true
        sessionGate.block()
        val restoreBlockSaved = setSessionRestoreAllowed(false)
        val pendingLogin = loginJob
        pendingLogin?.cancel()
        invalidateWalletWork()
        // Remove the former account's addresses immediately, including while logout is in flight.
        mutableState.value = ElevenAuthState(true, AuthPhase.RESTORING)
        logoutCleanup = LogoutCleanup(pendingLogin, completionMessage, restoreBlockSaved)
        logoutRecovery.start()
    }

    private fun finishLogout() {
        val cleanup = logoutCleanup ?: return
        if (!loggingOut || !sessionGate.isBlocked) return
        logoutRecovery.stop()
        logoutCleanup = null
        loggingOut = false
        mutableState.value = ElevenAuthState(true, AuthPhase.SIGNED_OUT,
            error = if (cleanup.restoreBlockSaved) cleanup.message else "Sign-out could not be saved securely. Please try again.")
    }

    private fun restoringSession(): Boolean = state.value.phase == AuthPhase.RESTORING ||
        state.value.phase == AuthPhase.SESSION_UNVERIFIED

    private fun setSessionRetryAvailable(available: Boolean) {
        if (!available || restoringSession()) {
            mutableState.value = state.value.copy(sessionRestoreRetryAvailable = available)
        }
    }

    /** An optional UI affordance; transient wallet setup failures also recover automatically. */
    fun retryWalletProvisioning() {
        val account = state.value.userId ?: return
        if (!state.value.authenticated || state.value.walletsReady) return
        invalidateWalletWork()
        provision(account)
    }

    private fun accept(auth: AuthState) {
        if (loggingOut || sessionGate.isBlocked || sessionGate.isSigningIn) return
        when (auth) {
            AuthState.NotReady -> {
                invalidateWalletWork()
                mutableState.value = ElevenAuthState(true, AuthPhase.RESTORING,
                    sessionRestoreRetryAvailable = state.value.sessionRestoreRetryAvailable)
                sessionRecovery.start()
            }
            AuthState.Unauthenticated -> {
                sessionRecovery.stop()
                sessionGate.block()
                setSessionRestoreAllowed(false)
                invalidateWalletWork()
                mutableState.value = ElevenAuthState(true, AuthPhase.SIGNED_OUT)
            }
            is AuthState.AuthenticatedUnverified -> {
                invalidateWalletWork()
                mutableState.value = ElevenAuthState(true, AuthPhase.SESSION_UNVERIFIED, error = "Reconnecting your secure session…",
                    sessionRestoreRetryAvailable = state.value.sessionRestoreRetryAvailable)
                sessionRecovery.start()
            }
            is AuthState.Authenticated -> {
                val accountId = auth.user.id
                if (!sessionGate.acceptsObservedUser(accountId)) {
                    clearSession("Your session changed. Please sign in again.")
                    return
                }
                sessionRecovery.stop()
                val sameAccount = state.value.userId == accountId
                if (!sameAccount) invalidateWalletWork()
                val wallets = auth.user.publicWallets()
                mutableState.value = ElevenAuthState(
                    configured = true,
                    phase = AuthPhase.AUTHENTICATED,
                    userId = accountId,
                    displayName = auth.user.linkedAccounts.filterIsInstance<LinkedAccount.GoogleOAuthAccount>()
                        .firstOrNull()?.let { it.name?.takeIf(String::isNotBlank) ?: it.email },
                    wallets = wallets,
                    provisioning = sameAccount && walletJob?.isActive == true,
                    error = if (sameAccount && !hasSolanaWallet(wallets)) state.value.error else null,
                )
                if (!state.value.walletsReady) provision(accountId)
            }
        }
    }

    private fun setSessionRestoreAllowed(allowed: Boolean): Boolean =
        sessionPreferences.edit().putBoolean("allowSessionRestore", allowed).commit()

    private fun invalidateWalletWork() {
        sessionEpoch++
        walletJob?.cancel()
        walletJob = null
    }

    private fun provision(accountId: String) {
        val privy = sdk ?: return
        if (walletJob?.isActive == true) return
        val epoch = sessionEpoch
        walletJob = scope.launch {
            var retryDelay = 5_000L
            while (isActive && epoch == sessionEpoch && state.value.userId == accountId) {
                mutableState.value = state.value.copy(provisioning = true)
                try {
                    val user = privy.getUser()?.takeIf { it.id == accountId } ?: return@launch
                    provisionWallets(
                        account = PrivyWalletAccount(user),
                        isCurrentAccount = { id ->
                            epoch == sessionEpoch && sessionGate.matchesVerifiedUser(id) && !loggingOut &&
                                state.value.userId == id && privy.getUser()?.id == id
                        },
                        onWallets = { wallets ->
                            if (epoch == sessionEpoch && state.value.userId == accountId) {
                                mutableState.value = state.value.copy(wallets = wallets)
                            }
                        },
                    )
                    if (epoch == sessionEpoch && state.value.userId == accountId) {
                        mutableState.value = state.value.copy(provisioning = false, error = null)
                    }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (epoch != sessionEpoch || state.value.userId != accountId) return@launch
                    if (BuildConfig.DEBUG) {
                        // Never pass the Throwable to Log: its message/body can contain credentials.
                        Log.w("ElevenWalletSetup", safeWalletFailureDiagnostic(failure))
                    }
                    mutableState.value = state.value.copy(
                        provisioning = false,
                        error = "Wallet setup is reconnecting automatically. Your existing wallet remains unchanged.",
                    )
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000)
                }
            }
        }
    }
}

private const val MAX_CONSUMED_TRANSFER_OPERATIONS = 256

private fun providerSubmissionFailure(failure: Throwable): WalletSubmissionException {
    val apiFailure = generateSequence(failure) { it.cause }
        .filterIsInstance<PrivyApiException>()
        .firstOrNull()
    val definitelyNotBroadcast = apiFailure?.responseBody?.code == "transaction_broadcast_failure"
    return if (definitelyNotBroadcast) {
        WalletSubmissionException(true,
            "Privy confirmed that the transaction was not broadcast. Review it again before retrying.")
    } else {
        WalletSubmissionException(false,
            "The transfer result is uncertain. Check its status before trying again.")
    }
}

private fun purchaseProviderFailure(): PurchaseException = PurchaseException(
    PurchaseFailureCode.SUBMISSION_UNCERTAIN,
    "The wallet result is uncertain. Eleven will check status and will not submit again.",
    retryable = false,
    providerInvoked = true,
)

private fun hasSolanaWallet(wallets: List<UserWallet>) = wallets.any { it.chain == WalletChain.SOLANA }

private fun PrivyUser.publicWallets(): List<UserWallet> =
    embeddedSolanaWallets.map { UserWallet(WalletChain.SOLANA, it.address, it.id.orEmpty()) }

private class PrivyWalletAccount(
    private val user: PrivyUser,
) : WalletAccountSession {
    // SDK user properties reflect its current session; retain the expected identity for guards.
    override val id: String = user.id
    override val wallets: List<UserWallet> get() = user.publicWallets()
    override suspend fun refresh() { user.refresh().getOrThrow() }
    override suspend fun create(chain: WalletChain): UserWallet = when (chain) {
        WalletChain.SOLANA -> user.createSolanaWallet(allowAdditional = false).getOrThrow()
            .let { UserWallet(chain, it.address, it.id.orEmpty()) }
    }
}
