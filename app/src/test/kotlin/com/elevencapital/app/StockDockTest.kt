package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.WalletStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StockDockTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392, screenHeight = 826,
            xdpi = 160, ydpi = 160, density = Density.MEDIUM,
            nightMode = NightMode.NIGHT, fontScale = 1f,
            locale = "en-rUS", softButtons = false,
        ),
        theme = "Theme.ElevenCapital",
        showSystemUi = false,
        useDeviceResolution = true,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun allFiveDockTabsNavigateAndPredictionsHasSelectedSemantics() {
        val selected = mutableListOf<RootTab>()
        var checked = false
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                CompositionLocalProvider(LocalReferenceScale provides 1f) {
                    Box(Modifier.fillMaxSize().background(WalletStyle.Background)) {
                        StockDock(RootTab.Predictions, { selected += it }, Modifier.align(Alignment.BottomCenter))
                    }
                }
                val root = LocalView.current as ViewRootForTest
                SideEffect {
                    val nodes = descendants(root.semanticsOwner.rootSemanticsNode)
                    for ((label, tab) in listOf("Home" to RootTab.Home,
                        "Stocks" to RootTab.Markets, "Predictions" to RootTab.Predictions,
                        "Community" to RootTab.Community, "Account" to RootTab.Account)) {
                        val node = nodes.single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                            it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label)
                        }
                        assertFalse(node.config.contains(SemanticsProperties.Disabled))
                        assertEquals(tab == RootTab.Predictions, node.config[SemanticsProperties.Selected])
                        val count = selected.size
                        assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
                        assertEquals(listOf(tab), selected.drop(count))
                    }
                    checked = true
                }
            }
        }
        paparazzi.snapshot(view, name = "predictions_dock")
        assertTrue("Production dock semantics were checked", checked)
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun communityAndPredictionTouchesNavigateWithoutOpeningTheStockUnderneath() {
        var underlyingClicks = 0
        val selected = mutableListOf<RootTab>()
        var checked = false
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                CompositionLocalProvider(LocalReferenceScale provides 1f) {
                    Box(Modifier.fillMaxSize().background(WalletStyle.Background)) {
                        Box(Modifier.fillMaxSize().clickable { underlyingClicks++ })
                        StockDock(RootTab.Markets, { selected += it }, Modifier.align(Alignment.BottomCenter))
                    }
                }
                val root = LocalView.current as ViewRootForTest
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    root.measureAndLayoutForTest()
                    val nodes = descendants(root.semanticsOwner.rootSemanticsNode)
                    fun tap(label: String) {
                        val node = nodes.single {
                            it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                                it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label)
                        }
                        val center = node.boundsInRoot.center
                        assertTrue("Dock target has been laid out", node.boundsInRoot.width > 0)
                        val time = SystemClock.uptimeMillis()
                        for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 16L)) {
                            val event = MotionEvent.obtain(time, time + offset, action, center.x, center.y, 0)
                            try { root.view.dispatchTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                    tap("Community")
                    assertEquals(0, underlyingClicks)
                    assertEquals(listOf(RootTab.Community), selected)
                    tap("Predictions")
                    assertEquals(listOf(RootTab.Community, RootTab.Predictions), selected)
                    assertEquals(0, underlyingClicks)
                    tap("Stocks")
                    assertEquals(listOf(RootTab.Community, RootTab.Predictions, RootTab.Markets), selected)
                    assertEquals(0, underlyingClicks)
                    checked = true
                }
            }
        }
        paparazzi.gif(view, name = "predictions_dock_touch_isolation", start = 0L, end = 200L, fps = 30)
        assertTrue("Physical dock touches were checked", checked)
    }
}
