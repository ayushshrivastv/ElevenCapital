package com.elevencapital.app.screens

import com.elevencapital.app.BuildConfig

/**
 * Hidden debug-build rehearsal requested for checking the complete launch UI without a wallet,
 * backend, or on-chain write. Nothing in this file is reachable from a release APK.
 */
internal fun isTokenLaunchUiPreview(symbol: String): Boolean =
    BuildConfig.DEBUG && symbol.trim().equals(TOKEN_LAUNCH_UI_PREVIEW_SYMBOL, ignoreCase = true)

internal fun tokenLaunchUiPreviewResult(): ConfirmedTokenLaunch = ConfirmedTokenLaunch(
    mintAddress = TOKEN_LAUNCH_UI_PREVIEW_MINT,
    signature = TOKEN_LAUNCH_UI_PREVIEW_SIGNATURE,
    poolAddress = TOKEN_LAUNCH_UI_PREVIEW_POOL,
)

internal const val TOKEN_LAUNCH_UI_PREVIEW_SYMBOL = "CHTMS"
internal const val TOKEN_LAUNCH_UI_PREVIEW_NAME = "Christmas"
internal const val TOKEN_LAUNCH_UI_PREVIEW_ARTWORK_URI =
    "android.resource://com.elevencapital.app/drawable/token_spxtr_christmas"
/** App-scoped QR payload: a generic wallet must not mistake the local preview mint for a payee. */
internal const val TOKEN_LAUNCH_UI_PREVIEW_LINK = "eleven://token/chtms"

// Canonical Solana values keep QR and compact-address rendering identical to a confirmed launch.
// They are never persisted as a receipt or submitted to a network.
private const val TOKEN_LAUNCH_UI_PREVIEW_MINT = "Xs3oZwbHvqis4NYcf4YKWmEia2eC84wSiVrcYcTqpH8"
private const val TOKEN_LAUNCH_UI_PREVIEW_POOL = "D8cy77BBepLMngZx6ZukaTff5hCt1HrWyKk3Hnd9oitf"
private const val TOKEN_LAUNCH_UI_PREVIEW_SIGNATURE =
    "5ru4KfnupVM23WDZy13HwHrkTBBwtgCzuifaLo7RFzwv2WYEUMmiqAc4J2GYeTNbJgTVu3YKUU1ArkgnFnJmLLDK"
