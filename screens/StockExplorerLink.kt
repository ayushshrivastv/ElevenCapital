package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchaseRouteState
import com.elevencapital.app.purchase.PurchaseStatus
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.wallet.isSolanaPublicKey
import java.net.URI

/** A link is exposed only after the deployment address has passed chain-specific validation. */
internal data class StockExplorerLink(
    val networkLabel: String,
    val explorerLabel: String,
    val address: String,
    val url: String,
)

internal fun exactStockExplorerLink(network: String?, address: String?): StockExplorerLink? {
    if (!network.equals("solana", ignoreCase = true) || address == null || !isSolanaPublicKey(address)) return null
    return StockExplorerLink(
        networkLabel = "Solana",
        explorerLabel = "Solscan",
        address = address,
        url = "https://solscan.io/address/$address",
    )
}

/** Only a confirmed purchase signature opens a Solscan transaction page. */
internal fun completedPurchaseSolscanUrl(status: PurchaseStatus?): String? {
    if (status?.state != PurchaseRouteState.COMPLETED) return null
    val signature = status.solanaTransactionSignature ?: return null
    return if (isCanonicalSolanaSignature(signature)) "https://solscan.io/tx/$signature" else null
}

/** Defense in depth before handing a provider-owned page to the platform URI handler. */
internal fun safeStockInformationUrl(value: String?): String? {
    if (value == null || value.length > 2_048) return null
    val uri = try { URI(value) } catch (_: Exception) { return null }
    return value.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null }
}
