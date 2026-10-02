package com.elevencapital.app.screens

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PortfolioCommunityTokenVisibilityTest {
    @Test fun `completed QR buy without a quantity is visible in Tokens`() {
        val scannedBuy = CommunityScannedTokenPurchase(
            mintAddress = "scanned-mint", purchasedAtMillis = 1000L,
            name = "Creator token", symbol = "CREATOR",
        )

        assertEquals(listOf(scannedBuy), visibleCommunityTokenPurchases(listOf(scannedBuy)))
    }

    @Test fun `positive local holding remains visible and sold out one is hidden`() {
        val held = CommunityScannedTokenPurchase("held-mint", 1000L,
            receivedTokens = BigDecimal("400"), spentUsd = BigDecimal("0.40"))
        val soldOut = CommunityScannedTokenPurchase("sold-mint", 1000L,
            receivedTokens = BigDecimal.ZERO)

        assertEquals(listOf(held), visibleCommunityTokenPurchases(listOf(held, soldOut)))
    }

    @Test fun `verified on chain holding does not get a duplicate local row`() {
        val scannedBuy = CommunityScannedTokenPurchase("same-mint", 1000L)

        assertEquals(emptyList<CommunityScannedTokenPurchase>(),
            visibleCommunityTokenPurchases(listOf(scannedBuy), setOf("SOLANA:same-mint")))
    }

    @Test fun `unknown scanned token uses its mint instead of a generic symbol`() {
        val scannedBuy = CommunityScannedTokenPurchase(
            "Abcd1234567890Efgh", 1000L,
        )

        assertEquals("Abcd...Efgh", portfolioCommunityTokenSymbol(scannedBuy))
    }
}
