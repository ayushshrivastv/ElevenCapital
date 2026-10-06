package com.elevencapital.app.screens

import java.math.BigDecimal

internal enum class PredictionHistoryKind {
    TRADE_OUT, SETTLEMENT_WIN, SETTLEMENT_LOSS, REFUND, LOCAL_USDC_ADD, LOCAL_USDC_WITHDRAW,
}

internal enum class PredictionHistoryStatus { CONFIRMED }

/** A completed local practice-ledger event. */
internal data class PredictionHistoryRow(
    val id: String,
    val kind: PredictionHistoryKind,
    val status: PredictionHistoryStatus,
    val amountUsd: BigDecimal,
    val realizedPnlUsd: BigDecimal? = null,
    val poolId: Long? = null,
    val entryId: String? = null,
    val createdAtMillis: Long? = null,
    val completedAtMillis: Long? = null,
)
