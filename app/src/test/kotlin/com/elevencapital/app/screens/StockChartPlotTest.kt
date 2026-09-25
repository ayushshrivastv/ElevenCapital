package com.elevencapital.app.screens

import com.elevencapital.core.stock.StockPricePoint
import com.elevencapital.core.stock.ChartRange
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockChartPlotTest {
    @Test fun `candles preserve elapsed time when observations have a gap`() {
        val start = Instant.parse("2026-09-24T00:00:00Z")
        val candles = observedCandles(listOf(
            StockPricePoint(start, BigDecimal("100")),
            StockPricePoint(start.plusSeconds(60), BigDecimal("101")),
            StockPricePoint(start.plusSeconds(3600), BigDecimal("102")),
        ), bucketCount = 12)

        assertEquals(2, candles.size)
        assertEquals(100.0, candles.first().open, 0.0)
        assertEquals(101.0, candles.first().close, 0.0)
        assertEquals(102.0, candles.last().close, 0.0)
        assertTrue(candles.last().timeFraction - candles.first().timeFraction > .8f)
    }

    @Test fun `flat subcent prices retain a usable vertical scale`() {
        val (low, high) = paddedChartBounds(.000001, .000001)
        assertTrue(low >= 0.0)
        assertTrue(high > low)
        assertTrue(high < .000002)
    }

    @Test fun `partial one day history occupies only the observed time span`() {
        val end = Instant.parse("2026-09-24T12:00:00Z")
        val points = listOf(
            StockPricePoint(end.minusSeconds(4 * 3600), BigDecimal("100")),
            StockPricePoint(end, BigDecimal("101")),
        )
        val domain = chartTimeDomain(points, ChartRange.ONE_DAY, anchorToSelectedRange = true)
        val candles = observedCandles(points, bucketCount = 24, timeDomain = domain)

        assertEquals(end.minusSeconds(24 * 3600), domain.start)
        assertTrue(candles.first().timeFraction > .8f)
        assertTrue(candles.last().timeFraction > candles.first().timeFraction)
    }

    @Test fun `one minute samples group into legible observed candles`() {
        val start = Instant.parse("2026-09-24T12:00:00Z")
        val points = (0 until 60).map { minute ->
            StockPricePoint(start.plusSeconds(minute * 60L), BigDecimal.valueOf(100L + minute))
        }
        val candles = observedCandles(points)

        assertEquals(20, observedCandleBucketCount(points.size))
        assertEquals(20, candles.size)
        assertEquals(100.0, candles.first().open, 0.0)
        assertEquals(102.0, candles.first().close, 0.0)
        assertEquals(102.0, candles.first().high, 0.0)
        assertEquals(100.0, candles.first().low, 0.0)
        assertEquals(159.0, candles.last().close, 0.0)
        assertTrue(candles.all { it.high > it.low && it.close > it.open })
    }
}
