package com.elevencapital.app.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.R
import com.elevencapital.app.ui.*

/** Visible account-center state from video 00:58. No authentication/key-management flow. */
@Composable
fun AccountCenterScreen(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(P.Background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = rd(22f))) {
            Row(Modifier.fillMaxWidth().height(rd(64f)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(rd(32f)).clickable(role = Role.Button, onClick = onBack),
                    contentAlignment = Alignment.CenterStart) { RefIcon("back", 24f) }
                Spacer(Modifier.width(rd(16f)))
                RefText("Account Center", 20f, weight = FontWeight.Bold)
            }
            Spacer(Modifier.height(rd(14f)))
            RefText("Aggregated balance", 16f, P.Muted)
            RefText("$0.671", 32f, weight = FontWeight.Bold)
            Row(Modifier.padding(top = rd(3f)), horizontalArrangement = Arrangement.spacedBy(rd(9f))) {
                repeat(2) { Box(Modifier.width(rd(64f)).height(rd(18f)).background(P.Card.copy(alpha = .35f), RoundedCornerShape(rd(3f)))) }
            }
            Spacer(Modifier.height(rd(20f)))
            Box(Modifier.fillMaxWidth().height(rd(.5f)).background(P.Chip))
            Spacer(Modifier.height(rd(26f)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefText("2 Accounts", 16f, P.Muted, modifier = Modifier.weight(1f))
                Row(Modifier.height(rd(32f)).clip(CircleShape).background(P.Card)
                    .padding(horizontal = rd(11f)).semantics { disabled() }, verticalAlignment = Alignment.CenterVertically) {
                    RefText("Sort by", 14f)
                    Spacer(Modifier.width(rd(9f)))
                    RefIcon("chevronDown", 13f, P.Muted)
                }
            }
            Spacer(Modifier.height(rd(25f)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.reference_google), "Google", Modifier.size(rd(24f)))
                Spacer(Modifier.width(rd(11f)))
                RefText("Jupiter ID", 20f, weight = FontWeight.Bold)
                Spacer(Modifier.width(rd(9f)))
                RefText("ayush.srivastav@icloud.com", 12f,
                    modifier = Modifier.background(P.Card.copy(alpha = .4f)).padding(horizontal = rd(3f)))
            }
            Spacer(Modifier.height(rd(18f)))
            ReferenceAccountRow("ayushshrivastv", "13U3…5XgR", "$0.671", true)
            Spacer(Modifier.height(rd(26f)))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefText("Recovery Phrase 1", 20f, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(rd(31f)).background(P.Card.copy(alpha = .6f), CircleShape)
                    .semantics { contentDescription = "Add account to group"; disabled() }, contentAlignment = Alignment.Center) {
                    RefIcon("plus", 18f)
                }
            }
            Spacer(Modifier.height(rd(18f)))
            ReferenceAccountRow("ayushshrivastv", "Af2h…trFT", "$0.00", false)
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(start = rd(22f), end = rd(22f), bottom = rd(7f))
            .fillMaxWidth().height(rd(53f)).clip(CircleShape).background(P.Lime.copy(alpha = .15f))
            .semantics { contentDescription = "Add Account"; disabled() }, contentAlignment = Alignment.Center) {
            RefText("Add Account", 18f, P.Lime)
        }
    }
}

@Composable
private fun ReferenceAccountRow(name: String, address: String, balance: String, active: Boolean) {
    Row(Modifier.fillMaxWidth().height(rd(70f)).clip(RoundedCornerShape(rd(17f)))
        .background(P.Card.copy(alpha = .4f))
        .then(if (active) Modifier.border(rd(.7f), P.Lime, RoundedCornerShape(rd(17f))) else Modifier)
        .padding(horizontal = rd(22f)), verticalAlignment = Alignment.CenterVertically) {
        SquareLogo(30f)
        Spacer(Modifier.width(rd(25f)))
        Column(Modifier.weight(1f)) {
            RefText(name, 18f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RefText(address, 14f, P.Muted)
                Spacer(Modifier.width(rd(5f)))
                RefIcon("copy", 13f, P.Muted, Modifier.semantics { disabled() })
            }
        }
        RefText(balance, 18f)
        Spacer(Modifier.width(rd(20f)))
        RefIcon("moreVertical", 15f, P.Muted, Modifier.semantics { disabled() })
    }
}
