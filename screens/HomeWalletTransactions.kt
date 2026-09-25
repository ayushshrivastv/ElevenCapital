package com.elevencapital.app.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
private val TransactionIconBackground = Color(0xFF393A36)
private val TransactionRefreshForeground = TransactionSecondary

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
    onPreviewPurchaseTransaction: ((PreviewWalletPurchase) -> Unit)? = null,
) {
    val visible = homeActivityItems(records, walletTransactions, purchaseTransactions,
        previewSpcxxPosition, previewWalletState)
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
                item.journal?.let { HomeWalletTransactionRow(it, unitPricesUsd, onHistory) }
                    ?: item.chain?.let { HomeWalletChainRow(it, onHistory) }
                    ?: item.purchase?.let { HomePurchaseRow(it, stockSymbols, onHistory) }
                    ?: item.previewFundingAt?.let { HomePreviewFundingRow(it) }
                    ?: item.previewPurchase?.let { HomePreviewPurchaseRow(it, onPreviewPurchaseTransaction) }
                    ?: item.previewSpcxx?.let { HomePreviewSpcxxRow(it, onPreviewSpcxxTransaction) }
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
    val previewPurchase: PreviewWalletPurchase? = null,
)

/** A completed stock route can emit several chain transfers; show the order once. */
internal fun homeActivityItems(
    records: List<TransferJournalRecord>,
    chainTransactions: List<WalletTransaction>,
    purchases: List<CompletedPurchaseTransaction>,
    previewSpcxxPosition: PreviewSpcxxPosition? = null,
    previewWalletState: PreviewWalletState = PreviewWalletState(),
): List<HomeTransactionItem> {
    val purchaseIds = purchases.flatMap { it.relatedTransactionIds + it.transactionId }
        .map(String::lowercase).toSet()
    val visibleChain = chainTransactions.filterNot { it.transactionId.lowercase() in purchaseIds }
    val chainIds = visibleChain.map { it.transactionId.lowercase() }.toSet()
    val visibleJournal = records.filterNot { record ->
        record.transactionId?.lowercase()?.let { it in purchaseIds || it in chainIds } ?: false
    }
    val previewActivity = previewSpcxxPosition?.takeIf {
        BuildConfig.DEBUG && it.purchasedAtEpochMillis > 0L &&
            previewWalletState.purchases.none { purchase -> purchase.stockId == SPCXX_PREVIEW_STOCK_ID }
    }?.let { listOf(HomeTransactionItem(it.purchasedAtEpochMillis, previewSpcxx = it)) }.orEmpty()
    val previewFunding = previewWalletState.fundedAtEpochMillis?.takeIf { BuildConfig.DEBUG && it > 0L }
        ?.let { listOf(HomeTransactionItem(it, previewFundingAt = it)) }.orEmpty()
    val previewPurchases = previewWalletState.purchases.takeIf { BuildConfig.DEBUG }.orEmpty()
        .map { HomeTransactionItem(it.purchasedAtEpochMillis, previewPurchase = it) }
    return (visibleJournal.map { HomeTransactionItem(it.review.createdAtEpochMillis, journal = it) } +
        visibleChain.map { HomeTransactionItem(it.timestamp.toEpochMilli(), chain = it) } +
        purchases.map { HomeTransactionItem(it.timestamp.toEpochMilli(), purchase = it) } +
        previewActivity + previewFunding + previewPurchases)
        .sortedByDescending(HomeTransactionItem::timeMillis)
}

@Composable
private fun HomePreviewFundingRow(receivedAt: Long) {
    HomeTransactionRow(
        title = "Solana",
        timeMillis = receivedAt,
        amount = homeDollarAmount(PreviewWalletState.INCOMING_USD, received = true),
        label = "Incoming · ${PreviewWalletState.INCOMING_SOL.toPlainString()} SOL",
        received = true,
        estimate = false,
        onHistory = null,
    )
}

@Composable
private fun HomePreviewPurchaseRow(purchase: PreviewWalletPurchase,
    onTransaction: ((PreviewWalletPurchase) -> Unit)?) {
    HomeTransactionRow(
        title = purchase.symbol,
        timeMillis = purchase.purchasedAtEpochMillis,
        amount = homeDollarAmount(purchase.spentUsd, received = false),
        label = "Buy ${purchase.symbol}",
        received = false,
        estimate = false,
        onHistory = onTransaction?.let { action -> { action(purchase) } },
        tapActionLabel = "Open linked transaction in browser.",
    )
}

@Composable
private fun HomePreviewSpcxxRow(position: PreviewSpcxxPosition, onTransaction: () -> Unit) {
    HomeTransactionRow(
        title = "SPCXx",
        timeMillis = position.purchasedAtEpochMillis,
        amount = homeDollarAmount(BigDecimal.ONE, received = false),
        label = "Buy SPCXx",
        received = false,
        estimate = false,
        onHistory = onTransaction,
        tapActionLabel = "Open linked historical SPCXx transaction on Solscan.",
    )
}

@Composable
private fun HomeWalletTransactionRow(record: TransferJournalRecord, unitPricesUsd: Map<String, BigDecimal>,
    onHistory: () -> Unit) {
    val confirmed = record.state in setOf(TransferJournalState.CONFIRMED, TransferJournalState.FINALIZED)
    val assetKey = when (record.review.assetId) {
        WalletAssetId.SOLANA_SOL -> "SOLANA:native"
        WalletAssetId.SOLANA_USDC -> record.review.contractOrMint?.let { "SOLANA:$it" }
        WalletAssetId.ETHEREUM_ETH -> "ETHEREUM:native"
        WalletAssetId.ETHEREUM_USDC -> record.review.contractOrMint?.let { "ETHEREUM:${it.lowercase()}" }
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
private fun HomeTransactionRow(title: String, timeMillis: Long, amount: String, label: String,
    received: Boolean?, estimate: Boolean, onHistory: (() -> Unit)?,
    tapActionLabel: String = "Open transaction history.") {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(16f)))
            .then(if (onHistory != null) Modifier.clickable(role = Role.Button, onClick = onHistory)
                else Modifier)
            .semantics(mergeDescendants = true) {
                val valueDescription = if (amount == "$—") "USD value unavailable"
                    else "$amount${if (estimate) " estimated" else ""}"
                contentDescription = "$title, $valueDescription, $label." +
                    if (onHistory != null) " $tapActionLabel" else ""
            }
            .padding(vertical = rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(rd(48f)).clip(CircleShape).background(TransactionIconBackground),
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
            BasicText(amount, modifier = Modifier.fillMaxWidth(), style = TextStyle(
                color = TransactionForeground,
                fontFamily = LocalReferenceFont.current,
                fontSize = rs(if (amount.length > 22) 12f else 14f),
                lineHeight = rs(17f),
                textAlign = TextAlign.End,
            ))
            Spacer(Modifier.height(rd(2f)))
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
            Modifier.size(rd(48f)).clip(CircleShape).background(TransactionIconBackground),
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
