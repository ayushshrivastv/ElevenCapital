package com.elevencapital.app.screens

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.app.wallet.TransferJournalState
import com.elevencapital.app.wallet.WalletAssetRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TransferHistoryScreen(
    verifiedUserId: String,
    records: List<TransferJournalRecord>,
    walletTransactions: List<WalletTransaction> = emptyList(),
    purchaseTransactions: List<CompletedPurchaseTransaction> = emptyList(),
    walletActivityStatus: WalletActivityStatus? = null,
    stockSymbols: Map<String, String> = emptyMap(),
    storageHealthy: Boolean,
    onBack: () -> Unit,
    previewSpcxxPosition: PreviewSpcxxPosition? = null,
    onPreviewSpcxxTransaction: () -> Unit = {},
    previewWalletState: PreviewWalletState = PreviewWalletState(),
    predictionTransfers: List<PredictionMainTransferItem> = emptyList(),
    communityActivity: List<CommunityUiActivity> = emptyList(),
    onPredictionBalance: (() -> Unit)? = null,
    onClearLocalUiFlow: () -> Unit = {},
) {
    // Defend the UI boundary as well as the repository boundary: records for a previous Privy
    // identity never render during account switching or logout.
    val scoped = remember(verifiedUserId, records) {
        records.filter { it.review.userId == verifiedUserId }
            .sortedByDescending(TransferJournalRecord::updatedAtEpochMillis)
    }
    val history = remember(scoped, walletTransactions, purchaseTransactions, previewSpcxxPosition,
        previewWalletState, predictionTransfers, communityActivity) {
        homeActivityItems(scoped, walletTransactions, purchaseTransactions, previewSpcxxPosition,
            previewWalletState, predictionTransfers, communityActivity).map { item ->
            HistoryItem(
                id = item.journal?.let { "journal:${it.review.operationId.value}" }
                    ?: item.chain?.let { "chain:${it.id}" }
                    ?: item.purchase?.let { "purchase:${it.id}" }
                    ?: item.predictionTransfer?.let { "prediction:${it.id}" }
                    ?: item.communityActivity?.let { "community:${it.id}" }
                    ?: item.previewPurchase?.let { "preview:purchase:${it.purchaseId}" }
                    ?: item.previewFundingAt?.let { "preview:funding:${item.previewFundingSymbol}:$it" }
                    ?: "preview:spcxx:${item.previewSpcxx?.purchasedAtEpochMillis}",
                timeMillis = item.timeMillis,
                journal = item.journal,
                chain = item.chain,
                purchase = item.purchase,
                previewSpcxx = item.previewSpcxx,
                previewFundingAt = item.previewFundingAt,
                previewFundingSymbol = item.previewFundingSymbol,
                previewPurchase = item.previewPurchase,
                predictionTransfer = item.predictionTransfer,
                communityActivity = item.communityActivity,
            )
        }
    }
    Column(Modifier.fillMaxSize().background(P.Background).padding(horizontal = rd(22f))) {
        WalletPageHeader("Transactions", onBack)
        if (!storageHealthy) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(15f)))
                    .background(Color(0xFF3A3021)).padding(rd(14f)),
            ) {
                RefText("History needs attention", 14f, Color(0xFFFFC457), FontWeight.Bold)
                Spacer(Modifier.height(rd(5f)))
                RefText(
                    "Saved transfer data could not be verified. Sending is blocked so an earlier transfer cannot be duplicated.",
                    12.5f,
                    P.Muted,
                    maxLines = 4,
                )
            }
            Spacer(Modifier.height(rd(10f)))
        }
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RefText(if (walletActivityStatus == WalletActivityStatus.UNAVAILABLE)
                        "Transactions unavailable" else "No transactions yet", 18f, P.White, FontWeight.Bold)
                    Spacer(Modifier.height(rd(7f)))
                    RefText("Transfers and stock trades for this wallet will appear here.", 13f, P.Muted)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(rd(11f)),
            ) {
                item { Spacer(Modifier.height(rd(10f))) }
                items(history, key = HistoryItem::id) { item ->
                    item.predictionTransfer?.let {
                        PredictionTransferHistoryCard(it, onPredictionBalance, onClearLocalUiFlow)
                    }
                        ?: item.communityActivity?.let { CommunityActivityHistoryCard(it, onClearLocalUiFlow) }
                        ?: item.journal?.let { TransferHistoryCard(it) }
                        ?: item.chain?.let { ChainHistoryCard(it) }
                        ?: item.purchase?.let { PurchaseHistoryCard(it, stockSymbols) }
                        ?: item.previewFundingAt?.let {
                            PreviewFundingHistoryCard(it, item.previewFundingSymbol.orEmpty(), onClearLocalUiFlow)
                        }
                        ?: item.previewPurchase?.let { PreviewPurchaseHistoryCard(it, onClearLocalUiFlow) }
                        ?: item.previewSpcxx?.let {
                            PreviewSpcxxHistoryCard(it, onPreviewSpcxxTransaction, onClearLocalUiFlow)
                        }
                }
                item { Spacer(Modifier.height(rd(28f))) }
            }
        }
    }
}

