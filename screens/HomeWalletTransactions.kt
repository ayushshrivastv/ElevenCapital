package com.elevencapital.app.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.PurchaseUsdBasis
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.app.wallet.TransferJournalState
import com.elevencapital.app.wallet.WalletAssetId
import com.elevencapital.app.wallet.WalletAssetRegistry
import com.elevencapital.app.wallet.homeWalletTransferStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale

// The neutral palette follows the supplied wallet transaction reference.
private val TransactionForeground = Color(0xFFF6F6EC)
private val TransactionSecondary = Color(0xFFAAAAA2)
private val TransactionIconBackground = Color.White.copy(alpha = .15f)
private val TransactionIconEdge = Color.White.copy(alpha = .10f)
private val TransactionRefreshForeground = TransactionSecondary

/** Main/prediction wallet movement, with a transaction hash only for confirmed chain transfers. */
data class PredictionMainTransferItem(
    val id: String,
    val occurredAtMillis: Long,
    val amountUsd: BigDecimal,
    val incomingToMain: Boolean,
    val transactionHash: String?,
    val localUsdc: Boolean = false,
)

/** Confirmed public wallet activity and completed, authenticated orders for this Privy user. */
@Composable
fun HomeWalletTransactions(
    records: List<TransferJournalRecord>,
    walletTransactions: List<WalletTransaction>,
    purchaseTransactions: List<CompletedPurchaseTransaction>,
    walletActivityStatus: WalletActivityStatus?,
    storageHealthy: Boolean,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    /** Current USD prices keyed by verified wallet asset ID, used for local pending sends. */
    unitPricesUsd: Map<String, BigDecimal> = emptyMap(),
    stockSymbols: Map<String, String> = emptyMap(),
    previewSpcxxPosition: PreviewSpcxxPosition? = null,
    onPreviewSpcxxTransaction: () -> Unit = {},
    previewWalletState: PreviewWalletState = PreviewWalletState(),
    onRefreshTransactions: () -> Unit = {},
    onClearLocalUiFlow: () -> Unit = {},
    predictionTransfers: List<PredictionMainTransferItem> = emptyList(),
    communityActivity: List<CommunityUiActivity> = emptyList(),
    onPredictionBalance: () -> Unit = {},
) {
    val visible = homeActivityItems(records, walletTransactions, purchaseTransactions,
        previewSpcxxPosition, previewWalletState, predictionTransfers, communityActivity)
    var refreshTaps by remember { mutableIntStateOf(0) }
    val refreshRotation by animateFloatAsState(
        targetValue = refreshTaps * 360f,
        animationSpec = tween(durationMillis = 650),
        label = "Transaction refresh rotation",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = rd(44f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.height(rd(44f)), contentAlignment = Alignment.CenterStart) {
                RefText("Transactions", 13f, TransactionSecondary)
            }
            Row(
                Modifier.height(rd(44f))
                    .clickable(role = Role.Button) {
                        refreshTaps++
                        onRefreshTransactions()
                    }
                    .semantics { contentDescription = "Refresh transactions" }
                    .padding(start = rd(8f), end = rd(3f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(rd(6f)),
            ) {
                TransactionRefreshIcon(
                    color = TransactionRefreshForeground,
                    modifier = Modifier.graphicsLayer { rotationZ = refreshRotation },
                )
                RefText("Refresh", 13f, TransactionRefreshForeground)
            }
        }
        when {
            visible.isNotEmpty() -> visible.take(5).forEach { item ->
                item.predictionTransfer?.let {
                    HomePredictionTransferRow(it, onPredictionBalance, onClearLocalUiFlow)
                }
                    ?: item.communityActivity?.let { HomeCommunityActivityRow(it, onHistory, onClearLocalUiFlow) }
                    ?: item.journal?.let { HomeWalletTransactionRow(it, unitPricesUsd, onHistory) }
                    ?: item.chain?.let { HomeWalletChainRow(it, onHistory) }
                    ?: item.purchase?.let { HomePurchaseRow(it, stockSymbols, onHistory) }
                    ?: item.previewFundingAt?.let {
                        HomePreviewFundingRow(it, item.previewFundingSymbol.orEmpty(), onClearLocalUiFlow)
                    }
                    ?: item.previewPurchase?.let { HomePreviewPurchaseRow(it, onClearLocalUiFlow) }
                    ?: item.previewSpcxx?.let {
                        HomePreviewSpcxxRow(it, onPreviewSpcxxTransaction, onClearLocalUiFlow)
                    }
            }
            !storageHealthy -> TransactionEmptyRow(
                title = "History unavailable",
                description = "Open history to review saved transfers.",
                onHistory = onHistory,
            )
            walletActivityStatus == WalletActivityStatus.UNAVAILABLE -> TransactionEmptyRow(
                title = "Transactions unavailable",
                description = "Wallet activity will refresh automatically.",
                onHistory = onHistory,
            )
            else -> TransactionEmptyRow(
                title = if (walletActivityStatus == WalletActivityStatus.OK) "No transactions yet" else "Transactions",
                description = "Transfers and stock trades for this wallet will appear here.",
                onHistory = onHistory,
            )
        }
    }
}

@Composable
private fun TransactionRefreshIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(rd(16f))) {
        val side = size.minDimension
        val left = side * 0.22f
        val diameter = side * 0.56f
        val stroke = side * 0.11f
        val arcSize = Size(diameter, diameter)
        drawArc(color, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(left, left), size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawArc(color, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(left, left), size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawPath(Path().apply {
            moveTo(side * 0.13f, side * 0.48f)
            lineTo(side * 0.33f, side * 0.38f)
            lineTo(side * 0.23f, side * 0.28f)
            close()
        }, color)
        drawPath(Path().apply {
            moveTo(side * 0.87f, side * 0.52f)
            lineTo(side * 0.67f, side * 0.62f)
            lineTo(side * 0.77f, side * 0.72f)
            close()
        }, color)
    }
}

internal data class HomeTransactionItem(
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

/** A completed stock route can emit several chain transfers; show the order once. */
internal fun homeActivityItems(
    records: List<TransferJournalRecord>,
    chainTransactions: List<WalletTransaction>,
    purchases: List<CompletedPurchaseTransaction>,
    previewSpcxxPosition: PreviewSpcxxPosition? = null,
    previewWalletState: PreviewWalletState = PreviewWalletState(),
    predictionTransfers: List<PredictionMainTransferItem> = emptyList(),
    communityActivity: List<CommunityUiActivity> = emptyList(),
): List<HomeTransactionItem> {
    val purchaseIds = purchases.flatMap { it.relatedTransactionIds + it.transactionId }
        .map(String::lowercase).toSet()
    val visibleChain = chainTransactions.filterNot { it.transactionId.lowercase() in purchaseIds }
    val chainIds = visibleChain.map { it.transactionId.lowercase() }.toSet()
    val visibleJournal = records.filterNot { record ->
        record.transactionId?.lowercase()?.let { it in purchaseIds || it in chainIds } ?: false
    }
    val previewActivity = previewSpcxxPosition?.takeIf {
        BuildConfig.DEBUG && it.purchasedAtEpochMillis > 0L
    }?.let { listOf(HomeTransactionItem(it.purchasedAtEpochMillis, previewSpcxx = it)) }.orEmpty()
    val previewFunding = previewWalletState.fundedAtEpochMillis?.takeIf { BuildConfig.DEBUG && it > 0L }
        ?.let { fundedAt -> listOf(
            HomeTransactionItem(fundedAt, previewFundingAt = fundedAt, previewFundingSymbol = "SOL"),
        ) }.orEmpty()
    val previewPurchases = previewWalletState.purchases.takeIf { BuildConfig.DEBUG }.orEmpty()
        .map { HomeTransactionItem(it.purchasedAtEpochMillis, previewPurchase = it) }
    return (visibleJournal.map { HomeTransactionItem(it.review.createdAtEpochMillis, journal = it) } +
        visibleChain.map { HomeTransactionItem(it.timestamp.toEpochMilli(), chain = it) } +
        purchases.map { HomeTransactionItem(it.timestamp.toEpochMilli(), purchase = it) } +
        previewActivity + previewFunding + previewPurchases +
        communityActivity.takeIf { BuildConfig.DEBUG }.orEmpty()
            .map { HomeTransactionItem(it.occurredAtMillis, communityActivity = it) } +
        predictionTransfers.map { HomeTransactionItem(it.occurredAtMillis, predictionTransfer = it) })
        .sortedByDescending(HomeTransactionItem::timeMillis)
}

@Composable
private fun HomeCommunityActivityRow(
    item: CommunityUiActivity,
    onHistory: () -> Unit,
    onClearLocalUiFlow: () -> Unit,
) {
    val action = when (item.kind) {
        CommunityUiActivityKind.MINT -> "Minted"
        CommunityUiActivityKind.BUY -> "Buy"
        CommunityUiActivityKind.SELL -> "Sell"
    }
    HomeTransactionRow(
        title = item.symbol,
        timeMillis = item.occurredAtMillis,
        amount = item.amountUsd?.let { homeDollarAmount(it,
            received = item.kind == CommunityUiActivityKind.SELL) },
        label = action,
        received = null,
        estimate = false,
        onHistory = onHistory,
        onHold = onClearLocalUiFlow,
    )
}

@Composable
private fun HomePredictionTransferRow(
    item: PredictionMainTransferItem,
    onPredictionBalance: () -> Unit,
    onClearLocalUiFlow: () -> Unit,
) {
    HomeTransactionRow(
        title = "Prediction wallet",
        timeMillis = item.occurredAtMillis,
        amount = homeDollarAmount(item.amountUsd, received = item.incomingToMain),
        label = if (item.localUsdc) "Practice USDC · Solana" else "Solana",
        received = item.incomingToMain,
        estimate = false,
        onHistory = onPredictionBalance,
        tapActionLabel = "Open prediction balance.",
        onHold = onClearLocalUiFlow.takeIf { item.localUsdc },
    )
}

@Composable
private fun HomePreviewFundingRow(receivedAt: Long, symbol: String, onClearLocalUiFlow: () -> Unit) {
    val value = if (symbol == "SOL") PreviewWalletState.INCOMING_SOL_USD else PreviewWalletState.INCOMING_USDC
    val quantity = if (symbol == "SOL") PreviewWalletState.INCOMING_SOL else PreviewWalletState.INCOMING_USDC
    HomeTransactionRow(
        title = symbol,
        timeMillis = receivedAt,
        amount = homeDollarAmount(value, received = true),
        label = "${quantity.stripTrailingZeros().toPlainString()} $symbol · Solana",
        received = true,
        estimate = false,
        onHistory = null,
        onHold = onClearLocalUiFlow,
    )
}

@Composable
private fun HomePreviewPurchaseRow(purchase: PreviewWalletPurchase, onClearLocalUiFlow: () -> Unit) {
    HomeTransactionRow(
        title = purchase.symbol,
        timeMillis = purchase.purchasedAtEpochMillis,
        amount = homeDollarAmount(purchase.spentUsd, received = false),
        label = "Buy ${purchase.symbol}",
        received = false,
        estimate = false,
        onHistory = null,
        onHold = onClearLocalUiFlow,
    )
}

@Composable
private fun HomePreviewSpcxxRow(
    position: PreviewSpcxxPosition,
    onTransaction: () -> Unit,
    onClearLocalUiFlow: () -> Unit,
) {
    HomeTransactionRow(
        title = "SPCXx",
        timeMillis = position.purchasedAtEpochMillis,
        amount = homeDollarAmount(BigDecimal.ONE, received = false),
        label = "Buy SPCXx",
        received = false,
        estimate = false,
        onHistory = onTransaction,
        tapActionLabel = "Open linked historical SPCXx transaction on Solscan.",
        onHold = onClearLocalUiFlow,
    )
}

@Composable
private fun HomeWalletTransactionRow(record: TransferJournalRecord, unitPricesUsd: Map<String, BigDecimal>,
    onHistory: () -> Unit) {
    val confirmed = record.state in setOf(TransferJournalState.CONFIRMED, TransferJournalState.FINALIZED)
    val assetKey = when (record.review.assetId) {
        WalletAssetId.SOLANA_SOL -> "SOLANA:native"
        WalletAssetId.SOLANA_USDC -> record.review.contractOrMint?.let { "SOLANA:$it" }
    }
    val estimate = if (confirmed && assetKey != null) unitPricesUsd[assetKey]
        ?.let { price -> runCatching { BigDecimal(record.review.displayAmount).multiply(price) }.getOrNull() }
        else null
    HomeTransactionRow(
        title = WalletAssetRegistry.asset(record.review.assetId).network.displayName,
        timeMillis = record.review.createdAtEpochMillis,
        amount = homeDollarAmount(estimate, received = false),
        label = if (confirmed) "Outgoing" else homeWalletTransferStatus(record.state),
        received = false,
        estimate = true,
        onHistory = onHistory,
    )
}

@Composable
private fun HomeWalletChainRow(transaction: WalletTransaction, onHistory: () -> Unit) {
    val received = transaction.direction == WalletTransactionDirection.RECEIVE
    HomeTransactionRow(
        title = transaction.chain.displayName,
        timeMillis = transaction.timestamp.toEpochMilli(),
        amount = transaction.valueUsd?.let { homeDollarAmount(it, received) }
            ?: "${if (received) "+" else "−"}${transaction.amount.stripTrailingZeros().toPlainString()} ${transaction.assetSymbol}",
        label = if (received) "Incoming" else "Outgoing",
        received = received,
        estimate = transaction.valueUsd != null,
        onHistory = onHistory,
    )
}

@Composable
private fun HomePurchaseRow(transaction: CompletedPurchaseTransaction, stockSymbols: Map<String, String>,
    onHistory: () -> Unit) {
    val symbol = stockSymbols[transaction.stockId] ?: transaction.stockId.substringAfterLast(':')
    val received = when (transaction.side) {
        CompletedPurchaseSide.BUY -> false
        CompletedPurchaseSide.SELL -> true
        null -> null
    }
    HomeTransactionRow(
        title = symbol,
        timeMillis = transaction.timestamp.toEpochMilli(),
        amount = homeDollarAmount(transaction.valueUsd, received),
        label = "${transaction.side?.name?.lowercase()?.replaceFirstChar(Char::uppercaseChar) ?: "Trade"} $symbol",
        received = received,
        estimate = transaction.usdBasis != PurchaseUsdBasis.STABLECOIN_EXACT,
        onHistory = onHistory,
    )
}

@Composable
private fun HomeTransactionRow(title: String, timeMillis: Long, amount: String?, label: String,
    received: Boolean?, estimate: Boolean, onHistory: (() -> Unit)?,
    tapActionLabel: String = "Open transaction history.",
    onHold: (() -> Unit)? = null) {
    val currentHistory by rememberUpdatedState(onHistory)
    val currentHold by rememberUpdatedState(onHold)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(16f)))
            .then(when {
                onHold != null -> Modifier.pointerInput(title, timeMillis) {
                    detectPreviewRowPress(
                        onTap = { currentHistory?.invoke() },
                        onHold = { currentHold?.invoke() },
                    )
                }
                onHistory != null -> Modifier.clickable(role = Role.Button, onClick = onHistory)
                else -> Modifier
            })
            .semantics(mergeDescendants = true) {
                val valueDescription = when (amount) {
                    null -> null
                    "$—" -> "USD value unavailable"
                    else -> "$amount${if (estimate) " estimated" else ""}"
                }
                contentDescription = "$title, ${valueDescription?.let { "$it, " }.orEmpty()}$label." +
                    if (onHistory != null) " $tapActionLabel" else ""
                if (onHold != null) {
                    if (onHistory != null) onClick(label = tapActionLabel) {
                        currentHistory?.invoke()
                        true
                    }
                    onLongClick(label = "Clear local UI-flow data") {
                        currentHold?.invoke()
                        true
                    }
                }
            }
            .padding(vertical = rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(rd(48f)).clip(CircleShape).background(TransactionIconBackground)
            .border(rd(.75f), TransactionIconEdge, CircleShape),
            contentAlignment = Alignment.Center) {
            if (received == true) RefIcon("deposit", 23f, TransactionSecondary)
            else RefIcon("arrowUpRight", 23f, TransactionSecondary)
        }
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(title, 14f, TransactionForeground, maxLines = 1)
            Spacer(Modifier.height(rd(2f)))
            RefText(homeTransactionTime(timeMillis), 11.5f, TransactionSecondary)
        }
        Spacer(Modifier.width(rd(10f)))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            if (amount != null) {
                BasicText(amount, modifier = Modifier.fillMaxWidth(), style = TextStyle(
                    color = TransactionForeground,
                    fontFamily = LocalReferenceFont.current,
                    fontSize = rs(if (amount.length > 22) 12f else 14f),
                    lineHeight = rs(17f),
                    textAlign = TextAlign.End,
                ))
                Spacer(Modifier.height(rd(2f)))
            }
            RefText(label, 11.5f, TransactionSecondary)
        }
    }
}

