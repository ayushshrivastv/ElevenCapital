package com.elevencapital.app

import com.elevencapital.app.data.CachedStartupChart
import com.elevencapital.app.data.LiveChart
import com.elevencapital.app.data.StartupMarketCache
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockPricePoint
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupMarketCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val backend = "http://127.0.0.1:8787"

    private fun chart(id: String = "backed:test") = LiveChart(StockId(id), ChartRange.ONE_DAY, "ok", "USD",
        (3 downTo 1).map { seconds ->
            StockPricePoint(Instant.ofEpochMilli(now).minusSeconds(seconds.toLong()), BigDecimal("99.000000000000000001"))
        }, "onchain_token_market", "Verified token history")

    @Test fun `public charts retain decimal precision provenance and original receipt across launches`() = runBlocking {
        val directory = temporary.newFolder()
        val entry = CachedStartupChart(chart(), now - 120_000)
        StartupMarketCache(directory, backend) { now }.write(listOf(entry))
        now += 30_000
        val restored = StartupMarketCache(directory, backend) { now }.read()
        assertEquals(listOf(entry), restored)
        StartupMarketCache(directory, backend) { now }.write(restored)
        now += 150_000
        assertTrue(StartupMarketCache(directory, backend) { now }.read().isEmpty())
    }

    @Test fun `future receipts and the exact five minute expiry never restore`() = runBlocking {
        val cache = StartupMarketCache(temporary.newFolder(), backend) { now }
        cache.write(listOf(CachedStartupChart(chart("backed:fresh"), now - 299_999),
            CachedStartupChart(chart("backed:expired"), now - 300_000),
            CachedStartupChart(chart("backed:future"), now + 1)))
        assertEquals(listOf("backed:fresh"), cache.read().map { it.chart.stockId.value })
        now -= 300_000
        assertTrue(cache.read().isEmpty())
    }

    @Test fun `a different backend cannot reuse cached chart data`() = runBlocking {
        val directory = temporary.newFolder()
        StartupMarketCache(directory, backend) { now }.write(listOf(CachedStartupChart(chart(), now)))
        assertTrue(StartupMarketCache(directory, "http://localhost:8787") { now }.read().isEmpty())
        assertTrue(StartupMarketCache(directory, "https://example.com/market") { now }.read().isEmpty())
        assertEquals(1, StartupMarketCache(directory, "$backend/") { now }.read().size)
    }

    @Test fun `unavailable and unsupported charts never displace useful day history`() = runBlocking {
        val cache = StartupMarketCache(temporary.newFolder(), backend) { now }
        val valid = chart()
        val invalid = listOf(valid.copy(stockId = StockId("backed:hour"), range = ChartRange.ONE_HOUR),
            valid.copy(stockId = StockId("backed:unavailable"), status = "unavailable", points = emptyList()),
            valid.copy(stockId = StockId("backed:short"), points = valid.points.take(2)),
            valid.copy(stockId = StockId("backed:unordered"), points = valid.points.reversed()),
            valid.copy(stockId = StockId("backed:basis"), basis = null))
        cache.write((invalid + valid).map { CachedStartupChart(it, now) })
        assertEquals(listOf(valid), cache.read().map { it.chart })
    }

    @Test fun `cache retains at most sixty four most recent distinct chart identities`() = runBlocking {
        val cache = StartupMarketCache(temporary.newFolder(), backend) { now }
        val entries = (0 until 70).map { index -> CachedStartupChart(chart("backed:$index"), now - index) }
        cache.write(entries.reversed() + entries.first().copy(receivedAtMillis = now - 100))
        val restored = cache.read()
        assertEquals(64, restored.size)
        assertEquals(entries.take(64), restored)
    }

    @Test fun `malformed or oversized files are ignored`() = runBlocking {
        val directory = temporary.newFolder()
        val cache = StartupMarketCache(directory, backend) { now }
        cache.write(listOf(CachedStartupChart(chart(), now)))
        val file = requireNotNull(directory.listFiles()).single()
        file.writeText("{broken")
        assertTrue(cache.read().isEmpty())
        file.writeBytes(ByteArray(4 * 1024 * 1024 + 1))
        assertTrue(cache.read().isEmpty())
    }

    @Test fun `an unwritable cache location does not prevent startup`() = runBlocking {
        val cache = StartupMarketCache(temporary.newFile(), backend) { now }
        cache.write(listOf(CachedStartupChart(chart(), now)))
        assertTrue(cache.read().isEmpty())
    }

    @Test fun `cache accepts only the same public endpoint URL forms as the client`() {
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?key=value",
            "https://example.com#fragment", "file:///tmp/market")) {
            assertThrows(IllegalArgumentException::class.java) { StartupMarketCache(temporary.root, url) }
        }
    }
}
