package com.elevencapital.app.data

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WalletBalancePhase { LOADING, READY, UPDATING, PARTIAL, UNAVAILABLE }

data class WalletPortfolioViewState(
    val balanceUsd: BigDecimal? = null,
    val holdings: List<LiveWalletHolding> = emptyList(),
    val tokenHoldings: List<LiveWalletTokenHolding> = emptyList(),
    val phase: WalletBalancePhase = WalletBalancePhase.LOADING,
) {
    val balancePlaceholder: String get() = if (phase == WalletBalancePhase.LOADING) "—" else "Unavailable"
    val notice: String get() = when (phase) {
        WalletBalancePhase.LOADING -> "Checking your balance…"
        WalletBalancePhase.READY -> "Estimated balance · supported assets"
        WalletBalancePhase.UPDATING -> "Last balance · updating automatically"
        WalletBalancePhase.PARTIAL -> "Some balances unavailable · updating"
        WalletBalancePhase.UNAVAILABLE -> if (holdings.isEmpty() && tokenHoldings.isEmpty()) "Balance unavailable · updating automatically"
            else "Last known holdings · updating"
    }
}

/** One verified identity owns this in-memory cache. No wallet addresses or balances are persisted. */
class WalletPortfolioTracker(
    private val fetch: suspend (List<UserWallet>) -> LiveWalletPortfolio,
    private val now: () -> Instant = Instant::now,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class WalletIdentity(val chain: WalletChain, val address: String)
    private data class SessionKey(val userId: String, val wallets: List<WalletIdentity>)
    private data class Session(val key: SessionKey, val wallets: List<UserWallet>)
    private var session: Session? = null
    private var epoch = 0L
    private var balanceObservedAt: Instant? = null
    private var balanceReceivedElapsed = 0L
    private var balanceAgeAtReceipt = 0L
    private val refreshMutex = Mutex()
    private val mutableState = MutableStateFlow(WalletPortfolioViewState())
    val state = mutableState.asStateFlow()
    internal val hasBoundSession: Boolean get() = session != null

    /** Returns true only when the verified wallet identity actually changes. */
    fun bind(userId: String?, wallets: List<UserWallet>): Boolean {
        // Portfolio reads need only public chain/address. Privy can update wallet IDs and
        // checksummed EVM spelling while the same wallet is connected; neither changes owner.
        val normalized = wallets.map { wallet ->
            if (wallet.chain == WalletChain.ETHEREUM) wallet.copy(address = wallet.address.lowercase()) else wallet
        }.groupBy { WalletIdentity(it.chain, it.address) }.values.map { duplicates ->
            duplicates.firstOrNull { it.walletId.isNotBlank() } ?: duplicates.first()
        }.sortedWith(compareBy({ it.chain.name }, { it.address }))
        val next = userId?.takeIf { it.isNotBlank() && WalletChain.entries.all { chain -> normalized.any { it.chain == chain } } }
            ?.let { Session(SessionKey(it, normalized.map { wallet -> WalletIdentity(wallet.chain, wallet.address) }), normalized) }
        if (session?.key == next?.key) {
            // Keep current SDK wallet metadata without dropping the balance for the same addresses.
            session = next
            return false
        }
        session = next
        epoch++
        balanceObservedAt = null
        mutableState.value = WalletPortfolioViewState()
        return true
    }

    /** Lifecycle cancellation cancels HTTP; network changes retry immediately, never requiring Retry. */
    suspend fun refreshWhileVisible(networkChanges: Flow<Unit>, skipInitialIfReady: Boolean = false) = coroutineScope {
        if (session == null) return@coroutineScope
        val operation = epoch
        expire()
        // A ViewModel-scoped warm fetch may already have completed behind the device credential.
        // Avoid immediately repeating the same balance request when the Activity becomes STARTED.
        var skipNextRefresh = skipInitialIfReady && mutableState.value.phase == WalletBalancePhase.READY
        launch {
            while (currentCoroutineContext().isActive && operation == epoch) {
                delay(1_000)
                expire()
            }
        }
        networkChanges.onStart { emit(Unit) }.collectLatest {
            var backoffMillis = 5_000L
            while (currentCoroutineContext().isActive && operation == epoch) {
                val succeeded = if (skipNextRefresh) {
                    skipNextRefresh = false
                    true
                } else refreshOnce()
                if (succeeded) backoffMillis = 5_000L
                delay(if (succeeded) 15_000L else backoffMillis)
                if (!succeeded) backoffMillis = (backoffMillis * 2).coerceAtMost(30_000L)
            }
        }
    }

    internal suspend fun refreshOnce(): Boolean = refreshMutex.withLock { refreshOnceExclusive() }

    private suspend fun refreshOnceExclusive(): Boolean {
        val owner = session ?: return false
        val operation = epoch
        try {
            val result = fetch(owner.wallets)
            if (operation != epoch || session?.key != owner.key) return false
            val holdings = if (result.holdingsComplete) result.holdings else {
                // An interrupted scan is not evidence that an existing position was sold.
                val previous = mutableState.value.holdings.associateBy { it.stockId }
                // A same-ID row may be only one chain's subtotal; it cannot replace a known total.
                (result.holdings.associateBy { it.stockId } + previous).values.map { it.copy(valueUsd = null) }
            }
            val tokenHoldings = if (result.holdingsComplete) result.tokenHoldings else {
                val previous = mutableState.value.tokenHoldings.associateBy { it.assetId }
                (result.tokenHoldings.associateBy { it.assetId } + previous).values.map {
                    it.copy(valueUsd = null, unitPriceUsd = null)
                }
            }
            val oldest = (result.networks.mapNotNull { it.observedAt } + result.receivedAt).minOrNull()!!
            val receiptElapsed = elapsedMillis()
            val wallAge = (now().toEpochMilli() - oldest.toEpochMilli()).coerceAtLeast(0)
            // Repeated cached observations cannot become younger after a device clock adjustment.
            val elapsedAge = balanceObservedAt?.let { previous ->
                (balanceAgeAtReceipt + (receiptElapsed - balanceReceivedElapsed).coerceAtLeast(0) -
                    (oldest.toEpochMilli() - previous.toEpochMilli())).coerceAtLeast(0)
            } ?: 0L
            val age = maxOf(wallAge, elapsedAge)
            val complete = result.status == WalletPortfolioStatus.OK && result.holdingsComplete && age < MAX_BALANCE_AGE_MILLIS
            balanceObservedAt = oldest
            balanceReceivedElapsed = receiptElapsed
            balanceAgeAtReceipt = age
            mutableState.value = WalletPortfolioViewState(
                balanceUsd = result.balanceUsd.takeIf { complete },
                holdings = holdings,
                tokenHoldings = tokenHoldings,
                phase = if (complete) WalletBalancePhase.READY else WalletBalancePhase.PARTIAL,
            )
            expire()
            return result.status == WalletPortfolioStatus.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (operation != epoch || session?.key != owner.key) return false
            expire()
            mutableState.value = mutableState.value.copy(phase =
                if (mutableState.value.balanceUsd != null) WalletBalancePhase.UPDATING else WalletBalancePhase.UNAVAILABLE)
            return false
        }
    }

    internal fun expire() {
        val observed = balanceObservedAt ?: return
        val elapsedAge = balanceAgeAtReceipt + (elapsedMillis() - balanceReceivedElapsed).coerceAtLeast(0)
        val wallAge = (now().toEpochMilli() - observed.toEpochMilli()).coerceAtLeast(0)
        if (maxOf(elapsedAge, wallAge) >= MAX_BALANCE_AGE_MILLIS) {
            mutableState.value = mutableState.value.copy(
                balanceUsd = null,
                holdings = mutableState.value.holdings.map { it.copy(valueUsd = null) },
                tokenHoldings = mutableState.value.tokenHoldings.map { it.copy(valueUsd = null, unitPriceUsd = null) },
                phase = WalletBalancePhase.UNAVAILABLE,
            )
        }
    }

    companion object { const val MAX_BALANCE_AGE_MILLIS = 60_000L }
}
