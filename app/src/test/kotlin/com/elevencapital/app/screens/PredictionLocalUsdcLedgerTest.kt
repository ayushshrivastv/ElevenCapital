package com.elevencapital.app.screens

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionLocalUsdcLedgerTest {
    private fun usd(value: String) = BigDecimal(value)
    private fun assertUsd(expected: String, actual: BigDecimal) =
        assertEquals(0, usd(expected).compareTo(actual))

    @Test fun `local USDC moves are durable idempotent and have no chain transaction hash`() {
        val credited = PredictionWalletLedger.empty().creditLocalUsdc("add-1", usd("80"), 1_000L)
        assertUsd("80", credited.availableQuoteUsd)
        assertUsd("80", credited.localUsdcNetFundingUsd)
        assertSame(credited, credited.creditLocalUsdc("add-1", usd("80.00"), 2_000L))
        assertEquals(PredictionHistoryKind.LOCAL_USDC_ADD, credited.history.single().kind)
        assertEquals(1, credited.mainTransferItems().size)
        assertTrue(credited.mainTransferItems().single().localUsdc)
        assertEquals(null, credited.mainTransferItems().single().transactionHash)

        val withdrawn = credited.withdrawLocalUsdc("withdraw-1", usd("25"), 3_000L)
        assertUsd("55", withdrawn.availableQuoteUsd)
        assertUsd("55", withdrawn.localUsdcNetFundingUsd)
        assertSame(withdrawn, withdrawn.withdrawLocalUsdc("withdraw-1", usd("25"), 4_000L))
        assertEquals(2, withdrawn.mainTransferItems().size)
        assertTrue(withdrawn.mainTransferItems().all { it.localUsdc && it.transactionHash == null })
        assertEquals(listOf("Withdrew USDC", "Added USDC"),
            withdrawn.balanceHistoryItems().map { it.title })
        assertFalse(withdrawn.balanceHistoryItems().first().incoming)

        val restored = PredictionWalletLedger.fromJson(withdrawn.toJson())
        assertUsd("55", restored.availableQuoteUsd)
        assertUsd("55", restored.localUsdcNetFundingUsd)
        assertEquals(withdrawn.history, restored.history)
        assertSame(restored, restored.creditLocalUsdc("add-1", usd("80"), 5_000L))
    }

    @Test fun `local USDC cannot be withdrawn twice or replayed with conflicting details`() {
        val funded = PredictionWalletLedger.empty().creditLocalUsdc("move", usd("80"), 1_000L)
        assertThrows(IllegalArgumentException::class.java) {
            funded.creditLocalUsdc("move", usd("81"), 2_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            funded.withdrawLocalUsdc("move", usd("1"), 2_000L)
        }
        assertThrows(IllegalStateException::class.java) {
            funded.withdrawLocalUsdc("too-much", usd("80.01"), 2_000L)
        }
        val spent = funded.withdrawLocalUsdc("all", usd("80"), 2_000L)
        assertUsd("0", spent.availableQuoteUsd)
        assertThrows(IllegalStateException::class.java) {
            spent.withdrawLocalUsdc("again", usd("1"), 3_000L)
        }
    }

    @Test fun `local USDC can collateralize a prediction without a chain deposit`() {
        val funded = PredictionWalletLedger.empty().creditLocalUsdc("add-1", usd("80"), 1_000L)
            .beginPool(7)
            .stake("position-1", 7, PredictionDirection.ABOVE, usd("5"), usd("0.5"), usd("3000"))
        assertUsd("75", funded.availableQuoteUsd)
        assertUsd("5", funded.reservedQuoteUsd)
        val restored = PredictionWalletLedger.fromJson(funded.toJson())
        assertUsd("75", restored.availableQuoteUsd)
        assertUsd("5", restored.reservedQuoteUsd)
        assertUsd("80", restored.localUsdcNetFundingUsd)
    }

    @Test fun `local funding reconciliation rejects fabricated saved balance`() {
        val saved = PredictionWalletLedger.empty().creditLocalUsdc("add-1", usd("80"), 1_000L)
        val altered = JSONObject(saved.toJson()).put("availableQuoteUsd", "81.000000")
        assertThrows(IllegalArgumentException::class.java) {
            PredictionWalletLedger.fromJson(altered.toString())
        }
    }
}
