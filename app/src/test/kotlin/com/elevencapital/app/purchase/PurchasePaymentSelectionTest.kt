package com.elevencapital.app.purchase

import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PurchasePaymentSelectionTest {
    @Test fun buyPrefersLargestSpendableDollarHoldingOverServerDefaultAndRawQuantity() {
        val options = options(
            asset("SOL", "2", "400"),
            asset("USDC", "500", "500"),
            defaultId = "SOLANA:USDC",
        )

        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY))
        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY, "SOLANA:USDC"))
    }

    @Test fun buyFallsBackToFundedServerChoiceWhenPricesAreUnavailable() {
        val options = options(
            asset("SOL", "3", null),
            asset("USDC", "40", null),
            defaultId = "SOLANA:USDC",
        )

        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY))
    }

    @Test fun buyExcludesEmptyAndDisabledHoldingsAndIgnoresUnavailableManualChoice() {
        val options = options(
            asset("SOL", "0", "500"),
            asset("USDC", "5", "5"),
            asset("BONK", "1", "4000", enabled = false),
            defaultId = "SOLANA:SOL",
        )

        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY))
        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY, "SOLANA:BONK"))
        assertEquals("SOLANA:SOL", preferredPaymentAssetId(options, OrderSide.BUY, "SOLANA:SOL"))
    }

    @Test fun sellKeepsTheVerifiedServerDefault() {
        val options = options(
            asset("SOL", "2", "400"),
            asset("USDC", "500", "500"),
            defaultId = "SOLANA:SOL",
        )

        assertEquals("SOLANA:SOL", preferredPaymentAssetId(options, OrderSide.SELL))
    }

    private fun options(vararg assets: PurchasePaymentAsset, defaultId: String) = PurchaseOptions(
        stockId = "backed:NVDAx", purchasable = false, reason = null,
        executionEnabled = false, executionReason = null, paymentAssets = assets.toList(),
        destinations = emptyList(), defaultPaymentAssetId = defaultId, defaultDestinationId = null,
    )

    private fun asset(
        symbol: String,
        balance: String,
        usdValue: String?,
        enabled: Boolean = true,
    ): PurchasePaymentAsset {
        val amount = BigDecimal(balance)
        return PurchasePaymentAsset(
            id = "SOLANA:$symbol", symbol = symbol, name = symbol, network = PurchaseNetwork.SOLANA,
            address = "native", decimals = 6, balanceBaseUnits = amount.movePointRight(6).toBigIntegerExact(),
            balance = amount, usdValue = usdValue?.let(::BigDecimal), enabled = enabled,
        )
    }
}
