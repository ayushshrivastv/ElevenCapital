package com.elevencapital.app.screens

import com.elevencapital.core.stock.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTokenDetailScreenTest {
    @Test fun `local token chart is stable for each range`() {
        ChartRange.entries.forEach { range ->
            val values = spxtrLocalTrendValues(range)
            assertEquals(values, spxtrLocalTrendValues(range))
            assertTrue(values.size >= 20)
            assertTrue(values.all { it in 0f..1f })
        }
    }

    @Test fun `newly launched token chart stays flat on every range`() {
        val shapes = ChartRange.entries.map(::spxtrLocalTrendValues)
        assertTrue(shapes.all { it.distinct().size == 1 })
        assertTrue(shapes.all { it == shapes.first() })
    }

    @Test fun `SPXTR opening price uses the USD buy quote and selected curve target`() {
        val opening = spxtrOpeningOverview(null)
        assertEquals("\$0.001", opening.priceUsd)
        assertEquals("0.00%", opening.change24h)
        assertEquals("1B tokens", opening.totalSupply)
        assertEquals("10 SOL", opening.targetValue)

        val custom = spxtrOpeningOverview(LaunchTokenDraft(
            totalSupply = "1000000", migrationThresholdSol = "25",
        ))
        assertEquals("1000000 tokens", custom.totalSupply)
        assertEquals("25 SOL", custom.targetValue)
    }
}
