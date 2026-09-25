package com.elevencapital.app.screens

import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewStockTradeTest {
    private fun stock(id: String, symbol: String) = Stock(
        StockId(id), symbol, symbol, StockQuote(BigDecimal("35.73"), "USD"),
    )

    @Test fun `only the two exact debug stock identities enter the local preview route`() {
        assertNotNull(previewStockPurchase(stock(SPCXX_PREVIEW_STOCK_ID, "SPCXx")))
        assertNotNull(previewStockPurchase(stock(NIKE_PREVIEW_STOCK_ID, "NKE.US")))
        assertNull(previewStockPurchase(stock("backed:NKEx", "NKE.US")))
        assertNull(previewStockPurchase(stock(NIKE_PREVIEW_STOCK_ID, "MSFT.US")))
        assertTrue(isPurchaseSolscanUrl(NIKE_PREVIEW_TRANSACTION))
        assertTrue(isPurchaseSolscanUrl(SPCXX_PREVIEW_TRANSACTION))
    }

    @Test fun `both preview purchases use the remaining seeded SOL balance`() {
        val nike = stock(NIKE_PREVIEW_STOCK_ID, "NKE.US")
        val purchase = requireNotNull(previewStockPurchase(nike))
        val before = previewStockTradeState(nike, BigDecimal("0.04151"), BigDecimal("4.80"))
        val after = previewStockTradeState(nike, BigDecimal("0.041423521"), BigDecimal("4.79"))

        assertEquals(BigDecimal("0.02"), purchase.inputUsd)
        assertEquals(BigDecimal("1.16"), purchase.receiveValueUsd)
        assertEquals(0, before.selectedPaymentAsset?.balance?.compareTo(BigDecimal("0.04151")))
        assertEquals(BigDecimal("4.80"), before.selectedPaymentAsset?.usdValue)
        assertEquals(BigDecimal("4.79"), after.selectedPaymentAsset?.usdValue)
        assertEquals(0, after.selectedPaymentAsset?.balance?.compareTo(BigDecimal("0.041423521")))
    }
}
