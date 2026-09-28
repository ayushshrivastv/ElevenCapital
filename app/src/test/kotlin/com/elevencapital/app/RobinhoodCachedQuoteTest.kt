package com.elevencapital.app

import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.RobinhoodStockReference
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RobinhoodCachedQuoteTest {
    private val receivedAt = Instant.parse("2026-09-29T11:07:19.768Z")
    private val original = Stock(StockId("robinhood:apple"), "AAPL", "Apple",
        StockQuote(BigDecimal("337.500000000000000000"), "USD", asOf = receivedAt),
        robinhoodReference = RobinhoodStockReference(BigDecimal("337"), BigDecimal("338"),
            null, null, null, BigDecimal.ONE, 18, receivedAt, receivedAt, false))

    private fun expired(stock: Stock = original, age: Long = 6 * 60 * 60 * 1000L) =
        expireMarketStock(stock, receivedAt, receivedAt.plusMillis(age), 0, 0, emptyMap())

    @Test fun `dated restored Robinhood references remain readable for twenty four hours without becoming live`() {
        val cleared = expired()
        assertNull(cleared.quote.price)
        assertNull(cleared.robinhoodReference)
        val cached = restoreCachedRobinhoodQuote(original, cleared, receivedAt,
            receivedAt.toEpochMilli() + 6 * 60 * 60 * 1000L)
        assertEquals(original.quote, cached.quote)
        assertEquals(original.robinhoodReference, cached.robinhoodReference)
        assertEquals(receivedAt, cached.quote.asOf)
        assertEquals(receivedAt, cached.robinhoodReference?.receivedAt)
    }

    @Test fun `exact twenty four hour boundary missing time and future cache time hide all financial references`() {
        for (time in listOf<Instant?>(receivedAt, null, receivedAt.plusSeconds(1))) {
            val checkedAt = if (time == receivedAt) receivedAt.toEpochMilli() + 24 * 60 * 60 * 1000L
                else receivedAt.toEpochMilli()
            val cleared = restoreCachedRobinhoodQuote(original, original, time, checkedAt)
            assertNull(cleared.quote.price)
            assertNull(cleared.robinhoodReference)
        }
    }

    @Test fun `cached display exception never changes live or other-provider expiry`() {
        assertNull(expired(age = 300_001).quote.price)
        val other = original.copy(id = StockId("backed:apple"), robinhoodReference = null)
        val cleared = expired(other)
        assertSame(cleared, restoreCachedRobinhoodQuote(other, cleared, receivedAt, receivedAt.toEpochMilli() + 3600_000))
        assertNull(cleared.quote.price)
    }
}