private data class HistoryItem(
    val id: String,
    val timeMillis: Long,
    val journal: TransferJournalRecord? = null,
    val chain: WalletTransaction? = null,
    val purchase: CompletedPurchaseTransaction? = null,
    val previewSpcxx: PreviewSpcxxPosition? = null,
    val previewFundingAt: Long? = null,
    val previewFundingSymbol: String? = null,
    val previewPurchase: PreviewWalletPurchase? = null,
    val predictionTransfer: PredictionMainTransferItem? = null,
    val communityActivity: CommunityUiActivity? = null,
)

@Composable
private fun Modifier.localUiHistoryHold(onClearLocalUiFlow: () -> Unit): Modifier {
    val currentClear by rememberUpdatedState(onClearLocalUiFlow)
    return this.pointerInput(Unit) {
        detectPreviewRowPress(onTap = null, onHold = { currentClear() })
    }.semantics {
        onLongClick(label = "Clear local UI-flow data") {
            currentClear()
            true
        }
    }
}

@Composable
private fun CommunityActivityHistoryCard(item: CommunityUiActivity, onClearLocalUiFlow: () -> Unit) {
    val action = when (item.kind) {
        CommunityUiActivityKind.MINT -> "Minted"
        CommunityUiActivityKind.BUY -> "Buy"
        CommunityUiActivityKind.SELL -> "Sell"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .localUiHistoryHold(onClearLocalUiFlow)
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon(if (item.kind == CommunityUiActivityKind.MINT) "addCircle" else "arrowUpRight",
                    20f, P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText("$action ${item.symbol}", 17f, P.White, FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(rd(2f)))
                RefText(historyTime(item.occurredAtMillis), 12f, P.Muted)
            }
            item.amountUsd?.let { value ->
                val sign = if (item.kind == CommunityUiActivityKind.SELL) "+" else "−"
                RefText(sign + NumberFormat.getCurrencyInstance(Locale.US).format(
                    value.setScale(2, RoundingMode.HALF_UP)), 14f, P.White, FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("Activity", "In Eleven")
        Spacer(Modifier.height(rd(8f)))
        HistoryValue("Token", item.symbol)
        item.amountUsd?.let { value ->
            Spacer(Modifier.height(rd(8f)))
            HistoryValue(if (item.kind == CommunityUiActivityKind.SELL) "Received" else "Paid",
                NumberFormat.getCurrencyInstance(Locale.US).format(
                    value.setScale(2, RoundingMode.HALF_UP)))
        }
    }
}

@Composable
private fun PredictionTransferHistoryCard(
    transfer: PredictionMainTransferItem,
    onPredictionBalance: (() -> Unit)?,
    onClearLocalUiFlow: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(transfer.transactionHash) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    val incoming = transfer.incomingToMain
    val amount = (if (incoming) "+" else "−") +
        NumberFormat.getCurrencyInstance(Locale.US).format(
            transfer.amountUsd.setScale(2, RoundingMode.HALF_UP))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .then(if (transfer.localUsdc) Modifier.localUiHistoryHold(onClearLocalUiFlow) else Modifier)
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon(if (incoming) "deposit" else "arrowUpRight", 20f,
                    if (incoming) P.Lime else P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText(if (incoming) "From prediction wallet" else "To prediction wallet",
                    16f, P.White, FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(rd(2f)))
                RefText("${if (transfer.localUsdc) "Practice USDC · Solana" else "Solana"} · ${historyTime(transfer.occurredAtMillis)}",
                    12f, P.Muted, maxLines = 1)
            }
            RefText(amount, 14f, if (incoming) P.Lime else P.White, FontWeight.Bold)
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("From", if (incoming) "Prediction wallet" else "Main wallet")
        Spacer(Modifier.height(rd(8f)))
        HistoryValue("To", if (incoming) "Main wallet" else "Prediction wallet")
        Spacer(Modifier.height(rd(8f)))
        HistoryValue(if (transfer.localUsdc) "Asset" else "Value",
            if (transfer.localUsdc) "USDC" else "Prediction quote")
        transfer.transactionHash?.let { hash ->
            Spacer(Modifier.height(rd(10f)))
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                    clipboard.setPrimaryClip(ClipData.newPlainText("Transaction identifier", hash))
                    copied = true
                }
            }.semantics { contentDescription = "Copy full Solana transaction identifier" },
                verticalAlignment = Alignment.CenterVertically) {
                RefText("Transaction", 12f, P.Muted, modifier = Modifier.width(rd(88f)))
                Column(Modifier.weight(1f)) {
                    RefText(if (copied) "Copied" else compactTransactionId(hash),
                        12f, if (copied) P.Lime else P.White)
                    RefText(if (copied) "Full ID copied" else "Tap to copy full ID", 10.5f, P.Muted)
                }
            }
        }
        if (onPredictionBalance != null) {
            Spacer(Modifier.height(rd(12f)))
            RefText("Open prediction balance", 12f, P.White, FontWeight.Medium,
                modifier = Modifier.clickable(role = Role.Button, onClick = onPredictionBalance)
                    .semantics { contentDescription = "Open prediction balance" })
        }
    }
}

