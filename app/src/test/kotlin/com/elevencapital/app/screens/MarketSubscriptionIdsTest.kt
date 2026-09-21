package com.elevencapital.app.screens

import com.elevencapital.core.stock.StockId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSubscriptionIdsTest {
    @Test
    fun `filtered first twenty and visible rows are subscribed together`() {
        val displayed = (0 until 40).map { StockId("stock-$it") }
        val visible = linkedSetOf(StockId("stock-27"), StockId("stock-28"))

        val result = marketSubscriptionIds(displayed, visible)

        assertEquals(22, result.size)
        assertTrue((0 until 20).all { StockId("stock-$it") in result })
        assertTrue(visible.all { it in result })
        assertFalse(StockId("stock-20") in result)
    }

    @Test
    fun `market subscription remains within transport bound`() {
        val displayed = (0 until 150).map { StockId("stock-$it") }
        val visible = (100 until 250).mapTo(linkedSetOf()) { StockId("stock-$it") }

        val result = marketSubscriptionIds(displayed, visible)

        assertEquals(100, result.size)
        assertTrue((0 until 20).all { StockId("stock-$it") in result })
    }
}
