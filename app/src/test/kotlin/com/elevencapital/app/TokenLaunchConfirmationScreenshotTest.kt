package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.screens.LaunchTokenExecutionPhase
import com.elevencapital.app.screens.LaunchTokenExecutionState
import com.elevencapital.app.screens.LaunchTokenFlow
import com.elevencapital.app.screens.tokenLaunchUiPreviewResult
import com.elevencapital.app.ui.LocalReferenceScale
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Focused visual contract for the QR, explorer action, and copy-link confirmation page. */
class TokenLaunchConfirmationScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 360,
            screenHeight = 780,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            nightMode = NightMode.NIGHT,
            fontScale = 1f,
            locale = "en-rUS",
            softButtons = false,
        ),
        theme = "Theme.ElevenCapital",
        showSystemUi = false,
        useDeviceResolution = true,
        maxPercentDifference = 0.0,
    )

    @Test fun launchedTokenShowsOnlyTickerQrAndLink() {
        val result = tokenLaunchUiPreviewResult()
        val artwork = listOf(
            File("app/src/main/res/drawable-nodpi/profile_orb_4.png"),
            File("src/main/res/drawable-nodpi/profile_orb_4.png"),
        ).first(File::isFile).toURI().toString()
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                    CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                        LaunchTokenFlow(
                            onClose = {},
                            initialDraft = LaunchTokenDraft(
                                imageUri = artwork,
                                name = "Christmas",
                                symbol = "CHTMS",
                            ),
                            executionState = LaunchTokenExecutionState(
                                phase = LaunchTokenExecutionPhase.CONFIRMED,
                                confirmed = result,
                            ),
                        )
                    }
                }
            }
        }
        paparazzi.snapshot(view, name = "token_launch_confirmed_360px")
    }
}
