package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd

/** The same title panel appears above each primary tab. */
@Composable
fun WalletTopPanel(
    title: String,
    alternateTitle: String,
    onAlternate: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(W.Background).padding(horizontal = rd(26f))) {
        Spacer(Modifier.height(rd(20f)))
        Box(Modifier.width(rd(30f)).height(rd(4f)).clip(CircleShape).background(W.Circle))
        Spacer(Modifier.height(rd(24f)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                RefText(title, 42f, W.White, FontWeight.Normal)
                Box(Modifier.align(Alignment.TopEnd).offset(x = rd(5f), y = rd(-3f))
                    .size(rd(8f)).background(W.Orange, CircleShape))
            }
            Spacer(Modifier.width(rd(16f)))
            Box(Modifier.height(rd(48f)).clip(CircleShape)
                .clickable(role = Role.Tab, onClick = onAlternate)
                .semantics { contentDescription = "$alternateTitle, open ${alternateTitle.lowercase()}" },
                contentAlignment = Alignment.Center) {
                RefText(alternateTitle, 23f, W.Muted)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(rd(44f)).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onMore)
                .semantics { contentDescription = "Account options" },
                contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(rd(5f))) {
                    listOf(W.White, W.CardMuted, W.Circle).forEach {
                        Box(Modifier.size(rd(5f)).background(it, CircleShape))
                    }
                }
            }
        }
    }
}
