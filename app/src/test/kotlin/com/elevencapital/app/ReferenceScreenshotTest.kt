package com.elevencapital.app

import android.app.Application
import android.content.Context
import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.data.FixtureStockData
import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.app.purchase.PurchaseOptions
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.screens.DetailSection
import com.elevencapital.app.screens.OrderPayReceiveCards
import com.elevencapital.app.screens.PaymentAssetWheel
import com.elevencapital.app.screens.PaymentTokenDropdown
import com.elevencapital.app.screens.StockDetailScreen
import com.elevencapital.app.screens.TradeScreen
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.flow.OrderSide
import com.elevencapital.core.stock.flow.StockDestination
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Renders the production Compose root and bundled fixture assets through Android Layoutlib.
 * The 392 x 826 mdpi viewport contains 24px status + 802px app content. The reference video's
 * final 24px gesture area is excluded; compare app-only crops y24..826, not Samsung status icons.
 * Record with :app:recordPaparazziDebug. The output is an animated PNG under snapshots/videos;
 * extract its last frame for a settled screenshot before visual comparison with the reference.
 */
class ReferenceScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392,
            screenHeight = 826,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            nightMode = NightMode.NIGHT,
            fontScale = 1f,
            locale = "en-rUS",
            softButtons = false,
        ),
        theme = "Theme.ElevenCapital",
        showSystemUi = true,
        useDeviceResolution = true,
        maxPercentDifference = 0.0,
    )

    @Test
    fun home() = render("01_home") { }

    @Test
    fun markets() = render("02_markets") { selectTab(RootTab.Markets) }

    @Test
    fun stockOverview() = render("03_stock_overview") { openStock(fixtures.defaultStockId) }

    @Test
    fun terminalCollapsed() = renderDetail(
        "04_terminal_collapsed", DetailSection.TERMINAL, initialScrollOffset = 26,
    )

    @Test
    fun terminalExpanded() = renderDetail(
        "05_terminal_expanded", DetailSection.TERMINAL, expanded = true,
        initialScrollIndex = 2, initialScrollOffset = 28,
    )

    @Test
    fun liveFeed() = renderDetail("09_live_feed", DetailSection.LIVE_FEED)

    @Test
    fun stockPurchase() = render("06_stock_purchase") {
        openStock(fixtures.defaultStockId)
        openOrder(OrderSide.BUY)
    }

    @Test
    fun stockSellUnavailable() = render("10_stock_sell_unavailable") {
        openStock(fixtures.defaultStockId)
        openOrder(OrderSide.SELL)
    }

    @Test
    fun signalDockOpensSignalAfterStockDetail() {
        val model = ElevenViewModel(RenderApplication(paparazzi.context), isLive = false)
        model.openStock(model.fixtures.defaultStockId)

        model.selectTab(RootTab.Trade)

        assertEquals(RootTab.Trade, model.tab.value)
        assertEquals(StockDestination.Markets, model.stockFlow.destination.value)
    }

    @Test
    fun paymentAssetWheelShowsThreePlainRows() = capture("11_payment_chain_dropdown") {
        BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
            CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    PaymentAssetWheel(
                        assets = purchaseAssets(),
                        selectedId = "SOLANA:SOL",
                        onSelect = {},
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = rd(350f)).width(rd(348f)),
                    )
                }
            }
        }
    }

    @Test
    fun paymentTokenDropdownShowsOnlySelectedChainTokens() = capture("12_payment_token_dropdown") {
        BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
            CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    PaymentTokenDropdown(
                        assets = purchaseAssets(),
                        network = PurchaseNetwork.BASE,
                        selectedId = "BASE:USDC",
                        onBack = {},
                        onSelect = {},
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = rd(350f)).width(rd(348f)),
                    )
                }
            }
        }
    }

    @Test
    fun orderCardsShowExactQuotedReceiveAmount() {
        val fixtures = FixtureStockData.load(paparazzi.context.assets)
        val stock = requireNotNull(fixtures.repository.getStock(fixtures.defaultStockId))
        val asset = purchaseAssets().first { it.id == "SOLANA:USDC" }
        capture("13_order_pay_receive_quote") {
            BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
                CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        OrderPayReceiveCards(
                            side = OrderSide.BUY,
                            input = "250",
                            stock = stock,
                            stockLogoReference = stock.logo,
                            selectedAsset = asset,
                            selectedDestination = null,
                            receiveAmount = BigDecimal("0.502"),
                            receiveSymbol = stock.symbol,
                            receivePending = "Request a quote to see the exact amount",
                            selectorEnabled = true,
                            destinationEnabled = true,
                            onPaymentClick = { _: Rect -> },
                            onDestinationClick = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun dollarOrderComposerMatchesReference() = renderDollarComposer(false)

    @Test
    fun plainPaymentWheelInComposer() = renderDollarComposer(true)

    private fun renderDollarComposer(expanded: Boolean) {
        val fixtures = FixtureStockData.load(paparazzi.context.assets)
        val stock = requireNotNull(fixtures.repository.getStock(fixtures.defaultStockId))
        val asset = purchaseAssets().first { it.id == "ETHEREUM:ETH" }
        val state = PurchaseUiState(
            stockId = stock.id.value,
            phase = PurchasePhase.ENTRY,
            selectedPaymentAssetId = asset.id,
            options = PurchaseOptions(
                stockId = "backed:MSFTx", purchasable = false, reason = null,
                executionEnabled = false, executionReason = null,
                paymentAssets = purchaseAssets(), destinations = emptyList(),
                defaultPaymentAssetId = asset.id, defaultDestinationId = null,
            ),
        )
        capture(if (expanded) "15_plain_payment_wheel" else "14_dollar_order_composer") {
            BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
                CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        TradeScreen(stock, paymentBalance = null, onChooseStock = {},
                            initialAmountUsd = "250", purchaseState = state, initialPaymentWheelExpanded = expanded)
                    }
                }
            }
        }
    }

    @Test
    fun account() = render("07_account") { selectTab(RootTab.Account) }

    @Test
    fun accountCenter() = render("08_account_center") { showAccountCenter() }

    private fun render(name: String, configure: ElevenViewModel.() -> Unit) {
        val model = ElevenViewModel(RenderApplication(paparazzi.context), isLive = false).apply(configure)
        capture(name) { ElevenApp(model) }
    }

    private fun renderDetail(
        name: String,
        section: DetailSection,
        expanded: Boolean = false,
        initialScrollIndex: Int = 0,
        initialScrollOffset: Int = 0,
    ) {
        val fixtures = FixtureStockData.load(paparazzi.context.assets)
        val stock = requireNotNull(fixtures.repository.getStock(fixtures.defaultStockId))
        capture(name) {
            // Matches the production detail route's outer frame, including its safe-drawing insets.
            BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
                CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        StockDetailScreen(
                            stock = stock,
                            onBack = {},
                            onBuy = {},
                            onSell = {},
                            isWatched = stock.id in fixtures.initialWatchedIds,
                            onWatch = {},
                            detail = fixtures.detail(stock.id),
                            initialSection = section,
                            initiallyExpanded = expanded,
                            listState = rememberLazyListState(
                                initialFirstVisibleItemIndex = initialScrollIndex,
                                initialFirstVisibleItemScrollOffset = initialScrollOffset,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent(content)
        }
        // A single timestamp jump can start a delayed Compose animation at capture time.
        // Advance actual frames through the entry delay/reveal. Paparazzi writes an animated PNG;
        // its last frame is the settled screenshot, without requiring java.desktop in this source set.
        paparazzi.gif(view, name = name, start = 0L, end = 1_200L, fps = 30)
    }

    private fun purchaseAssets(): List<PurchasePaymentAsset> = listOf(
        asset("ETHEREUM:ETH", "ETH", PurchaseNetwork.ETHEREUM, "2.00", "7000"),
        asset("ETHEREUM:USDC", "USDC", PurchaseNetwork.ETHEREUM, "180", "180"),
        asset("BASE:ETH", "ETH", PurchaseNetwork.BASE, "0.5", "1750"),
        asset("BASE:USDC", "USDC", PurchaseNetwork.BASE, "92", "92"),
        asset("ARBITRUM:ETH", "ETH", PurchaseNetwork.ARBITRUM, "0.25", "875"),
        asset("ARBITRUM:USDC", "USDC", PurchaseNetwork.ARBITRUM, "64", "64"),
        asset("SOLANA:SOL", "SOL", PurchaseNetwork.SOLANA, "32", "4800"),
        asset("SOLANA:USDC", "USDC", PurchaseNetwork.SOLANA, "310", "310"),
        // Backend-disabled entries must stay out of the account dropdown and its available count.
        asset("BASE:DAI", "DAI", PurchaseNetwork.BASE, "99", "99", enabled = false),
    )

    private fun asset(
        id: String,
        symbol: String,
        network: PurchaseNetwork,
        balance: String,
        usdValue: String,
        enabled: Boolean = true,
    ) = PurchasePaymentAsset(
        id = id,
        symbol = symbol,
        name = when (symbol) {
            "ETH" -> "Ether"
            "SOL" -> "Solana"
            "DAI" -> "Dai"
            else -> "USD Coin"
        },
        network = network,
        address = if (network == PurchaseNetwork.SOLANA) "11111111111111111111111111111111"
        else "0x0000000000000000000000000000000000000000",
        decimals = if (symbol == "USDC") 6 else if (symbol == "SOL") 9 else 18,
        balanceBaseUnits = BigInteger.ONE,
        balance = BigDecimal(balance),
        usdValue = BigDecimal(usdValue),
        enabled = enabled,
    )
}

/** Uses Layoutlib's real merged Android assets/resources without an Activity or emulator. */
private class RenderApplication(context: Context) : Application() {
    init {
        attachBaseContext(context)
    }
}
