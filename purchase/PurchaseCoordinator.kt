package com.elevencapital.app.purchase

import com.elevencapital.app.BuildConfig
import com.elevencapital.app.auth.PrivyAuthController
import com.elevencapital.app.auth.ElevenAuthState
import com.elevencapital.app.auth.AuthPhase
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.core.stock.flow.OrderSide
import android.os.SystemClock
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface PurchaseActionBroadcaster {
    suspend fun broadcast(userId: String, action: PurchaseAction): PurchaseActionSubmission
}

/**
 * One exact-input purchase state machine. Quotes and actions are server verified; every signing
 * boundary is durably recorded and is deliberately never retried after Privy may have been called.
 */
class PurchaseCoordinator internal constructor(
    baseUrl: String,
    private val auth: PrivyAuthController,
    private val journal: PurchaseJournal,
    allowLoopbackHttp: Boolean = false,
    client: PurchaseClient? = null,
    private val broadcaster: PurchaseActionBroadcaster = PurchaseActionBroadcaster(auth::broadcastPurchaseAction),
    private val now: () -> Instant = Instant::now,
) {
    private val client = client ?: PurchaseClient(baseUrl, PurchaseAccessTokenProvider(auth::freshPurchaseAccessToken),
        allowLoopbackHttp = allowLoopbackHttp, now = now)
    private val executionEngine = PurchaseExecutionEngine(
        backend = this.client,
        journal = journal,
        broadcaster = broadcaster,
        now = now,
        sessionIsCurrent = { expected ->
            val current = auth.state.value
            current.authenticated && current.userId == expected && boundUserId == expected
        },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var operationJob: Job? = null
    private var optionsRefreshJob: Job? = null
    private var boundUserId: String? = null
    private var boundWallets: List<UserWallet>? = null
    private var manuallySelectedPaymentAssetId: String? = null
    private data class WalletSession(val userId: String, val wallets: List<UserWallet>)
    private data class OptionsKey(val session: WalletSession, val stockId: String, val side: OrderSide)
    private data class CachedOptions(val value: PurchaseOptions, val receivedAtElapsed: Long)
    private val optionsLock = Any()
    private var optionsSession: WalletSession? = null
    private val optionsCache = LinkedHashMap<OptionsKey, CachedOptions>()
    private val optionsInFlight = mutableMapOf<OptionsKey, Deferred<PurchaseOptions>>()
    private val mutableState = MutableStateFlow(PurchaseUiState())
    val state: StateFlow<PurchaseUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            auth.state.collect { observed ->
                val session = walletSession(observed)
                if (session != null || observed.phase in setOf(AuthPhase.SIGNED_OUT, AuthPhase.UNCONFIGURED)) {
                    useOptionsSession(session)
                }
            }
        }
    }

    /** Fetch the verified wallet options while the stock detail is already on screen. */
    fun prewarm(stockId: String, side: OrderSide = OrderSide.BUY) {
        if (!stockId.isSafeStockId()) return
        val session = walletSession(auth.state.value) ?: return
        useOptionsSession(session)
        val key = OptionsKey(session, stockId, side)
        if (cachedOptions(key) != null) return
        scope.launch {
            try { sharedOptionsRequest(key).await() }
            catch (_: Exception) { /* A screen bind can retry; prewarm never blocks navigation. */ }
        }
    }

    fun bind(stockId: String?, side: OrderSide = OrderSide.BUY) {
        val user = auth.state.value
        val session = walletSession(user)
        if (session != null || user.phase in setOf(AuthPhase.SIGNED_OUT, AuthPhase.UNCONFIGURED)) {
            useOptionsSession(session)
        }
        // Closing or changing the order screen must not cancel a committed wallet action.
        // The application-scoped coordinator continues tracking it for this authenticated user.
        if (user.authenticated && user.userId == boundUserId && mutableState.value.phase in setOf(
                PurchasePhase.COMMITTING, PurchasePhase.SIGNING, PurchasePhase.TRACKING,
            )
        ) return
        if (stockId == null || !stockId.isSafeStockId() || !user.authenticated || !user.walletsReady) {
            optionsRefreshJob?.cancel()
            operationJob?.cancel()
            boundUserId = user.userId
            boundWallets = user.wallets.toList()
            manuallySelectedPaymentAssetId = null
            mutableState.value = PurchaseUiState(stockId = stockId, phase = PurchasePhase.UNAVAILABLE,
                message = if (user.walletsReady) "This listing is not available for wallet orders."
                else "Connect both Eleven wallets to place orders.", side = side)
            return
        }
        if (boundWallets == user.wallets && canReusePurchaseState(boundUserId, user.userId, mutableState.value.stockId, stockId,
                mutableState.value.phase, mutableState.value.side, side)) return
        optionsRefreshJob?.cancel()
        operationJob?.cancel()
        boundUserId = user.userId
        boundWallets = user.wallets.toList()
        manuallySelectedPaymentAssetId = null
        mutableState.value = PurchaseUiState(stockId, PurchasePhase.LOADING_OPTIONS,
            message = "Checking wallet balances and order networks…", side = side)
        operationJob = scope.launch {
            val active = try { journal.activeForUser(requireNotNull(user.userId)) } catch (failure: PurchaseException) {
                fail(stockId, failure, side = side); return@launch
            }
            if (active != null) {
                mutableState.value = PurchaseUiState(active.stockId, PurchasePhase.TRACKING,
                    message = "Checking your in-progress order…", side = active.side)
                recoverStatus(active)
                return@launch
            }
            loadOptions(requireNotNull(session), stockId, side)
        }
    }

    fun refreshOptions() {
        val current = mutableState.value
        val user = auth.state.value
        if (current.phase !in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED, PurchasePhase.UNAVAILABLE) ||
            current.busy || current.quote != null || current.status != null || current.ambiguousSubmission ||
            !user.walletsReady || user.userId != boundUserId || current.stockId?.isSafeStockId() != true) return
        val side = current.side
        val session = walletSession(user) ?: return
        val key = OptionsKey(session, requireNotNull(current.stockId), side)
        discardCachedOptions(key)
        // Keep idle balance reads separate from the quote/signing job. A Buy tap can reserve
        // QUOTING concurrently; cancelling operationJob here would strand that purchase.
        optionsRefreshJob?.cancel()
        optionsRefreshJob = scope.launch { loadOptions(session, key.stockId, side) }
    }

    fun selectPaymentAsset(id: String) {
        val current = mutableState.value
        if (auth.state.value.userId != boundUserId) return
        if (current.busy || current.phase !in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED)) return
        val asset = current.options?.paymentAssets?.singleOrNull { it.id == id && it.enabled } ?: return
        manuallySelectedPaymentAssetId = asset.id
        mutableState.value = current.copy(phase = PurchasePhase.ENTRY, selectedPaymentAssetId = asset.id,
            quote = null, status = null, message = null, ambiguousSubmission = false)
    }

    fun selectDestination(id: String?) {
        val current = mutableState.value
        if (auth.state.value.userId != boundUserId) return
        if (current.busy || current.phase !in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED)) return
        if (id != null && current.options?.destinations?.none { it.id == id && it.enabled } != false) return
        mutableState.value = current.copy(phase = PurchasePhase.ENTRY, selectedDestinationId = id,
            quote = null, status = null, message = null, ambiguousSubmission = false)
    }

    fun requestQuote(displayAmount: String, slippageBps: Int = DEFAULT_SLIPPAGE_BPS) {
        requestQuoteInternal(displayAmount, slippageBps, executeBuy = false)
    }

    /** A Buy tap requests an exact quote and executes that same bound quote once, without a second app review step. */
    fun requestAndExecuteBuy(displayAmount: String, slippageBps: Int = DEFAULT_SLIPPAGE_BPS) {
        requestQuoteInternal(displayAmount, slippageBps, executeBuy = true)
    }

    private fun requestQuoteInternal(displayAmount: String, slippageBps: Int, executeBuy: Boolean) {
        val snapshot = mutableState.value
        val user = auth.state.value
        if (snapshot.phase !in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED) || snapshot.busy ||
            snapshot.ambiguousSubmission || !user.walletsReady || user.userId != boundUserId ||
            snapshot.stockId == null || (executeBuy && snapshot.side != OrderSide.BUY)) return
        val session = walletSession(user) ?: return
        val options = snapshot.options ?: return
        val asset = snapshot.selectedPaymentAsset ?: return
        val side = snapshot.side
        val amount = try { parseAmount(displayAmount, asset) } catch (failure: PurchaseException) {
            mutableState.value = snapshot.copy(phase = PurchasePhase.FAILED, message = failure.userMessage); return
        }
        if (amount.requestedDisplay > asset.balance || amount.baseUnits > asset.balanceBaseUnits) {
            mutableState.value = snapshot.copy(phase = PurchasePhase.FAILED,
                message = "The selected wallet balance is not enough for this order.")
            return
        }
        // Reserve the tap before launching IO. Another tap now sees QUOTING and cannot create
        // another operation while the first request is waiting for the backend.
        val quoting = reserveQuoteRequest(mutableState, snapshot) ?: return
        optionsRefreshJob?.cancel()
        operationJob?.cancel()
        operationJob = scope.launch {
            operationMutex.withLock {
                if (mutableState.value !== quoting || !isCurrentOptionsBinding(session.userId, snapshot.stockId, side) ||
                    walletSession(auth.state.value) != session) return@withLock
                try {
                    val request = PurchaseQuoteRequest(
                        operationId = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                        userId = session.userId, stockId = snapshot.stockId,
                        fromAssetId = asset.id, fromNetwork = asset.network, inputDecimals = asset.decimals,
                        destinationId = snapshot.selectedDestinationId,
                        amountBaseUnits = amount.baseUnits, slippageBps = slippageBps,
                        wallets = session.wallets, destinations = options.destinations, side = side,
                        inputUiMultiplier = asset.uiMultiplier,
                    )
                    val quote = client.quote(request)
                    if (quote.executionEnabled && !options.executionEnabled) purchaseProtocolFailure()
                    val binding = quote.binding
                    if (binding.operationId != request.operationId || binding.userId != session.userId ||
                        binding.stockId != snapshot.stockId || binding.side != side ||
                        binding.fromAssetId != asset.id || binding.fromNetwork != asset.network ||
                        binding.inputBaseUnits != amount.baseUnits || binding.inputDecimals != asset.decimals ||
                        binding.inputUiMultiplier.compareTo(asset.uiMultiplier) != 0 ||
                        binding.requestedDestinationId != snapshot.selectedDestinationId ||
                        binding.slippageBps != slippageBps) purchaseProtocolFailure()
                    if (mutableState.value !== quoting || !isCurrentOptionsBinding(session.userId, snapshot.stockId, side) ||
                        walletSession(auth.state.value) != session) return@withLock
                    if (executeBuy) {
                        if (!BuildConfig.PURCHASE_EXECUTION_ENABLED || !quote.executionEnabled) {
                            mutableState.value = snapshot.copy(phase = PurchasePhase.FAILED, quote = null,
                                message = quote.executionReason ?: "Live execution is not available for this route.")
                            return@withLock
                        }
                        quote.requireUsable(now())
                        // COMMITTING is reserved before execution starts; UI recomposition or a
                        // second callback cannot replay this quote or cancel its signing job.
                        clearOptionsCache()
                        mutableState.value = snapshot.copy(phase = PurchasePhase.COMMITTING, quote = quote,
                            status = null, message = "Securing your purchase…")
                        execute(session.userId, snapshot, quote)
                    } else {
                        mutableState.value = snapshot.copy(phase = PurchasePhase.REVIEW, quote = quote, status = null,
                            message = null, ambiguousSubmission = false)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: PurchaseException) {
                    if (mutableState.value === quoting) fail(snapshot.stockId, failure, snapshot.options, asset.id,
                        snapshot.selectedDestinationId, side)
                }
                catch (_: Exception) {
                    if (mutableState.value === quoting) fail(snapshot.stockId, serviceFailure(), snapshot.options, asset.id,
                        snapshot.selectedDestinationId, side)
                }
            }
        }
    }

    /** Called only by the explicit Buy or Sell button on the review state. */
    fun executeReviewedQuote() {
        val snapshot = mutableState.value
        val quote = snapshot.quote ?: return
        val userId = auth.state.value.userId ?: return
        if (snapshot.phase != PurchasePhase.REVIEW || quote.binding.userId != userId || userId != boundUserId ||
            quote.binding.side != snapshot.side) return
        if (!BuildConfig.PURCHASE_EXECUTION_ENABLED || !quote.executionEnabled) {
            mutableState.value = snapshot.copy(message = quote.executionReason
                ?: "Live execution is not enabled yet. You can review routes without moving funds.")
            return
        }
        try { quote.requireUsable(now()) } catch (failure: PurchaseException) {
            mutableState.value = snapshot.copy(phase = PurchasePhase.FAILED, quote = null,
                message = failure.userMessage)
            return
        }
        operationJob?.cancel()
        // A committed order can change balances on either chain. Never reuse its old options.
        clearOptionsCache()
        mutableState.value = snapshot.copy(phase = PurchasePhase.COMMITTING, quote = quote,
            status = null, message = "Securing your order…")
        operationJob = scope.launch {
            operationMutex.withLock { execute(userId, snapshot, quote) }
        }
    }

    fun editQuote() {
        val current = mutableState.value
        if (current.phase != PurchasePhase.REVIEW || auth.state.value.userId != boundUserId) return
        mutableState.value = current.copy(phase = PurchasePhase.ENTRY, quote = null, message = null)
    }

    private suspend fun loadOptions(session: WalletSession, stockId: String, side: OrderSide) {
        val userId = session.userId
        val key = OptionsKey(session, stockId, side)
        cachedOptions(key)?.let { cached ->
            if (isCurrentOptionsBinding(userId, stockId, side)) {
                presentOptions(stockId, side, cached)
            }
        }
        var retryDelayMillis = OPTIONS_INITIAL_RETRY_MILLIS
        while (isCurrentOptionsBinding(userId, stockId, side)) {
            if (mutableState.value.options == null) {
                mutableState.value = PurchaseUiState(stockId, PurchasePhase.LOADING_OPTIONS, side = side)
            }
            try {
                val user = auth.state.value
                if (walletSession(user) != session) return
                val options = sharedOptionsRequest(key).await()
                if (!isCurrentOptionsBinding(userId, stockId, side)) return
                presentOptions(stockId, side, options)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PurchaseException) {
                if (!failure.retryable) {
                    fail(stockId, failure, side = side)
                    return
                }
            } catch (_: Exception) {
                // Options loading cannot move funds. A transient connection failure is therefore
                // safe to retry while this exact user and stock remain on screen.
            }
            if (!isCurrentOptionsBinding(userId, stockId, side)) return
            if (mutableState.value.options == null) {
                mutableState.value = PurchaseUiState(stockId, PurchasePhase.LOADING_OPTIONS, side = side)
            }
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(OPTIONS_MAX_RETRY_MILLIS)
        }
    }

    private fun isCurrentOptionsBinding(userId: String, stockId: String, side: OrderSide): Boolean {
        val user = auth.state.value
        return boundUserId == userId && user.authenticated && user.walletsReady && user.userId == userId &&
            boundWallets == user.wallets && mutableState.value.stockId == stockId && mutableState.value.side == side
    }

    private fun presentOptions(stockId: String, side: OrderSide, options: PurchaseOptions) {
        while (true) {
            val current = mutableState.value
            // A balance refresh can finish just as a Buy tap reserves QUOTING. Never replace that
            // state or a payment choice made while the request was in flight.
            if (current.stockId != stockId || current.side != side ||
                current.quote != null || current.status != null || current.ambiguousSubmission ||
                current.phase !in setOf(PurchasePhase.LOADING_OPTIONS, PurchasePhase.ENTRY,
                    PurchasePhase.FAILED, PurchasePhase.UNAVAILABLE)) return
            val selectedAsset = preferredPaymentAssetId(options, side, manuallySelectedPaymentAssetId)
            val refreshed = if (options.purchasable && selectedAsset != null) {
                PurchaseUiState(stockId, PurchasePhase.ENTRY, options, selectedAsset,
                    selectedDestinationId = current.selectedDestinationId?.takeIf { selected ->
                        options.destinations.any { it.id == selected && it.enabled }
                    } ?: options.defaultDestinationId, side = side)
            } else {
                PurchaseUiState(stockId, PurchasePhase.UNAVAILABLE, options, message = options.reason
                    ?: "No verified ${side.name.lowercase()} route is currently available for this listing.", side = side)
            }
            if (mutableState.compareAndSet(current, refreshed)) return
        }
    }

    private fun walletSession(state: ElevenAuthState): WalletSession? = state
        .takeIf { it.authenticated && it.walletsReady }
        ?.let { WalletSession(requireNotNull(it.userId), it.wallets.toList()) }

    private fun useOptionsSession(session: WalletSession?) = synchronized(optionsLock) {
        if (optionsSession == session) return@synchronized
        optionsSession = session
        optionsCache.clear()
        optionsInFlight.values.toList().forEach(Job::cancel)
        optionsInFlight.clear()
    }

    private fun clearOptionsCache() = synchronized(optionsLock) {
        optionsCache.clear()
        optionsInFlight.values.toList().forEach(Job::cancel)
        optionsInFlight.clear()
    }

    private fun discardCachedOptions(key: OptionsKey) = synchronized(optionsLock) { optionsCache.remove(key) }

    private fun cachedOptions(key: OptionsKey): PurchaseOptions? = synchronized(optionsLock) {
        val cached = optionsCache[key] ?: return@synchronized null
        if (optionsSession != key.session || SystemClock.elapsedRealtime() - cached.receivedAtElapsed !in 0..OPTIONS_CACHE_MILLIS) {
            optionsCache.remove(key)
            return@synchronized null
        }
        cached.value
    }

    private fun sharedOptionsRequest(key: OptionsKey): Deferred<PurchaseOptions> = synchronized(optionsLock) {
        optionsInFlight[key]?.let { return@synchronized it }
        val request = scope.async(start = CoroutineStart.LAZY) {
            val options = client.options(key.session.userId, key.stockId, key.session.wallets, key.side)
            synchronized(optionsLock) {
                if (optionsSession == key.session && walletSession(auth.state.value) == key.session) {
                    optionsCache[key] = CachedOptions(options, SystemClock.elapsedRealtime())
                    while (optionsCache.size > MAX_CACHED_OPTIONS) optionsCache.remove(optionsCache.keys.first())
                }
            }
            options
        }
        optionsInFlight[key] = request
        request.invokeOnCompletion {
            synchronized(optionsLock) {
                if (optionsInFlight[key] === request) optionsInFlight.remove(key)
            }
        }
        request.start()
        request
    }

    private suspend fun execute(userId: String, original: PurchaseUiState, quote: PurchaseQuote) {
        try {
            val sourceNetwork = original.selectedPaymentAsset?.network ?: purchaseProtocolFailure()
            val latestStatus = executionEngine.execute(userId, quote, sourceNetwork) { progress ->
                mutableState.value = original.copy(
                    phase = progress.phase,
                    quote = quote,
                    status = progress.status,
                    message = progress.message,
                )
            }
            mutableState.value = original.copy(phase = PurchasePhase.TRACKING, quote = quote,
                status = latestStatus, message = "Tracking the route to ${quote.destination.symbol}…")
            track(userId, quote.id, quote.binding.stockId, quote, latestStatus, quote.binding.side)
        } catch (cancelled: CancellationException) {
            // Application-lifetime work is not normally cancelled. If it is, durable state decides
            // whether a future process may only observe or can safely start a fresh operation.
            throw cancelled
        } catch (failure: PurchaseException) {
            val active = runCatching { journal.activeForUser(userId) }.getOrNull()
            mutableState.value = original.copy(phase = PurchasePhase.FAILED, quote = quote,
                message = failure.userMessage,
                ambiguousSubmission = failure.providerInvoked || active?.phase == PurchaseJournalPhase.PROVIDER_INVOKED)
            if (active != null) scope.launch { recoverStatus(active) }
        } catch (_: Exception) {
            val active = runCatching { journal.activeForUser(userId) }.getOrNull()
            mutableState.value = original.copy(phase = PurchasePhase.FAILED, quote = quote,
                message = "The route result is uncertain. Eleven will check status and will not submit again.",
                ambiguousSubmission = active != null)
            if (active != null) scope.launch { recoverStatus(active) }
        }
    }

    private suspend fun recoverStatus(record: PurchaseJournalRecord) {
        val recoverySide = record.side
        when (record.phase) {
            PurchaseJournalPhase.PLANNED -> {
                // No wallet provider call occurred. Perform one authenticated observation, then
                // release the local block; a server-side commit can expire without risking funds.
                val status = runCatching { client.status(record.userId, record.quoteId) }.getOrNull()
                journal.finish(record.quoteId)
                mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED, status = status,
                    message = "The previous route stopped before wallet confirmation. Request a fresh quote.", side = recoverySide)
            }
            PurchaseJournalPhase.COMMITTED -> {
                val status = runCatching { client.status(record.userId, record.quoteId) }.getOrNull()
                journal.finish(record.quoteId)
                mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED, status = status,
                    message = "The committed route stopped before a wallet action. Request a fresh quote.", side = recoverySide)
            }
            PurchaseJournalPhase.INVOKING, PurchaseJournalPhase.RELEASING -> {
                val actionId = record.actionId
                if (actionId == null) {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED,
                        message = "Order recovery data is incomplete. Nothing will be submitted.", side = recoverySide)
                    return
                }
                try {
                    val status = client.releaseDefinitelyNotInvoked(record.userId, record.quoteId, actionId)
                    journal.finish(record.quoteId)
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED, status = status,
                        message = "The previous route stopped before Privy was called. Request a fresh quote.", side = recoverySide)
                } catch (_: Exception) {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED,
                        message = "Releasing an unused route lock. No wallet action was submitted.", side = recoverySide)
                }
            }
            PurchaseJournalPhase.BROADCAST -> {
                val savedSubmission = savedSubmissionForRecovery(record)
                if (savedSubmission == null) {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED,
                        message = "Order recovery data is incomplete. No transaction will be submitted again.",
                        ambiguousSubmission = true, side = recoverySide)
                    return
                }
                try {
                    val status = client.submitted(record.userId, record.quoteId,
                        savedSubmission.actionId, savedSubmission.transactionId)
                    journal.markReported(record)
                    if (status.state in setOf(PurchaseRouteState.COMPLETED, PurchaseRouteState.FAILED, PurchaseRouteState.EXPIRED)) {
                        track(record.userId, record.quoteId, record.stockId, quote = null, initial = status,
                            side = record.side)
                    } else {
                        mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.TRACKING, status = status,
                            message = "The submitted action is being tracked. Review is required before any further wallet confirmation.",
                            ambiguousSubmission = true, side = recoverySide)
                    }
                } catch (_: Exception) {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.TRACKING,
                        message = "The submitted transaction is saved. Status will reconnect without resubmitting it.",
                        ambiguousSubmission = true, side = recoverySide)
                }
            }
            PurchaseJournalPhase.PROVIDER_INVOKED -> {
                val status = runCatching { client.status(record.userId, record.quoteId) }.getOrNull()
                if (status?.state in setOf(PurchaseRouteState.COMPLETED, PurchaseRouteState.FAILED, PurchaseRouteState.EXPIRED)) {
                    track(record.userId, record.quoteId, record.stockId, quote = null, initial = status,
                        side = record.side)
                } else {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.FAILED, status = status,
                        message = "Wallet submission is unresolved. Eleven will not submit this action again.",
                        ambiguousSubmission = true, side = recoverySide)
                }
            }
            PurchaseJournalPhase.TRACKING -> {
                val status = runCatching { client.status(record.userId, record.quoteId) }.getOrNull()
                if (status?.state in setOf(PurchaseRouteState.COMPLETED, PurchaseRouteState.FAILED, PurchaseRouteState.EXPIRED)) {
                    track(record.userId, record.quoteId, record.stockId, quote = null, initial = status,
                        side = record.side)
                } else {
                    mutableState.value = PurchaseUiState(record.stockId, PurchasePhase.TRACKING, status = status,
                        message = "Route is being tracked. Review is required before another wallet confirmation.",
                        ambiguousSubmission = true, side = recoverySide)
                }
            }
        }
    }

    private suspend fun track(
        userId: String,
        quoteId: String,
        stockId: String,
        quote: PurchaseQuote?,
        initial: PurchaseStatus?,
        side: OrderSide,
    ) {
        var latest = initial
        var delayMillis = 2_000L
        while (auth.state.value.userId == userId && auth.state.value.authenticated) {
            try {
                val status = latest ?: client.status(userId, quoteId)
                latest = null
                when (status.state) {
                    PurchaseRouteState.COMPLETED -> {
                        clearOptionsCache()
                        journal.finish(quoteId)
                        mutableState.value = PurchaseUiState(stockId, PurchasePhase.COMPLETE, quote = quote,
                            status = status, message = "${quote?.destination?.symbol ?: "Your asset"} arrived in your Eleven wallet.",
                            side = side)
                        return
                    }
                    PurchaseRouteState.FAILED, PurchaseRouteState.EXPIRED -> {
                        clearOptionsCache()
                        journal.finish(quoteId)
                        mutableState.value = PurchaseUiState(stockId, PurchasePhase.FAILED, quote = quote,
                            status = status, message = status.message ?: if (status.state == PurchaseRouteState.EXPIRED)
                                "The route expired before it completed." else "The route did not complete. No action will be retried automatically.",
                            side = side)
                        return
                    }
                    PurchaseRouteState.COMMITTED, PurchaseRouteState.EXECUTING -> {
                        mutableState.value = mutableState.value.copy(stockId = stockId, side = side,
                            phase = PurchasePhase.TRACKING,
                            quote = quote, status = status,
                            message = "Route progress ${status.step} of ${status.stepCount}")
                    }
                }
                delay(delayMillis)
                delayMillis = (delayMillis * 2).coerceAtMost(10_000)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = mutableState.value.copy(stockId = stockId, side = side,
                    phase = PurchasePhase.TRACKING,
                    message = "Route status is reconnecting automatically…", ambiguousSubmission = true)
                delay(10_000)
            }
        }
    }

    private fun fail(
        stockId: String,
        failure: PurchaseException,
        options: PurchaseOptions? = null,
        paymentId: String? = null,
        destinationId: String? = null,
        side: OrderSide = mutableState.value.side,
    ) {
        mutableState.value = PurchaseUiState(stockId, if (failure.code == PurchaseFailureCode.NOT_PURCHASABLE)
            PurchasePhase.UNAVAILABLE else PurchasePhase.FAILED, options, paymentId, destinationId,
            message = failure.userMessage, ambiguousSubmission = failure.providerInvoked, side = side)
    }

    private data class ParsedAmount(val requestedDisplay: BigDecimal, val baseUnits: BigInteger)

    private fun parseAmount(input: String, asset: PurchasePaymentAsset): ParsedAmount {
        if (input.length !in 1..80 || !AMOUNT.matches(input)) {
            throw PurchaseException(PurchaseFailureCode.INVALID_AMOUNT,
                "Enter a positive amount using ordinary decimal digits.", false)
        }
        val amount = try { BigDecimal(input) } catch (_: Exception) { null }
            ?: throw PurchaseException(PurchaseFailureCode.INVALID_AMOUNT, "Enter a valid amount.", false)
        if (amount.signum() <= 0 || amount.scale() > 72) {
            throw PurchaseException(PurchaseFailureCode.INVALID_AMOUNT,
                "Enter an amount supported by ${asset.symbol}.", false)
        }
        val units = try { rawBaseUnitsForDisplay(amount, asset.decimals, asset.uiMultiplier) }
        catch (_: Exception) { throw PurchaseException(PurchaseFailureCode.INVALID_AMOUNT,
            "Enter an amount supported by ${asset.symbol}.", false) }
        if (units.signum() <= 0 || units.bitLength() > 256) throw PurchaseException(
            PurchaseFailureCode.INVALID_AMOUNT, "Enter a smaller positive amount.", false)
        return ParsedAmount(amount, units)
    }

    private fun serviceFailure() = PurchaseException(PurchaseFailureCode.SERVICE_UNAVAILABLE,
        "The route service is unavailable. Nothing was sent.", retryable = true)

    private companion object {
        const val DEFAULT_SLIPPAGE_BPS = 50
        const val OPTIONS_INITIAL_RETRY_MILLIS = 1_000L
        const val OPTIONS_MAX_RETRY_MILLIS = 10_000L
        const val OPTIONS_CACHE_MILLIS = 30_000L
        const val MAX_CACHED_OPTIONS = 32
        val AMOUNT = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    }
}

