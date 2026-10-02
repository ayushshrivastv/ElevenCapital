package com.elevencapital.app.screens

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityTokenBuyScreenTest {
    @Test fun amountAcceptsFourDecimalsAndRejectsMore() {
        assertEquals(BigDecimal("0.0001"), parseCommunityTokenBuyAmount("0.0001"))
        assertEquals(BigDecimal("1.2345"), parseCommunityTokenBuyAmount("1.2345"))
        assertNull(parseCommunityTokenBuyAmount("0"))
        assertNull(parseCommunityTokenBuyAmount("1."))
        assertNull(parseCommunityTokenBuyAmount("1.23456"))
        assertNull(parseCommunityTokenBuyAmount("01"))
        assertNull(parseCommunityTokenBuyAmount("-1"))
    }

    @Test fun keypadKeepsInputCanonicalAndWithinPrecision() {
        assertEquals("5", appendCommunityTokenBuyKey("0", "5"))
        assertEquals("0.", appendCommunityTokenBuyKey("0", "."))
        assertEquals("0.0123", appendCommunityTokenBuyKey("0.012", "3"))
        assertEquals("0.0123", appendCommunityTokenBuyKey("0.0123", "4"))
        assertEquals("1.2", appendCommunityTokenBuyKey("1.2", "."))
        assertEquals("1", appendCommunityTokenBuyKey("1", "-"))
    }

    @Test fun defaultsToWalletAssetWithGreaterUsdValue() {
        assertEquals(CommunityPayAsset.SOL, preferredCommunityPayAsset(
            BigDecimal("0.4"), BigDecimal.ZERO, BigDecimal("205")))
        assertEquals(CommunityPayAsset.USDC, preferredCommunityPayAsset(
            BigDecimal("0.1"), BigDecimal("30"), BigDecimal("205")))
    }

    @Test fun selectedAssetConvertsToUsdBeforeQuotingTokens() {
        assertEquals(BigDecimal("0.4"), communityTokenBuyQuote(
            BigDecimal("0.4"), CommunityPayAsset.USDC, BigDecimal("205"))?.spentUsd)
        assertEquals(BigDecimal("82.0"), communityTokenBuyQuote(
            BigDecimal("0.4"), CommunityPayAsset.SOL, BigDecimal("205"))?.spentUsd)
        assertEquals(BigDecimal("400.000000"), communityTokenBuyQuote(
            BigDecimal("0.4"), CommunityPayAsset.USDC, BigDecimal("205"))?.receivedTokens)
    }

    @Test fun sellAcceptsEightFractionalTokenDigits() {
        assertEquals(BigDecimal("24.8933883"), parseCommunityTokenSellAmount("24.8933883"))
        assertNull(parseCommunityTokenSellAmount("24.893388388"))
        assertEquals("24.8933883", appendCommunityTokenSellKey("24.893388", "3"))
    }

    @Test fun sellMaxUsesTheExactHoldingBeyondManualKeypadPrecision() {
        val held = BigDecimal("24.893388388123")
        assertNull(parseCommunityTokenSellAmount("24.893388388123"))
        assertEquals(held, resolveCommunityTokenSellAmount("24.893388388123", held))
        assertEquals(BigDecimal("20500.000000"), resolveCommunityTokenSellAmount(
            "20500", BigDecimal("20500.000000")))
        assertNull(resolveCommunityTokenSellAmount("0", held))
    }
}
