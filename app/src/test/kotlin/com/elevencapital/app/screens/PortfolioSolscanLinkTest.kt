package com.elevencapital.app.screens

import com.elevencapital.app.data.LiveWalletTokenHolding
import com.elevencapital.app.data.PortfolioNetwork
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioSolscanLinkTest {
    private val wallet = encodeBase58(ByteArray(32) { (it + 1).toByte() })
    private val mint = encodeBase58(ByteArray(32) { (it + 2).toByte() })

    private fun token(chain: PortfolioNetwork, assetId: String) = LiveWalletTokenHolding(
        chain, assetId, "SOL", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)

    @Test fun `native SOL links its verified wallet on the correct cluster`() {
        val mainnet = portfolioTokenSolscanUrl(token(PortfolioNetwork.SOLANA, "SOLANA:native"), wallet)
        val devnet = devnetSolSolscanUrl(wallet)
        assertEquals("https://solscan.io/account/$wallet", mainnet)
        assertEquals("https://solscan.io/account/$wallet?cluster=devnet", devnet)
        assertTrue(isPortfolioSolscanUrl(mainnet!!))
        assertTrue(isPortfolioSolscanUrl(devnet!!))
    }

    @Test fun `SPL token links its mint and never an EVM asset`() {
        assertEquals("https://solscan.io/token/$mint",
            portfolioTokenSolscanUrl(token(PortfolioNetwork.SOLANA, "SOLANA:$mint"), wallet))
        assertNull(portfolioTokenSolscanUrl(token(PortfolioNetwork.ETHEREUM, "ETHEREUM:native"), wallet))
        assertNull(portfolioTokenSolscanUrl(token(PortfolioNetwork.ARBITRUM, "ARBITRUM:native"), wallet))
        assertNull(portfolioTokenSolscanUrl(token(PortfolioNetwork.SOLANA, "SOLANA:invalid"), wallet))
        assertNull(portfolioTokenSolscanUrl(token(PortfolioNetwork.SOLANA, "SOLANA:native"), "invalid"))
    }

    @Test fun `browser gate rejects non Solscan or mismatched cluster links`() {
        assertFalse(isPortfolioSolscanUrl("https://solscan.io/account/$wallet?cluster=mainnet"))
        assertFalse(isPortfolioSolscanUrl("https://solscan.io/account/$wallet?cluster=devnet&foo=bar"))
        assertFalse(isPortfolioSolscanUrl("https://solscan.io.evil.test/account/$wallet"))
        assertFalse(isPortfolioSolscanUrl("javascript:alert(1)"))
        assertFalse(isPortfolioSolscanUrl("https://solscan.io/tx/$wallet"))
    }
}