@Composable
private fun PreviewFundingHistoryCard(receivedAt: Long, symbol: String, onClearLocalUiFlow: () -> Unit) {
    val value = if (symbol == "SOL") PreviewWalletState.INCOMING_SOL_USD else PreviewWalletState.INCOMING_USDC
    val quantity = if (symbol == "SOL") PreviewWalletState.INCOMING_SOL else PreviewWalletState.INCOMING_USDC
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .localUiHistoryHold(onClearLocalUiFlow)
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon("deposit", 20f, P.Lime)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText(symbol, 17f, P.White, FontWeight.Bold)
                Spacer(Modifier.height(rd(2f)))
                RefText("Solana · ${historyTime(receivedAt)}", 12f, P.Muted)
            }
            RefText("+\$${value.setScale(2).toPlainString()}", 14f, P.Lime, FontWeight.Bold)
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("Incoming", "${quantity.stripTrailingZeros().toPlainString()} $symbol")
    }
}

@Composable
private fun PreviewPurchaseHistoryCard(purchase: PreviewWalletPurchase, onClearLocalUiFlow: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .localUiHistoryHold(onClearLocalUiFlow)
        .semantics { contentDescription = "Buy ${purchase.symbol} details" }
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon("arrowUpRight", 20f, P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText("Buy ${purchase.symbol}", 17f, P.White, FontWeight.Bold)
                Spacer(Modifier.height(rd(2f)))
                RefText(historyTime(purchase.purchasedAtEpochMillis), 12f, P.Muted)
            }
            RefText("−\$${purchase.spentUsd.setScale(2).toPlainString()}", 14f, P.White, FontWeight.Bold)
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("From", "USDC on Solana")
        purchase.receivedTokens?.let { quantity ->
            Spacer(Modifier.height(rd(8f)))
            HistoryValue("Estimated output", "${quantity.stripTrailingZeros().toPlainString()} ${purchase.symbol}")
        }
    }
}

@Composable
private fun PreviewSpcxxHistoryCard(
    position: PreviewSpcxxPosition,
    onHistoricalTransaction: () -> Unit,
    onClearLocalUiFlow: () -> Unit,
) {
    val currentHistoricalTransaction by rememberUpdatedState(onHistoricalTransaction)
    val currentClear by rememberUpdatedState(onClearLocalUiFlow)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .pointerInput(position.purchasedAtEpochMillis) {
            detectPreviewRowPress(
                onTap = { currentHistoricalTransaction() },
                onHold = { currentClear() },
            )
        }
        .semantics {
            contentDescription = "Buy SPCXx, open linked historical transaction on Solscan"
            onClick(label = "Open linked historical transaction") { currentHistoricalTransaction(); true }
            onLongClick(label = "Clear local UI-flow data") { currentClear(); true }
        }
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon("arrowUpRight", 20f, P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText("Buy SPCXx", 17f, P.White, FontWeight.Bold)
                Spacer(Modifier.height(rd(2f)))
                RefText(historyTime(position.purchasedAtEpochMillis), 12f, P.Muted)
            }
            RefText("−$1.00", 14f, P.White, FontWeight.Bold)
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("From", "SOL on Solana")
        Spacer(Modifier.height(rd(8f)))
        HistoryValue("Received", "$0.114 worth of SPCXx")
    }
}

