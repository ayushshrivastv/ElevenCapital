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
internal const val ROBINHOOD_TESLA_PREVIEW_STOCK_ID =
    "robinhood:0x00000000000000000000000000000000cfece3244ea34bb29414dd9488b32d9f"
internal const val ROBINHOOD_TESLA_PREVIEW_CONTRACT = "0x322F0929c4625eD5bAd873c95208D54E1c003b2d"
private val robinhoodTeslaTokensPerUsd = BigDecimal("0.006613")

/** A debug-only screen state. It never enters the purchase coordinator or wallet broadcaster. */
internal fun isSpcxxPreviewStock(stock: Stock): Boolean = BuildConfig.DEBUG &&
    stock.id.value == SPCXX_PREVIEW_STOCK_ID && stock.symbol.equals("SPCXx", ignoreCase = true)

internal fun isNikePreviewStock(stock: Stock): Boolean = BuildConfig.DEBUG &&
    stock.id.value == NIKE_PREVIEW_STOCK_ID && stock.symbol.equals("NKE.US", ignoreCase = true)

internal fun isRobinhoodTeslaPreviewStock(stock: Stock): Boolean = BuildConfig.DEBUG &&
    stock.id.value == ROBINHOOD_TESLA_PREVIEW_STOCK_ID && stock.symbol == "TSLA" &&
    stock.deployments.any { it.network == "Robinhood Chain" &&
        it.address.equals(ROBINHOOD_TESLA_PREVIEW_CONTRACT, ignoreCase = true) && it.chainId == "4663" }

internal data class PreviewStockAsset(
    val mint: String,
    val decimals: Int,
    val estimatedGasFeeUsd: BigDecimal,
)

internal fun previewStockAsset(stock: Stock): PreviewStockAsset? = when {
    isSpcxxPreviewStock(stock) -> PreviewStockAsset(SPCXX_PREVIEW_MINT, 8, BigDecimal("0.01"))
    isNikePreviewStock(stock) -> PreviewStockAsset(NIKE_PREVIEW_MINT, 6, BigDecimal("0.02"))
    isRobinhoodTeslaPreviewStock(stock) -> PreviewStockAsset(ROBINHOOD_TESLA_PREVIEW_CONTRACT, 18, BigDecimal.ZERO)
    else -> null
}

/** Local purchase terms for the amount entered by the user and the current stock quote. */
internal data class PreviewStockPurchase(
    val stockId: String,
    val inputUsd: BigDecimal,
    val receiveValueUsd: BigDecimal,
    val receiveTokens: BigDecimal,
    val mint: String,
    val decimals: Int,
    val purchaseId: String = "",
)

internal fun previewStockPurchase(stock: Stock, inputUsd: BigDecimal): PreviewStockPurchase? {
    val asset = previewStockAsset(stock) ?: return null
    if (inputUsd.signum() <= 0 || inputUsd.scale() > 2) return null
    if (isRobinhoodTeslaPreviewStock(stock)) {
        val quantity = inputUsd.multiply(robinhoodTeslaTokensPerUsd)
            .setScale(asset.decimals, RoundingMode.DOWN)
        return PreviewStockPurchase(
            stockId = stock.id.value,
            inputUsd = inputUsd,
            receiveValueUsd = inputUsd,
            receiveTokens = quantity,
            mint = asset.mint,
            decimals = asset.decimals,
        )
    }
    val price = stock.quote.price?.takeIf {
        it.signum() > 0 && stock.quote.currencyCode in setOf("USD", "USDC")
    } ?: return null
    val quantity = inputUsd.divide(price, asset.decimals, RoundingMode.DOWN)
        .takeIf { it.signum() > 0 } ?: return null
    return PreviewStockPurchase(
        stockId = stock.id.value,
        inputUsd = inputUsd,
        receiveValueUsd = quantity.multiply(price).min(inputUsd),
        receiveTokens = quantity,
        mint = asset.mint,
        decimals = asset.decimals,
    )
}

data class StockTradePreview(
    val estimatedGasFeeUsd: BigDecimal,
    val paymentBalanceLabel: String,
    val processing: Boolean,
)

internal fun previewStockTradeState(
    stock: Stock,
    availableSol: BigDecimal,
    availableSolUsd: BigDecimal,
): PurchaseUiState {
    val asset = requireNotNull(previewStockAsset(stock))
    require(availableSol.signum() >= 0 && availableSolUsd.signum() >= 0)
    val solBalance = availableSol.setScale(9, RoundingMode.DOWN)
    val sol = PurchasePaymentAsset(
        id = "SOLANA:SOL", symbol = "SOL", name = "Solana", network = PurchaseNetwork.SOLANA,
        address = "11111111111111111111111111111111", decimals = 9,
        balanceBaseUnits = solBalance.movePointRight(9).toBigIntegerExact(), balance = solBalance,
        usdValue = availableSolUsd, enabled = true,
    )
    val destination = if (isRobinhoodTeslaPreviewStock(stock)) null else PurchaseDestination(
        id = "SOLANA:${asset.mint}", network = PurchaseNetwork.SOLANA,
        address = asset.mint, symbol = stock.symbol, decimals = asset.decimals, enabled = true,
    )
    return PurchaseUiState(
        stockId = stock.id.value, phase = PurchasePhase.ENTRY,
        options = PurchaseOptions(stock.id.value, purchasable = destination != null, reason = null,
            executionEnabled = false, executionReason = "Trading unavailable", paymentAssets = listOf(sol),
            destinations = listOfNotNull(destination), defaultPaymentAssetId = sol.id,
            defaultDestinationId = destination?.id),
        selectedPaymentAssetId = sol.id, selectedDestinationId = destination?.id,
    )
}

internal fun previewStockTrade(
    stock: Stock,
    availableSol: BigDecimal,
    availableSolUsd: BigDecimal,
    processing: Boolean,
): StockTradePreview {
    val asset = requireNotNull(previewStockAsset(stock))
    require(availableSol.signum() >= 0 && availableSolUsd.signum() >= 0)
    return StockTradePreview(
        estimatedGasFeeUsd = asset.estimatedGasFeeUsd,
        paymentBalanceLabel = "${availableSol.stripTrailingZeros().toPlainString()} SOL",
        processing = processing,
    )
}
