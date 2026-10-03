package com.elevencapital.app.data

import com.elevencapital.app.wallet.encodeBase58
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeteoraMarketWarmupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `startup fetch begins once and simultaneous refresh shares the same request`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val warmup = MeteoraMarketWarmup(
            MeteoraMarketSnapshotCache(temporary.root),
            fetchTokens = { requests.incrementAndGet(); gate.await(); listOf(token) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        warmup.start()
        warmup.start()
        withTimeout(3_000) { while (requests.get() == 0) delay(10) }
        val joined = async { warmup.refresh() }
        delay(50)
        assertEquals(1, requests.get())
        gate.complete(Unit)
        assertTrue(joined.await())
        assertEquals(token, warmup.state.value.tokens.single())
        assertFalse(warmup.state.value.loading)
    }

    @Test fun `failed refresh retains the last good top tokens`() = runBlocking {
        var fail = false
        val warmup = MeteoraMarketWarmup(
            MeteoraMarketSnapshotCache(temporary.root),
            fetchTokens = { if (fail) throw IOException("offline") else listOf(token) },
        )
        assertTrue(warmup.refresh())
        fail = true
        assertFalse(warmup.refresh())
        assertEquals(listOf(token), warmup.state.value.tokens)
        assertNull(warmup.state.value.error)
    }

    @Test fun `startup exposes saved rows while live sync is still pending`() = runBlocking {
        val cache = MeteoraMarketSnapshotCache(temporary.root)
        cache.write(listOf(token), System.currentTimeMillis())
        val gate = CompletableDeferred<Unit>()
        val warmup = MeteoraMarketWarmup(cache, fetchTokens = {
            gate.await()
            listOf(token.copy(volume24hUsd = BigDecimal("200")))
        })
        warmup.start()
        val restored = withTimeout(3_000) { warmup.state.first { it.tokens.isNotEmpty() } }
        assertTrue(restored.loading)
        assertEquals(BigDecimal("120"), restored.tokens.single().volume24hUsd)
        gate.complete(Unit)
        val fresh = withTimeout(3_000) { warmup.state.first { !it.loading &&
            it.tokens.singleOrNull()?.volume24hUsd == BigDecimal("200") } }
        assertEquals(BigDecimal("200"), fresh.tokens.single().volume24hUsd)
    }

    @Test fun `bounded snapshot restores only recent validated rows`() = runBlocking {
        var time = 1_000_000L
        val cache = MeteoraMarketSnapshotCache(temporary.root, now = { time })
        cache.write(listOf(token), time)
        assertEquals(listOf(token), cache.read()?.tokens)
        time += TimeUnit.MINUTES.toMillis(11)
        assertNull(cache.read())
    }

    @Test fun `startup warms the one day chart before a token is opened`() = runBlocking {
        val marketCache = MeteoraMarketSnapshotCache(temporary.root)
        marketCache.write(listOf(token), System.currentTimeMillis())
        val liveFeedGate = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val chart = MeteoraTokenChart(listOf(
            MeteoraTokenChartPoint(System.currentTimeMillis() / 1_000 - 60, BigDecimal("0.41")),
            MeteoraTokenChartPoint(System.currentTimeMillis() / 1_000, BigDecimal("0.45")),
        ))
        val warmup = MeteoraMarketWarmup(
            marketCache,
            fetchTokens = { liveFeedGate.await(); listOf(token) },
            chartCache = MeteoraChartSnapshotCache(temporary.root),
            fetchChart = { requests.incrementAndGet(); chart },
        )
        warmup.start()
        withTimeout(3_000) { warmup.state.first { it.charts.isNotEmpty() } }
        assertEquals(1, requests.get())
        assertEquals(chart, warmup.cachedChartFor(token))
        liveFeedGate.complete(Unit)
        Unit
    }

    @Test fun `all twenty token charts prefetch with at most two requests in flight`() = runBlocking {
        val rows = (1..20).map { index ->
            token.copy(
                poolAddress = encodeBase58(ByteArray(32).also { it[31] = index.toByte() }),
                mintAddress = encodeBase58(ByteArray(32).also { it[31] = (index + 100).toByte() }),
            )
        }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        val warmup = MeteoraMarketWarmup(
            MeteoraMarketSnapshotCache(temporary.root),
            fetchTokens = { rows },
            fetchChart = {
                requests.incrementAndGet()
                val concurrent = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, concurrent) }
                try {
                    delay(20)
                    MeteoraTokenChart(listOf(MeteoraTokenChartPoint(
                        System.currentTimeMillis() / 1_000, BigDecimal("0.45"))))
                } finally {
                    active.decrementAndGet()
                }
            },
        )
        assertTrue(warmup.refresh())
        withTimeout(5_000) { warmup.state.first { it.charts.size == 20 } }
        assertEquals(20, requests.get())
        assertTrue(peak.get() in 1..2)
        assertTrue(rows.all { warmup.cachedChartFor(it) != null })
    }

    private companion object {
        val token = MeteoraMarketToken(
            mintAddress = "METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL",
            poolAddress = "FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB",
            name = "Meteora", symbol = "MET",
            priceUsd = BigDecimal("0.45"), volume24hUsd = BigDecimal("120"),
            imageUrl = "https://assets.geckoterminal.com/token-image",
            change24hPercent = BigDecimal("-5.25"),
        )
    }
}