@Composable
private fun ChainHistoryCard(transaction: WalletTransaction) {
    val received = transaction.direction == WalletTransactionDirection.RECEIVE
    val amount = "${if (received) "+" else "−"}${transaction.amount.stripTrailingZeros().toPlainString()} ${transaction.assetSymbol}"
    val context = LocalContext.current
    var copied by remember(transaction.transactionId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon(if (received) "deposit" else "arrowUpRight", 20f,
                    if (received) P.Lime else P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText(amount, 17f, P.White, FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(rd(2f)))
                RefText("${transaction.chain.displayName} · ${historyTime(transaction.timestamp.toEpochMilli())}", 12f, P.Muted)
            }
            RefText(if (received) "Received" else "Sent", 12f,
                if (received) P.Lime else P.White, FontWeight.Bold)
        }
        transaction.counterparty?.let { counterparty ->
            Spacer(Modifier.height(rd(12f)))
            HistoryValue(if (received) "From" else "To", compactAddress(counterparty))
        }
        Spacer(Modifier.height(rd(9f)))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
            context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Transaction identifier", transaction.transactionId))
                copied = true
            }
        }.semantics { contentDescription = "Copy full transaction identifier" },
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Transaction", 12f, P.Muted, modifier = Modifier.width(rd(88f)))
            Column(Modifier.weight(1f)) {
                RefText(if (copied) "Copied" else compactTransactionId(transaction.transactionId),
                    12f, if (copied) P.Lime else P.White)
                RefText(if (copied) "Full ID copied" else "Tap to copy full ID", 10.5f, P.Muted)
            }
        }
    }
}

@Composable
private fun PurchaseHistoryCard(transaction: CompletedPurchaseTransaction, stockSymbols: Map<String, String>) {
    val symbol = stockSymbols[transaction.stockId] ?: transaction.stockId.substringAfterLast(':')
    val action = when (transaction.side) {
        CompletedPurchaseSide.BUY -> "Buy"
        CompletedPurchaseSide.SELL -> "Sell"
        null -> "Trade"
    }
    val received = transaction.side == CompletedPurchaseSide.SELL
    val amount = historyUsdAmount(transaction.valueUsd, transaction.side)
    val solscanUrl = remember(transaction.transactionId) { purchaseHistorySolscanUrl(transaction.transactionId) }
    val context = LocalContext.current
    var copied by remember(transaction.transactionId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
        .padding(horizontal = rd(16f), vertical = rd(15f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon(if (received) "deposit" else "arrowUpRight", 20f,
                    if (received) P.Lime else P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText("$action $symbol", 17f, P.White, FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(rd(2f)))
                RefText(historyTime(transaction.timestamp.toEpochMilli()), 12f, P.Muted)
            }
            RefText(amount, 14f, P.White, FontWeight.Bold)
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("Status", "Completed")
        transaction.receivedAmount?.let { quantity ->
            Spacer(Modifier.height(rd(8f)))
            HistoryValue("Received", quantity.stripTrailingZeros().toPlainString())
        }
        Spacer(Modifier.height(rd(8f)))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
            context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Transaction identifier", transaction.transactionId))
                copied = true
            }
        }.semantics { contentDescription = "Copy full transaction identifier" },
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Transaction", 12f, P.Muted, modifier = Modifier.width(rd(88f)))
            Column(Modifier.weight(1f)) {
                RefText(if (copied) "Copied" else compactTransactionId(transaction.transactionId),
                    12f, if (copied) P.Lime else P.White)
                RefText(if (copied) "Full ID copied" else "Tap to copy full ID", 10.5f, P.Muted)
            }
        }
        solscanUrl?.let { url ->
            Spacer(Modifier.height(rd(13f)))
            Box(
                Modifier.fillMaxWidth().height(rd(42f)).clip(CircleShape).background(P.White)
                    .clickable(role = Role.Button) { openPurchaseSolscan(context, url) }
                    .semantics { contentDescription = "View completed stock trade on Solscan" },
                contentAlignment = Alignment.Center,
            ) {
                RefText("View on Solscan", 13f, P.Background, FontWeight.SemiBold)
            }
        }
    }
}

private fun historyUsdAmount(value: BigDecimal?, side: CompletedPurchaseSide?): String {
    if (value == null || value.signum() < 0) return "$—"
    val amount = NumberFormat.getCurrencyInstance(Locale.US).format(value.setScale(2, RoundingMode.HALF_UP))
    val sign = when (side) { CompletedPurchaseSide.BUY -> "−"; CompletedPurchaseSide.SELL -> "+"; null -> "" }
    return "$sign$amount"
}

