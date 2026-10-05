package com.elevencapital.app

import com.elevencapital.app.data.*
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PredictionPythClientTest {
    private val timestamp = 1_790_700_000L
    private fun quote(time: Long = timestamp, price: String = "3000.12345678", confidence: String = "0.5") =
        """{"priceUsd":"$price","confidenceUsd":"$confidence","publishTime":$time}"""
    private fun snapshot(time: Long = timestamp, status: String = "live", feed: String = PYTH_ETH_FEED) =
        """{"schemaVersion":1,"status":"$status","serverTimeMs":${timestamp * 1000},"feedId":"$feed","quote":${quote(time)}}"""

    @Test fun `preserves decimal precision and publication freshness`() {
        val parsed = PredictionPythClient.parseSnapshot(snapshot())
        assertEquals(BigDecimal("3000.12345678"), parsed.quote?.priceUsd)
        assertNotNull(parsed.freshQuote(timestamp * 1000 + 4999))
        assertNull(parsed.freshQuote(timestamp * 1000 + 5001))
        assertNotNull(PredictionPythClient.parseSnapshot(snapshot(feed = PYTH_ETH_FEED.removePrefix("0x"))).quote)
        assertTrue(parsed.hasServerTime)
        assertFalse(PythEthSnapshot().hasServerTime)
    }

    @Test fun `rejects wrong feeds future ticks and false live status`() {
        for (json in listOf(snapshot(feed = "btc"), snapshot(time = timestamp + 2), snapshot(time = timestamp - 6))) {
            assertThrows(IllegalArgumentException::class.java) { PredictionPythClient.parseSnapshot(json) }
        }
        assertNull(PredictionPythClient.parseSnapshot(snapshot(status = "stale")).freshQuote(timestamp * 1000))
    }

    @Test fun `rejects malformed prices and excessive confidence`() {
        for (q in listOf(quote(price = "NaN"), quote(price = "0"), quote(confidence = "31"), quote(price = "1e9000"))) {
            val json = snapshot().replace(quote(), q)
            assertThrows(IllegalArgumentException::class.java) { PredictionPythClient.parseSnapshot(json) }
        }
    }

    @Test fun `historical price is bounded to the requested boundary`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","targetTimestamp":$timestamp,"quote":${quote(timestamp + 1)},"updateData":["0xab"]}"""))
            server.start()
            val client = PredictionPythClient(server.url("/").toString())
            assertEquals(timestamp + 1, client.boundary(timestamp).publishTime)
            val request = server.takeRequest()
            assertEquals("/v1/prediction/eth/at/$timestamp", request.path)
            assertNull(request.getHeader("Authorization"))
        }
    }

    @Test fun `historical response for wrong timestamp cannot settle a round`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","targetTimestamp":$timestamp,"quote":${quote(timestamp + 6)}}"""))
            server.start()
            var rejected = false
            try { PredictionPythClient(server.url("/").toString()).boundary(timestamp) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
    }

    @Test fun `round targets preserve cents and use the fixed opening report`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":$pool,"bandUsd":"0.75","forecastSigmaUsd":"1.250000","quote":${quote(start + 1, confidence = "0.25")}}"""))
            server.start()
            val terms = PredictionPythClient(server.url("/").toString()).roundTerms(pool)
            assertEquals(BigDecimal("0.75"), terms.bandUsd)
            assertEquals(BigDecimal("1.250000"), terms.forecastSigmaUsd)
            assertEquals(start + 1, terms.anchor.publishTime)
            assertEquals("/v1/prediction/eth/round/$pool", server.takeRequest().path)
        }
    }

    @Test fun `btc prediction reads its own feed and fixes targets around opening price`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        val btcQuote = quote(start + 1, price = "83404", confidence = "4.0")
        val body = """{"schemaVersion":1,"feedId":"$PYTH_BTC_FEED","poolId":$pool,"bandUsd":"3.75","forecastSigmaUsd":"42.000000","quote":$btcQuote}"""
        assertNotNull(PredictionPythClient.parseSnapshot(
            snapshot(feed = PYTH_BTC_FEED), PredictionPriceMarket.BTC).quote)
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPythClient.parseSnapshot(snapshot(feed = PYTH_ETH_FEED), PredictionPriceMarket.BTC)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            server.start()
            val client = PredictionPythClient(server.url("/").toString(), PredictionPriceMarket.BTC)
            val terms = client.roundTerms(pool)
            assertEquals(BigDecimal("3.75"), terms.bandUsd)
            assertEquals(BigDecimal("83407.75"), terms.anchor.priceUsd + terms.bandUsd)
            assertEquals(BigDecimal("83400.25"), terms.anchor.priceUsd - terms.bandUsd)
            assertEquals(BigDecimal("42.000000"), terms.forecastSigmaUsd)
            assertEquals("/v1/prediction/btc/round/$pool", server.takeRequest().path)
        }
    }

    @Test fun `btc accepts adaptive cent bands up to one hundred dollars`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        for (band in listOf("0.01", "3.75", "100.00")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(
                    """{"schemaVersion":1,"feedId":"$PYTH_BTC_FEED","poolId":$pool,"bandUsd":"$band","forecastSigmaUsd":"42.000000","quote":${quote(start + 1, price = "68000.25", confidence = "4.0")}}"""))
                server.start()
                assertEquals(BigDecimal(band), PredictionPythClient(server.url("/").toString(),
                    PredictionPriceMarket.BTC).roundTerms(pool).bandUsd)
            }
        }
        for (band in listOf("0.00", "100.01", "101.00")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(
                    """{"schemaVersion":1,"feedId":"$PYTH_BTC_FEED","poolId":$pool,"bandUsd":"$band","forecastSigmaUsd":"42.000000","quote":${quote(start + 1, price = "68000.25", confidence = "4.0")}}"""))
                server.start()
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { PredictionPythClient(server.url("/").toString(),
                        PredictionPriceMarket.BTC).roundTerms(pool) }
                }
            }
        }
    }

    @Test fun `btc retains round terms when opening confidence exceeds the adaptive band`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        for (confidence in listOf("10.01", "40.01")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(
                    """{"schemaVersion":1,"feedId":"$PYTH_BTC_FEED","poolId":$pool,"bandUsd":"0.01","forecastSigmaUsd":"240.000000","quote":${quote(start + 1, price = "68000.25", confidence = confidence)}}"""))
                server.start()
                val terms = PredictionPythClient(server.url("/").toString(),
                    PredictionPriceMarket.BTC).roundTerms(pool)
                assertEquals(BigDecimal("0.01"), terms.bandUsd)
                assertEquals(BigDecimal(confidence), terms.anchor.confidenceUsd)
            }
        }
    }

    @Test fun `round targets reject mismatched pools and uncertain anchors`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        for (body in listOf(
            """{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":${pool + 1},"bandUsd":"0.75","forecastSigmaUsd":"0.32","quote":${quote(start, confidence = "0.1")}}""",
            """{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":$pool,"bandUsd":"0.75","forecastSigmaUsd":"0.32","quote":${quote(start, confidence = "0.4")}}""",
            """{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":$pool,"bandUsd":"0.75","forecastSigmaUsd":"0.32","quote":${quote(start + 6, confidence = "0.1")}}""",
            """{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":$pool,"bandUsd":"0.75","forecastSigmaUsd":"8.01","quote":${quote(start, confidence = "0.1")}}""",
        )) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(body))
                server.start()
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { PredictionPythClient(server.url("/").toString()).roundTerms(pool) }
                }
            }
        }
    }

    @Test fun `foreground feed prefetches one set of targets and reuses it on page entry`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        val live = PredictionPythClient.parseSnapshot(
            """{"schemaVersion":1,"status":"live","serverTimeMs":${start * 1000 + 1_000},"feedId":"$PYTH_ETH_FEED","quote":${quote(start + 1)}}"""
        )
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"schemaVersion":1,"feedId":"$PYTH_ETH_FEED","poolId":$pool,"bandUsd":"0.75","forecastSigmaUsd":"0.32","quote":${quote(start + 1, confidence = "0.1")}}"""))
            server.start()
            val feed = PredictionPythFeedSession(
                PredictionPythClient(server.url("/").toString()),
                stopGraceMs = 0,
                snapshotStream = flow { emit(live); awaitCancellation() },
            )
            try {
                feed.startForeground()
                withTimeout(5_000) {
                    while (feed.currentRoundTerms == null) delay(20)
                }
                assertEquals(pool, feed.currentRoundTerms?.poolId)
                assertEquals(live.quote?.priceUsd, feed.currentSnapshot.quote?.priceUsd)
                assertEquals(feed.currentRoundTerms, feed.roundTerms(pool))
                assertEquals(1, server.requestCount)
                assertEquals("/v1/prediction/eth/round/$pool", server.takeRequest().path)
            } finally {
                feed.stopForeground()
                feed.close()
            }
        }
    }

    @Test fun `omitted pool is identified and never refetched during that round`() = runBlocking {
        val pool = Math.floorDiv(timestamp, 90L) + 1L
        val start = pool * 90L
        val live = PredictionPythClient.parseSnapshot(
            """{"schemaVersion":1,"status":"live","serverTimeMs":${start * 1000 + 1_000},"feedId":"$PYTH_ETH_FEED","quote":${quote(start + 1)}}"""
        )
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":"pyth_round_unavailable","message":"Prediction price is temporarily unavailable."}"""))
            server.start()
            val feed = PredictionPythFeedSession(
                PredictionPythClient(server.url("/").toString()),
                stopGraceMs = 0,
                snapshotStream = flow { emit(live); awaitCancellation() },
            )
            try {
                feed.startForeground()
                withTimeout(5_000) {
                    while (feed.unavailableRoundId != pool) delay(20)
                }
                assertNull(feed.currentRoundTerms)
                assertThrows(PredictionRoundUnavailableException::class.java) {
                    runBlocking { feed.roundTerms(pool) }
                }
                delay(500)
                assertEquals(1, server.requestCount)
            } finally {
                feed.stopForeground()
                feed.close()
            }
        }
    }

    @Test fun `foreground leases keep one stream through a short screen interruption`() = runBlocking {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        MockWebServer().use { server ->
            server.start()
            val feed = PredictionPythFeedSession(
                PredictionPythClient(server.url("/").toString()),
                stopGraceMs = 60,
                subscriberStopGraceMs = 0,
                snapshotStream = flow {
                    starts.incrementAndGet()
                    try { emit(PythEthSnapshot()); awaitCancellation() }
                    finally { stops.incrementAndGet() }
                },
            )
            try {
                feed.startForeground()
                feed.startForeground()
                withTimeout(5_000) { while (starts.get() != 1) delay(10) }
                feed.stopForeground()
                delay(100)
                assertEquals(0, stops.get())
                feed.stopForeground()
                withTimeout(5_000) { while (stops.get() != 1) delay(10) }
                assertEquals(1, starts.get())
            } finally { feed.close() }
        }
    }

    @Test fun `stream decodes price events and invalidates out of order reports`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: price\ndata: ${snapshot()}\n\nevent: price\ndata: ${snapshot(time = timestamp - 1)}\n\n"))
            server.start()
            val updates = withTimeout(5000) {
                PredictionPythClient(server.url("/").toString()).stream().take(2).toList()
            }
            assertEquals("live", updates.first().status)
            assertEquals("unavailable", updates.last().status)
        }
    }

    @Test fun `rejects insecure remote or credential-bearing backend URLs`() {
        for (url in listOf("http://example.com", "https://secret@example.com", "https://example.com?key=secret")) {
            assertThrows(IllegalArgumentException::class.java) { PredictionPythClient(url) }
        }
    }
}
