package com.elevencapital.app

import com.elevencapital.app.data.RobinhoodFeaturedCatalogCache
import com.elevencapital.app.data.RobinhoodFeaturedClient
import com.elevencapital.app.data.RobinhoodMarketWarmup
import com.elevencapital.app.data.StartupMarketCache
import com.elevencapital.app.data.LiveChart
import com.elevencapital.app.data.LiveMarketDataParser
import com.elevencapital.app.data.RobinhoodFeaturedSymbols
import com.elevencapital.core.stock.StockPricePoint
import java.io.IOException
import java.time.Instant
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RobinhoodMarketWarmupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val backend = "http://127.0.0.1:8787"
    private val capturedAt = Instant.parse(fixture().getString("receivedAt"))
    private val now = capturedAt.plusSeconds(6 * 60 * 60).toEpochMilli()
    private fun fixture(): JSONObject = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-catalog.json"))
        .bufferedReader().use { JSONObject(it.readText()) }

    @Test fun `offline launch publishes cached metadata before revalidation can finish`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = RobinhoodFeaturedCatalogCache(temporary.newFolder(), backend) { now }
            cache.write(fixture())
            val networkStarted = CompletableDeferred<Unit>()
            val failNetwork = CompletableDeferred<Unit>()
            val warmup = RobinhoodMarketWarmup(cache, StartupMarketCache(temporary.newFolder(), backend) { now },
                fetchCatalog = { networkStarted.complete(Unit); failNetwork.await(); throw IOException("Offline") },
                scope = scope, now = { now })
            warmup.start()
            val cached = withTimeout(2_000) { warmup.state.first { it.catalog != null } }
            assertTrue(cached.cached)
            assertFalse(cached.initialAttemptComplete)
            assertEquals("stale", cached.catalog?.providers?.single { it.id == "robinhood" }?.status)
            assertEquals(capturedAt, cached.catalog?.receivedAt)
            assertNotNull(cached.catalog?.instruments?.first()?.stock?.description)
            networkStarted.await()
            failNetwork.complete(Unit)
            val failed = withTimeout(2_000) { warmup.state.first { it.initialAttemptComplete } }
            assertTrue(failed.cached)
            assertTrue(failed.refreshFailed)
            assertEquals(cached.catalog, failed.catalog)
        } finally { scope.cancel() }
    }

    @Test fun `concurrent consumers share one featured request and successful refresh persists public data`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = RobinhoodFeaturedCatalogCache(temporary.newFolder(), backend) { now }
            val networkStarted = CompletableDeferred<Unit>()
            val response = CompletableDeferred<JSONObject>()
            var calls = 0
            val warmup = RobinhoodMarketWarmup(cache, StartupMarketCache(temporary.newFolder(), backend) { now },
                fetchCatalog = { calls++; networkStarted.complete(Unit); response.await() }, scope = scope, now = { now })
            val first = async { warmup.refresh() }
            networkStarted.await()
            val second = async { warmup.refresh() }
            kotlinx.coroutines.yield()
            response.complete(fixture())
            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(1, calls)
            assertFalse(warmup.state.value.cached)
            assertFalse(warmup.state.value.refreshFailed)
            assertNotNull(cache.read())
        } finally { scope.cancel() }
    }

    @Test fun `first installation can show a dated bundled seed while the network is unavailable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val warmup = RobinhoodMarketWarmup(
                RobinhoodFeaturedCatalogCache(temporary.newFolder(), backend) { now },
                StartupMarketCache(temporary.newFolder(), backend) { now },
                fetchCatalog = { throw IOException("Offline") }, seedCatalog = { fixture() }, scope = scope, now = { now })
            warmup.start()
            val restored = withTimeout(2_000) { warmup.state.first { it.initialAttemptComplete } }
            assertNotNull(restored.catalog)
            assertTrue(restored.cached)
            assertTrue(restored.refreshFailed)
        } finally { scope.cancel() }
    }

    @Test fun `featured HTTP client requests only the public subset without wallet or authorization headers`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(fixture().toString()))
            server.start()
            RobinhoodFeaturedClient(server.url("/").toString()).catalog()
            val request = server.takeRequest()
            assertEquals("/v1/stocks/featured?provider=robinhood", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        } finally { server.shutdown() }
    }

    @Test fun `featured original stock histories warm with two requests and persist their original receipts`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val chartDirectory = temporary.newFolder()
            val charts = StartupMarketCache(chartDirectory, backend) { now }
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val started = AtomicInteger()
            val twoStarted = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val originalChartReceipt = now - 120_000
            val expected = LiveMarketDataParser.catalog(fixture()).instruments
                .filter { it.stock.symbol in RobinhoodFeaturedSymbols }.size
            val warmup = RobinhoodMarketWarmup(
                RobinhoodFeaturedCatalogCache(temporary.newFolder(), backend) { now }, charts,
                fetchCatalog = { fixture() }, fetchChart = { id, range ->
                    val concurrency = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, concurrency) }
                    if (started.incrementAndGet() == 2) twoStarted.complete(Unit)
                    try {
                        finish.await()
                        LiveChart(id, range, "ok", "USD", (3 downTo 1).map { offset ->
                            StockPricePoint(Instant.ofEpochMilli(originalChartReceipt).minusSeconds(offset.toLong()), BigDecimal("99.000000000000000001"))
                        }, "underlying_share_reference", "Yahoo original-stock history", Instant.ofEpochMilli(originalChartReceipt))
                    } finally { active.decrementAndGet() }
                }, scope = scope, now = { now })
            assertTrue(warmup.refresh())
            withTimeout(2_000) { twoStarted.await() }
            assertEquals(2, maximum.get())
            finish.complete(Unit)
            withTimeout(2_000) {
                warmup.state.first { it.charts.size == expected }
                while (charts.read().size != expected) kotlinx.coroutines.delay(10)
            }
            assertEquals(expected, started.get())
            assertTrue(maximum.get() <= 2)
            val restored = StartupMarketCache(chartDirectory, backend) { now }.read()
            assertEquals(expected, restored.size)
            assertTrue(restored.all { it.receivedAtMillis == originalChartReceipt &&
                it.chart.receivedAt?.toEpochMilli() == originalChartReceipt && it.chart.basis == "underlying_share_reference" })
            warmup.refreshCharts()
            kotlinx.coroutines.delay(30)
            assertEquals(expected, started.get())
        } finally { scope.cancel() }
    }
}
