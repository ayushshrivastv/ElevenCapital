package com.elevencapital.app

import com.elevencapital.app.data.LiveInstrument
import com.elevencapital.app.data.LiveChart
import com.elevencapital.core.stock.*
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarketCatalogPerformanceTest {
    private val now = Instant.parse("2026-09-25T00:00:00Z")
    private val stock = Stock(StockId("backed:test"), "TEST", "Test", StockQuote(BigDecimal.ONE, "USD", asOf = now))
    private val instrument = LiveInstrument(stock, "backed", "Backed", null, now, "onchain_token_market", "quoted_currency")

    @Test fun `reopening a canceled warmup loads foreground history and completed warmups remain reusable`() = runBlocking {
        val chart = LiveChart(stock.id, ChartRange.ONE_DAY, "ok", "USD",
            listOf(StockPricePoint(now, BigDecimal.ONE)), "onchain_token_market")
        val canceled = CompletableDeferred<LiveChart>().apply { cancel() }
        var requests = 0
        val reopened = awaitChartPrewarmOrLoad(canceled) { requests++; chart }
        assertSame(chart, reopened)
        assertEquals(1, requests)
        val cached = awaitChartPrewarmOrLoad(CompletableDeferred(chart)) { requests++; chart }
        assertSame(chart, cached)
        assertEquals(1, requests)
    }

    @Test fun `warmup cancellation while detail awaits retries foreground history`() = runBlocking {
        val chart = LiveChart(stock.id, ChartRange.ONE_DAY, "ok", "USD",
            listOf(StockPricePoint(now, BigDecimal.ONE)), "onchain_token_market")
        val warmup = CompletableDeferred<LiveChart>()
        var requests = 0
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            awaitChartPrewarmOrLoad(warmup) { requests++; chart }
        }
        assertFalse(detail.isCompleted)
        warmup.cancel()
        assertSame(chart, detail.await())
        assertEquals(1, requests)
    }

    @Test fun `canceling detail during a shared warmup does not load or cancel that warmup`() = runBlocking {
        val warmup = CompletableDeferred<LiveChart>()
        var requests = 0
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            awaitChartPrewarmOrLoad(warmup) {
                requests++
                error("Canceled detail must not start another request")
            }
        }
        assertFalse(detail.isCompleted)
        detail.cancel()
        detail.join()
        assertTrue(detail.isCancelled)
        assertEquals(0, requests)
        assertTrue(warmup.isActive)
        warmup.cancel()
    }

    @Test fun `freshness checks preserve fresh and already expired object identities`() {
        assertSame(stock, expire(stock, 300_000))
        val expired = expire(stock, 300_001)
        assertNull(expired.quote.price)
        assertEquals(now, expired.quote.asOf)
        assertSame(expired, expire(expired, 400_000))
    }

    @Test fun `repeated stale catalog deltas reuse projections without restoring expired prices`() {
        val cache = MarketCatalogExpiryCache()
        val expired = cache.expire(instrument, now.plusSeconds(301))
        assertNull(expired.quote.price)
        repeat(100) { index ->
            assertSame(expired, cache.expire(instrument, now.plusSeconds(302L + index)))
        }
        val refreshed = instrument.copy(quoteReceivedAt = now.plusSeconds(401))
        assertEquals(BigDecimal.ONE, cache.expire(refreshed, now.plusSeconds(402)).quote.price)
        cache.retain(emptySet())
        assertNotSame(expired, cache.expire(instrument, now.plusSeconds(403)))
    }

    @Test fun `cache reevaluates future observations when server snapshot catches up`() {
        val cache = MarketCatalogExpiryCache()
        assertNull(cache.expire(instrument, now.minusMillis(1)).quote.price)
        assertEquals(BigDecimal.ONE, cache.expire(instrument, now).quote.price)
        assertEquals(BigDecimal.ONE, cache.expire(instrument, now.plusSeconds(300)).quote.price)
        assertNull(cache.expire(instrument, now.plusMillis(300_001)).quote.price)
    }

    @Test fun `independent analytics expiry still removes values and retains provenance`() {
        val reference = StockStatisticsReference("solana", "mint", StockMetricKey.entries.associateWith { key ->
            StockMetricData(BigDecimal.ONE, key.unit, "Jupiter", "token", now, null)
        }, now.minusSeconds(301))
        val activity = StockMarketActivity("USD", "Jupiter", "solana_token", BigDecimal.TEN, BigDecimal.ONE,
            now, now.minusSeconds(301), null, null)
        val input = stock.copy(statistics = reference.toStatistics(), activity = activity)
        val result = expire(input, 0)
        assertEquals(BigDecimal.ONE, result.quote.price)
        assertNull(result.statistics?.marketCapitalization)
        assertTrue(result.statistics?.reference?.metrics?.values?.all { it.value == null && it.reason != null } == true)
        assertNull(result.activity?.volume24h)
        assertNull(result.activity?.netVolume24h)
        assertEquals("Jupiter", result.activity?.source)
        assertSame(result, expire(result, 1))
        val cache = MarketCatalogExpiryCache()
        val cached = cache.expire(instrument.copy(stock = input), now)
        assertEquals(result, cached)
        assertSame(cached, cache.expire(instrument.copy(stock = input), now.plusSeconds(1)))
    }

    @Test fun `graph expires at the same monotonic deadline without changing fresh financial fields`() {
        val range = ChartRange.ONE_DAY
        val input = stock.copy(charts = mapOf(range to listOf(StockPricePoint(now, BigDecimal.ONE))))
        val receipt = mapOf((stock.id to range) to 100L)
        assertSame(input, expireMarketStock(input, now, now, 0, 300_099, receipt))
        val expired = expireMarketStock(input, now, now, 0, 300_100, receipt)
        assertTrue(expired.charts.isEmpty())
        assertSame(input.quote, expired.quote)
    }

    @Test fun `prewarm targets include first twenty listings from each source and ignore quote changes`() {
        val instruments = listOf("backed", "backpack", "prestocks").flatMap { provider ->
            (0 until 24).map { index -> instrument.copy(provider = provider,
                stock = stock.copy(id = StockId("$provider:$index"), symbol = "LISTING$index")) }
        }
        val targets = marketPrewarmTargets(instruments)
        assertEquals(60, targets.size)
        assertEquals(72, instruments.size)
        for (index in 0 until 20) {
            assertEquals(listOf("backed:$index", "backpack:$index", "prestocks:$index"),
                targets.drop(index * 3).take(3).map { it.first.value })
        }
        assertTrue(targets.all { it.second == ChartRange.ONE_DAY })
        val priceTick = instruments.map { it.copy(stock = it.stock.copy(quote = StockQuote(BigDecimal.TEN, "USD"))) }
        assertTrue(hasSameMarketListings(instruments, priceTick))
        assertFalse(hasSameMarketListings(instruments, instruments.reversed()))
        assertFalse(hasSameMarketListings(instruments, instruments.dropLast(1)))
        assertFalse(hasSameMarketListings(instruments, instruments.mapIndexed { index, row ->
            if (index == 0) row.copy(stock = row.stock.copy(symbol = "MSFT")) else row
        }))
    }

    @Test fun `warm quote subscriptions deduplicate and keep visible rows before the hundred item cap`() {
        val visible = (0 until 70).map { StockId("backed:$it") }.toSet()
        val targets = (60 until 120).map { StockId("backed:$it") to ChartRange.ONE_DAY }
        val combined = marketWarmSubscriptionIds(visible, targets)
        assertEquals(100, combined.size)
        assertEquals(visible.toList(), combined.take(70))
        assertEquals((0 until 100).map { StockId("backed:$it") }.toSet(), combined)
        assertEquals(targets.map { it.first }.toSet(), marketWarmSubscriptionIds(emptySet(), targets))
    }

    private fun expire(input: Stock, elapsed: Long): Stock =
        expireMarketStock(input, now, now, elapsed, elapsed, emptyMap())
}
