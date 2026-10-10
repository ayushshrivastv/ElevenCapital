package com.elevencapital.app.screens

import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockDeployment
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.flow.OrderSide
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

    private fun robinhoodTesla(contract: String = ROBINHOOD_TESLA_PREVIEW_CONTRACT) = Stock(
        StockId(ROBINHOOD_TESLA_PREVIEW_STOCK_ID), "TSLA", "Tesla",
        StockQuote(BigDecimal("355.00"), "USD"),
        deployments = listOf(StockDeployment("Robinhood Chain", contract, "4663", 18)),
    )

    @Test fun `Robinhood Tesla uses the exact local dollar to token conversion`() {
        val tesla = robinhoodTesla()
        val penny = requireNotNull(previewStockPurchase(tesla, BigDecimal("0.01")))
        val twoCents = requireNotNull(previewStockPurchase(tesla, BigDecimal("0.02")))

        assertEquals(0, penny.receiveTokens.compareTo(BigDecimal("0.00006613")))
        assertEquals(0, twoCents.receiveTokens.compareTo(BigDecimal("0.00013226")))
        assertEquals(0, penny.receiveValueUsd.compareTo(BigDecimal("0.01")))
        assertEquals("SOLANA:SOL", previewStockTradeState(tesla, BigDecimal("0.4"), BigDecimal("82"))
            .selectedPaymentAsset?.id)
        assertNull(previewStockTradeState(tesla, BigDecimal("0.4"), BigDecimal("82")).selectedDestination)
        assertNull(previewStockAsset(robinhoodTesla("0x1111111111111111111111111111111111111111")))
        assertNull(previewStockAsset(stock(ROBINHOOD_TESLA_PREVIEW_STOCK_ID, "TSLA")))
    }

    @Test fun `only the exact debug stock identities enter the local purchase route`() {
        val amount = BigDecimal("5.00")
        assertNotNull(previewStockAsset(stock(SPCXX_PREVIEW_STOCK_ID, "SPCXx")))
        assertNotNull(previewStockAsset(stock(NIKE_PREVIEW_STOCK_ID, "NKE.US")))
        assertNotNull(previewStockPurchase(stock(SPCXX_PREVIEW_STOCK_ID, "SPCXx"), amount))
        assertNotNull(previewStockPurchase(stock(NIKE_PREVIEW_STOCK_ID, "NKE.US"), amount))
        assertNull(previewStockAsset(stock("backed:NKEx", "NKE.US")))
        assertNull(previewStockPurchase(stock(NIKE_PREVIEW_STOCK_ID, "MSFT.US"), amount))
        val missingPrice = Stock(StockId(NIKE_PREVIEW_STOCK_ID),
            "NKE.US", "Nike", StockQuote(null, "USD"))
        assertNotNull(previewStockAsset(missingPrice))
        assertNull(previewStockPurchase(missingPrice, amount))
        assertEquals("SOLANA:SOL", previewStockTradeState(missingPrice,
            BigDecimal("0.4"), BigDecimal("82")).selectedPaymentAsset?.id)
    }

    @Test fun `typed dollar amount uses SOL valuation for local payment and stock quantity`() {
        val nike = stock(NIKE_PREVIEW_STOCK_ID, "NKE.US")
        val small = requireNotNull(previewStockPurchase(nike, BigDecimal("0.02")))
        val purchase = requireNotNull(previewStockPurchase(nike, BigDecimal("12.34")))
        val before = previewStockTradeState(nike, BigDecimal("0.4"), BigDecimal("82.00"))
        val after = previewStockTradeState(nike, BigDecimal("0.330048780"), BigDecimal("67.66"))

        assertEquals(BigDecimal("12.34"), purchase.inputUsd)
        assertTrue(purchase.receiveTokens > small.receiveTokens)
        assertTrue(purchase.receiveValueUsd <= purchase.inputUsd)
        assertEquals(0, purchase.receiveTokens.multiply(requireNotNull(nike.quote.price))
            .compareTo(purchase.receiveValueUsd))
        assertEquals(BigDecimal("0.02"), previewStockTrade(nike, BigDecimal("0.4"),
            BigDecimal("82"), false).estimatedGasFeeUsd)
        assertEquals(BigDecimal("0.01"), previewStockTrade(
            stock(SPCXX_PREVIEW_STOCK_ID, "SPCXx"), BigDecimal("0.4"),
            BigDecimal("82"), false).estimatedGasFeeUsd)
        assertEquals("0.4 SOL", previewStockTrade(nike, BigDecimal("0.4"), BigDecimal("82"),
            false).paymentBalanceLabel)
        assertEquals("SOLANA:SOL", before.selectedPaymentAsset?.id)
        assertEquals("11111111111111111111111111111111", before.selectedPaymentAsset?.address)
        assertEquals(9, before.selectedPaymentAsset?.decimals)
        assertEquals(0, before.selectedPaymentAsset?.balance?.compareTo(BigDecimal("0.4")))
        assertEquals(0, before.selectedPaymentAsset?.usdValue?.compareTo(BigDecimal("82.00")))
        assertEquals(0, requireNotNull(orderInputAmount(OrderSide.BUY, BigDecimal("41.00"),
            before.selectedPaymentAsset)).compareTo(BigDecimal("0.2")))
        assertEquals(0, after.selectedPaymentAsset?.usdValue?.compareTo(BigDecimal("67.66")))
        assertEquals(0, after.selectedPaymentAsset?.balance?.compareTo(BigDecimal("0.330048780")))
    }

    @Test fun `invalid or unavailable amounts never produce purchasable terms`() {
        val nike = stock(NIKE_PREVIEW_STOCK_ID, "NKE.US")
        assertNull(previewStockPurchase(nike, BigDecimal.ZERO))
        assertNull(previewStockPurchase(nike, BigDecimal("-1")))
        assertNull(previewStockPurchase(nike, BigDecimal("0.001")))
        assertNull(previewStockPurchase(Stock(nike.id, nike.symbol, nike.name,
            StockQuote(BigDecimal("35.73"), "EUR")), BigDecimal("1")))
    }
}
