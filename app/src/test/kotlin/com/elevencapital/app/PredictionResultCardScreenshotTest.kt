package com.elevencapital.app

import android.graphics.Bitmap
import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.screens.PredictionDirection
import com.elevencapital.app.screens.PredictionPricePoint
import com.elevencapital.app.screens.PredictionResultContent
import com.elevencapital.app.screens.PredictionResultData
import com.elevencapital.app.screens.renderPredictionResultBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Focused viewport checks for the result card and its glass actions. */
class PredictionResultCardScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392, screenHeight = 826, xdpi = 160, ydpi = 160,
            density = Density.MEDIUM, nightMode = NightMode.NIGHT,
            fontScale = 1f, locale = "en-rUS", softButtons = false,
        ),
        theme = "Theme.ElevenCapital", showSystemUi = true,
        useDeviceResolution = true, maxPercentDifference = 0.0,
    )

    @Test fun winResult() = capture("win_result", previewResult(won = true))

    @Test fun lossResult() = capture("loss_result", previewResult(won = false))

    @Test fun bitmapExportsFourTimesArtboardAsPng() {
        val previewDirectory = System.getenv("ELEVEN_RESULT_PREVIEW_DIR")?.let(::File)
        previewDirectory?.mkdirs()
        for ((name, won) in listOf("win" to true, "loss" to false)) {
            val bitmap = renderPredictionResultBitmap(paparazzi.context, previewResult(won))
            try {
                assertEquals(2880, bitmap.width)
                assertEquals(4200, bitmap.height)
                val output = ByteArrayOutputStream()
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                val png = output.toByteArray()
                assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), png.copyOfRange(0, 8))
                assertEquals(2880, ByteBuffer.wrap(png, 16, 4).int)
                assertEquals(4200, ByteBuffer.wrap(png, 20, 4).int)
                previewDirectory?.resolve("prediction-result-$name-design-preview-4k.png")?.writeBytes(png)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun previewResult(won: Boolean) = PredictionResultData(
        direction = if (won) PredictionDirection.ABOVE else PredictionDirection.BELOW,
        stake = 20,
        anchor = 64482.64,
        finalPrice = 64515.92,
        band = 20.50,
        won = won,
        marketSymbol = "BTC",
        payoutUsd = if (won) 37.50 else 0.0,
        finishedAtMillis = 1_790_780_000_000L,
        priceHistory = listOf(
            PredictionPricePoint(0, 64482.64), PredictionPricePoint(12, 64485.22),
            PredictionPricePoint(28, 64481.91), PredictionPricePoint(43, 64495.71),
            PredictionPricePoint(61, 64502.83), PredictionPricePoint(75, 64508.05),
            PredictionPricePoint(90, 64515.92),
        ),
    )

    private fun capture(name: String, result: PredictionResultData) {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    PredictionResultContent(result, onShare = {}, onDownload = {},
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        paparazzi.snapshot(view, name = name)
    }
}
