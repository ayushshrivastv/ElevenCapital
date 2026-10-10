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

    @Test fun `background catalog projection keeps newest quote and original chart observation times`() {
        val range = ChartRange.ONE_DAY
        val oldPoint = StockPricePoint(now.minusSeconds(1), BigDecimal.ONE)
        val previous = stock.copy(charts = mapOf(range to listOf(oldPoint)))
        val incoming = instrument.copy(stock = stock.copy(quote = stock.quote.copy(price = BigDecimal.TEN)))
        val chart = LiveChart(stock.id, range, "ok", "USD", listOf(oldPoint), "onchain_token_market")
        val projected = projectCatalogStock(incoming, previous, false, false, now, 1_000, now.toEpochMilli(),
            mapOf((stock.id to range) to chart), mapOf((stock.id to range) to 900L), MarketCatalogExpiryCache())
        assertSame(incoming.stock.quote, projected.quote)
        assertEquals(listOf(oldPoint, StockPricePoint(now, BigDecimal.TEN)), projected.charts[range])
        assertEquals(now, projected.quote.asOf)
        assertEquals(listOf(oldPoint), previous.charts[range])
        assertEquals(listOf(oldPoint), chart.points)
    }

    @Test fun `background projection removes expired graphs and graphs from a changed price basis`() {
        val range = ChartRange.ONE_DAY
        val previous = stock.copy(charts = mapOf(range to listOf(StockPricePoint(now, BigDecimal.ONE))))
        val receipts = mapOf((stock.id to range) to 100L)
        val expired = projectCatalogStock(instrument, previous, false, false, now, 300_100, now.toEpochMilli(),
            emptyMap(), receipts, MarketCatalogExpiryCache())
        val changed = projectCatalogStock(instrument, previous, true, false, now, 101, now.toEpochMilli(),
            emptyMap(), receipts, MarketCatalogExpiryCache())
        assertTrue(expired.charts.isEmpty())
        assertTrue(changed.charts.isEmpty())
        assertEquals(BigDecimal.ONE, expired.quote.price)
        assertEquals(BigDecimal.ONE, changed.quote.price)
    }

    @Test fun `background freshness projection never gives cached prices a new observation age`() {
        val id = StockId("robinhood:test")
        val activity = StockMarketActivity("USD", "GeckoTerminal", "robinhood_chain_token",
            BigDecimal.TEN, BigDecimal.ONE, now, now, null, null)
        val original = stock.copy(id = id, activity = activity)
        val incoming = instrument.copy(stock = original, provider = "robinhood")
        val projected = projectCatalogStock(incoming, null, false, true, now.plusSeconds(301), 1_000,
            now.plusSeconds(301).toEpochMilli(), emptyMap(), emptyMap(), MarketCatalogExpiryCache())
        assertSame(original.quote, projected.quote)
        assertEquals(now, projected.quote.asOf)
        assertNull(projected.activity?.volume24h)
        assertNull(projected.activity?.netVolume24h)
        assertEquals(now, projected.activity?.receivedAt)
    }

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

    @Test fun `prewarm targets keep a bounded first five listings from each source and ignore quote changes`() {
        val instruments = listOf("backed", "backpack", "prestocks", "robinhood").flatMap { provider ->
            (0 until 24).map { index -> instrument.copy(provider = provider,
                stock = stock.copy(id = StockId("$provider:$index"), symbol = "LISTING$index")) }
        }
        val targets = marketPrewarmTargets(instruments)
        assertEquals(20, targets.size)
        assertEquals(96, instruments.size)
        for (index in 0 until 5) {
            assertEquals(listOf("backed:$index", "backpack:$index", "prestocks:$index", "robinhood:$index"),
                targets.drop(index * 4).take(4).map { it.first.value })
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

    @Test fun `receipt only ticks across the full catalog retain the complete rendered row list`() {
        val cache = LiveMarketRowsCache()
        val stocks = (0 until 2215).map { stock.copy(id = StockId("backed:$it")) }
        val metadata = stocks.associate { it.id to instrument.copy(stock = it) }
        val first = cache.select(stocks, metadata, emptySet(), emptySet())
        val tick = metadata.mapValues { (_, row) -> row.copy(quoteReceivedAt = now.plusSeconds(1)) }
        assertSame(first, cache.select(stocks, tick, emptySet(), emptySet()))
    }

    @Test fun `one live price and activity tick only replaces its row and keeps exact newest values`() {
        val cache = LiveMarketRowsCache()
        val stocks = (0 until 2215).map { stock.copy(id = StockId("backed:$it")) }
        val first = cache.select(stocks, emptyMap(), emptySet(), emptySet())
        val updated = stocks[15].copy(quote = stocks[15].quote.copy(price = BigDecimal.TEN),
            activity = StockMarketActivity("USD", "Jupiter", "solana_token", BigDecimal("100"),
                BigDecimal("-25"), now, now, null, null))
        val next = cache.select(stocks.mapIndexed { index, row -> if (index == 15) updated else row },
            emptyMap(), emptySet(), emptySet())
        assertSame(updated, next[15].stock)
        assertEquals(BigDecimal.TEN, next[15].stock.quote.price)
        assertEquals(BigDecimal("100"), next[15].volume)
        assertEquals(BigDecimal("-25"), next[15].netVolume)
        assertEquals(1, first.indices.count { first[it] !== next[it] })
    }

    @Test fun `row reuse follows stable ids and preserves watch changes removals and ordering`() {
        val cache = LiveMarketRowsCache()
        val stocks = (0 until 3).map { stock.copy(id = StockId("backed:$it")) }
        val first = cache.select(stocks, emptyMap(), emptySet(), emptySet())
        val watched = cache.select(stocks, emptyMap(), setOf(stocks[1].id), emptySet())
        assertTrue(watched[1].watched)
        assertSame(first[0], watched[0])
        assertSame(first[2], watched[2])
        val reversed = cache.select(stocks.reversed(), emptyMap(), setOf(stocks[1].id), emptySet())
        assertEquals(watched.reversed(), reversed)
        assertTrue(reversed.indices.all { reversed[it] === watched[watched.lastIndex - it] })
        cache.select(stocks.drop(1), emptyMap(), emptySet(), emptySet())
        val restored = cache.select(stocks, emptyMap(), emptySet(), emptySet())
        assertNotSame(first[0], restored[0])
        assertFalse(restored[1].watched)
    }

    private fun expire(input: Stock, elapsed: Long): Stock =
        expireMarketStock(input, now, now, elapsed, elapsed, emptyMap())
}
