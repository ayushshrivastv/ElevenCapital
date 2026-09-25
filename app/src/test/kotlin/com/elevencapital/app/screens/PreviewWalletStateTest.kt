package com.elevencapital.app.screens

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewWalletStateTest {
    @Test fun twoPreviewBuysDebitOnlyTheLocalSolBalance() {
        val state = PreviewWalletState(
            refreshTapCount = 4,
            fundedAtEpochMillis = 1_000L,
            purchases = listOf(
                PreviewWalletPurchase(SPCXX_PREVIEW_STOCK_ID, "SPCXx", BigDecimal("0.01"), 2_000L),
                PreviewWalletPurchase(NIKE_PREVIEW_STOCK_ID, "NKE.US", BigDecimal("0.02"), 3_000L),
            ),
        )
        assertEquals(BigDecimal("4.77"), state.availableUsd)
        assertTrue(state.availableSol < PreviewWalletState.INCOMING_SOL)
        assertTrue(state.availableSol > BigDecimal.ZERO)
        assertEquals(BigDecimal.ZERO, PreviewWalletState().availableUsd)
    }

    @Test fun removingPreviewSolPreservesPurchasedPositionsWithoutAVisibleSolBalance() {
        val purchase = PreviewWalletPurchase(SPCXX_PREVIEW_STOCK_ID, "SPCXx", BigDecimal("0.01"), 2_000L)
        val cleared = PreviewWalletState(4, 1_000L, listOf(purchase)).withoutFunding()

        assertEquals(0, cleared.refreshTapCount)
        assertEquals(null, cleared.fundedAtEpochMillis)
        assertEquals(BigDecimal.ZERO, cleared.availableSol)
        assertEquals(BigDecimal.ZERO, cleared.availableUsd)
        assertEquals(listOf(purchase), cleared.purchases)
    }
}
