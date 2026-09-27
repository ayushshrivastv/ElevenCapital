package com.elevencapital.app

import com.elevencapital.app.data.LiveChart
import com.elevencapital.app.data.freshChartReceiptMillis
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MarketChartReceiptTest {
    private val now = Instant.parse("2026-09-29T11:22:26.366Z").toEpochMilli()
    private val chart = LiveChart(StockId("robinhood:test"), ChartRange.ONE_DAY, "ok", "USD", emptyList(),
        "underlying_share_reference")

    @Test fun `server cached history keeps its original freshness instead of the current HTTP response time`() {
        val source = chart.copy(receivedAt = Instant.ofEpochMilli(now - 299_999))
        assertEquals(now - 299_999, freshChartReceiptMillis(source, now, now))
        assertNull(freshChartReceiptMillis(source, now + 1, now + 1))
        assertNull(freshChartReceiptMillis(chart.copy(receivedAt = Instant.ofEpochMilli(now + 1)), now))
        assertNull(freshChartReceiptMillis(chart.copy(receivedAt = Instant.MAX), now))
    }

    @Test fun `providers without upstream receipts retain their original local receipt`() {
        assertEquals(now - 120_000, freshChartReceiptMillis(chart, now, now - 120_000))
        assertNull(freshChartReceiptMillis(chart, now, now - 300_000))
    }
}
