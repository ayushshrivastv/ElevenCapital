package com.elevencapital.app.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class TradeScreenInputTest {
    @Test
    fun displayGroupsIntegerAndPreservesTypedFraction() {
        assertEquals("1,234,567.00", tradeDisplayInput("1234567.00"))
        assertEquals("1,200.", tradeDisplayInput("1200."))
    }

    @Test
    fun inputRespectsSelectedAssetPrecision() {
        var input = "0"
        listOf("1", ".", "2", "3", "4").forEach { input = tradeAppendKey(input, it, maximumDecimals = 2) }
        assertEquals("1.23", input)
    }

    @Test
    fun inputRejectsDuplicateDecimalAndUnboundedInteger() {
        assertEquals("12.3", tradeAppendKey("12.3", ".", maximumDecimals = 6))
        assertEquals("1234567890123456", tradeAppendKey("1234567890123456", "7"))
    }
}
