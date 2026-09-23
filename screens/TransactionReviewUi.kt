package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs

@Composable
internal fun TransactionReviewHeader(title: String, backEnabled: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(rd(64f))) {
        Box(
            Modifier.align(Alignment.CenterStart).size(rd(44f)).clip(CircleShape)
                .background(WalletStyle.Panel)
                .clickable(enabled = backEnabled, role = Role.Button, onClick = onBack)
                .semantics {
                    contentDescription = "Back"
                    if (!backEnabled) disabled()
                },
            contentAlignment = Alignment.Center,
        ) { RefIcon("back", 22f, WalletStyle.White) }
        RefText(title, 19f, WalletStyle.White, FontWeight.SemiBold, Modifier.align(Alignment.Center))
        Spacer(Modifier.align(Alignment.CenterEnd).size(rd(44f)))
    }
}

@Composable
internal fun TransactionReviewPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(28f)))
            .background(WalletStyle.Panel).padding(horizontal = rd(18f), vertical = rd(18f)),
        verticalArrangement = Arrangement.spacedBy(rd(11f)),
        content = content,
    )
}

@Composable
internal fun TransactionReviewRow(label: String, value: String, fullAddress: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        RefText(label, 13f, WalletStyle.Muted, modifier = Modifier.width(rd(120f)))
        BasicText(
            value,
            modifier = Modifier.weight(1f),
            style = TextStyle(
                color = WalletStyle.White,
                fontSize = rs(if (fullAddress) 12f else 13.5f),
                lineHeight = rs(if (fullAddress) 17f else 18f),
                textAlign = TextAlign.End,
                fontFamily = LocalReferenceFont.current,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
internal fun TransactionReviewDivider() {
    Box(Modifier.fillMaxWidth().height(rd(1f)).background(WalletStyle.White.copy(alpha = .10f)))
}

@Composable
internal fun TransactionReviewNote(message: String) {
    BasicText(
        message,
        Modifier.fillMaxWidth(),
        style = TextStyle(
            color = WalletStyle.Muted,
            fontSize = rs(12.5f),
            lineHeight = rs(18f),
            fontFamily = LocalReferenceFont.current,
        ),
    )
}

@Composable
internal fun TransactionReviewPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(rd(56f)).clip(CircleShape)
            .background(if (enabled) WalletStyle.Cream else WalletStyle.Circle)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        RefText(label, 16f, if (enabled) WalletStyle.Ink else WalletStyle.Muted, FontWeight.SemiBold)
    }
}

@Composable
internal fun TransactionReviewSecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(rd(44f)).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { RefText(label, 14f, if (enabled) WalletStyle.White else WalletStyle.Muted, FontWeight.Medium) }
}
