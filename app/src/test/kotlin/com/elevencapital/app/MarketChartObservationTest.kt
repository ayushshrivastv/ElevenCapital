package com.elevencapital.app

import com.elevencapital.app.data.*
import com.elevencapital.core.stock.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MarketChartObservationTest {
    private val now = Instant.parse("2026-09-19T00:00:00Z")
    private val row = LiveInstrument(Stock(StockId("backed:test"), "TEST", "Test stock",
        StockQuote(BigDecimal("99.000000000000000001"), "USD", asOf = now)),
        "backed", "xStocks", null, now, "onchain_token_market", "quoted_currency")
    private val chart = LiveChart(row.stock.id, ChartRange.ONE_DAY, "ok", "USD",
        listOf(StockPricePoint(now.minusSeconds(2), BigDecimal("98")), StockPricePoint(now.minusSeconds(1), BigDecimal("99"))),
        "onchain_token_market", "Observed since service start")

    @Test fun `same basis appends exact current observation without rewriting historical prices`() {
        val aligned = alignChartObservation(chart, row)
        assertEquals(chart.points, aligned.points.dropLast(1))
        assertEquals(row.stock.quote.price, aligned.points.last().price)
        assertEquals(now, aligned.points.last().timestamp)
        assertEquals(chart.statusReason, aligned.statusReason)
    }

    @Test fun `same timestamp replaces final observation without duplicate timestamps`() {
        val previous = chart.copy(points = chart.points + StockPricePoint(now, BigDecimal("100")))
        val aligned = alignChartObservation(previous, row)
        assertEquals(previous.points.size, aligned.points.size)
        assertEquals(row.stock.quote.price, aligned.points.last().price)
    }

    @Test fun `different identity unit or quote basis cannot create a mixed price chart`() {
        for (incompatible in listOf(
            chart.copy(stockId = StockId("backpack:test")), chart.copy(currency = "USDC"),
            chart.copy(basis = "underlying_stock"), chart.copy(basis = null), chart.copy(points = emptyList()),
        )) assertSame(incompatible, alignChartObservation(incompatible, row))
    }

    @Test fun `older quote cannot roll history backward and missing price creates no point`() {
        val later = chart.copy(points = chart.points + StockPricePoint(now.plusSeconds(1), BigDecimal("101")))
        assertSame(later, alignChartObservation(later, row))
        val unavailable = row.copy(stock = row.stock.copy(quote = row.stock.quote.copy(price = null)))
        assertSame(chart, alignChartObservation(chart, unavailable))
    }
    @Test fun `underlying reference and token chart cannot survive a change in quote basis`() {
        val reference = row.copy(stock = row.stock.copy(quote = row.stock.quote.copy(currencyCode = "USDC")),
            quoteBasis = "underlying_share_reference", currencyBasis = "market_symbol")
        val referenceChart = chart.copy(currency = "USDC", basis = "underlying_share_reference")
        assertTrue(isChartBasisCompatible(referenceChart, reference))
        assertFalse(isChartBasisCompatible(referenceChart, row))
        assertFalse(isChartBasisCompatible(chart, reference))
        assertSame(chart, alignChartObservation(chart, reference))
        assertSame(referenceChart, alignChartObservation(referenceChart, row))
    }

}
