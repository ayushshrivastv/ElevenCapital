package com.elevencapital.core.stock

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class StockQuoteTest {
    @Test fun unavailablePriceIsNotZero() {
        val quote = StockQuote(null, "USD")
        assertNull(quote.price)
        assertNull(quote.asOf)
        assertNull(quote.changePercent)
    }

    @Test fun usdcReferenceDoesNotBecomeUsd() {
        val quote = StockQuote(BigDecimal("497.79273498273498723"), "USDC")
        assertEquals("USDC", quote.currencyCode)
        assertEquals("497.79273498273498723", quote.price!!.toPlainString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePriceIsRejected() { StockQuote(BigDecimal("-0.001"), "USD") }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCurrencyIsRejected() { StockQuote(BigDecimal.ONE, "usd") }
}
