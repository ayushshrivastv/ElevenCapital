package com.elevencapital.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevencapital.app.auth.AuthPhase
import com.elevencapital.app.auth.ElevenAuthState
import com.elevencapital.app.ui.ElevenRoboto
import com.elevencapital.app.ui.BrandLogo

private val LoginBackground = Color(0xFF08090B)
private val LoginWhite = Color(0xFFF6F6F4)
private val LoginMuted = Color(0xFF9A9B9E)
private const val ReferenceWidth = 418f
private const val ReferenceHeight = 862f

/**
 * Dark Eleven Capital adaptation of the supplied 418 x 862 login reference.
 *
 * The actual Android status and navigation bars remain native. Every visible element between
 * them is drawn by Compose, so the screen scales cleanly without baking another phone frame or
 * platform chrome into the application.
 */
@Composable
fun LoginScreen(
    authState: ElevenAuthState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") onLogout: () -> Unit = {},
) {
    val restoring = authState.phase == AuthPhase.RESTORING
    val signingIn = authState.phase == AuthPhase.SIGNING_IN
    val reconnecting = authState.phase == AuthPhase.SESSION_UNVERIFIED
    val enabled = when {
        !authState.configured || restoring || reconnecting || signingIn || authState.authenticated -> false
        else -> true
    }
    val actionLabel = when (authState.phase) {
        AuthPhase.RESTORING -> "Restoring session…"
        AuthPhase.SIGNING_IN -> "Signing in with Google…"
        AuthPhase.SESSION_UNVERIFIED -> "Restoring session…"
        AuthPhase.UNCONFIGURED -> "Sign-in unavailable"
        else -> "Login with Google"
    }
    val feedback = if (!authState.configured) {
        "Google sign-in is not configured in this build."
    } else {
        authState.error
    }

    BoxWithConstraints(modifier.fillMaxSize().background(LoginBackground)) {
        val unit = maxWidth.value / ReferenceWidth
        fun at(referenceDp: Float) = (referenceDp * unit).dp
        val compositionHeight = maxOf(maxHeight, at(ReferenceHeight))

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(compositionHeight)) {
                ElevenLoginArtwork(Modifier.fillMaxWidth().height(at(448f)))

                Row(
                    modifier = Modifier.offset(x = at(32f), y = at(431f)).height(at(30f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandLogo(
                        Modifier.size(at(23f))
                            .semantics { contentDescription = "Eleven Capital logo" },
                    )
                    LoginText(
                        text = "Eleven Capital",
                        size = 16f * unit,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.offset(x = at(10f)),
                    )
                }

                Box(
                    Modifier.offset(x = at(32f), y = at(500f))
                        .width(at(354f)).height(at(127f)),
                    contentAlignment = Alignment.TopStart,
                ) {
                    LoginText(
                        text = "Your Stocks,\nOne Wallet,\nSimply Connected",
                        size = 37f * unit,
                        lineHeight = 40f * unit,
                        weight = FontWeight.Light,
                        maxLines = 3,
                    )
                }

                Box(
                    Modifier.offset(x = at(32f), y = at(645f))
                        .width(at(354f)).height(at(58f)),
                    contentAlignment = Alignment.TopStart,
                ) {
                    LoginText(
                        text = "Choose the asset you have and the stock you want. Eleven handles the route between them.",
                        size = 12.5f * unit,
                        lineHeight = 15.5f * unit,
                        color = LoginMuted,
                        maxLines = 4,
                    )
                }

                if (!feedback.isNullOrBlank()) {
                    Box(
                        Modifier.offset(x = at(32f), y = at(704f))
                            .width(at(354f)).height(at(36f))
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        contentAlignment = Alignment.TopStart,
                    ) {
                        LoginText(
                            text = feedback,
                            size = 11.5f * unit,
                            lineHeight = 14f * unit,
                            color = Color(0xFFD2A3A8),
                            maxLines = 2,
                        )
                    }
                }

                val buttonHeight = at(52f)
                val touchHeight = maxOf(48.dp, buttonHeight)
                Box(
                    Modifier.offset(
                        x = at(31f),
                        y = at(739f) - (touchHeight - buttonHeight) / 2,
                    ).width(at(356f)).height(touchHeight)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onSignIn)
                        .semantics {
                            contentDescription = actionLabel
                            liveRegion = LiveRegionMode.Polite
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(buttonHeight)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (enabled) LoginWhite else Color(0xFF77787B)),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoginText(
                            text = actionLabel,
                            size = 15.5f * unit,
                            color = LoginBackground,
                            weight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Quiet grid artwork based on the supplied reference, using the shared Eleven mark. */
@Composable
private fun ElevenLoginArtwork(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.background(
            Brush.verticalGradient(
                colors = listOf(Color(0xFF111316), Color(0xFF0D0F11), LoginBackground),
            ),
        ),
    ) {
        val unit = maxWidth.value / ReferenceWidth
        BrandLogo(
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (64f * unit).dp)
                .size((274f * unit).dp),
            alpha = .22f,
        )

        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / ReferenceWidth
            fun sx(value: Float) = value * scale

            // Fine grid and deterministic pixels preserve the reference's technical texture.
            val gridColor = Color(0xFF2B2D30).copy(alpha = .34f)
            var line = 0f
            while (line <= ReferenceWidth) {
                drawLine(gridColor, Offset(sx(line), 0f), Offset(sx(line), sx(405f)), sx(.55f))
                line += 24f
            }
            line = 0f
            while (line <= 408f) {
                drawLine(gridColor, Offset(0f, sx(line)), Offset(size.width, sx(line)), sx(.55f))
                line += 24f
            }

            for (row in 0..50) {
                for (column in 0..52) {
                    val code = (column * 37 + row * 17 + column * row) % 19
                    if (code < 8) {
                        drawRect(
                            color = Color(0xFF727579).copy(alpha = .26f),
                            topLeft = Offset(sx(column * 8f + 1f), sx(row * 8f + 1f)),
                            size = Size(sx(if (code == 0) 2f else 1f), sx(if (code == 0) 2f else 1f)),
                        )
                    }
                }
            }

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, LoginBackground),
                    startY = sx(310f),
                    endY = sx(448f),
                ),
                topLeft = Offset(0f, sx(306f)),
                size = Size(size.width, sx(142f)),
            )
        }
    }
}

@Composable
private fun LoginText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    lineHeight: Float = size * 1.2f,
    color: Color = LoginWhite,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        style = TextStyle(
            fontFamily = ElevenRoboto,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
            color = color,
            fontWeight = weight,
            textAlign = textAlign,
        ),
    )
}
