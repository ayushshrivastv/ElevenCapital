package com.elevencapital.app.screens

import java.math.BigDecimal
import org.junit.Assert.assertEquals
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
        assertEquals(BigDecimal.ZERO, state.availableUsdc)
        assertEquals(BigDecimal("81.97"), state.availableSolUsd)
        assertEquals(BigDecimal("0.399853658"), state.availableSol)
        assertEquals(BigDecimal("81.97"), state.availableUsd)
        assertEquals(BigDecimal.ZERO, PreviewWalletState().availableUsd)
    }

}