internal fun canReusePurchaseState(
    boundUserId: String?,
    requestedUserId: String?,
    currentStockId: String?,
    requestedStockId: String?,
    phase: PurchasePhase,
    currentSide: OrderSide = OrderSide.BUY,
    requestedSide: OrderSide = OrderSide.BUY,
): Boolean = boundUserId != null && boundUserId == requestedUserId &&
    currentStockId == requestedStockId && currentSide == requestedSide && phase != PurchasePhase.IDLE

/** Atomically claim one quote request before any asynchronous work or wallet action can start. */
internal fun reserveQuoteRequest(
    state: MutableStateFlow<PurchaseUiState>,
    snapshot: PurchaseUiState,
): PurchaseUiState? {
    if (snapshot.phase !in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED) ||
        snapshot.busy || snapshot.ambiguousSubmission) return null
    val quoting = snapshot.copy(phase = PurchasePhase.QUOTING, quote = null, status = null,
        message = "Finding the best verified route…", ambiguousSubmission = false)
    return quoting.takeIf { state.compareAndSet(snapshot, quoting) }
}

/** Choose the largest spendable holding by portfolio value, keeping an explicit wallet choice. */
internal fun preferredPaymentAssetId(
    options: PurchaseOptions,
    side: OrderSide,
    manuallySelectedId: String? = null,
): String? {
    val enabled = options.paymentAssets.filter { it.enabled }
    enabled.firstOrNull { it.id == manuallySelectedId }?.let { return it.id }
    if (side == OrderSide.SELL) {
        return options.defaultPaymentAssetId ?: enabled.firstOrNull { it.balanceBaseUnits.signum() > 0 }?.id
            ?: enabled.firstOrNull()?.id
    }

    val funded = enabled.filter { it.balanceBaseUnits.signum() > 0 && it.balance.signum() > 0 }
    funded.filter { it.usdValue?.signum() == 1 }
        .sortedWith(compareByDescending<PurchasePaymentAsset> { it.usdValue }.thenBy { it.id })
        .firstOrNull()?.let { return it.id }
    options.defaultPaymentAssetId?.takeIf { id -> funded.any { it.id == id } }?.let { return it }
    return funded.firstOrNull { it.symbol.equals("USDC", ignoreCase = true) }?.id
        ?: funded.minByOrNull { it.id }?.id
        ?: options.defaultPaymentAssetId
        ?: enabled.minByOrNull { it.id }?.id
}
