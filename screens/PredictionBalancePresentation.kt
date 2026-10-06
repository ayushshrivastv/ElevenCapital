package com.elevencapital.app.screens

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.util.Date

internal fun PredictionWalletLedger.lifetimePnlPercent(): BigDecimal? {
    if (!historyComplete) return null
    val totalStaked = history.asSequence().filter { row ->
        row.kind in setOf(PredictionHistoryKind.TRADE_OUT, PredictionHistoryKind.SETTLEMENT_WIN,
            PredictionHistoryKind.SETTLEMENT_LOSS)
    }.mapNotNull { row -> row.realizedPnlUsd?.let { row.amountUsd - it } }
        .fold(BigDecimal.ZERO) { total, stake -> total + stake }
    return if (totalStaked.signum() > 0) lifetimeRealizedPnlUsd.multiply(BigDecimal(100))
        .divide(totalStaked, 2, RoundingMode.HALF_UP)
    else if (lifetimeRealizedPnlUsd.signum() == 0) BigDecimal.ZERO else null
}

internal fun PredictionWalletLedger.balanceHistoryItems(): List<PredictionBalanceHistoryItem> =
    history.sortedByDescending { it.completedAtMillis ?: it.createdAtMillis ?: 0L }.map { row ->
        val pnl = row.realizedPnlUsd.takeUnless { row.kind == PredictionHistoryKind.REFUND }
        val detail = buildString {
            append(row.completedAtMillis?.let(::predictionHistoryDate)
                ?: row.createdAtMillis?.let(::predictionHistoryDate)
                ?: "Earlier activity")
        }
        val title = when (row.kind) {
            PredictionHistoryKind.LOCAL_USDC_ADD -> "Added USDC"
            PredictionHistoryKind.LOCAL_USDC_WITHDRAW -> "Withdrew USDC"
            PredictionHistoryKind.TRADE_OUT -> when (pnl?.signum()) {
                1 -> "Trade out profit"
                -1 -> "Trade out loss"
                else -> "Traded out"
            }
            PredictionHistoryKind.SETTLEMENT_WIN -> "Prediction win"
            PredictionHistoryKind.SETTLEMENT_LOSS -> "Prediction loss"
            PredictionHistoryKind.REFUND -> "Refund"
        }
        val change = pnl ?: row.amountUsd
        PredictionBalanceHistoryItem(row.id, title, detail, change.abs(),
            incoming = if (pnl != null) pnl.signum() >= 0 else row.kind in setOf(
                PredictionHistoryKind.LOCAL_USDC_ADD, PredictionHistoryKind.REFUND),
            performance = pnl != null)
    }

internal fun PredictionWalletLedger.mainTransferItems(): List<PredictionMainTransferItem> =
    history.mapNotNull { row ->
        if (row.kind !in setOf(PredictionHistoryKind.LOCAL_USDC_ADD,
                PredictionHistoryKind.LOCAL_USDC_WITHDRAW)) return@mapNotNull null
        PredictionMainTransferItem(
            id = row.id,
            occurredAtMillis = row.completedAtMillis ?: row.createdAtMillis ?: 0L,
            amountUsd = row.amountUsd,
            incomingToMain = row.kind == PredictionHistoryKind.LOCAL_USDC_WITHDRAW,
            transactionHash = null,
            localUsdc = true,
        )
    }

private fun predictionHistoryDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
