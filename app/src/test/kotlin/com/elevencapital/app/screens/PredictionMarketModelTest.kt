package com.elevencapital.app.screens

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class PredictionMarketModelTest {
    private val sharedPoolStart = 90_000L * 18_900_000L

    private fun observation(second: Int, price: Double = 3_000.0, confidence: Double = 0.2) =
        PredictionOracleQuote(price, confidence, sharedPoolStart / 1_000L + second)

    @Test fun `live entry waits two seconds without extending the round or midpoint`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1))
        assertEquals(89, opening.remaining)
        assertTrue(opening.canTradeLiveAt(sharedPoolStart + 1_999))
        assertFalse(opening.canEnterLiveAt(sharedPoolStart + 1_999))
        assertFalse(opening.syncTo(sharedPoolStart + 1_999).canStake)
        assertTrue(opening.canEnterLiveAt(sharedPoolStart + 2_000))
        assertTrue(opening.syncTo(sharedPoolStart + 2_000).canStake)
        val beforeMidpoint = opening.ingest(observation(44), sharedPoolStart + 44_000)
        assertTrue(beforeMidpoint.canEnterLiveAt(sharedPoolStart + 44_999))
        assertFalse(beforeMidpoint.canEnterLiveAt(sharedPoolStart + 45_000))
        assertEquals(45, beforeMidpoint.syncTo(sharedPoolStart + 45_000).remaining)
    }

    @Test fun `live clock never manufactures chart ticks or settles an unknown final price`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 2_000, observation(1))
        val later = opening.syncTo(sharedPoolStart + 40_000)
        assertTrue(later.isLive)
        assertEquals(opening.anchorOracleQuote, later.anchorOracleQuote)
        assertSame(opening.points, later.points)
        assertEquals(40, later.elapsed)
        assertFalse(later.canStake)
        val expired = later.syncTo(sharedPoolStart + 900_000)
        assertEquals(0, expired.remaining)
        assertSame(opening.points, expired.points)
        assertFalse(expired.settled)
        assertFalse(expired.running)
        assertFalse(expired.canStake)
        assertTrue(expired.sharePrices.range < 1.0)
        assertEquals(expired, expired.advance())
    }

    @Test fun `live chart ingests observations in order and rejects stale future or uncertain prices`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 2_000, observation(1))
        val observed = opening.ingest(observation(40, 3_010.0), sharedPoolStart + 40_200)
        assertEquals(3_010.0, observed.price, 0.0)
        assertEquals(40, observed.points.last().second)
        assertEquals(2, observed.points.size)
        assertTrue(observed.canStake)
        listOf(observation(39, 2_000.0), observation(40, 2_000.0), observation(41, 2_000.0),
            observation(40, 2_000.0, 2.01)).forEach { rejected ->
            assertEquals(observed, observed.ingest(rejected, sharedPoolStart + 40_200))
        }
        val stale = observed.ingest(observation(44, 3_100.0), sharedPoolStart + 50_000)
        assertSame(observed.points, stale.points)
        assertFalse(stale.canStake)
        assertEquals(stale, stale.syncTo(sharedPoolStart + 30_000))
    }

    @Test fun `live targets use epoch boundary and cannot use the current price for a missing opening`() {
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 50_000, observation(50))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 2_000, observation(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 5_000, observation(1, confidence = 2.01))
        }
        val round = PredictionPreviewRound.liveAt(sharedPoolStart + 40_000, observation(1))
            .ingest(observation(40, 3_010.0), sharedPoolStart + 40_000)
        val entered = round.start(PredictionDirection.ABOVE, 5)
        assertEquals(3_000.0, entered.anchor, 0.0)
        assertEquals(50, entered.remaining)
        assertEquals(3_010.0, entered.entryPrice!!, 0.0)
        assertEquals(round.roundId, entered.tradeOut().roundId)
    }

    @Test fun `sub dollar live band stays locked as price and entry odds move`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1), bandUsd = 0.50)
        val moved = opening.ingest(observation(40, 3_001.0), sharedPoolStart + 40_000)
        assertEquals(0.50, opening.band, 0.0)
        assertEquals(0.50, moved.band, 0.0)
        assertEquals(opening.anchor, moved.anchor, 0.0)
        assertTrue(moved.sharePrices.above > opening.sharePrices.above)
        assertEquals(0.50, moved.start(PredictionDirection.ABOVE, 5).tradeOut().band, 0.0)
        fun settled(price: Double) = opening.settleLive(observation(90, price), sharedPoolStart + 90_000).outcome
        assertEquals(PredictionDirection.BELOW, settled(2_999.49))
        assertEquals(PredictionDirection.AROUND, settled(2_999.50))
        assertEquals(PredictionDirection.AROUND, settled(3_000.50))
        assertEquals(PredictionDirection.ABOVE, settled(3_000.51))

        val now = sharedPoolStart + 40_000
        val narrowBase = opening.syncTo(now)
        val wideBase = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1)).syncTo(now)
        val quarterMove = observation(40, 3_000.25)
        val narrowResponse = narrowBase.ingest(quarterMove, now).sharePrices.above - narrowBase.sharePrices.above
        val wideResponse = wideBase.ingest(quarterMove, now).sharePrices.above - wideBase.sharePrices.above
        assertTrue(narrowResponse > wideResponse)
    }

    @Test fun `normal terminal forecast makes a rising upper position more expensive inside fixed range`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.1),
            bandUsd = 0.50, forecastSigmaUsd = 0.40)
        val at = listOf(3_000.0, 3_000.10, 3_000.25, 3_000.49).map { spot ->
            opening.ingest(observation(40, spot, confidence = 0.1), sharedPoolStart + 40_000)
        }
        assertTrue(at.all { it.anchor == 3_000.0 && it.band == 0.50 && it.forecastSigmaUsd == 0.40 })
        val abovePrices = at.map { it.sharePrices.above }
        assertTrue(abovePrices.zipWithNext().all { (first, next) -> next > first })
        val laterProfits = abovePrices.map { 5.0 / it - 5.0 }
        assertTrue(laterProfits.zipWithNext().all { (first, next) -> next < first })
        assertTrue(at.all { it.outcome == PredictionDirection.AROUND })

        // Moving toward an edge makes Range less likely. Its new-entry profit must rise,
        // rather than being artificially reduced just because spot remains inside the band.
        val rangePrices = at.map { it.sharePrices.range }
        assertTrue(rangePrices.zipWithNext().all { (first, next) -> next < first })
        assertTrue(at.all { kotlin.math.abs(it.sharePrices.total - 1.0) < 0.0000001 })
    }

    @Test fun `locked volatility and observation confidence prevent false certainty near expiry`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.20),
            bandUsd = 0.50, forecastSigmaUsd = 0.01)
        val edge = opening.ingest(observation(89, 3_000.50, confidence = 0.20), sharedPoolStart + 89_999)
        assertEquals(0.01, edge.forecastSigmaUsd!!, 0.0)
        assertTrue(edge.sharePrices.above in 0.45..0.55)
        assertTrue(edge.sharePrices.range in 0.40..0.55)
        assertEquals(1.0, edge.sharePrices.total, 0.0)
        val reentered = edge.start(PredictionDirection.ABOVE, 5)
        assertEquals(0.01, reentered.forecastSigmaUsd!!, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.20),
                bandUsd = 0.50, forecastSigmaUsd = Double.NaN)
        }
    }

    @Test fun `one sigma locked band gives coherent three way forecast at the anchor`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.1),
            bandUsd = 1.0, forecastSigmaUsd = 1.0)
        val prices = opening.sharePrices
        assertEquals(prices.above, prices.below, 0.0001)
        // A normal one-sigma interval contains about 68.27% probability. The 2% floor
        // blends it to approximately 66.17% without changing the $1 three-way sum.
        assertEquals(0.6617, prices.range, 0.01)
        assertEquals(1.0, prices.total, 0.0)
    }

    @Test fun `btc sized live range can move above or below and settle at its locked edges`() {
        val anchor = PredictionOracleQuote(68_000.0, 4.0, sharedPoolStart / 1_000L + 1)
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, anchor,
            bandUsd = 20.50, forecastSigmaUsd = 42.0)
        val later = opening.ingest(PredictionOracleQuote(68_022.0, 4.0,
            sharedPoolStart / 1_000L + 40), sharedPoolStart + 40_200)
        assertTrue(later.canStake)
        assertEquals(20.50, later.band, 0.0)
        assertTrue(later.sharePrices.above > opening.sharePrices.above)
        fun outcome(price: Double) = opening.settleLive(PredictionOracleQuote(price, 4.0,
            sharedPoolStart / 1_000L + 90), sharedPoolStart + 90_000).outcome
        assertEquals(PredictionDirection.BELOW, outcome(67_979.49))
        assertEquals(PredictionDirection.AROUND, outcome(67_979.50))
        assertEquals(PredictionDirection.AROUND, outcome(68_020.50))
        assertEquals(PredictionDirection.ABOVE, outcome(68_020.51))
    }

    @Test fun `new btc round uses its adaptive band around the locked opening price`() {
        val startSecond = sharedPoolStart / 1_000L
        val anchor = PredictionOracleQuote(83_404.0, 4.0, startSecond + 1)
        val opening = PredictionPreviewRound.liveForPool(
            Math.floorDiv(sharedPoolStart, PredictionPreviewRound.DURATION_MILLIS),
            anchor, sharedPoolStart + 2_000,
            bandUsd = 7.50,
            forecastSigmaUsd = 40.0,
            allowWatchOnlyAnchor = true,
        )
        assertEquals(83_396.50, opening.anchor - opening.band, 0.0)
        assertEquals(83_411.50, opening.anchor + opening.band, 0.0)
        fun outcome(price: Double) = opening.settleLive(
            PredictionOracleQuote(price, 4.0, startSecond + 90),
            sharedPoolStart + 90_000,
        ).outcome
        assertEquals(PredictionDirection.BELOW, outcome(83_396.49))
        assertEquals(PredictionDirection.AROUND, outcome(83_396.50))
        assertEquals(PredictionDirection.AROUND, outcome(83_411.50))
        assertEquals(PredictionDirection.ABOVE, outcome(83_411.51))
    }

    @Test fun `btc preview accepts cent to hundred dollar bands and rejects wider targets`() {
        val poolId = Math.floorDiv(sharedPoolStart, PredictionPreviewRound.DURATION_MILLIS)
        val anchor = PredictionOracleQuote(83_404.0, 4.0, sharedPoolStart / 1_000L + 1)
        for (band in listOf(0.01, 100.0)) {
            val round = PredictionPreviewRound.liveForPool(poolId, anchor, sharedPoolStart + 2_000,
                bandUsd = band, forecastSigmaUsd = 150.0, allowWatchOnlyAnchor = true)
            assertEquals(band, round.band, 0.0)
            assertTrue(round.canStake)
        }
        for (band in listOf(0.001, 100.01)) {
            assertThrows(IllegalArgumentException::class.java) {
                PredictionPreviewRound.liveForPool(poolId, anchor, sharedPoolStart + 2_000,
                    bandUsd = band, forecastSigmaUsd = 150.0, allowWatchOnlyAnchor = true)
            }
        }
    }

    @Test fun `btc accepts authentic high confidence reports during first half but closes entry at divider`() {
        val poolId = Math.floorDiv(sharedPoolStart, PredictionPreviewRound.DURATION_MILLIS)
        val startSecond = sharedPoolStart / 1_000L
        val anchor = PredictionOracleQuote(68_000.0, 30.0, startSecond + 1)
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveForPool(poolId, anchor, sharedPoolStart + 1_000,
                bandUsd = 20.0, forecastSigmaUsd = 40.0)
        }
        val opening = PredictionPreviewRound.liveForPool(poolId, anchor, sharedPoolStart + 1_000,
            bandUsd = 20.0, forecastSigmaUsd = 40.0, allowWatchOnlyAnchor = true)
        assertFalse(opening.canStake)
        assertTrue(opening.syncTo(sharedPoolStart + 2_000).canStake)
        val uncertain = PredictionOracleQuote(68_005.0, 35.0, startSecond + 30)
        val observed = opening.ingest(uncertain, sharedPoolStart + 30_000,
            allowWatchOnlyObservation = true)
        assertEquals(2, observed.points.size)
        assertTrue(observed.canEnterLiveAt(sharedPoolStart + 30_000))
        val atDivider = observed.ingest(PredictionOracleQuote(68_010.0, 35.0, startSecond + 45),
            sharedPoolStart + 45_000, allowWatchOnlyObservation = true)
        assertTrue(atDivider.canTradeLiveAt(sharedPoolStart + 45_000))
        assertFalse(atDivider.canEnterLiveAt(sharedPoolStart + 45_000))
        val finalQuote = PredictionOracleQuote(68_025.0, 35.0, startSecond + 90)
        assertEquals(PredictionDirection.ABOVE,
            atDivider.settleLive(finalQuote, sharedPoolStart + 90_000).outcome)
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveForPool(poolId,
                PredictionOracleQuote(68_000.0, 681.0, startSecond + 1),
                sharedPoolStart + 1_000, bandUsd = 20.0,
                forecastSigmaUsd = 40.0, allowWatchOnlyAnchor = true)
        }
    }

    @Test fun `narrow three-round band may be smaller than volatility and does not make range dominant`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000,
            observation(1, confidence = 0.01), bandUsd = 0.15, forecastSigmaUsd = 0.348)
        assertTrue(opening.forecastSigmaUsd!! > opening.band)
        assertTrue(opening.sharePrices.range < 0.50)
        assertEquals(1.0, opening.sharePrices.total, 0.0)
        val restored = opening.copy()
        assertEquals(PredictionDirection.ABOVE,
            restored.settleLive(observation(90, 3_000.16, 0.01), sharedPoolStart + 90_000).outcome)
    }

    @Test fun `forecast remains finite and bounded across extreme observed prices`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.1),
            bandUsd = 0.50, forecastSigmaUsd = 0.20)
        for (spot in listOf(0.01, 1_000.0, 3_000.0, 10_000.0, 1.0e100)) {
            val round = opening.ingest(observation(40, spot, confidence = 0.1), sharedPoolStart + 40_000)
            val prices = round.sharePrices
            listOf(prices.above, prices.range, prices.below).forEach { price ->
                assertTrue(price.isFinite() && price in 0.02..0.96)
            }
            assertEquals(1.0, prices.total, 0.0000001)
        }
    }

    @Test fun `narrow band requires oracle confidence at most half its width`() {
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1, confidence = 0.26), bandUsd = 0.50)
        }
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1), bandUsd = 0.50)
        val rejected = opening.ingest(observation(40, 3_001.0, 0.26), sharedPoolStart + 40_000)
        assertEquals(opening.points, rejected.points)
        assertFalse(rejected.canStake)
        assertThrows(IllegalArgumentException::class.java) {
            opening.settleLive(observation(90, 3_000.51, 0.26), sharedPoolStart + 90_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1), bandUsd = 0.005)
        }
    }

    @Test fun `live settlement requires this exact boundary window and retains inclusive range edges`() {
        val opening = PredictionPreviewRound.liveAt(sharedPoolStart + 1_000, observation(1))
        for (bad in listOf(observation(89, 3_100.0), observation(96, 3_100.0),
            observation(90, 3_100.0, 2.01))) {
            assertThrows(IllegalArgumentException::class.java) { opening.settleLive(bad, sharedPoolStart + 100_000) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            opening.settleLive(observation(91), sharedPoolStart + 90_000)
        }
        val finished = opening.settleLive(observation(91, 3_004.0), sharedPoolStart + 1_000_000)
        assertTrue(finished.settled)
        assertEquals(PredictionDirection.AROUND, finished.outcome)
        assertEquals(PredictionSharePrices(0.0, 1.0, 0.0), finished.sharePrices)
        assertEquals(2, finished.points.size)
        assertSame(finished, finished.settleLive(observation(91, 3_004.0), sharedPoolStart + 1_000_000))
        assertThrows(IllegalArgumentException::class.java) {
            finished.settleLive(observation(92, 3_100.0), sharedPoolStart + 1_000_000)
        }
    }

    @Test fun `clients entering one epoch window see the same running pool and deterministic path`() {
        val first = PredictionPreviewRound.sharedAt(sharedPoolStart + 12_700)
        val second = PredictionPreviewRound.sharedAt(sharedPoolStart + 47_900)
        assertEquals(18_900_000L, first.roundId)
        assertEquals(first.roundId, second.roundId)
        assertTrue(first.started)
        assertTrue(first.running)
        assertTrue(first.canStake)
        assertNull(first.direction)
        assertEquals(12, first.elapsed)
        assertEquals(78, first.remaining)
        assertEquals(47, second.elapsed)
        assertEquals(43, second.remaining)
        assertEquals(first.anchor, second.anchor, 0.0)
        assertEquals(second, first.syncTo(sharedPoolStart + 47_900))
    }

    @Test fun `joining midway attaches a position without changing the pool anchor path or deadline`() {
        val pool = PredictionPreviewRound.sharedAt(sharedPoolStart + 48_500)
        val joined = pool.start(PredictionDirection.ABOVE, 25, pool.price + 2_000)
        assertEquals(pool.roundId, joined.roundId)
        assertEquals(pool.anchor, joined.anchor, 0.0)
        assertEquals(pool.priceOrigin, joined.priceOrigin, 0.0)
        assertSame(pool.points, joined.points)
        assertEquals(48, joined.elapsed)
        assertEquals(42, joined.remaining)
        assertEquals(pool.price, joined.price, 0.0)
        assertEquals(pool.price, joined.entryPrice!!, 0.0)
        assertEquals(pool.quoteFor(PredictionDirection.ABOVE), joined.entryQuote!!, 0.0)
        assertEquals(25.0, joined.positionValue!!, 0.0000001)
        assertTrue(joined.active)
        assertFalse(joined.canStake)
        assertEquals(PredictionPreviewRound.sharedAt(sharedPoolStart + 70_000).price,
            joined.syncTo(sharedPoolStart + 70_000).price, 0.0)
    }

    @Test fun `trading out and reentering a shared pool keeps its original epoch deadline`() {
        val initial = PredictionPreviewRound.sharedAt(sharedPoolStart + 35_000)
            .start(PredictionDirection.BELOW, 10)
        val flat = initial.tradeOut().syncTo(sharedPoolStart + 47_000)
        assertTrue(flat.canStake)
        assertEquals(43, flat.remaining)
        assertNull(flat.entryPrice)
        assertNull(flat.entryQuote)
        assertNull(flat.currentQuote)
        assertNull(flat.positionValue)
        val reentered = flat.start(PredictionDirection.AROUND, 50, flat.price + 2_000)
        assertEquals(initial.roundId, reentered.roundId)
        assertEquals(initial.anchor, reentered.anchor, 0.0)
        assertEquals(flat.points, reentered.points)
        assertEquals(47, reentered.elapsed)
        assertEquals(43, reentered.remaining)
        assertEquals(flat.price, reentered.entryPrice!!, 0.0)
        assertEquals(flat.quoteFor(PredictionDirection.AROUND), reentered.entryQuote!!, 0.0)
        assertEquals(50.0, reentered.positionValue!!, 0.0000001)
        val settled = reentered.syncTo(sharedPoolStart + 90_000)
        assertEquals(initial.roundId, settled.roundId)
        assertEquals(90, settled.elapsed)
        assertEquals(0, settled.remaining)
        assertTrue(settled.settled)
        assertFalse(settled.canStake)
    }

    @Test fun `reentry buys at fresh odds and cannot inherit the earlier position gain`() {
        val first = PredictionPreviewRound.sharedAt(sharedPoolStart + 35_000)
            .start(PredictionDirection.ABOVE, 25)
        val moved = first.syncTo(sharedPoolStart + 47_000)
        assertNotEquals(25.0, moved.positionValue!!, 0.0000001)
        val reentered = moved.tradeOut().start(PredictionDirection.ABOVE, 25, first.entryPrice!!)
        assertEquals(moved.price, reentered.entryPrice!!, 0.0)
        assertNotEquals(first.entryPrice!!, reentered.entryPrice!!, 0.0)
        assertNotEquals(first.entryQuote!!, reentered.entryQuote!!, 0.0000001)
        assertEquals(25.0, reentered.positionValue!!, 0.0000001)
        assertEquals(first.roundId, reentered.roundId)
        assertEquals(first.anchor, reentered.anchor, 0.0)
        assertEquals(first.remaining - 12, reentered.remaining)
    }

    @Test fun `simulated ternary quotes are bounded sum to one and move within a chart second`() {
        val pool = PredictionPreviewRound.sharedAt(sharedPoolStart + 47_100)
        assertSame(pool, pool.start(PredictionDirection.ABOVE, 0))
        assertSame(pool, pool.start(PredictionDirection.ABOVE, -5))
        for (offset in listOf(47_100L, 47_800L, 89_999L)) {
            val observed = pool.syncTo(sharedPoolStart + offset)
            val quotes = PredictionDirection.entries.map { observed.quoteFor(it) }
            assertEquals(1.0, quotes.sum(), 0.0000001)
            quotes.forEach { assertTrue(it.isFinite() && it in 0.02..0.96) }
        }
        val entered = pool.start(PredictionDirection.AROUND, 10)
        val later = entered.syncTo(sharedPoolStart + 47_800)
        assertSame(entered.points, later.points)
        assertEquals(entered.price, later.price, 0.0)
        assertEquals(47_100, entered.elapsedMillis)
        assertEquals(47_800, later.elapsedMillis)
        assertNotEquals(entered.currentQuote!!, later.currentQuote!!, 0.0000001)
        assertNotEquals(entered.positionValue!!, later.positionValue!!, 0.0000001)
        assertTrue(later.positionValue!! >= 0.0)
        assertTrue(later.positionValue!! <= later.stake / later.entryQuote!!)
    }

    @Test fun `rising upper share costs more and reduces payout for a later equal stake`() {
        val early = PredictionPreviewRound.ready(3_000.0).start(PredictionDirection.ABOVE, 20)
        val earlyQuote = early.entryQuote!!
        val lateMarket = early.tradeOut().copy(
            points = listOf(PredictionPricePoint(70, early.anchor + 10.0)),
            elapsed = 70,
            elapsedMillis = 70_000,
        )
        val late = lateMarket.start(PredictionDirection.ABOVE, 20)
        val lateQuote = late.entryQuote!!

        assertEquals(early.anchor, late.anchor, 0.0)
        assertEquals(20, late.remaining)
        assertEquals(earlyQuote, early.entryQuote!!, 0.0)
        assertTrue(lateQuote > earlyQuote)
        assertTrue(20.0 / lateQuote < 20.0 / earlyQuote)
        assertTrue((1.0 / lateQuote - 1.0) < (1.0 / earlyQuote - 1.0))
        assertEquals(lateQuote, late.sharePrices.above, 0.0)
        assertEquals(1.0, late.sharePrices.total, 0.0000001)
    }

    @Test fun `upper range and below shares rebalance with price and time`() {
        val opening = PredictionPreviewRound.ready(3_000.0).start(PredictionDirection.AROUND, 5)
        fun at(price: Double, elapsed: Int) = opening.tradeOut().copy(
            points = listOf(PredictionPricePoint(elapsed, price)),
            elapsed = elapsed,
            elapsedMillis = elapsed * 1_000,
        ).sharePrices

        val centered = at(opening.anchor, 80)
        val rising = at(opening.anchor + 10.0, 80)
        val falling = at(opening.anchor - 10.0, 80)
        assertTrue(centered.range > opening.sharePrices.range)
        assertTrue(rising.above > centered.above)
        assertTrue(rising.range < centered.range)
        assertTrue(rising.below < centered.below)
        assertTrue(falling.below > centered.below)
        assertTrue(falling.range < centered.range)
        assertTrue(falling.above < centered.above)
        listOf(centered, rising, falling).forEach { prices ->
            assertEquals(1.0, prices.total, 0.0000001)
            val displayedTicks = listOf(prices.above, prices.range, prices.below)
                .map { (it * 10_000).roundToInt() }
            assertEquals(10_000, displayedTicks.sum())
            assertEquals(prices.above, displayedTicks[0] / 10_000.0, 0.0)
            assertEquals(prices.range, displayedTicks[1] / 10_000.0, 0.0)
            assertEquals(prices.below, displayedTicks[2] / 10_000.0, 0.0)
            assertEquals(prices.above, prices[PredictionDirection.ABOVE], 0.0)
            assertEquals(prices.range, prices[PredictionDirection.AROUND], 0.0)
            assertEquals(prices.below, prices[PredictionDirection.BELOW], 0.0)
        }
    }

    @Test fun `three share prices resolve to exactly one winner at 90 seconds`() {
        val active = PredictionPreviewRound.ready(3_000.0).start(PredictionDirection.BELOW, 5)
        val finished = active.copy(
            points = listOf(PredictionPricePoint(90, 2_990.0)),
            elapsed = 90,
            elapsedMillis = 90_000,
            settled = true,
        )
        assertEquals(PredictionSharePrices(0.0, 0.0, 1.0), finished.sharePrices)
        assertEquals(1.0, finished.sharePrices.total, 0.0)
        assertEquals(1.0, finished.currentQuote!!, 0.0)
        assertEquals(5.0 / active.entryQuote!!, finished.positionValue!!, 0.0000001)
    }

    @Test fun `all entrants settle against shared targets despite different ETH entry prices`() {
        val early = PredictionPreviewRound.sharedAt(sharedPoolStart + 35_000)
            .start(PredictionDirection.ABOVE, 20)
        val late = PredictionPreviewRound.sharedAt(sharedPoolStart + 70_000)
            .start(PredictionDirection.ABOVE, 20)
        assertEquals(early.roundId, late.roundId)
        assertEquals(early.anchor, late.anchor, 0.0)
        assertNotEquals(early.entryPrice!!, late.entryPrice!!, 0.0)
        val insidePoolBand = early.entryPrice!! + early.band + 0.01
        val finishedInside = late.copy(
            points = listOf(PredictionPricePoint(90, insidePoolBand)),
            elapsed = 90,
            elapsedMillis = 90_000,
            settled = true,
        )
        assertEquals(PredictionDirection.AROUND, finishedInside.outcome)
        assertEquals(PredictionDirection.AROUND, finishedInside.positionOutcome)
        assertEquals(0.0, finishedInside.currentQuote!!, 0.0)
        assertEquals(0.0, finishedInside.positionValue!!, 0.0)
        val abovePoolBand = early.anchor + early.band + 0.01
        val finishedEarly = early.copy(
            points = listOf(PredictionPricePoint(90, abovePoolBand)),
            elapsed = 90,
            elapsedMillis = 90_000,
            settled = true,
        )
        val finishedLate = late.copy(
            points = listOf(PredictionPricePoint(90, abovePoolBand)),
            elapsed = 90,
            elapsedMillis = 90_000,
            settled = true,
        )
        listOf(finishedEarly, finishedLate).forEach { finished ->
            assertEquals(PredictionDirection.ABOVE, finished.outcome)
            assertEquals(PredictionDirection.ABOVE, finished.positionOutcome)
            assertEquals(1.0, finished.currentQuote!!, 0.0)
            assertEquals(finished.stake / finished.entryQuote!!, finished.positionValue!!, 0.0000001)
        }
        assertNotEquals(early.entryQuote!!, late.entryQuote!!, 0.0000001)
    }

    @Test fun `resume catches up the old pool then a new pool begins exactly at the wall clock boundary`() {
        val held = PredictionPreviewRound.sharedAt(sharedPoolStart + 4_000)
            .start(PredictionDirection.ABOVE, 5)
        val resumed = held.syncTo(sharedPoolStart + 89_999)
        assertEquals(89, resumed.elapsed)
        assertEquals(1, resumed.remaining)
        assertFalse(resumed.settled)
        assertEquals(PredictionPreviewRound.sharedAt(sharedPoolStart + 89_999).points, resumed.points)
        val settled = resumed.syncTo(sharedPoolStart + 90_000)
        assertEquals(held.roundId, settled.roundId)
        assertTrue(settled.settled)
        assertEquals(0, settled.remaining)
        assertSame(settled, settled.syncTo(sharedPoolStart + 300_000))
        val next = PredictionPreviewRound.sharedAt(sharedPoolStart + 90_000)
        assertEquals(held.roundId!! + 1, next.roundId)
        assertTrue(next.running)
        assertTrue(next.canStake)
        assertEquals(90, next.remaining)
        assertEquals(0, next.elapsed)
        assertEquals(settled.price, next.anchor, 0.0)
    }

    @Test fun `an initial market freezes its starting price and gets the full 90 seconds`() {
        val preview = PredictionPreviewRound.ready().advanceBy(48)
        PredictionDirection.entries.forEach { direction ->
            val trade = preview.start(direction, 25)
            assertEquals(preview.price, trade.anchor, 0.0)
            assertEquals(preview.price, trade.price, 0.0)
            assertEquals(25.0, trade.positionValue!!, 0.0)
            assertEquals(90, trade.remaining)
            assertEquals(0, trade.elapsed)
            assertTrue(trade.started)
            assertTrue(trade.running)
            assertTrue(trade.active)
            assertFalse(trade.canStake)
        }
    }

    @Test fun `round completes at 90 seconds and stays settled when catching up after backgrounding`() {
        val trade = PredictionPreviewRound.ready().start(PredictionDirection.ABOVE, 5)
        val almostDone = trade.advanceBy(89)
        assertEquals(1, almostDone.remaining)
        assertFalse(almostDone.settled)
        val done = almostDone.advance()
        assertEquals(0, done.remaining)
        assertTrue(done.settled)
        assertFalse(done.active)
        assertSame(done, done.advance())
        assertEquals(done, trade.advanceBy(300))
    }

    @Test fun `final price alone decides above around and below with inclusive band edges`() {
        val trade = PredictionPreviewRound.ready(3_000.0).start(PredictionDirection.AROUND, 5)
        fun result(price: Double) = trade.copy(points = listOf(PredictionPricePoint(90, price)),
            elapsed = 90, settled = true).outcome
        assertEquals(PredictionDirection.BELOW, result(2_995.99))
        assertEquals(PredictionDirection.AROUND, result(2_996.0))
        assertEquals(PredictionDirection.AROUND, result(3_004.0))
        assertEquals(PredictionDirection.ABOVE, result(3_004.01))
    }

    @Test fun `trading out keeps the locked market history and remaining countdown`() {
        val active = PredictionPreviewRound.ready().start(PredictionDirection.BELOW, 10, 3_400.0)
            .advanceBy(35)
        val flat = active.tradeOut()
        assertTrue(flat.started)
        assertTrue(flat.running)
        assertFalse(flat.active)
        assertFalse(flat.settled)
        assertTrue(flat.canStake)
        assertNull(flat.direction)
        assertNull(flat.positionValue)
        assertEquals(active.price, flat.price, 0.0)
        assertEquals(active.anchor, flat.anchor, 0.0)
        assertEquals(active.band, flat.band, 0.0)
        assertEquals(active.priceOrigin, flat.priceOrigin, 0.0)
        assertSame(active.points, flat.points)
        assertEquals(35, flat.elapsed)
        assertEquals(55, flat.remaining)
        assertSame(flat, flat.tradeOut())
    }

    @Test fun `the market expires at 90 even with no open position`() {
        val market = PredictionPreviewRound.ready().start(PredictionDirection.ABOVE, 10)
        val flat = market.advanceBy(35).tradeOut()
        val almostDone = flat.advanceBy(54)
        assertTrue(almostDone.running)
        assertTrue(almostDone.canStake)
        assertEquals(1, almostDone.remaining)
        val done = almostDone.advance()
        assertTrue(done.settled)
        assertFalse(done.running)
        assertFalse(done.active)
        assertFalse(done.canStake)
        assertEquals(90, done.elapsed)
        assertEquals(0, done.remaining)
        assertNull(done.direction)
        assertEquals(market.advanceBy(90).points, done.points)
        assertSame(done, done.advance())
    }

    @Test fun `reentry preserves the existing price band history and deadline`() {
        val market = PredictionPreviewRound.ready().start(PredictionDirection.BELOW, 10, 3_400.0)
        val flat = market.advanceBy(35).tradeOut().advanceBy(12)
        val reentered = flat.start(PredictionDirection.AROUND, 25, flat.price + 2_000)
        assertTrue(reentered.started)
        assertTrue(reentered.running)
        assertTrue(reentered.active)
        assertFalse(reentered.canStake)
        assertEquals(PredictionDirection.AROUND, reentered.direction)
        assertEquals(25, reentered.stake)
        assertEquals(flat.anchor, reentered.anchor, 0.0)
        assertEquals(flat.band, reentered.band, 0.0)
        assertEquals(flat.priceOrigin, reentered.priceOrigin, 0.0)
        assertEquals(flat.price, reentered.price, 0.0)
        assertSame(flat.points, reentered.points)
        assertEquals(47, reentered.elapsed)
        assertEquals(43, reentered.remaining)
        val done = reentered.advanceBy(43)
        assertTrue(done.settled)
        assertEquals(90, done.elapsed)
        assertEquals(0, done.remaining)
        assertEquals(market.advanceBy(90).points, done.points)
    }

    @Test fun `holding a position or settling the market prevents a restart`() {
        val market = PredictionPreviewRound.ready().start(PredictionDirection.ABOVE, 5)
        val holding = market.advanceBy(10)
        assertSame(holding, holding.start(PredictionDirection.BELOW, 100, 1.0))
        val settledMarkets = listOf(market.advanceBy(90), market.tradeOut().advanceBy(90))
        settledMarkets.forEach { settled ->
            assertFalse(settled.canStake)
            assertSame(settled, settled.start(PredictionDirection.AROUND, 25, 1.0))
            assertSame(settled, settled.tradeOut())
        }
    }

    @Test fun `ready explicitly opens a fresh market after the previous deadline`() {
        val expired = PredictionPreviewRound.ready().start(PredictionDirection.ABOVE, 10, 3_400.0)
            .tradeOut().advanceBy(90)
        val ready = PredictionPreviewRound.ready(expired.price)
        assertFalse(ready.started)
        assertFalse(ready.running)
        assertTrue(ready.canStake)
        assertEquals(0, ready.elapsed)
        val newMarket = ready.start(PredictionDirection.BELOW, 25)
        assertTrue(newMarket.started)
        assertTrue(newMarket.running)
        assertEquals(expired.price, newMarket.anchor, 0.0)
        assertEquals(expired.price, newMarket.priceOrigin, 0.0)
        assertEquals(expired.price, newMarket.points.single().price, 0.0)
        assertEquals(0, newMarket.elapsed)
        assertEquals(90, newMarket.remaining)
    }

    @Test fun `locking an older quote does not change the simulated market price origin`() {
        val quoted = PredictionPreviewRound.ready().advanceBy(48)
        val latest = quoted.advanceBy(37)
        val locked = latest.start(PredictionDirection.ABOVE, 10, quoted.price)
        val current = latest.start(PredictionDirection.ABOVE, 10)
        assertEquals(quoted.price, locked.anchor, 0.0)
        assertEquals(latest.price, locked.priceOrigin, 0.0)
        assertEquals(latest.price, locked.points.single().price, 0.0)
        assertEquals(current.advance().price, locked.advance().price, 0.0)
    }
}
