package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class StockStatisticsReferenceTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private fun reference() = StockStatisticsReference("solana", "issuer-mint", StockMetricKey.entries.associateWith { key ->
        StockMetricData(when (key) {
            StockMetricKey.MARKET_CAPITALIZATION -> BigDecimal("52563755.03160228")
            StockMetricKey.LIQUIDITY -> BigDecimal("563042.8596252465")
            StockMetricKey.HOLDER_COUNT -> BigDecimal("23412")
            StockMetricKey.ORGANIC_SCORE -> BigDecimal("77.48139464970512")
        }, key.unit, "Jupiter", key.wireName, now, null)
    }, now)

    @Test fun analyticsKeepTheirOwnCurrencyAndExactDecimalValues() {
        val statistics = reference().toStatistics()
        assertEquals("USD", statistics.currencyCode)
        assertEquals(BigDecimal("52563755.03160228"), statistics.marketCapitalization)
        assertEquals(23412L, statistics.holderCount)
        assertEquals(BigDecimal("77.48139464970512"), statistics.organicScore)
    }

    @Test fun measuredZeroIsDifferentFromUnavailable() {
        val reference = reference()
        val zero = reference.copy(metrics = reference.metrics.mapValues { (_, metric) -> metric.copy(value = BigDecimal.ZERO) })
        assertEquals(BigDecimal.ZERO, zero.toStatistics().liquidity)
        assertEquals(0L, zero.toStatistics().holderCount)
        val missing = reference.copy(metrics = reference.metrics.mapValues { (_, metric) ->
            metric.copy(value = null, reason = "Provider has no analytics.")
        })
        assertNull(missing.toStatistics().holderCount)
        assertNull(missing.toStatistics().organicScore)
    }

    @Test fun individualOldMetricExpiresWithoutErasingFreshFields() {
        val source = reference()
        val liquidity = source.metrics.getValue(StockMetricKey.LIQUIDITY).copy(receivedAt = now.minusSeconds(301))
        val expired = source.copy(metrics = source.metrics + (StockMetricKey.LIQUIDITY to liquidity)).expire(now, 0)
        assertNull(expired.toStatistics().liquidity)
        assertEquals(source.toStatistics().holderCount, expired.toStatistics().holderCount)
        assertEquals("Data expired. Updates resume automatically when the source recovers.", expired.metrics.getValue(StockMetricKey.LIQUIDITY).reason)
    }

    @Test fun upstreamAgeAndForegroundElapsedTimeBothExpireStatistics() {
        val source = reference().copy(updatedAt = now.minusSeconds(240))
        assertNotNull(source.expire(now, 30_000).toStatistics().organicScore)
        assertNull(source.expire(now, 61_000).toStatistics().organicScore)
        assertNull(reference().expire(now, 301_000).toStatistics().marketCapitalization)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scoreAboveOneHundredRejected() {
        val source = reference()
        source.copy(metrics = source.metrics + (StockMetricKey.ORGANIC_SCORE to
            source.metrics.getValue(StockMetricKey.ORGANIC_SCORE).copy(value = BigDecimal("100.01"))))
    }

    @Test(expected = ArithmeticException::class)
    fun fractionalHolderCountsRejected() {
        val source = reference()
        source.copy(metrics = source.metrics + (StockMetricKey.HOLDER_COUNT to
            source.metrics.getValue(StockMetricKey.HOLDER_COUNT).copy(value = BigDecimal("23.5"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun quotedUsdcMustNotBeUsedForUsdAnalytics() {
        val source = reference()
        source.copy(metrics = source.metrics + (StockMetricKey.LIQUIDITY to
            source.metrics.getValue(StockMetricKey.LIQUIDITY).copy(unit = "USDC")))
    }
}
