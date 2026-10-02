package com.elevencapital.app.screens

import com.elevencapital.app.BuildConfig
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.net.URI

private const val MAX_SOLANA_TOKEN_QR_LENGTH = 2_048
private const val LEGACY_TOKEN_LAUNCH_UI_PREVIEW_LINK = "eleven://token/spxtr"

/**
 * Extracts a canonical Solana address from a token QR payload without opening or fetching it.
 *
 * Accepted inputs are a raw address, a `solana:<address>` URI, an official Solana Explorer
 * address URL, or a Solscan token URL. Web URLs are intentionally limited to exact HTTPS hosts
 * and paths so a scanned lookalike URL can never become a navigation target.
 */
internal fun parseSolanaTokenQrPayload(raw: String?): String? {
    if (raw == null || raw.length > MAX_SOLANA_TOKEN_QR_LENGTH) return null
    val payload = raw.trim()
    if (payload.isEmpty()) return null

    // QR images shared before the ticker rename still resolve to the same local token.
    if (BuildConfig.DEBUG && (payload == TOKEN_LAUNCH_UI_PREVIEW_LINK ||
            payload == LEGACY_TOKEN_LAUNCH_UI_PREVIEW_LINK)) {
        return tokenLaunchUiPreviewResult().mintAddress
    }

    canonicalSolanaPublicKey(payload)?.let { return it }

    if (payload.regionMatches(0, "solana:", 0, "solana:".length, ignoreCase = true)) {
        return canonicalSolanaPublicKey(payload.substring("solana:".length))
    }

    val uri = try {
        URI(payload)
    } catch (_: Exception) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.port != -1 ||
        uri.userInfo != null || uri.rawFragment != null) return null
    // Mainnet token links are query-free. Never follow or trust a scanned URL's parameters.
    if (uri.rawQuery != null) return null

    val expectedPrefix = when {
        uri.host.equals("explorer.solana.com", ignoreCase = true) -> "/address/"
        uri.host.equals("solscan.io", ignoreCase = true) -> "/token/"
        else -> return null
    }
    val rawPath = uri.rawPath ?: return null
    if (!rawPath.startsWith(expectedPrefix)) return null
    val candidate = rawPath.removePrefix(expectedPrefix)
    if (candidate.isEmpty() || candidate.contains('/')) return null
    return canonicalSolanaPublicKey(candidate)
}
