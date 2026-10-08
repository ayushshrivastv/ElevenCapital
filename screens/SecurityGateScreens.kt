package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.DeviceUnlockState
import com.elevencapital.app.ui.BrandArtwork
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle

/** Opaque branded surface while Privy restores the verified session. */
@Composable
fun SecureSessionScreen(
    retryAvailable: Boolean = false,
    onRetry: () -> Unit = {},
    message: String? = null,
) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BrandArtwork(Modifier.size(176.dp).semantics {
            contentDescription = "Opening Eleven Capital"
        })
        if (retryAvailable) UnlockRetryAction("Unlock Eleven",
            message ?: "Your secure session is still connecting. Try again.", onRetry,
            Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Only the branded unlock surface is visible behind Android's credential prompt. Personal content
 * stays uncomposed until the existing identity-bound device gate succeeds. If the user
 * cancels or a prompt fails to appear, the visible retry action starts a fresh attempt.
 */
@Composable
fun DeviceUnlockScreen(
    state: DeviceUnlockState,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
) {
    val setupRequired = state.phase == DeviceUnlockPhase.DEVICE_LOCK_REQUIRED
    val prompting = state.phase == DeviceUnlockPhase.PROMPTING
    val retryAvailable = setupRequired || state.retryAvailable
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BrandArtwork(
            Modifier.size(176.dp)
                .clickable(
                    enabled = !prompting && retryAvailable,
                    role = Role.Button,
                    onClickLabel = if (setupRequired) "Open phone security settings" else "Unlock Eleven Capital",
                    onClick = if (setupRequired) onOpenSecuritySettings else onUnlock,
                ).semantics {
                    contentDescription = if (setupRequired) {
                        "Eleven Capital. Tap the logo to set up your phone PIN, pattern, or password."
                    } else if (retryAvailable) "Eleven Capital. Unlock Eleven to continue."
                    else "Eleven Capital. Opening phone security."
                    stateDescription = if (prompting) "Waiting for phone security" else state.message ?: "Locked"
                    liveRegion = LiveRegionMode.Polite
                },
        )
        if (retryAvailable) UnlockRetryAction(
            if (setupRequired) "Set up phone lock" else "Unlock Eleven",
            state.message ?: "Use your phone PIN, pattern, or password to continue.",
            if (setupRequired) onOpenSecuritySettings else onUnlock,
            Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun UnlockRetryAction(label: String, message: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.navigationBarsPadding().padding(bottom = 18.dp)
            .clip(CircleShape).background(WalletStyle.Panel).padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(260.dp).height(58.dp).clip(CircleShape).background(Color.White)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .semantics {
                    stateDescription = message
                    liveRegion = LiveRegionMode.Polite
                },
            contentAlignment = Alignment.Center,
        ) {
            RefText(label, 17f, Color.Black, FontWeight.SemiBold)
        }
    }
}
