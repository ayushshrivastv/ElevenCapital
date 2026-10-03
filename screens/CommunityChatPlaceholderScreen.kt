package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd

/** Non-SPXTR rooms are deliberately not connected to a chat service yet. */
@Composable
fun CommunityChatPlaceholderScreen(tokenName: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(W.Background)) {
        Row(
            Modifier.fillMaxWidth().height(rd(80f)).padding(horizontal = rd(20f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(44f)).clip(CircleShape).background(W.Panel)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close chat" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 22f, W.White) }
            Spacer(Modifier.width(rd(14f)))
            RefText(tokenName, 19f, W.White, FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                SquareLogo(104f, .18f)
                Spacer(Modifier.height(rd(20f)))
                RefText("Chat coming soon", 17f, W.Muted, FontWeight.Medium)
            }
        }
    }
}
