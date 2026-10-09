package com.elevencapital.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.R
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
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
    previewSpcxxPosition: PreviewSpcxxPosition? = null,
    onPreviewSpcxxTransaction: () -> Unit = {},
    previewWalletState: PreviewWalletState = PreviewWalletState(),
    previewPredictionFundedUsd: BigDecimal = BigDecimal.ZERO,
    onRefreshTransactions: () -> Unit = {},
    onClearLocalUiFlow: () -> Unit = {},
    predictionTransfers: List<PredictionMainTransferItem> = emptyList(),
    communityActivity: List<CommunityUiActivity> = emptyList(),
    onPredictionBalance: () -> Unit = {},
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
    val displayedBalance = if (BuildConfig.DEBUG && previewWalletState.funded) {
        (balance ?: BigDecimal.ZERO) +
            previewWalletState.availableUsd
    } else balance
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val pageHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (showTopPanel) {
                SharedMainHeader(profileAvatarVariant, onManage, onStocks)
                Spacer(Modifier.height(rd(16f)))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = rd(26f))) {
                HomeWalletOverview(displayedBalance, walletAddress, balancePlaceholder)
            }
            Spacer(Modifier.height(rd(26f)))
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(min = (pageHeight - rd(if (showTopPanel) 338f else 244f)).coerceAtLeast(rd(300f)))
                    .clip(RoundedCornerShape(topStart = rd(30f), topEnd = rd(30f)))
                    .background(W.Panel)
                    .padding(start = rd(26f), end = rd(26f), top = rd(24f)),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f)),
                    verticalAlignment = Alignment.CenterVertically) {
                    WalletHomeAction("Add", "addCircle", Modifier.weight(1f), onReceive)
                    WalletHomeAction("Send", "arrowUpRight", Modifier.weight(1f), onSend,
                        enabled = walletConnected)
                }
                Spacer(Modifier.height(rd(26f)))
                HomeWalletTransactions(transactions, walletTransactions, purchaseTransactions,
                    walletActivityStatus, historyStorageHealthy, onHistory,
                    unitPricesUsd = unitPricesUsd, stockSymbols = stockSymbols,
                    previewSpcxxPosition = previewSpcxxPosition,
                    onPreviewSpcxxTransaction = onPreviewSpcxxTransaction,
                    previewWalletState = previewWalletState,
                    onRefreshTransactions = onRefreshTransactions,
                    onClearLocalUiFlow = onClearLocalUiFlow,
                    predictionTransfers = predictionTransfers,
                    communityActivity = communityActivity,
                    onPredictionBalance = onPredictionBalance)
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
private fun HomeWalletOverview(
    balance: BigDecimal?,
    address: String?,
    placeholder: String,
) {
    val context = LocalContext.current
    val amount = remember(balance) {
        balance?.let { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(it) }
    }
    Column(
        Modifier.fillMaxWidth().height(rd(218f))
            .clip(RoundedCornerShape(rd(28f))).background(W.BalanceCard)
            .padding(rd(18f)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(Modifier.size(rd(28f)), onLightBackground = true)
                Spacer(Modifier.width(rd(6f)))
                RefText("Eleven", 18f, W.Ink, FontWeight.SemiBold)
            }
            val addressLabel = address?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: "—"
            Row(
                Modifier.heightIn(min = rd(40f)).clip(CircleShape).background(W.Ink.copy(alpha = .56f))
                    .then(if (address != null) Modifier.clickable(role = Role.Button) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Solana wallet address", address))
                    } else Modifier)
                    .semantics { contentDescription = if (address == null) "Solana wallet address unavailable" else "Copy Solana wallet address" }
                    .padding(horizontal = rd(10f), vertical = rd(7f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefText(addressLabel, 13f, W.White, FontWeight.Medium)
                if (address != null) {
                    Spacer(Modifier.width(rd(5f)))
                    RefIcon("copy", 16f, W.White)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            RefText(
                amount?.let { "$" + it } ?: placeholder,
                if (amount == null) 24f else if (amount.length > 11) 25f else if (amount.length > 8) 31f else 37f,
                W.Ink,
                FontWeight.Medium,
                Modifier.weight(1f).semantics {
                    contentDescription = amount?.let { "Main wallet estimated balance $${it}, supported assets across your wallets" }
                        ?: "Main wallet balance $placeholder"
                },
            )
            Spacer(Modifier.width(rd(8f)))
            Row(Modifier.semantics { contentDescription = "Wallet by Privy" },
                verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.privy_square_blurple), null,
                    Modifier.size(rd(19f)))
                Spacer(Modifier.width(rd(5f)))
                Image(painterResource(R.drawable.privy_wordmark_black), null,
                    Modifier.width(rd(53f)).height(rd(16f)), colorFilter = ColorFilter.tint(W.Ink))
            }
        }
    }
}

@Composable
private fun WalletHomeAction(label: String, icon: String, modifier: Modifier, onClick: () -> Unit,
    enabled: Boolean = true) {
    Row(modifier.height(rd(58f)).clip(CircleShape)
        .background(Brush.verticalGradient(
            0f to W.Cream.copy(alpha = .94f),
            1f to W.Cream.copy(alpha = .79f),
        ))
        .border(rd(1f), Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .82f), Color.White.copy(alpha = .22f))), CircleShape)
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .then(if (enabled) Modifier.semantics { contentDescription = "Add, receive crypto" } else Modifier)
        .padding(horizontal = rd(16f)), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        RefIcon(icon, 19f, W.Ink)
        Spacer(Modifier.width(rd(8f)))
        RefText(label, 15f, W.Ink, FontWeight.Medium)
    }
}
