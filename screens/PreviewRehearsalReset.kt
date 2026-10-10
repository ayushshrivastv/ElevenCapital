package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig

/** Clears only account-scoped debug rehearsal data; live wallet activity is stored elsewhere. */
internal fun resetPreviewRehearsal(
    context: Context,
    userId: String,
    walletId: String,
    ledgerStore: PredictionLedgerStore,
    currentLedger: PredictionWalletLedger,
): PreviewWalletState? {
    if (!BuildConfig.DEBUG || userId.isBlank() || walletId.isBlank()) return null
    if (!PreviewSpcxxHoldingStore.remove(context, userId)) return null
    if (!ledgerStore.clear(userId, walletId)) return null
    val clearedWallet = PreviewWalletStore.clear(context, userId)
    if (clearedWallet != PreviewWalletState()) {
        ledgerStore.save(userId, walletId, currentLedger)
        return null
    }
    return clearedWallet
}
