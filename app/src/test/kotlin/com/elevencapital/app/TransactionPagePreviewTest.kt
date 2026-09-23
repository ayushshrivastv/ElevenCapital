package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import com.elevencapital.app.data.PurchaseUsdBasis
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.data.WalletUsdBasis
import com.elevencapital.app.purchase.EvmPurchaseAction
import com.elevencapital.app.purchase.EvmPurchaseTransaction
import com.elevencapital.app.purchase.PurchaseActionKind
import com.elevencapital.app.purchase.PurchaseDestination
import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchaseOptions
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseQuote
import com.elevencapital.app.purchase.PurchaseQuoteBinding
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.app.screens.HomeScreen
import com.elevencapital.app.screens.TradeScreen
import com.elevencapital.app.screens.TransferHistoryScreen
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.WalletStyle
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
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

    @Test fun finalPurchaseReviewPreview() = capture("purchase_review_preview") {
        val stock = Stock(StockId("prestocks:Anthropic"), "ANTHROPIC", "Anthropic",
            StockQuote(BigDecimal("10"), "USD"))
        val paymentAddress = "0x" + "a".repeat(40)
        val solanaAddress = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
        val payment = PurchasePaymentAsset("ARBITRUM:USDC", "USDC", "USD Coin",
            PurchaseNetwork.ARBITRUM, "0x" + "b".repeat(40), 6,
            BigInteger("1000000"), BigDecimal.ONE, BigDecimal.ONE, true)
        val destination = PurchaseDestination("prestocks:Anthropic:solana", PurchaseNetwork.SOLANA,
            solanaAddress, "ANTHROPIC", 6, true)
        val options = PurchaseOptions(stock.id.value, true, null, true, null,
            listOf(payment), listOf(destination), payment.id, destination.id)
        val binding = PurchaseQuoteBinding("123e4567-e89b-42d3-a456-426614174000", "preview-user",
            stock.id.value, payment.id, PurchaseNetwork.ARBITRUM, 6, destination.id,
            BigInteger("1000000"), 50, setOf(paymentAddress, solanaAddress))
        val action = EvmPurchaseAction("sample-action", 0, PurchaseActionKind.EVM_ROUTE,
            PurchaseNetwork.ARBITRUM, paymentAddress,
            EvmPurchaseTransaction(paymentAddress, "0x" + "c".repeat(40), "0x", "0x0", "0x5208",
                "0x1", null, null, null))
        val quote = PurchaseQuote("sample-quote", binding, destination, BigDecimal.ONE,
            BigDecimal("0.10"), BigDecimal("10"), BigDecimal("0.05"), null,
            50, BigDecimal("0.095"), BigInteger("95000"), Instant.now().plusSeconds(300),
            1, listOf(action), true, null)
        TradeScreen(stock, paymentBalance = BigDecimal.ONE, onChooseStock = {},
            purchaseState = PurchaseUiState(stockId = stock.id.value, phase = PurchasePhase.REVIEW,
                options = options, selectedPaymentAssetId = payment.id,
                selectedDestinationId = destination.id, quote = quote, side = OrderSide.BUY),
            onExecutePurchase = {})
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
