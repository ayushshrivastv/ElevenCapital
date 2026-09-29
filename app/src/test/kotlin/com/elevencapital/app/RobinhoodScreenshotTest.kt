package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.lazy.rememberLazyListState
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.data.LiveMarketDataParser
import com.elevencapital.app.screens.DetailPresentation
import com.elevencapital.app.screens.DetailChartContext
import com.elevencapital.app.screens.MarketQuery
import com.elevencapital.app.screens.MarketSource
import com.elevencapital.app.screens.MarketsScreen
import com.elevencapital.app.screens.PrimaryStockRow
import com.elevencapital.app.screens.StockDetailScreen
import com.elevencapital.app.screens.SharedMainHeader
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.ChartRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Production screens rendered from dated public token-market and original-share snapshots. */
class RobinhoodScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392, screenHeight = 826, xdpi = 160, ydpi = 160,
            density = Density.MEDIUM, nightMode = NightMode.NIGHT,
            fontScale = 1f, locale = "en-rUS", softButtons = false,
        ),
        theme = "Theme.ElevenCapital", showSystemUi = true,
        useDeviceResolution = true, maxPercentDifference = 0.0,
    )

    private val catalog by lazy {
        val input = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-catalog.json"))
        val source = input.bufferedReader().use { JSONObject(it.readText()) }
        LiveMarketDataParser.catalog(source)
    }

    @Test
    fun robinhoodStocks() {
        assertEquals(20, catalog.instruments.size)
        catalog.instruments.forEach { instrument ->
            assertEquals("onchain_token_market", instrument.quoteBasis)
            assertTrue(instrument.stock.quote.price != null)
            assertTrue(instrument.stock.quote.changePercent != null)
            assertTrue(instrument.stock.activity?.volume24h != null)
            assertTrue(instrument.stock.activity?.netVolume24h != null)
        }
        capture("robinhood_stocks") {
            Column(Modifier.fillMaxSize()) {
                SharedMainHeader(avatarVariant = 0, onAccount = {})
                Spacer(Modifier.height(rd(26f)))
                Box(Modifier.weight(1f)) {
                    MarketsScreen(
                        rows = catalog.instruments.map { PrimaryStockRow(it.stock, ageLabel = "Robinhood") },
                        onStock = {}, onWatch = {}, onFilter = {},
                        query = MarketQuery(source = MarketSource.ROBINHOOD),
                    )
                }
            }
            StockDock(RootTab.Markets, onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    @Test
    fun robinhoodAppleDetail() {
        val apple = catalog.instruments.single { it.provider == "robinhood" && it.stock.symbol == "AAPL" }
        val source = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-apple-chart.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val chart = LiveMarketDataParser.chart(source, apple.stock.id, ChartRange.ONE_DAY)
        assertEquals("underlying_share_reference", chart.basis)
        assertTrue(chart.points.size > 2)
        capture("robinhood_apple_detail", animated = true) {
            StockDetailScreen(
                stock = apple.stock.copy(charts = mapOf(ChartRange.ONE_DAY to chart.points)),
                onBack = {}, onBuy = {}, onSell = {},
                isWatched = false, onWatch = {},
                detail = DetailPresentation(providerLabel = apple.providerLabel, ageLabel = "Robinhood"),
                priceNotice = "Robinhood · USD · indicative reference",
                chartMessage = chart.statusReason,
                chartContext = DetailChartContext(ChartRange.ONE_DAY, "USD", "Yahoo · original stock · USD"),
                useQuoteChangeForAllRanges = false,
            )
        }
    }

    @Test
    fun robinhoodAppleOwnedDetail() {
        val apple = catalog.instruments.single { it.provider == "robinhood" && it.stock.symbol == "AAPL" }
        capture("robinhood_apple_owned_detail") {
            StockDetailScreen(
                stock = apple.stock, onBack = {}, onBuy = {}, onSell = {},
                hasPosition = true, isWatched = false, onWatch = {},
                detail = DetailPresentation(providerLabel = apple.providerLabel, ageLabel = "Robinhood"),
            )
        }
    }

    @Test
    fun robinhoodAppleAbout() {
        val apple = catalog.instruments.single { it.provider == "robinhood" && it.stock.symbol == "AAPL" }
        assertEquals("4663", apple.stock.deployments.single().chainId)
        capture("robinhood_apple_about") {
            StockDetailScreen(
                stock = apple.stock, onBack = {}, onBuy = {}, onSell = {},
                isWatched = false, onWatch = {},
                detail = DetailPresentation(providerLabel = apple.providerLabel, ageLabel = "Robinhood"),
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 1),
            )
        }
    }

    private fun capture(name: String, animated: Boolean = false, content: @Composable BoxScope.() -> Unit) {
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
        if (animated) paparazzi.gif(view, name = name, start = 0L, end = 1_500L, fps = 30)
        else paparazzi.snapshot(view, name = name)
    }
}
