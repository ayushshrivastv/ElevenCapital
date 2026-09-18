package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import com.elevencapital.app.R
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.ProfileAvatar
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.app.wallet.TransferJournalRecord
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalTime
import java.util.Locale

/** The supplied wallet composition, with live account values and the existing transfer routes. */
@Composable
fun HomeScreen(
    balance: BigDecimal?,
    walletAddress: String?,
    walletConnected: Boolean,
    displayName: String?,
    transactions: List<TransferJournalRecord>,
    walletTransactions: List<WalletTransaction> = emptyList(),
    purchaseTransactions: List<CompletedPurchaseTransaction> = emptyList(),
    walletActivityStatus: WalletActivityStatus? = null,
    stockSymbols: Map<String, String> = emptyMap(),
    unitPricesUsd: Map<String, BigDecimal> = emptyMap(),
    historyStorageHealthy: Boolean,
    onStocks: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onManage: () -> Unit,
    onHistory: () -> Unit,
    balancePlaceholder: String = "—",
    profileAvatarVariant: Int = 0,
    greeting: String = currentWalletGreeting(),
    showTopPanel: Boolean = true,
) {
    val firstName = displayName?.trim()?.substringBefore(' ')?.takeIf(String::isNotBlank)
    BoxWithConstraints(Modifier.fillMaxSize().background(W.Background)) {
        val pageHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (showTopPanel) WalletTopPanel("Wallet", "Stocks", onStocks, onManage)
            Column(Modifier.fillMaxWidth().padding(horizontal = rd(26f))) {
                if (showTopPanel) Spacer(Modifier.height(rd(26f)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RefText(if (firstName == null) "$greeting." else "$greeting,", 22f, W.White)
                    Spacer(Modifier.width(rd(7f)))
                    ProfileAvatar(profileAvatarVariant, Modifier.size(rd(21f)))
                    if (firstName != null) {
                        Spacer(Modifier.width(rd(7f)))
                        RefText("$firstName.", 22f, W.White, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(rd(32f)))
                MainWalletCard(balance, walletAddress, walletConnected, balancePlaceholder, onManage)
                Spacer(Modifier.height(rd(26f)))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = (pageHeight - rd(if (showTopPanel) 397f else 275f)).coerceAtLeast(rd(300f)))
                    .clip(RoundedCornerShape(topStart = rd(30f), topEnd = rd(30f)))
                    .background(W.Panel).padding(start = rd(26f), end = rd(26f), top = rd(24f)),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f)),
                    verticalAlignment = Alignment.CenterVertically) {
                    WalletHomeAction("Add", "addCircle", Modifier.weight(1f), onReceive)
                    WalletHomeAction("Send", "arrowUpRight", Modifier.weight(1f), onSend)
                }
                Spacer(Modifier.height(rd(26f)))
                HomeWalletTransactions(transactions, walletTransactions, purchaseTransactions,
                    walletActivityStatus, historyStorageHealthy, onHistory,
                    unitPricesUsd = unitPricesUsd, stockSymbols = stockSymbols)
                // The floating dock must never cover the last row or the empty-state action.
                Spacer(Modifier.height(rd(112f)))
            }
        }
    }
}

private fun currentWalletGreeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun MainWalletCard(
    balance: BigDecimal?,
    address: String?,
    connected: Boolean,
    placeholder: String,
    onManage: () -> Unit,
) {
    val amount = remember(balance) {
        balance?.let { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(it) }
    }
    Box(Modifier.fillMaxWidth().height(rd(204f))) {
        Box(Modifier.padding(horizontal = rd(28f)).fillMaxWidth().height(rd(64f))
            .clip(RoundedCornerShape(rd(28f))).background(Color(0xFF41413F)))
        Column(
            Modifier.padding(top = rd(12f)).fillMaxSize().clip(RoundedCornerShape(rd(30f)))
                .background(W.White).padding(rd(18f)),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(rd(48f)).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFAE3015), Color(0xFFFF783E), Color(0xFFF5DCC0)))))
                Spacer(Modifier.width(rd(13f)))
                Column(Modifier.weight(1f)) {
                    RefText("Main wallet", 13f, W.CardMuted)
                    Spacer(Modifier.height(rd(4f)))
                    RefText(address?.let { "${it.take(6)}…${it.takeLast(5)}" } ?: "Wallet not connected",
                        14f, W.Ink)
                }
                Spacer(Modifier.width(rd(8f)))
                if (connected) {
                    Row(Modifier.semantics { contentDescription = "Privy wallet connected" },
                        verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.privy_square_blurple), null,
                            Modifier.size(rd(20f)))
                        Spacer(Modifier.width(rd(4f)))
                        Image(painterResource(R.drawable.privy_wordmark_black), null,
                            Modifier.width(rd(53f)).height(rd(16f)))
                    }
                } else {
                    Box(Modifier.size(rd(19f)).background(W.CardMuted, CircleShape)
                        .semantics { contentDescription = "Wallet not connected" },
                        contentAlignment = Alignment.Center) {
                        RefIcon("minus", 12f, W.White)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).semantics {
                    contentDescription = amount?.let { "Main wallet estimated balance $${it}, supported assets across your wallets" }
                        ?: "Main wallet balance $placeholder"
                }, verticalAlignment = Alignment.CenterVertically) {
                    if (amount != null) RefText("$", 32f, Color(0xFFACACAC))
                    RefText(amount ?: placeholder,
                        if (amount == null) 24f else if (amount.length > 11) 23f else if (amount.length > 8) 28f else 32f,
                        W.Ink, FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                }
                Row(Modifier.height(rd(44f)).clip(CircleShape).background(Color(0xFFF7F7F7))
                    .clickable(role = Role.Button, onClick = onManage).padding(horizontal = rd(11f)),
                    verticalAlignment = Alignment.CenterVertically) {
                    RefIcon("edit", 13f, W.CardMuted)
                    Spacer(Modifier.width(rd(6f)))
                    RefText("Manage", 12f, W.Ink)
                }
            }
        }
    }
}

@Composable
private fun WalletHomeAction(label: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(rd(58f)).clip(CircleShape).background(W.Cream)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (label == "Add") "Add, receive crypto" else "Send crypto to a wallet or exchange" }
        .padding(horizontal = rd(16f)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        RefIcon(icon, 19f, W.Ink)
        Spacer(Modifier.width(rd(8f)))
        RefText(label, 15f, W.Ink, FontWeight.Medium)
    }
}
