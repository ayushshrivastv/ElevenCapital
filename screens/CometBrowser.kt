package com.elevencapital.app.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.elevencapital.app.wallet.isSolanaPublicKey
import java.net.URI

private const val COMET_PACKAGE = "ai.perplexity.comet"

/** Prefer Comet for Solscan, then use the user's browser if Comet is unavailable. */
internal fun openPortfolioSolscan(context: Context, url: String): Boolean =
    openVettedSolscan(context, url, ::isPortfolioSolscanUrl)

internal fun openPurchaseSolscan(context: Context, url: String): Boolean =
    openVettedSolscan(context, url, ::isPurchaseSolscanUrl)

private fun openVettedSolscan(context: Context, url: String, accepts: (String) -> Boolean): Boolean {
    if (!accepts(url)) return false
    val uri = Uri.parse(url)
    fun intent(packageName: String?): Intent = Intent(Intent.ACTION_VIEW, uri).apply {
        if (packageName != null) setPackage(packageName)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent(COMET_PACKAGE))
        return true
    } catch (_: ActivityNotFoundException) {
        // Comet is optional; the same vetted Solscan URL can use the default browser.
    } catch (_: SecurityException) {
        // A device policy can block explicit browser launches.
    }
    return try {
        context.startActivity(intent(null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** The transaction URL must be exactly the one generated from a canonical Solana signature. */
internal fun isPurchaseSolscanUrl(url: String): Boolean {
    val prefix = "https://solscan.io/tx/"
    return url.startsWith(prefix) && purchaseHistorySolscanUrl(url.removePrefix(prefix)) == url
}

/** Only generated Solscan portfolio pages are allowed through the external-app intent. */
internal fun isPortfolioSolscanUrl(url: String): Boolean {
    val uri = try { URI(url) } catch (_: Exception) { return false }
    if (uri.scheme != "https" || uri.host != "solscan.io" || uri.port != -1 ||
        uri.userInfo != null || uri.fragment != null ||
        (uri.rawQuery != null && uri.rawQuery != "cluster=devnet")) return false
    val segments = uri.path?.split('/') ?: return false
    return segments.size == 3 && segments[0].isEmpty() &&
        segments[1] in setOf("account", "token") && isSolanaPublicKey(segments[2])
}
