package com.elevencapital.app

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.data.*
import com.elevencapital.core.stock.StockId
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WalletPortfolioTrackerTest {
    private val time = Instant.parse("2026-09-18T14:00:00Z")
    private val wallets = listOf(UserWallet(WalletChain.SOLANA, "solana-public-address"),
        UserWallet(WalletChain.ETHEREUM, "ethereum-public-address"))
    private val position = LiveWalletHolding(StockId("backed:example"), BigDecimal("0.25"), BigDecimal("123.45"))

    private fun snapshot(balance: BigDecimal? = BigDecimal.ZERO, holdings: List<LiveWalletHolding> = emptyList(),
        status: WalletPortfolioStatus = WalletPortfolioStatus.OK, complete: Boolean = true,
        at: Instant = time, tokenHoldings: List<LiveWalletTokenHolding> = emptyList()) = LiveWalletPortfolio(
        status = status, balanceUsd = balance, holdings = holdings, holdingsComplete = complete,
        unpricedAssets = if (status == WalletPortfolioStatus.PARTIAL) 1 else 0,
        networks = WalletChain.entries.map { LiveWalletNetwork(it, WalletNetworkStatus.OK, at) },
        receivedAt = at, message = null, tokenHoldings = tokenHoldings,
    )

    @Test fun `confirmed empty wallet becomes numeric zero with no stock rows`() = runBlocking {
        val tracker = WalletPortfolioTracker({ snapshot() }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        assertNull(tracker.state.value.balanceUsd)
        assertTrue(tracker.refreshOnce())
        assertEquals(BigDecimal.ZERO, tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.READY, tracker.state.value.phase)
        assertTrue(tracker.state.value.holdings.isEmpty())
    }

    @Test fun `failed initial scan cannot imply zero`() = runBlocking {
        val tracker = WalletPortfolioTracker({ throw IllegalStateException("unavailable") }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        assertFalse(tracker.refreshOnce())
        assertNull(tracker.state.value.balanceUsd)
        assertEquals("Unavailable", tracker.state.value.balancePlaceholder)
    }

    @Test fun `recent balance survives a failure but expires even if wall clock moves backwards`() = runBlocking {
        var elapsed = 0L
        var wall = time
        var fail = false
        val tracker = WalletPortfolioTracker({ if (fail) error("offline") else snapshot(BigDecimal("12.34")) },
            { wall }, { elapsed })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        fail = true
        tracker.refreshOnce()
        assertEquals(BigDecimal("12.34"), tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.UPDATING, tracker.state.value.phase)
        wall = time.minusSeconds(3_600)
        elapsed = 60_001L
        tracker.expire()
        assertNull(tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.UNAVAILABLE, tracker.state.value.phase)
    }

    @Test fun `late response after logout cannot repopulate the account`() = runBlocking {
        lateinit var tracker: WalletPortfolioTracker
        tracker = WalletPortfolioTracker({ tracker.bind(null, emptyList()); snapshot(BigDecimal.TEN, listOf(position)) },
            { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        assertNull(tracker.state.value.balanceUsd)
        assertTrue(tracker.state.value.holdings.isEmpty())
    }

    @Test fun `wallet metadata refresh during an in-flight balance read retains its verified result`() = runBlocking {
        lateinit var tracker: WalletPortfolioTracker
        tracker = WalletPortfolioTracker({
            assertFalse(tracker.bind("user-a", wallets.map { it.copy(walletId = "new-sdk-id") }))
            snapshot(BigDecimal.TEN)
        }, { time }, { 0L })
        tracker.bind("user-a", wallets.map { it.copy(walletId = "old-sdk-id") })
        assertTrue(tracker.refreshOnce())
        assertEquals(BigDecimal.TEN, tracker.state.value.balanceUsd)
    }

    @Test fun `switching identity or wallet clears prior values before next scan`() = runBlocking {
        val tracker = WalletPortfolioTracker({ snapshot(BigDecimal.TEN, listOf(position)) }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        tracker.bind("user-b", wallets)
        assertNull(tracker.state.value.balanceUsd)
        assertTrue(tracker.state.value.holdings.isEmpty())
        tracker.refreshOnce()
        tracker.bind("user-b", wallets.map { it.copy(address = it.address + "-new") })
        assertNull(tracker.state.value.balanceUsd)
        assertTrue(tracker.state.value.holdings.isEmpty())
    }

    @Test fun `Privy wallet ID or checksum casing changes do not reset the same public wallet`() = runBlocking {
        var calls = 0
        val evm = UserWallet(WalletChain.ETHEREUM, "0xAbCdEf0000000000000000000000000000000001")
        val tracker = WalletPortfolioTracker({ owned ->
            calls++
            assertEquals(2, owned.size)
            assertEquals(evm.address.lowercase(), owned.single { it.chain == WalletChain.ETHEREUM }.address)
            assertEquals(if (calls == 1) "evm-id-one" else "evm-id-two",
                owned.single { it.chain == WalletChain.ETHEREUM }.walletId)
            snapshot(BigDecimal("12.34"))
        }, { time }, { 0L })
        assertTrue(tracker.bind("user-a", listOf(wallets[0].copy(walletId = "sol-id-one"),
            evm.copy(walletId = "evm-id-one"))))
        tracker.refreshOnce()
        assertEquals(BigDecimal("12.34"), tracker.state.value.balanceUsd)
        assertFalse(tracker.bind("user-a", listOf(wallets[0].copy(walletId = "sol-id-two"),
            evm.copy(address = evm.address.lowercase(), walletId = "evm-id-two"), evm)))
        assertEquals(BigDecimal("12.34"), tracker.state.value.balanceUsd)
        tracker.refreshOnce()
        assertEquals(2, calls)
    }

    @Test fun `incomplete scan retains known quantity but complete empty scan removes stocks`() = runBlocking {
        var next = snapshot(BigDecimal("123.45"), listOf(position))
        val tracker = WalletPortfolioTracker({ next }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        next = snapshot(null, emptyList(), WalletPortfolioStatus.PARTIAL, complete = false)
        tracker.refreshOnce()
        assertEquals(position.quantity, tracker.state.value.holdings.single().quantity)
        assertNull(tracker.state.value.holdings.single().valueUsd)
        assertNull(tracker.state.value.balanceUsd)
        next = snapshot()
        tracker.refreshOnce()
        assertTrue(tracker.state.value.holdings.isEmpty())
        assertEquals(BigDecimal.ZERO, tracker.state.value.balanceUsd)
    }

    @Test fun `a positive unpriced stock remains visible without fabricating its value`() = runBlocking {
        val tracker = WalletPortfolioTracker({ snapshot(null, listOf(position.copy(valueUsd = null)),
            WalletPortfolioStatus.PARTIAL) }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        assertEquals(position.quantity, tracker.state.value.holdings.single().quantity)
        assertNull(tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.PARTIAL, tracker.state.value.phase)
    }

    @Test fun `partial chain subtotal cannot replace a known complete stock quantity`() = runBlocking {
        var next = snapshot(BigDecimal("123.45"), listOf(position))
        val tracker = WalletPortfolioTracker({ next }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        val newlyObserved = position.copy(stockId = StockId("backpack:new"))
        next = snapshot(null, listOf(position.copy(quantity = BigDecimal("0.1")), newlyObserved),
            WalletPortfolioStatus.PARTIAL, complete = false)
        tracker.refreshOnce()
        assertEquals(position.quantity, tracker.state.value.holdings.single { it.stockId == position.stockId }.quantity)
        assertEquals(2, tracker.state.value.holdings.size)
        assertTrue(tracker.state.value.holdings.all { it.valueUsd == null })
        next = snapshot(BigDecimal("49"), listOf(position.copy(quantity = BigDecimal("0.1"), valueUsd = BigDecimal("49"))))
        tracker.refreshOnce()
        assertEquals(BigDecimal("0.1"), tracker.state.value.holdings.single().quantity)
    }

    @Test fun `repeated cached snapshot never resets monotonic expiry after clock rollback`() = runBlocking {
        var elapsed = 0L
        var wall = time
        val tracker = WalletPortfolioTracker({ snapshot(BigDecimal.TEN) }, { wall }, { elapsed })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        elapsed = 58_000
        wall = time.plusSeconds(28)
        tracker.refreshOnce()
        assertEquals(BigDecimal.TEN, tracker.state.value.balanceUsd)
        elapsed = 60_001
        tracker.expire()
        assertNull(tracker.state.value.balanceUsd)
        tracker.refreshOnce()
        assertNull(tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.UNAVAILABLE, tracker.state.value.phase)
    }

    @Test fun `partial position values expire independently of unavailable total`() = runBlocking {
        var elapsed = 0L
        val tracker = WalletPortfolioTracker({ snapshot(null, listOf(position), WalletPortfolioStatus.PARTIAL) },
            { time }, { elapsed })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        assertNotNull(tracker.state.value.holdings.single().valueUsd)
        elapsed = 60_001
        tracker.expire()
        assertNull(tracker.state.value.holdings.single().valueUsd)
    }

    @Test fun `incomplete token scan preserves known quantity but clears price and stale value`() = runBlocking {
        val token = LiveWalletTokenHolding(WalletChain.SOLANA, "SOLANA:native", "SOL", BigDecimal("0.25"),
            BigDecimal("25"), BigDecimal("100"))
        var next = snapshot(balance = BigDecimal("25"), tokenHoldings = listOf(token))
        var elapsed = 0L
        val tracker = WalletPortfolioTracker({ next }, { time }, { elapsed })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        next = snapshot(balance = null, status = WalletPortfolioStatus.PARTIAL, complete = false)
        tracker.refreshOnce()
        assertEquals(token.quantity, tracker.state.value.tokenHoldings.single().quantity)
        assertNull(tracker.state.value.tokenHoldings.single().valueUsd)
        assertNull(tracker.state.value.tokenHoldings.single().unitPriceUsd)
        next = snapshot(balance = BigDecimal("25"), tokenHoldings = listOf(token))
        tracker.refreshOnce()
        elapsed = 60_001L
        tracker.expire()
        assertNull(tracker.state.value.tokenHoldings.single().valueUsd)
        assertNull(tracker.state.value.tokenHoldings.single().unitPriceUsd)
    }

    @Test fun `old server observation never gets fresh money merely because HTTP succeeded`() = runBlocking {
        val tracker = WalletPortfolioTracker({ snapshot(BigDecimal.TEN, at = time.minusSeconds(70)) }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        tracker.refreshOnce()
        assertNull(tracker.state.value.balanceUsd)
        assertEquals(WalletBalancePhase.UNAVAILABLE, tracker.state.value.phase)
    }

    @Test fun `unverified or incompletely provisioned account makes no balance request`() = runBlocking {
        var requests = 0
        val tracker = WalletPortfolioTracker({ requests++; snapshot() }, { time }, { 0L })
        tracker.bind(null, wallets)
        tracker.refreshOnce()
        tracker.bind("user-a", wallets.take(1))
        tracker.refreshOnce()
        assertEquals(0, requests)
    }

    @Test fun `lifecycle cancellation propagates without converting into a financial error`() = runBlocking {
        val tracker = WalletPortfolioTracker({ throw CancellationException("background") }, { time }, { 0L })
        tracker.bind("user-a", wallets)
        val result = runCatching { tracker.refreshOnce() }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(WalletBalancePhase.LOADING, tracker.state.value.phase)
    }
}
