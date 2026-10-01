package com.elevencapital.app.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI

internal data class TokenLaunchExplorerDestination(
    val url: String,
    val contentDescription: String,
    val isSolscan: Boolean,
)

/** The local launch preview has no Solana mint or transaction to open on Solscan. */
internal fun tokenLaunchExplorerDestination(
    draft: LaunchTokenDraft,
    result: ConfirmedTokenLaunch?,
    backendBaseUrl: String,
): TokenLaunchExplorerDestination? {
    val mint = result?.mintAddress ?: return null
    if (isTokenLaunchUiPreview(draft.symbol) && mint == tokenLaunchUiPreviewResult().mintAddress) {
        val page = backendExplorerPageUrl(backendBaseUrl) ?: return null
        return TokenLaunchExplorerDestination(page, "View token page", isSolscan = false)
    }
    val solscan = purchaseHistorySolscanUrl(result.signature) ?: return null
    return TokenLaunchExplorerDestination(solscan, "Check Transaction on Solscan", isSolscan = true)
}

/** Keep the preview page on the configured backend origin; never accept a user-authored URL. */
internal fun backendExplorerPageUrl(backendBaseUrl: String): String? {
    val base = try { URI(backendBaseUrl.trim()) } catch (_: Exception) { return null }
    if (base.host.isNullOrBlank() || base.userInfo != null || base.rawQuery != null ||
        base.rawFragment != null || (base.rawPath != null && base.rawPath != "" && base.rawPath != "/")) {
        return null
    }
    if (base.scheme != "https" &&
        !(base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "::1"))) {
        return null
    }
    return try { base.resolve("/explorer").toASCIIString() } catch (_: Exception) { null }
}

internal fun openTokenLaunchExplorer(context: Context, destination: TokenLaunchExplorerDestination): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(destination.url)).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
