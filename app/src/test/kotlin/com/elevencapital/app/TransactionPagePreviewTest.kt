package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.FixtureStockData
import com.elevencapital.app.data.PurchaseUsdBasis
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.data.WalletUsdBasis
import com.elevencapital.app.purchase.PurchaseDestination
import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchaseOptions
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.app.screens.HomeScreen
import com.elevencapital.app.screens.AccountScreen
import com.elevencapital.app.screens.PreviewStockHolding
import com.elevencapital.app.screens.PreviewWalletPurchase
import com.elevencapital.app.screens.PreviewWalletState
import com.elevencapital.app.screens.previewStockTrade
import com.elevencapital.app.screens.previewStockTradeState
import com.elevencapital.app.screens.StockBuySuccessNotice
import com.elevencapital.app.screens.StockDetailScreen
import com.elevencapital.app.screens.TradeScreen
import com.elevencapital.app.screens.TransferHistoryScreen
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.flow.OrderSide
import org.junit.Rule
import org.junit.Test

/** Render-only sample activity for reviewing the production transaction composables without funds. */
class TransactionPagePreviewTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392, screenHeight = 826, xdpi = 160, ydpi = 160,
            density = Density.MEDIUM, nightMode = NightMode.NIGHT,
            fontScale = 1f, locale = "en-rUS", softButtons = false,
        ),
        theme = "Theme.ElevenCapital", showSystemUi = true,
        useDeviceResolution = true, maxPercentDifference = 0.0,
    )

    @Test fun homeWithSampleActivity() = capture("home_activity_preview") {
        HomeScreen(
            balance = BigDecimal.ZERO,
            walletAddress = "8EMfogw4PH43J5vZ9",
            walletConnected = true,
            displayName = "Ayush",
            transactions = emptyList(),
            walletTransactions = sampleTransfers(),
            purchaseTransactions = samplePurchases(),
            walletActivityStatus = WalletActivityStatus.OK,
            stockSymbols = mapOf("stock:spcx" to "SPCX.US", "stock:anthropic" to "ANTHROPIC"),
            historyStorageHealthy = true,
            onStocks = {}, onReceive = {}, onSend = {}, onManage = {}, onHistory = {},
            greeting = "Good evening",
        )
        StockDock(RootTab.Home, {}, Modifier.align(Alignment.BottomCenter))
    }

    @Test fun homeWithPreviewFundingAndBuys() = capture("home_preview_funding_and_buys") {
        val now = System.currentTimeMillis()
        HomeScreen(
            balance = BigDecimal.ZERO,
            walletAddress = "8EMfogw4PH43J5vZ9",
            walletConnected = true,
            displayName = "Ayush",
            transactions = emptyList(),
            walletActivityStatus = WalletActivityStatus.OK,
            previewWalletState = PreviewWalletState(4, now - 120_000L, listOf(
                PreviewWalletPurchase("backed:eba060bd-f7b3-49e3-8ef1-99869351b434", "SPCXx",
                    BigDecimal("0.01"), now - 60_000L),
                PreviewWalletPurchase("backpack:NKE.US", "NKE.US",
                    BigDecimal("0.02"), now - 30_000L),
            )),
            historyStorageHealthy = true,
            onStocks = {}, onReceive = {}, onSend = {}, onManage = {}, onHistory = {},
            greeting = "Good evening",
        )
        StockDock(RootTab.Home, {}, Modifier.align(Alignment.BottomCenter))
    }

    @Test fun nikePreviewBuyEntry() = capture("nike_preview_buy_entry") {
        val nike = Stock(StockId("backpack:NKE.US"), "NKE.US", "NIKE, Inc.",
            StockQuote(BigDecimal("35.73"), "USD"))
        TradeScreen(nike, paymentBalance = BigDecimal("4.80"), onChooseStock = {},
            initialAmountUsd = "0.02",
            purchaseState = previewStockTradeState(nike, BigDecimal("0.04151"), BigDecimal("4.80")),
            preview = previewStockTrade(nike, BigDecimal("0.04151"), processing = false))
    }

    @Test fun portfolioWithPreviewSolAndStocks() = capture("portfolio_preview_sol_and_stocks") {
        val spcxx = Stock(StockId("backed:eba060bd-f7b3-49e3-8ef1-99869351b434"),
            "SPCXx", "SpaceX xStock", StockQuote(BigDecimal("147.00"), "USD"))
        val nike = Stock(StockId("backpack:NKE.US"), "NKE.US", "NIKE, Inc.",
            StockQuote(BigDecimal("35.73"), "USD"))
        AccountScreen(
            balance = BigDecimal("6.044"), tokenHoldings = emptyList(), holdings = emptyList(),
            onStock = {}, previewSolBalance = BigDecimal("0.04125056"),
            previewSolValueUsd = BigDecimal("4.77"),
            previewHoldings = listOf(
                PreviewStockHolding(spcxx, BigDecimal("0.00077351"), BigDecimal("0.114")),
                PreviewStockHolding(nike, BigDecimal("0.032505"), BigDecimal("1.16")),
            ),
        )
    }

    @Test fun allTransactionsWithSampleActivity() = capture("transactions_preview") {
        TransferHistoryScreen(
            verifiedUserId = "preview-user",
            records = emptyList(),
            walletTransactions = sampleTransfers(),
            purchaseTransactions = samplePurchases(),
            walletActivityStatus = WalletActivityStatus.OK,
            stockSymbols = mapOf("stock:spcx" to "SPCX.US", "stock:anthropic" to "ANTHROPIC"),
            storageHealthy = true,
            onBack = {},
        )
    }

    @Test fun purchaseEntryPreview() = capture("purchase_entry_preview") {
        val stock = Stock(StockId("prestocks:Anthropic"), "ANTHROPIC", "Anthropic",
            StockQuote(BigDecimal("10"), "USD"),
            logo = StockLogoReference("reference/anthropic/standalone"))
        val solanaAddress = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
        val payment = PurchasePaymentAsset("ARBITRUM:USDC", "USDC", "USD Coin",
            PurchaseNetwork.ARBITRUM, "0x" + "b".repeat(40), 6,
            BigInteger("1000000"), BigDecimal.ONE, BigDecimal.ONE, true)
        val destination = PurchaseDestination("prestocks:Anthropic:solana", PurchaseNetwork.SOLANA,
            solanaAddress, "ANTHROPIC", 6, true)
        val options = PurchaseOptions(stock.id.value, true, null, true, null,
            listOf(payment), listOf(destination), payment.id, destination.id)
        TradeScreen(stock, paymentBalance = BigDecimal.ONE, onChooseStock = {},
            initialAmountUsd = "1",
            purchaseState = PurchaseUiState(stockId = stock.id.value, phase = PurchasePhase.ENTRY,
                options = options, selectedPaymentAssetId = payment.id,
                selectedDestinationId = destination.id, side = OrderSide.BUY),
            onPurchaseNow = {})
    }

    @Test fun completedBuyNoticePreview() = capture("completed_buy_notice_preview") {
        val fixtures = FixtureStockData.load(paparazzi.context.assets)
        val stock = requireNotNull(fixtures.repository.getStock(StockId("reference-spcx")))
        StockDetailScreen(stock, onBack = {}, onBuy = {}, isWatched = false,
            onWatch = {}, detail = fixtures.detail(stock.id))
        StockBuySuccessNotice(stock,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = rd(9f)))
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(WalletStyle.Background)) {
                    CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), content = content)
                    }
                }
            }
        }
        paparazzi.snapshot(view, name = name)
    }

    private fun sampleTransfers(): List<WalletTransaction> {
        val now = Instant.now()
        return listOf(
            WalletTransaction("in-arb", WalletActivityChain.ARBITRUM, "0x" + "1".repeat(64),
                now.minusSeconds(8 * 60), WalletTransactionDirection.RECEIVE, "ETH",
                BigDecimal("0.0005"), BigDecimal("2.12"), WalletUsdBasis.CURRENT_SPOT,
                "0x" + "a".repeat(40)),
            WalletTransaction("out-sol", WalletActivityChain.SOLANA, "2".repeat(88),
                now.minusSeconds(4 * 3600), WalletTransactionDirection.SEND, "SOL",
                BigDecimal("0.01"), BigDecimal("1.86"), WalletUsdBasis.CURRENT_SPOT,
                "3".repeat(44)),
        )
    }

    private fun samplePurchases(): List<CompletedPurchaseTransaction> {
        val now = Instant.now()
        return listOf(
            CompletedPurchaseTransaction("buy-spcx", "4".repeat(88), emptyList(), now.minusSeconds(90 * 60),
                "SETTLEMENT", CompletedPurchaseSide.BUY, "stock:spcx", "ARBITRUM:USDC",
                BigDecimal("1.00"), BigDecimal("0.05"), BigDecimal("1.00"),
                PurchaseUsdBasis.STABLECOIN_EXACT),
            CompletedPurchaseTransaction("sell-anthropic", "5".repeat(88), emptyList(), now.minusSeconds(6 * 3600),
                "SETTLEMENT", CompletedPurchaseSide.SELL, "stock:anthropic", "SOLANA:USDC",
                BigDecimal("0.1"), BigDecimal("2.00"), BigDecimal("2.00"),
                PurchaseUsdBasis.STABLECOIN_EXACT),
        )
    }
}
