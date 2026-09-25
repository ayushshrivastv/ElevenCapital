package com.elevencapital.core.stock

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StockQuoteCurrencyValidationTest {
    @Test fun `currency validator accepts exactly uppercase ASCII triples and USDC`() {
        for (code in listOf("USD", "INR", "AAA", "ZZZ", "USDC")) {
            assertEquals(code, StockQuote(BigDecimal.ONE, code).copy(price = null).currencyCode)
        }
        for (code in listOf("", "US", "USDD", "usdc", "usd", "Usd", "US1", "UŚD", "ＵＳＤ", "USD\n", " USD")) {
            assertThrows("Invalid currency accepted: $code", IllegalArgumentException::class.java) {
                StockQuote(BigDecimal.ONE, code)
            }
        }
    }
}
