package com.elevencapital.app.screens

import com.elevencapital.app.BuildConfig
import com.elevencapital.app.purchase.PurchaseDestination
import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchaseOptions
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.core.stock.Stock
import java.math.BigDecimal
import java.math.RoundingMode

internal const val SPCXX_PREVIEW_STOCK_ID = "backed:eba060bd-f7b3-49e3-8ef1-99869351b434"
internal const val SPCXX_PREVIEW_MINT = "Xs3oZwbHvqis4NYcf4YKWmEia2eC84wSiVrcYcTqpH8"
internal const val SPCXX_PREVIEW_TRANSACTION =
    "https://solscan.io/tx/5ru4KfnupVM23WDZy13HwHrkTBBwtgCzuifaLo7RFzwv2WYEUMmiqAc4J2GYeTNbJgTVu3YKUU1ArkgnFnJmLLDK"
internal const val NIKE_PREVIEW_STOCK_ID = "backpack:NKE.US"
internal const val NIKE_PREVIEW_MINT = "NKEda5nHhNGgjrE9nDdMvaEmkmJ96qqxzBVZEcKmjSg"
internal const val NIKE_PREVIEW_TRANSACTION =
    "https://solscan.io/tx/513TFFjTWrQLCKkNHt7qgLZi2qmuH5g4hQUf1QaU29tXDJAPUusRnLiJZsr1K4wb3GY77cYFsqSzqFsFngbukg5h"

/** A debug-only screen state. It never enters the purchase coordinator or wallet broadcaster. */
internal fun isSpcxxPreviewStock(stock: Stock): Boolean = BuildConfig.DEBUG &&
    stock.id.value == SPCXX_PREVIEW_STOCK_ID && stock.symbol.equals("SPCXx", ignoreCase = true)

internal fun isNikePreviewStock(stock: Stock): Boolean = BuildConfig.DEBUG &&
    stock.id.value == NIKE_PREVIEW_STOCK_ID && stock.symbol.equals("NKE.US", ignoreCase = true)

/** Local visual rehearsal values. The linked transactions predate these UI purchases. */
internal data class PreviewStockPurchase(
    val stockId: String,
    val inputUsd: BigDecimal,
    val receiveValueUsd: BigDecimal,
    val receiveTokens: BigDecimal,
    val mint: String,
    val decimals: Int,
    val transactionUrl: String,
)

internal fun previewStockPurchase(stock: Stock): PreviewStockPurchase? = when {
    isSpcxxPreviewStock(stock) -> PreviewStockPurchase(
        stockId = SPCXX_PREVIEW_STOCK_ID,
        inputUsd = BigDecimal("0.01"),
        receiveValueUsd = BigDecimal("0.114"),
        receiveTokens = BigDecimal("0.00077351"),
        mint = SPCXX_PREVIEW_MINT,
        decimals = 8,
        transactionUrl = SPCXX_PREVIEW_TRANSACTION,
    )
    isNikePreviewStock(stock) -> PreviewStockPurchase(
        stockId = NIKE_PREVIEW_STOCK_ID,
        inputUsd = BigDecimal("0.02"),
        receiveValueUsd = BigDecimal("1.16"),
        // Quantity shown in the supplied historical Solana transaction.
        receiveTokens = BigDecimal("0.032505"),
        mint = NIKE_PREVIEW_MINT,
        decimals = 6,
        transactionUrl = NIKE_PREVIEW_TRANSACTION,
    )
    else -> null
}

data class StockTradePreview(
    val inputUsd: BigDecimal,
    val receiveValueUsd: BigDecimal,
    val receiveTokens: BigDecimal,
    val paymentBalanceLabel: String,
    val processing: Boolean,
)

internal fun previewStockTradeState(
    stock: Stock,
    availableSol: BigDecimal,
    availableUsd: BigDecimal,
): PurchaseUiState {
    val purchase = requireNotNull(previewStockPurchase(stock))
    require(availableSol.signum() >= 0 && availableUsd.signum() >= 0)
    val solBalance = availableSol.setScale(9, RoundingMode.DOWN)
    val sol = PurchasePaymentAsset(
        id = "SOLANA:SOL", symbol = "SOL", name = "Solana", network = PurchaseNetwork.SOLANA,
        address = "11111111111111111111111111111111", decimals = 9,
        balanceBaseUnits = solBalance.movePointRight(9).toBigIntegerExact(), balance = solBalance,
        usdValue = availableUsd, enabled = true,
    )
    val destination = PurchaseDestination(
        id = "SOLANA:${purchase.mint}", network = PurchaseNetwork.SOLANA,
        address = purchase.mint, symbol = stock.symbol, decimals = purchase.decimals, enabled = true,
    )
    return PurchaseUiState(
        stockId = stock.id.value, phase = PurchasePhase.ENTRY,
        options = PurchaseOptions(stock.id.value, purchasable = true, reason = null,
            executionEnabled = false, executionReason = "Local debug preview", paymentAssets = listOf(sol),
            destinations = listOf(destination), defaultPaymentAssetId = sol.id,
            defaultDestinationId = destination.id),
        selectedPaymentAssetId = sol.id, selectedDestinationId = destination.id,
    )
}

internal fun previewStockTrade(
    stock: Stock,
    availableSol: BigDecimal,
    processing: Boolean,
): StockTradePreview {
    val purchase = requireNotNull(previewStockPurchase(stock))
    return StockTradePreview(
        inputUsd = purchase.inputUsd,
        receiveValueUsd = purchase.receiveValueUsd,
        receiveTokens = purchase.receiveTokens,
        paymentBalanceLabel = "${availableSol.stripTrailingZeros().toPlainString()} SOL",
        processing = processing,
    )
}