@Composable
private fun TransferHistoryCard(record: TransferJournalRecord) {
    val appearance = historyAppearance(record.state)
    val asset = WalletAssetRegistry.asset(record.review.assetId)
    val time = remember(record.updatedAtEpochMillis) { historyTime(record.updatedAtEpochMillis) }
    val context = LocalContext.current
    var copied by remember(record.transactionId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
            .padding(horizontal = rd(16f), vertical = rd(15f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(40f)).clip(CircleShape).background(P.Chip),
                contentAlignment = Alignment.Center) {
                RefIcon("arrowUpRight", 20f, P.White)
            }
            Spacer(Modifier.width(rd(11f)))
            Column(Modifier.weight(1f)) {
                RefText("${record.review.displayAmount} ${record.review.assetSymbol}", 18f, P.White, FontWeight.Bold)
                Spacer(Modifier.height(rd(2f)))
                RefText("${asset.network.displayName} Mainnet · $time", 12f, P.Muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(rd(8f)).clip(CircleShape).background(appearance.color))
                Spacer(Modifier.width(rd(6f)))
                RefText(appearance.label, 12f, appearance.color, FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(rd(13f)))
        HistoryValue("To", compactAddress(record.review.recipient.value))
        record.transactionId?.let {
            Spacer(Modifier.height(rd(8f)))
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                        clipboard.setPrimaryClip(ClipData.newPlainText("Transaction identifier", it))
                        copied = true
                    }
                }.semantics { contentDescription = "Copy full transaction identifier" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefText("Transaction", 12f, P.Muted, modifier = Modifier.width(rd(88f)))
                Column(Modifier.weight(1f)) {
                    RefText(if (copied) "Copied" else compactTransactionId(it), 12f,
                        if (copied) P.Lime else P.White)
                    RefText(if (copied) "Full ID copied" else "Tap to copy full ID", 10.5f, P.Muted)
                }
            }
        }
        Spacer(Modifier.height(rd(12f)))
        BasicText(
            appearance.message,
            style = TextStyle(
                color = P.Muted,
                fontSize = rs(12.5f),
                lineHeight = rs(17f),
                fontFamily = LocalReferenceFont.current,
            ),
        )
    }
}

@Composable
private fun HistoryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        RefText(label, 12f, P.Muted, modifier = Modifier.width(rd(88f)))
        RefText(value, 12f, P.White, modifier = Modifier.weight(1f))
    }
}

private data class HistoryAppearance(val label: String, val message: String, val color: Color)

private fun historyAppearance(state: TransferJournalState): HistoryAppearance = when (state) {
    TransferJournalState.COMMITTING,
    TransferJournalState.RELEASING,
    TransferJournalState.SUBMITTING,
    TransferJournalState.AMBIGUOUS -> HistoryAppearance(
        "Needs attention",
        "No transaction identifier was safely recorded. Do not resend from this wallet until this attempt is verified.",
        Color(0xFFFFC457),
    )
    TransferJournalState.DEFINITELY_NOT_BROADCAST -> HistoryAppearance(
        "Not sent",
        "Privy confirmed that this attempt was not broadcast.",
        P.Muted,
    )
    TransferJournalState.BROADCAST,
    TransferJournalState.UNKNOWN -> HistoryAppearance(
        "Awaiting network",
        "The transaction identifier is saved. Eleven Capital will keep checking it; do not resend it.",
        Color(0xFFFFC457),
    )
    TransferJournalState.PENDING -> HistoryAppearance(
        "Pending",
        "The network has seen this transfer and is processing it.",
        Color(0xFFFFC457),
    )
    TransferJournalState.CONFIRMED -> HistoryAppearance(
        "Confirmed",
        "The transfer is included and waiting for finality.",
        Color(0xFF8CCBFF),
    )
    TransferJournalState.FINALIZED -> HistoryAppearance(
        "Finalized",
        "The network finalized this transfer.",
        P.Lime,
    )
    TransferJournalState.FAILED_NONFINAL -> HistoryAppearance(
        "Failed · confirming",
        "Execution failed in a non-final block. Eleven Capital will keep checking through finality; do not resend.",
        Color(0xFFFF7088),
    )
    TransferJournalState.FAILED_FINALIZED -> HistoryAppearance(
        "Failed on network",
        "The network finalized this failed execution. Review the transaction before taking another action.",
        Color(0xFFFF7088),
    )
}

private fun compactTransactionId(value: String): String = when {
    value.length <= 24 -> value
    else -> value.take(12) + "…" + value.takeLast(10)
}

private fun historyTime(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd MMM · h:mm a", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
}.getOrDefault("Saved")
