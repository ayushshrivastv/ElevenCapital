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
            asset("ETH", "1", "3000", PurchaseNetwork.BASE),
            defaultId = "SOLANA:USDC",
        )

        assertEquals("BASE:ETH", preferredPaymentAssetId(options, OrderSide.BUY))
        assertEquals("SOLANA:USDC", preferredPaymentAssetId(options, OrderSide.BUY, "SOLANA:USDC"))
    }

    @Test fun buyFallsBackToFundedServerChoiceWhenPricesAreUnavailable() {
        val options = options(
            asset("SOL", "3", null),
            asset("USDC", "40", null, PurchaseNetwork.BASE),
            defaultId = "BASE:USDC",
        )

        assertEquals("BASE:USDC", preferredPaymentAssetId(options, OrderSide.BUY))
    }

    @Test fun buyExcludesEmptyAndDisabledHoldingsAndIgnoresUnavailableManualChoice() {
        val options = options(
            asset("SOL", "0", "500"),
            asset("USDC", "5", "5", PurchaseNetwork.BASE),
            asset("ETH", "1", "4000", PurchaseNetwork.ETHEREUM, enabled = false),
            defaultId = "SOLANA:SOL",
        )

        assertEquals("BASE:USDC", preferredPaymentAssetId(options, OrderSide.BUY))
        assertEquals("BASE:USDC", preferredPaymentAssetId(options, OrderSide.BUY, "ETHEREUM:ETH"))
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
        network: PurchaseNetwork = PurchaseNetwork.SOLANA,
        enabled: Boolean = true,
    ): PurchasePaymentAsset {
        val amount = BigDecimal(balance)
        return PurchasePaymentAsset(
            id = "${network.name}:$symbol", symbol = symbol, name = symbol, network = network,
            address = "native", decimals = 6, balanceBaseUnits = amount.movePointRight(6).toBigIntegerExact(),
            balance = amount, usdValue = usdValue?.let(::BigDecimal), enabled = enabled,
        )
    }
}
