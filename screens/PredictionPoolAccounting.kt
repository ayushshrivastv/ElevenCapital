package com.elevencapital.app.screens

/**
 * Reconcile epoch bookkeeping without guessing an unknown ETH outcome.
 * Legacy synthetic positions refund once. Live positions remain reserved until the caller fetches
 * their historical Pyth boundary observation or explicitly applies the local refund timeout.
 */
internal fun PredictionWalletLedger.advanceToPool(nowMillis: Long): PredictionWalletLedger {
    val durationMillis = PredictionPreviewRound.DURATION * 1_000L
    val activePoolId = Math.floorDiv(nowMillis, durationMillis)
    if (currentPoolId != null && currentPoolId > activePoolId) return this
    val next = refundLegacyEntries(nowMillis)
    return if (next.currentPoolId == activePoolId) next else next.beginLivePool(activePoolId)
}
