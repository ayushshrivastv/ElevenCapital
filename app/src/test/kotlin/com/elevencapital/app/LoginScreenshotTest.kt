package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.auth.AuthPhase
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.DeviceUnlockState
import com.elevencapital.app.auth.ElevenAuthState
import com.elevencapital.app.screens.AccountScreen
import com.elevencapital.app.screens.DeviceUnlockScreen
import com.elevencapital.app.screens.LoginScreen
import com.elevencapital.app.screens.SecureSessionScreen
import com.elevencapital.app.ui.LocalReferenceScale
import com.elevencapital.app.ui.P
import org.junit.Rule
import org.junit.Test

/**
 * Separate signed-out-page goldens. Record these after an intentional login redesign.
 * The S22's 1080 x 2340 display at 3x density is a 360 x 780 full-screen composition.
 * LoginScreen owns its edge-to-edge canvas, so no duplicate safe-area padding or simulated
 * phone chrome is added. Inspect these PNGs alongside the real S22 capture.
 * The logout image renders only the AccountScreen component, without constructing an
 * authenticated SDK session, wallet, activity, or production ViewModel.
 */
class LoginScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
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

    @Test
    fun welcomeSignedOut() = renderLogin("welcome_signed_out", AuthPhase.SIGNED_OUT)

    @Test
    fun welcomeSigningIn() = renderLogin("welcome_signing_in", AuthPhase.SIGNING_IN)

    @Test
    fun welcomeSignInError() = renderLogin(
        "welcome_sign_in_error", AuthPhase.SIGNED_OUT,
        error = "Sign-in did not complete. Please try again.",
    )

    @Test
    fun welcomeSessionUnverified() = capture("welcome_session_unverified") {
        SecureSessionScreen()
    }

    @Test
    fun returningUserDeviceUnlock() = capture("returning_user_device_unlock") {
        DeviceUnlockScreen(
            state = DeviceUnlockState(DeviceUnlockPhase.LOCKED, userId = "user"),
            onUnlock = {},
            onOpenSecuritySettings = {},
        )
    }

    @Test
    fun deviceLockSetupRequired() = capture("device_lock_setup_required") {
        DeviceUnlockScreen(
            state = DeviceUnlockState(
                DeviceUnlockPhase.DEVICE_LOCK_REQUIRED,
                userId = "user",
                message = "Set a phone PIN, pattern, or password in Android Settings to open Eleven Capital.",
            ),
            onUnlock = {},
            onOpenSecuritySettings = {},
        )
    }

    @Test
    fun accountPortfolioVisible() = capture("account_portfolio_visible") {
        BoxWithConstraints(Modifier.fillMaxSize().background(P.Background)) {
            CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
                AccountScreen(
                    balance = null,
                    tokenHoldings = emptyList(),
                    holdings = emptyList(),
                    onStock = {},
                    accountNotice = "Wallet connected",
                )
            }
        }
    }

    private fun renderLogin(name: String, phase: AuthPhase, error: String? = null) = capture(name) {
        LoginScreen(
            authState = ElevenAuthState(configured = true, phase = phase, error = error),
            onSignIn = {},
            onLogout = {},
        )
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent(content)
        }
        paparazzi.snapshot(view, name = name)
    }
}