private fun homeDollarAmount(valueUsd: BigDecimal?, received: Boolean?): String {
    if (valueUsd == null || valueUsd.signum() < 0) return "$—"
    val sign = when (received) { true -> "+"; false -> "−"; null -> "" }
    val rounded = valueUsd.setScale(2, RoundingMode.HALF_UP)
    val currency = NumberFormat.getCurrencyInstance(Locale.US).format(rounded)
    return "$sign$currency"
}

@Composable
private fun TransactionEmptyRow(title: String, description: String, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(16f)))
            .clickable(role = Role.Button, onClick = onHistory).padding(vertical = rd(12f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(rd(48f)).clip(CircleShape).background(TransactionIconBackground)
                .border(rd(.75f), TransactionIconEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RefIcon("history", 21f, TransactionSecondary)
        }
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(title, 14f, TransactionForeground)
            Spacer(Modifier.height(rd(4f)))
            RefText(description, 12f, TransactionSecondary, maxLines = 2)
        }
    }
}

private fun homeTransactionTime(epochMillis: Long): String = runCatching {
    val zone = ZoneId.systemDefault()
    val instant = Instant.ofEpochMilli(epochMillis)
    val date = instant.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val day = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.US).format(date)
    }
    val time = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone).format(instant)
    "$day, $time"
}.getOrDefault("Saved")
