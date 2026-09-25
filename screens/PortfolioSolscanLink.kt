package com.elevencapital.app.screens

import com.elevencapital.app.data.LiveWalletTokenHolding
import com.elevencapital.app.data.PortfolioNetwork
import com.elevencapital.app.wallet.isSolanaPublicKey

/** Resolve a holding by its verified mint; native SOL belongs to the verified wallet account. */
internal fun portfolioTokenSolscanUrl(
    token: LiveWalletTokenHolding,
    solanaWalletAddress: String?,
): String? {
    if (token.chain != PortfolioNetwork.SOLANA || !token.assetId.startsWith("SOLANA:")) return null
    val mint = token.assetId.removePrefix("SOLANA:")
    return if (mint == "native") {
        solscanAccountUrl(solanaWalletAddress)
    } else {
        mint.takeIf(::isSolanaPublicKey)?.let { "https://solscan.io/token/$it" }
    }
}

/** Faucet SOL is on devnet and must never resolve to a mainnet balance page. */
internal fun devnetSolSolscanUrl(solanaWalletAddress: String?): String? =
    solscanAccountUrl(solanaWalletAddress, devnet = true)

private fun solscanAccountUrl(address: String?, devnet: Boolean = false): String? =
    address?.takeIf(::isSolanaPublicKey)?.let {
        "https://solscan.io/account/$it" + if (devnet) "?cluster=devnet" else ""
    }
