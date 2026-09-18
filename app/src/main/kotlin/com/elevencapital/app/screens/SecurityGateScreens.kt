package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.DeviceUnlockState
import com.elevencapital.app.ui.BrandLogo

/** Opaque branded surface while Privy restores the verified session. */
@Composable
fun SecureSessionScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BrandLogo(Modifier.size(176.dp).semantics {
            contentDescription = "Opening Eleven Capital"
        })
    }
}

/**
 * Only the branded unlock surface is visible behind Android's credential prompt. Personal content
 * stays uncomposed until the existing identity-bound device gate succeeds. If the user
 * cancels, tapping the mark deliberately retries rather than trapping them in a prompt loop.
 */
@Composable
fun DeviceUnlockScreen(
    state: DeviceUnlockState,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val setupRequired = state.phase == DeviceUnlockPhase.DEVICE_LOCK_REQUIRED
    val prompting = state.phase == DeviceUnlockPhase.PROMPTING
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BrandLogo(
            Modifier.size(176.dp).clip(CircleShape)
                .clickable(
                    enabled = !prompting,
                    role = Role.Button,
                    onClickLabel = if (setupRequired) "Open phone security settings" else "Unlock Eleven Capital",
                    onClick = if (setupRequired) onOpenSecuritySettings else onUnlock,
                ).semantics {
                    contentDescription = if (setupRequired) {
                        "Eleven Capital. Tap the logo to set up your phone PIN, pattern, or password."
                    } else "Eleven Capital. Tap the logo to unlock."
                    stateDescription = if (prompting) "Waiting for phone security" else state.message ?: "Locked"
                    liveRegion = LiveRegionMode.Polite
                },
        )
    }
}
