package com.elevencapital.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.ProfileAvatar
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd

private val AnalyticsAlert = Color(0xFFE65359)

/** The logo, stock search, decorative bell, and account avatar shared by the main tabs. */
@Composable
fun SharedMainHeader(avatarVariant: Int, onAccount: () -> Unit, onSearch: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().background(W.Background)) {
        Row(
            Modifier.fillMaxWidth().height(rd(56f))
                .padding(start = rd(32f), end = rd(12f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandLogo(Modifier.size(rd(36f)))
            Spacer(Modifier.width(rd(12f)))
            Row(
                Modifier.weight(1f).height(rd(44f))
                    .clip(RoundedCornerShape(rd(11f))).background(Color(0xFF1C1C1C))
                    .clickable(role = Role.Button, onClick = onSearch)
                    .semantics { contentDescription = "Search stocks" }
                    .padding(horizontal = rd(12f)),
                horizontalArrangement = Arrangement.spacedBy(rd(10f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnalyticsSearchGlyph(glyphSize = 20f, color = W.Muted)
                RefText("Search", 16f, W.Muted)
            }
            Spacer(Modifier.width(rd(10f)))
            Box(
                Modifier.size(rd(52f)).clip(CircleShape).background(W.Panel)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                RefIcon("bell", 21f, W.White)
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .offset(x = rd(-19f), y = rd(16f))
                        .size(rd(6f)).background(AnalyticsAlert, CircleShape),
                )
            }
            Spacer(Modifier.width(rd(4f)))
            Box(
                Modifier.size(rd(52f)).clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onAccount)
                    .semantics { contentDescription = "Open account" },
                contentAlignment = Alignment.Center,
            ) {
                ProfileAvatar(avatarVariant, Modifier.size(rd(52f)))
            }
        }
    }
}

/** Portfolio's two-line title and decorative filter chips below the shared header. */
@Composable
fun PortfolioAnalyticsSection() {
    Column(Modifier.fillMaxWidth().background(W.Background)) {
        Spacer(Modifier.height(rd(16f)))
        Row(
            Modifier.fillMaxWidth().height(rd(88f))
                .padding(start = rd(32f), end = rd(12f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefText("Your\nAnalytics", 34f, W.White, FontWeight.Light,
                Modifier.weight(1f), maxLines = 2)
        }

        Spacer(Modifier.height(rd(24f)))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = rd(16f)),
            horizontalArrangement = Arrangement.spacedBy(rd(8f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(50f)).clip(CircleShape).background(W.White)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                AnalyticsSlidersGlyph()
            }
            AnalyticsFilterChip("Sales", 108f)
            AnalyticsFilterChip("Tech Products", 160f)
        }
        Spacer(Modifier.height(rd(16f)))
    }
}

@Composable
private fun AnalyticsFilterChip(label: String, width: Float) {
    Row(
        Modifier.width(rd(width)).height(rd(50f)).clip(CircleShape).background(W.Panel)
            .clearAndSetSemantics { }.padding(horizontal = rd(16f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefText(label, 14f, W.White)
        RefText("×", 18f, W.Muted)
    }
}

@Composable
private fun AnalyticsSearchGlyph(glyphSize: Float = 19f, color: Color = W.White.copy(alpha = .78f)) {
    Canvas(Modifier.size(rd(glyphSize))) {
        val line = size.width * .065f
        drawCircle(color, radius = size.width * .27f,
            center = Offset(size.width * .43f, size.height * .43f), style = Stroke(line))
        drawLine(color, Offset(size.width * .64f, size.height * .64f),
            Offset(size.width * .85f, size.height * .85f), line)
    }
}

@Composable
private fun AnalyticsSlidersGlyph() {
    Canvas(Modifier.size(rd(19f))) {
        val line = size.width * .06f
        for ((xFraction, knobFraction) in listOf(.25f to .59f, .5f to .35f, .75f to .52f)) {
            val x = size.width * xFraction
            val knob = Offset(x, size.height * knobFraction)
            drawLine(W.Ink, Offset(x, size.height * .2f), Offset(x, size.height * .8f), line)
            drawCircle(W.White, radius = size.width * .115f, center = knob)
            drawCircle(W.Ink, radius = size.width * .115f, center = knob, style = Stroke(line))
        }
    }
}
