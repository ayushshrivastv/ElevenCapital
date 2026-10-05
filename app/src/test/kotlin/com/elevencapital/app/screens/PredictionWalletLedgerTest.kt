package com.elevencapital.app.screens

import java.math.BigDecimal
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PredictionWalletLedgerTest {
    private val liveStart = 90_000L * 18_900_000L
    private fun liveQuote(second: Int, price: Double = 3_000.0) =
        PredictionOracleQuote(price, 0.2, liveStart / 1_000L + second)
    private fun liveRound(second: Int = 1, price: Double = 3_000.0): PredictionPreviewRound =
        PredictionPreviewRound.liveAt(liveStart + second * 1_000L, liveQuote(1))
            .ingest(liveQuote(second, price), liveStart + second * 1_000L)
    private fun money(value: String) = BigDecimal(value)
    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals(0, money(expected).compareTo(actual))

    private fun funded(amount: String = "10"): PredictionWalletLedger =
        PredictionWalletLedger.empty().creditLocalUsdc("funding", money(amount), 1_000L)

    @Test fun `ledger rejects opening pause stakes and accepts entry at two seconds`() {
        val opening = liveRound()
        assertThrows(IllegalStateException::class.java) {
            funded().enterLive("too-early", opening, PredictionDirection.ABOVE,
                money("5"), liveStart + 1_999)
        }
        val entered = funded().enterLive("on-time", opening, PredictionDirection.ABOVE,
            money("5"), liveStart + 2_000)
        assertEquals(1, entered.openEntryCount)
    }

    @Test fun `live stake persists fixed oracle targets and blocks legacy arbitrary payouts`() {
        val round = liveRound(40, 3_006.0)
        val ledger = funded().enterLive("live", round, PredictionDirection.ABOVE, money("5"), liveStart + 40_000)
        val entry = ledger.openEntry("live")!!
        assertTrue(entry.isLive)
        assertEquals("pyth", entry.oracleSourceId)
        assertEquals(liveQuote(1), entry.anchorOracleQuote)
        assertEquals(liveQuote(40).publishTime, entry.entryPublishTime)
        assertMoney("5", ledger.availableQuoteUsd)
        val restored = PredictionWalletLedger.fromJson(ledger.toJson())
        assertEquals(entry, restored.openEntry("live"))
        assertThrows(IllegalStateException::class.java) { restored.tradeOut("live", money("999")) }
        assertThrows(IllegalStateException::class.java) { restored.settle("live", PredictionDirection.ABOVE) }
        assertThrows(IllegalStateException::class.java) {
            restored.enterLive("other", round, PredictionDirection.ABOVE, money("1"), liveStart + 46_000)
        }
        assertThrows(IllegalStateException::class.java) {
            restored.enterLive("live", round, PredictionDirection.ABOVE, money("1"), liveStart + 40_000)
        }
    }

    @Test fun `adaptive band persists and cannot change during exit or reentry`() {
        val narrow = liveRound(40, 3_000.40).copy(bandUsd = 0.50, forecastSigmaUsd = 0.80)
        val first = funded("15").enterLive("narrow", narrow, PredictionDirection.ABOVE,
            money("5"), liveStart + 40_000)
        val entry = first.openEntry("narrow")!!
        assertEquals(0.50, entry.bandUsd, 0.0)
        assertEquals(0.80, entry.forecastSigmaUsd!!, 0.0)
        val restored = PredictionWalletLedger.fromJson(first.toJson())
        assertEquals(entry, restored.openEntry("narrow"))
        val missingForecast = JSONObject(first.toJson()).also { json ->
            json.getJSONArray("openEntries").getJSONObject(0).remove("forecastSigmaUsd")
        }
        assertThrows(Exception::class.java) { PredictionWalletLedger.fromJson(missingForecast.toString()) }
        assertThrows(IllegalStateException::class.java) {
            restored.tradeOutLive("narrow", narrow.copy(bandUsd = 4.0), liveStart + 40_000)
        }
        assertThrows(IllegalStateException::class.java) {
            restored.tradeOutLive("narrow", narrow.copy(forecastSigmaUsd = 0.25), liveStart + 40_000)
        }
        assertThrows(IllegalStateException::class.java) {
            restored.enterLive("other", narrow.copy(bandUsd = 4.0), PredictionDirection.BELOW,
                money("5"), liveStart + 40_000)
        }
        val later = narrow.ingest(liveQuote(50, 3_000.70), liveStart + 50_000)
        val exited = restored.tradeOutLive("narrow", later, liveStart + 50_000)
        assertEquals(0, exited.openEntryCount)
        val flatRestored = PredictionWalletLedger.fromJson(exited.toJson())
        assertEquals(PredictionPoolTerms(liveQuote(1), 0.50, 0.80), flatRestored.currentLivePoolTerms)
        assertThrows(IllegalStateException::class.java) {
            flatRestored.enterLive("changed", later.copy(bandUsd = 4.0), PredictionDirection.ABOVE,
                money("1"), liveStart + 50_000)
        }
    }

    @Test fun `narrow band determines settlement after restore and old snapshots keep four dollars`() {
        val narrow = liveRound().copy(bandUsd = 0.50)
        val ledger = funded().enterLive("range", narrow, PredictionDirection.AROUND,
            money("5"), liveStart + 2_000)
        val restored = PredictionWalletLedger.fromJson(ledger.toJson())
        val inside = restored.settleLivePool(narrow.roundId!!, liveQuote(90, 3_000.50), liveStart + 90_000)
        assertEquals(PredictionHistoryKind.SETTLEMENT_WIN, inside.history.last().kind)
        val outside = restored.settleLivePool(narrow.roundId!!, liveQuote(90, 3_000.51), liveStart + 90_000)
        assertEquals(PredictionHistoryKind.SETTLEMENT_LOSS, outside.history.last().kind)
        val old = JSONObject(funded().enterLive("old", liveRound(), PredictionDirection.AROUND,
            money("5"), liveStart + 2_000).toJson()).put("version", 3).also { json ->
            json.getJSONArray("openEntries").getJSONObject(0).remove("bandUsd")
        }
        assertEquals(4.0, PredictionWalletLedger.fromJson(old.toString()).openEntry("old")!!.bandUsd, 0.0)
        val damaged = JSONObject(ledger.toJson()).also { json ->
            json.getJSONArray("openEntries").getJSONObject(0).remove("bandUsd")
        }
        assertThrows(Exception::class.java) { PredictionWalletLedger.fromJson(damaged.toString()) }
    }

    @Test fun `live exits require fresh same-pool reports and reentry locks new quotes`() {
        val first = liveRound(10)
        val ledger = funded().enterLive("first", first, PredictionDirection.ABOVE, money("5"), liveStart + 10_000)
        val later = first.ingest(liveQuote(40, 3_010.0), liveStart + 40_000)
        val closed = ledger.tradeOutLive("first", later, liveStart + 40_000)
        assertTrue(closed.availableQuoteUsd > money("10"))
        assertEquals(0, closed.openEntryCount)
        assertEquals(PredictionHistoryKind.TRADE_OUT, closed.history.last().kind)
        assertThrows(IllegalStateException::class.java) { closed.tradeOutLive("first", later, liveStart + 40_000) }
        assertThrows(IllegalStateException::class.java) { ledger.tradeOutLive("first", later, liveStart + 46_000) }
        assertThrows(IllegalStateException::class.java) {
            ledger.tradeOutLive("first", later.copy(anchorOracleQuote = liveQuote(1, 3_001.0)), liveStart + 40_000)
        }
        assertThrows(IllegalStateException::class.java) { ledger.tradeOutLive("first", later, liveStart + 90_000) }
        val reentered = closed.enterLive("second", later, PredictionDirection.ABOVE, money("5"), liveStart + 40_000)
        val entry = reentered.openEntry("second")!!
        assertEquals(ledger.openEntry("first")!!.anchorOracleQuote, entry.anchorOracleQuote)
        assertTrue(entry.entryQuote > ledger.openEntry("first")!!.entryQuote)
        assertTrue(entry.quotedWinningPayoutUsd < ledger.openEntry("first")!!.quotedWinningPayoutUsd)
        assertEquals(reentered.toJson(), PredictionWalletLedger.fromJson(reentered.toJson()).toJson())
    }

    @Test fun `entry closes at forty five seconds while an existing position can exit later`() {
        val beforeDivider = liveRound(44, 3_001.0)
        assertTrue(beforeDivider.canEnterLiveAt(liveStart + 44_999))
        val held = funded().enterLive("early", beforeDivider, PredictionDirection.ABOVE,
            money("5"), liveStart + 44_999)
        val atDivider = beforeDivider.ingest(liveQuote(45, 3_002.0), liveStart + 45_000)
        assertTrue(atDivider.canTradeLiveAt(liveStart + 45_000))
        assertFalse(atDivider.canEnterLiveAt(liveStart + 45_000))
        assertFalse(atDivider.canStake)
        assertThrows(IllegalStateException::class.java) {
            funded().enterLive("late", atDivider, PredictionDirection.ABOVE,
                money("5"), liveStart + 45_000)
        }
        val later = atDivider.ingest(liveQuote(60, 3_005.0), liveStart + 60_000)
        assertEquals(0, held.tradeOutLive("early", later, liveStart + 60_000).openEntryCount)
    }

    @Test fun `narrow btc position with confidence wider than range survives restore exit and settlement`() {
        val poolId = Math.floorDiv(liveStart, PredictionPreviewRound.DURATION_MILLIS)
        val startSecond = liveStart / 1_000L
        val anchor = PredictionOracleQuote(68_000.0, 44.81, startSecond + 1)
        val opening = PredictionPreviewRound.liveForPool(poolId, anchor, liveStart + 1_000,
            bandUsd = 1.25, forecastSigmaUsd = 40.0, allowWatchOnlyAnchor = true)
        val staked = funded("20").enterLive("btc", opening, PredictionDirection.ABOVE,
            money("5"), liveStart + 2_000)
        val restored = PredictionWalletLedger.fromJson(staked.toJson())
        assertTrue(restored.openEntry("btc")!!.btcMarket)
        assertTrue(restored.currentLivePoolTerms!!.btcMarket)
        assertEquals(1.25, restored.currentLivePoolTerms!!.bandUsd, 0.0)
        val later = opening.ingest(PredictionOracleQuote(68_025.0, 45.0, startSecond + 60),
            liveStart + 60_000, allowWatchOnlyObservation = true)
        assertFalse(later.canEnterLiveAt(liveStart + 60_000))
        assertTrue(later.canTradeLiveAt(liveStart + 60_000))
        assertEquals(0, restored.tradeOutLive("btc", later, liveStart + 60_000).openEntryCount)
        val finalQuote = PredictionOracleQuote(68_030.0, 45.0, startSecond + 90)
        val settled = restored.settleLivePool(poolId, finalQuote, liveStart + 90_000)
        assertEquals(0, settled.openEntryCount)
        assertEquals(PredictionHistoryKind.SETTLEMENT_WIN, settled.history.last().kind)
    }

    @Test fun `historical settlement closes only its pool and never credits a new pool P L`() {
        val first = liveRound(1)
        val ledger = funded("30")
            .enterLive("winner", first, PredictionDirection.ABOVE, money("5"), liveStart + 2_000)
            .enterLive("loser", first, PredictionDirection.BELOW, money("5"), liveStart + 2_000)
        val second = PredictionPreviewRound.liveAt(liveStart + 91_000, liveQuote(91, 3_010.0))
        val advanced = ledger.advanceToPool(liveStart + 91_000)
            .enterLive("new", second, PredictionDirection.AROUND, money("5"), liveStart + 92_000)
        val settled = advanced.settleLivePool(first.roundId!!, liveQuote(90, 3_010.0), liveStart + 95_000)
        assertEquals(1, settled.openEntryCount)
        assertNotNull(settled.openEntry("new"))
        assertMoney("0", settled.realizedPoolPnlUsd)
        assertNull(settled.finalizedOutcome)
        val payout = money("5").divide(ledger.openEntry("winner")!!.entryQuote, 6, java.math.RoundingMode.DOWN)
        assertEquals(0, (money("15") + payout).compareTo(settled.availableQuoteUsd))
        assertEquals(0, (payout - money("10")).compareTo(settled.lifetimeRealizedPnlUsd))
        assertEquals(2, settled.history.count { it.kind == PredictionHistoryKind.SETTLEMENT_WIN ||
            it.kind == PredictionHistoryKind.SETTLEMENT_LOSS })
        assertSame(settled, settled.settleLivePool(first.roundId!!, liveQuote(90, 3_010.0), liveStart + 96_000))
        assertEquals(settled.toJson(), PredictionWalletLedger.fromJson(settled.toJson()).toJson())
    }

    @Test fun `missing boundary cannot use latest price and timeout refunds principal exactly once`() {
        val round = liveRound(1)
        val ledger = funded().enterLive("live", round, PredictionDirection.ABOVE, money("5"), liveStart + 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.settleLivePool(round.roundId!!, liveQuote(125, 3_100.0), liveStart + 125_000)
        }
        assertThrows(IllegalStateException::class.java) { ledger.cancelLivePool(round.roundId!!, liveStart + 119_999) }
        val refunded = ledger.cancelLivePool(round.roundId!!, liveStart + 120_000)
        assertMoney("10", refunded.availableQuoteUsd)
        assertMoney("0", refunded.lifetimeRealizedPnlUsd)
        assertMoney("0", refunded.reservedQuoteUsd)
        assertEquals(PredictionHistoryKind.REFUND, refunded.history.last().kind)
        assertSame(refunded, refunded.cancelLivePool(round.roundId!!, liveStart + 121_000))
        assertSame(refunded, refunded.settleLivePool(round.roundId!!, liveQuote(90, 3_100.0), liveStart + 121_000))
        assertEquals(refunded.toJson(), PredictionWalletLedger.fromJson(refunded.toJson()).toJson())
        assertThrows(IllegalStateException::class.java) {
            refunded.enterLive("reopen", round, PredictionDirection.ABOVE, money("1"), liveStart + 2_000)
        }
    }

    @Test fun `v2 synthetic positions migrate to one refund and live metadata cannot disappear`() {
        val source = funded().beginPool(7).stake("old", 7, PredictionDirection.ABOVE,
            money("5"), money("0.5"), money("3480"))
        val v2 = JSONObject(source.toJson()).put("version", 2).also { json ->
            json.remove("closedLivePools")
            json.getJSONArray("openEntries").getJSONObject(0).apply {
                remove("oracleSourceId"); remove("anchorOracleQuote"); remove("entryPublishTime")
            }
        }.toString()
        val migrated = PredictionWalletLedger.fromJson(v2).refundLegacyEntries(liveStart)
        assertMoney("10", migrated.availableQuoteUsd)
        assertEquals(PredictionHistoryKind.REFUND, migrated.history.last().kind)
        assertSame(migrated, migrated.refundLegacyEntries(liveStart + 1_000))
        assertEquals(migrated.toJson(), PredictionWalletLedger.fromJson(migrated.toJson()).toJson())
        val live = funded().enterLive("live", liveRound(), PredictionDirection.ABOVE, money("5"), liveStart + 2_000)
        val damaged = JSONObject(live.toJson()).also { json ->
            json.getJSONArray("openEntries").getJSONObject(0).remove("anchorOracleQuote")
        }.toString()
        assertThrows(Exception::class.java) { PredictionWalletLedger.fromJson(damaged) }
    }

    @Test fun `empty ledger cannot stake until practice funding is supplied`() {
        val empty = PredictionWalletLedger.empty()
        assertMoney("0", empty.availableQuoteUsd)
        assertMoney("0", empty.reservedQuoteUsd)
        assertMoney("0", empty.realizedPoolPnlUsd)
        val pool = empty.beginPool(7)
        assertThrows(IllegalStateException::class.java) {
            pool.stake("entry-1", 7, PredictionDirection.ABOVE,
                money("1"), money("0.25"), money("3482.64"))
        }
        assertMoney("0", pool.availableQuoteUsd)
    }

    @Test fun `local funding is idempotent and cannot replay a different amount`() {
        val first = PredictionWalletLedger.empty().creditLocalUsdc("funding", money("5.000000"), 1_000L)
        assertMoney("5", first.availableQuoteUsd)
        assertEquals(PredictionHistoryKind.LOCAL_USDC_ADD, first.history.single().kind)
        assertSame(first, first.creditLocalUsdc("funding", money("5"), 2_000L))
        assertThrows(IllegalArgumentException::class.java) {
            first.creditLocalUsdc("funding", money("6"), 2_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.creditLocalUsdc("not a request", money("1"), 2_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.creditLocalUsdc("second", money("0"), 2_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.creditLocalUsdc("second", money("0.0000001"), 2_000L)
        }
        assertMoney("5", first.availableQuoteUsd)
    }

    @Test fun `stake immediately reserves quote and supports multiple entries in one pool`() {
        val pool = funded().beginPool(7)
        val first = pool.stake("entry-1", 7, PredictionDirection.ABOVE,
            money("3"), money("0.25"), money("3482.64"))
        assertMoney("7", first.availableQuoteUsd)
        assertMoney("3", first.reservedQuoteUsd)
        assertEquals(1, first.openEntryCount)
        assertMoney("12", first.openEntry("entry-1")!!.quotedWinningPayoutUsd)
        assertMoney("3482.64", first.openEntry("entry-1")!!.entryPriceUsd)
        val second = first.stake("entry-2", 7, PredictionDirection.BELOW,
            money("4"), money("0.5"), money("3480.00"))
        assertMoney("3", second.availableQuoteUsd)
        assertMoney("7", second.reservedQuoteUsd)
        assertEquals(2, second.openEntries.size)
        assertThrows(IllegalStateException::class.java) {
            second.stake("entry-3", 7, PredictionDirection.AROUND,
                money("4"), money("0.5"), money("3480"))
        }
        assertThrows(IllegalStateException::class.java) {
            second.stake("entry-1", 7, PredictionDirection.AROUND,
                money("1"), money("0.5"), money("3480"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            second.stake("entry-3", 7, PredictionDirection.AROUND,
                money("1"), money("0"), money("3480"))
        }
        assertMoney("3", second.availableQuoteUsd)
    }

    @Test fun `later share price buys fewer shares and locks a smaller winning return`() {
        val pool = funded().beginPool(7)
            .stake("early", 7, PredictionDirection.ABOVE,
                money("5"), money("0.50"), money("3482.64"))
            .stake("late", 7, PredictionDirection.ABOVE,
                money("5"), money("0.80"), money("3485.00"))

        assertMoney("10", pool.openEntry("early")!!.quotedWinningPayoutUsd)
        assertMoney("6.25", pool.openEntry("late")!!.quotedWinningPayoutUsd)
        assertMoney("0", pool.availableQuoteUsd)

        val settled = pool.settle("early", PredictionDirection.ABOVE)
            .settle("late", PredictionDirection.ABOVE)
        assertMoney("16.25", settled.availableQuoteUsd)
        assertMoney("6.25", settled.realizedPoolPnlUsd)

        val reentered = funded().beginPool(8)
            .stake("first", 8, PredictionDirection.ABOVE,
                money("5"), money("0.50"), money("3482.64"))
            .tradeOut("first", money("8"))
            .stake("second", 8, PredictionDirection.ABOVE,
                money("5"), money("0.80"), money("3485.00"))
        assertMoney("6.25", reentered.openEntry("second")!!.quotedWinningPayoutUsd)
    }

    @Test fun `trade outs credit explicit exits and accumulate realized pool P L`() {
        val open = funded().beginPool(7)
            .stake("first", 7, PredictionDirection.ABOVE, money("3"), money("0.25"), money("3482"))
            .stake("second", 7, PredictionDirection.BELOW, money("4"), money("0.5"), money("3481"))
        val firstClosed = open.tradeOut("first", money("4.5"))
        assertMoney("7.5", firstClosed.availableQuoteUsd)
        assertMoney("4", firstClosed.reservedQuoteUsd)
        assertMoney("1.5", firstClosed.realizedPoolPnlUsd)
        val allClosed = firstClosed.tradeOut("second", money("2"))
        assertMoney("9.5", allClosed.availableQuoteUsd)
        assertMoney("-0.5", allClosed.realizedPoolPnlUsd)
        assertMoney("-0.5", allClosed.lifetimeRealizedPnlUsd)
        assertEquals(0, allClosed.openEntryCount)
        assertThrows(IllegalStateException::class.java) { allClosed.tradeOut("first", money("4.5")) }
        assertThrows(IllegalStateException::class.java) {
            allClosed.stake("first", 7, PredictionDirection.ABOVE,
                money("1"), money("0.25"), money("3482"))
        }
        val next = allClosed.beginPool(8)
        assertMoney("0", next.realizedPoolPnlUsd)
        assertMoney("-0.5", next.lifetimeRealizedPnlUsd)
        assertMoney("9.5", next.availableQuoteUsd)
        assertSame(next, next.beginPool(8))
    }

    @Test fun `settlement credits only winners and fixes one shared outcome for the pool`() {
        val open = funded().beginPool(7)
            .stake("winner", 7, PredictionDirection.ABOVE, money("2"), money("0.25"), money("3482"))
            .stake("loser", 7, PredictionDirection.BELOW, money("1"), money("0.5"), money("3483"))
        val oneSettled = open.settle("winner", PredictionDirection.ABOVE)
        assertMoney("15", oneSettled.availableQuoteUsd)
        assertMoney("6", oneSettled.realizedPoolPnlUsd)
        assertEquals(PredictionDirection.ABOVE, oneSettled.finalizedOutcome)
        assertThrows(IllegalStateException::class.java) { oneSettled.beginPool(8) }
        assertThrows(IllegalStateException::class.java) { oneSettled.tradeOut("loser", money("1")) }
        assertThrows(IllegalStateException::class.java) {
            oneSettled.settle("loser", PredictionDirection.BELOW)
        }
        val allSettled = oneSettled.settle("loser", PredictionDirection.ABOVE)
        assertMoney("15", allSettled.availableQuoteUsd)
        assertMoney("5", allSettled.realizedPoolPnlUsd)
        assertMoney("5", allSettled.lifetimeRealizedPnlUsd)
        assertEquals(0, allSettled.openEntryCount)
        assertThrows(IllegalStateException::class.java) { allSettled.settle("winner", PredictionDirection.ABOVE) }
        val next = allSettled.beginPool(8)
        assertMoney("0", next.realizedPoolPnlUsd)
        assertNull(next.finalizedOutcome)
        assertMoney("15", next.availableQuoteUsd)
    }

    @Test fun `snapshot restores open entry quote and price for deterministic settlement`() {
        val before = funded().beginPool(7)
            .stake("closed", 7, PredictionDirection.BELOW,
                money("2"), money("0.4"), money("3482.10"))
            .tradeOut("closed", money("1.25"))
            .stake("open", 7, PredictionDirection.ABOVE,
                money("3"), money("0.333333333333333333"), money("3480.25"))
        val restored = PredictionWalletLedger.fromJson(before.toJson())
        assertMoney("6.25", restored.availableQuoteUsd)
        assertMoney("3", restored.reservedQuoteUsd)
        assertMoney("-0.75", restored.realizedPoolPnlUsd)
        assertEquals(1, restored.openEntryCount)
        val entry = restored.openEntry("open")!!
        assertEquals(PredictionDirection.ABOVE, entry.direction)
        assertMoney("3480.25", entry.entryPriceUsd)
        assertEquals(0, money("0.333333333333333333").compareTo(entry.entryQuote))
        assertMoney("9", entry.quotedWinningPayoutUsd)
        assertSame(restored, restored.creditLocalUsdc("funding", money("10"), 2_000L))
        val settled = restored.settle("open", PredictionDirection.ABOVE)
        assertMoney("15.25", settled.availableQuoteUsd)
        assertMoney("5.25", settled.realizedPoolPnlUsd)
        assertMoney("5.25", settled.lifetimeRealizedPnlUsd)
        assertEquals(0, settled.openEntryCount)
    }

    @Test fun `snapshot rejects edited balance missing quote and reopened entry`() {
        val source = funded().beginPool(7)
            .stake("one", 7, PredictionDirection.ABOVE,
                money("2"), money("0.25"), money("3482"))
            .toJson()
        val editedBalance = JSONObject(source).put("availableQuoteUsd", "999.000000").toString()
        assertThrows(IllegalArgumentException::class.java) { PredictionWalletLedger.fromJson(editedBalance) }
        val missingQuote = JSONObject(source).also {
            it.getJSONArray("openEntries").getJSONObject(0).remove("entryQuote")
        }.toString()
        assertThrows(Exception::class.java) { PredictionWalletLedger.fromJson(missingQuote) }
        val reopened = JSONObject(source).also { it.getJSONArray("closedIds").put("one") }.toString()
        assertThrows(IllegalArgumentException::class.java) { PredictionWalletLedger.fromJson(reopened) }
    }
}
