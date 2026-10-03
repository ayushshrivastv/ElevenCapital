package com.elevencapital.app.data

import com.elevencapital.app.wallet.encodeBase58
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeteoraChartSnapshotCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `round trips a fresh chart for the exact pool and mint`() = runBlocking {
        val cache = MeteoraChartSnapshotCache(temporary.root, now = { NOW })
        val entry = chart(points = listOf(
            MeteoraTokenChartPoint(100, BigDecimal("0.001")),
            MeteoraTokenChartPoint(200, BigDecimal("0.002")),
        ))

        cache.write(listOf(entry))

        assertEquals(listOf(entry), cache.read())
    }

    @Test fun `invalid and expired charts are not restored`() = runBlocking {
        var time = NOW
        val cache = MeteoraChartSnapshotCache(temporary.root, now = { time })
        val good = chart()
        cache.write(listOf(
            good,
            chart(mint = "not-a-solana-key"),
            chart(points = emptyList()),
            chart(points = listOf(point(2), point(1))),
            chart(points = listOf(MeteoraTokenChartPoint(1, BigDecimal.ZERO))),
            chart(receivedAt = NOW - 600_000),
        ))
        assertEquals(listOf(good), cache.read())

        time += 600_000
        assertTrue(cache.read().isEmpty())
    }

    @Test fun `limits snapshots to twenty charts and newest three hundred points`() = runBlocking {
        val cache = MeteoraChartSnapshotCache(temporary.root, now = { NOW })
        val points = (1L..301L).map(::point)
        val entries = (1..21).map { index -> chart(mint = mint(index), points = points) }

        cache.write(entries)
        val restored = cache.read()

        assertEquals(entries.take(20).map { it.mintAddress }, restored.map { it.mintAddress })
        assertTrue(restored.all { it.chart.points.size == 300 })
        assertEquals(2L, restored.first().chart.points.first().timestampSeconds)
        assertEquals(301L, restored.first().chart.points.last().timestampSeconds)
    }

    @Test fun `rejects future and two day old candles`() = runBlocking {
        val time = 1_000_000_000L
        val cache = MeteoraChartSnapshotCache(temporary.root, now = { time })
        val nowSeconds = time / 1_000
        val current = chart(mint = mint(1), points = listOf(point(nowSeconds)), receivedAt = time)
        cache.write(listOf(
            current,
            chart(mint = mint(2), points = listOf(point(nowSeconds + 1)), receivedAt = time),
            chart(mint = mint(3), points = listOf(point(nowSeconds - 172_801)), receivedAt = time),
        ))

        assertEquals(listOf(current), cache.read())
    }

    private fun chart(
        mint: String = mint(1),
        points: List<MeteoraTokenChartPoint> = listOf(point(1)),
        receivedAt: Long = NOW,
    ) = CachedMeteoraChart(
        poolAddress = POOL,
        mintAddress = mint,
        chart = MeteoraTokenChart(points),
        receivedAtMillis = receivedAt,
    )

    private fun point(timestamp: Long) = MeteoraTokenChartPoint(timestamp, BigDecimal("1.25"))

    private fun mint(index: Int): String = encodeBase58(ByteArray(32).also { it[31] = index.toByte() })

    private companion object {
        const val NOW = 1_000_000L
        const val POOL = "FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB"
    }
}
