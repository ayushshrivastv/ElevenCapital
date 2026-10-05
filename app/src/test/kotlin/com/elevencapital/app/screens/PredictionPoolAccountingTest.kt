package com.elevencapital.app.screens

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionPoolAccountingTest {
    @Test fun legacyPositionRefundsOnceInsteadOfInventingASettlementAfterAppWasAway() {
        val now = 1_800_000_000_045L
        val round = PredictionPreviewRound.sharedAt(now)
        val id = requireNotNull(round.roundId)
        val funded = PredictionWalletLedger.empty()
            .creditLocalUsdc("funding", BigDecimal("20"), now - 1_000L)
            .beginPool(id)
            .stake("entry-1", id, PredictionDirection.ABOVE, BigDecimal("5"),
                BigDecimal("0.5"), BigDecimal.valueOf(round.price))

        val after = funded.advanceToPool((id + 1L) * PredictionPreviewRound.DURATION * 1_000L + 1L)
        assertEquals(0, after.openEntryCount)
        assertEquals(id + 1L, after.currentPoolId)
        assertEquals(after.toJson(), after.advanceToPool((id + 1L) * PredictionPreviewRound.DURATION * 1_000L + 1L).toJson())
        assertEquals(BigDecimal("20.000000"), after.availableQuoteUsd)
        assertEquals(BigDecimal("0.000000"), after.lifetimeRealizedPnlUsd)
        assertEquals(PredictionHistoryKind.REFUND, after.history.last().kind)
    }

    @Test fun livePositionRemainsReservedAcrossMissingEpochsUntilRealBoundaryOrRefund() {
        val start = 90_000L * 18_900_000L
        val anchor = PredictionOracleQuote(3_000.0, 0.2, start / 1_000 + 1)
        val round = PredictionPreviewRound.liveAt(start + 1_000, anchor)
        val funded = PredictionWalletLedger.empty()
            .creditLocalUsdc("funding", BigDecimal("20"), start)
            .enterLive("live", round, PredictionDirection.ABOVE, BigDecimal("5"), start + 2_000)
        val later = funded.advanceToPool(start + 900_000)
        assertEquals(1, later.openEntryCount)
        assertEquals(BigDecimal("15.000000"), later.availableQuoteUsd)
        assertEquals(BigDecimal("5.000000"), later.reservedQuoteUsd)
        assertEquals(funded.history, later.history)
        assertEquals(funded.currentPoolId!! + 10, later.currentPoolId)
        val restored = PredictionWalletLedger.fromJson(later.toJson())
        assertTrue(restored.openEntry("live")!!.isLive)
        assertEquals(later.toJson(), restored.toJson())
    }
}
