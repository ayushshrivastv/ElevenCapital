package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrderAmountTest {
    @Test
    fun convertsDollarAmountToSolUsingVerifiedPortfolioValuation() {
        val sol = asset(balance = "2", usdValue = "300")

        assertEquals(BigDecimal("1.666666666"), paymentAmountForUsd(BigDecimal("250"), sol))
        assertEquals(BigDecimal("250.000000000000000000"), paymentUsdForAmount(BigDecimal("2.5"),
            asset(balance = "3", usdValue = "300")))
        assertEquals(BigDecimal("249.999999900000000000"), paymentUsdForAmount(
            paymentAmountForUsd(BigDecimal("250"), sol)!!, sol,
        ))
    }

    @Test
    fun floorsToAssetPrecisionWithoutRoundingTheIntermediateUnitPrice() {
        val token = asset(balance = "3", usdValue = "7", decimals = 2)

        assertEquals(BigDecimal("0.42"), paymentAmountForUsd(BigDecimal.ONE, token))
        assertEquals(BigDecimal("0.00"), paymentAmountForUsd(BigDecimal("0.001"), token))
        assertEquals(BigDecimal("0"), paymentAmountForUsd(BigDecimal.ONE, token.copy(decimals = 0)))
    }

    @Test
    fun missingAndZeroValuationsDoNotAssumeAStablecoinPeg() {
        val unknownUsdc = asset(balance = "100", usdValue = null).copy(symbol = "USDC")
        listOf(null, unknownUsdc, unknownUsdc.copy(usdValue = BigDecimal.ZERO),
            asset(balance = "0", usdValue = "100")).forEach { unavailable ->
            assertNull(paymentAmountForUsd(BigDecimal.TEN, unavailable))
            assertNull(paymentUsdForAmount(BigDecimal.TEN, unavailable))
        }

        val discountedUsdc = unknownUsdc.copy(usdValue = BigDecimal("80"))
        assertEquals(BigDecimal("12.500000000"), paymentAmountForUsd(BigDecimal.TEN, discountedUsdc))
        assertEquals(BigDecimal("8.000000000000000000"), paymentUsdForAmount(BigDecimal.TEN, discountedUsdc))
    }

    @Test
    fun rejectsZeroAndNegativeInputs() {
        listOf(BigDecimal.ZERO, BigDecimal("-1")).forEach { invalid ->
            assertNull(paymentAmountForUsd(invalid, asset()))
            assertNull(paymentUsdForAmount(invalid, asset()))
            assertNull(indicativeStockQuantity(invalid, stock()))
        }
    }

    @Test
    fun estimatesStockQuantityOnlyFromPositiveUsdPrices() {
        assertEquals(BigDecimal("1.666666666666666666"), indicativeStockQuantity(
            BigDecimal("250"), stock(price = "150"),
        ))
        listOf(stock(currency = "USDC"), stock(currency = "EUR"), stock(price = null),
            stock(price = "0")).forEach { unavailable ->
            assertNull(indicativeStockQuantity(BigDecimal("250"), unavailable))
        }
    }

    @Test
    fun sellUsesExactStockTokenQuantityAndRawBalance() {
        val holding = asset(balance = "1.25", usdValue = "300", decimals = 9).copy(symbol = "TSLAx")

        assertEquals(BigDecimal("0.75"), orderInputAmount(OrderSide.SELL, BigDecimal("0.75"), holding))
        assertEquals(BigDecimal("0.005000000"), orderInputAmount(OrderSide.BUY, BigDecimal("1.2"), holding))
        assertEquals(false, orderExceedsBalance(OrderSide.SELL, BigDecimal("1.25"), holding, null))
        assertEquals(true, orderExceedsBalance(OrderSide.SELL, BigDecimal("1.250000001"), holding, null))
        assertEquals(true, orderExceedsBalance(OrderSide.BUY, BigDecimal("300.01"), holding, null))
    }

    private fun asset(
        balance: String = "2",
        usdValue: String? = "300",
        decimals: Int = 9,
    ): PurchasePaymentAsset {
        val tokenBalance = BigDecimal(balance)
        return PurchasePaymentAsset(
            id = "solana:sol",
            symbol = "SOL",
            name = "Solana",
            network = PurchaseNetwork.SOLANA,
            address = "native",
            decimals = decimals,
            balanceBaseUnits = tokenBalance.movePointRight(decimals).toBigIntegerExact(),
            balance = tokenBalance,
            usdValue = usdValue?.let(::BigDecimal),
            enabled = true,
        )
    }

    private fun stock(price: String? = "150", currency: String = "USD") = Stock(
        id = StockId("backed:TSLAx"),
        symbol = "TSLAx",
        name = "Tesla",
        quote = StockQuote(price = price?.let(::BigDecimal), currencyCode = currency),
    )
}
