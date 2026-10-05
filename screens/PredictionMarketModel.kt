package com.elevencapital.app.screens

import androidx.compose.ui.graphics.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

internal object PredictionPalette {
    val Background = Color.Black
    val Panel = Color(0xFF202020)
    val Border = Color(0xFF353535)
    val Muted = Color(0xFF9B9B9B)
    val Lime = Color(0xFFB8E77A)
    val Orange = Color(0xFFF19F41)
    val Purple = Color(0xFF373142)
    val White = Color(0xFFE6E6E6)
    val Red = Color(0xFFEF7E75)
}

internal enum class PredictionDirection(val label: String) {
    ABOVE("Above"), AROUND("Range"), BELOW("Below")
}

internal data class PredictionPricePoint(val second: Int, val price: Double)

/** A timestamped market observation supplied by the Pyth client, never a generated chart tick. */
internal data class PredictionOracleQuote(
    val priceUsd: Double,
    val confidenceUsd: Double,
    val publishTime: Long,
    val sourceId: String = "pyth",
) {
    init {
        require(priceUsd.isFinite() && priceUsd > 0.0)
        require(confidenceUsd.isFinite() && confidenceUsd >= 0.0)
        require(publishTime in 1..Long.MAX_VALUE / 1_000L)
        require(sourceId == "pyth") { "Unsupported prediction price source" }
    }

    /** Visual observation only. A heartbeat cannot refresh an old publication timestamp. */
    fun isWatchableAt(nowMillis: Long): Boolean =
        nowMillis >= publishTime * 1_000L && nowMillis - publishTime * 1_000L <= MAX_AGE_MILLIS &&
            confidenceUsd <= priceUsd / 100.0

    /** BTC uses the feed's one-percent confidence ceiling; ETH keeps its tighter pool rule. */
    fun isUsableAt(nowMillis: Long, bandUsd: Double = PredictionPreviewRound.BAND,
        btcMarket: Boolean = false): Boolean =
        if (btcMarket) isWatchableAt(nowMillis) else
            nowMillis >= publishTime * 1_000L && nowMillis - publishTime * 1_000L <= MAX_AGE_MILLIS &&
                confidenceUsd <= MAX_CONFIDENCE_USD && confidenceUsd <= bandUsd / 2.0

    /** The first authentic observation in the fixed boundary window can anchor a Watch chart. */
    fun isWatchableBoundary(boundarySecond: Long): Boolean =
        publishTime >= boundarySecond && publishTime - boundarySecond <= BOUNDARY_WINDOW_SECONDS &&
            confidenceUsd <= priceUsd / 100.0

    /** The client must fetch the first observation at/after this timestamp from the history API. */
    fun isBoundaryQuote(boundarySecond: Long, bandUsd: Double = PredictionPreviewRound.BAND,
        btcMarket: Boolean = false): Boolean =
        if (btcMarket) isWatchableBoundary(boundarySecond) else
            publishTime >= boundarySecond && publishTime - boundarySecond <= BOUNDARY_WINDOW_SECONDS &&
                confidenceUsd <= MAX_CONFIDENCE_USD && confidenceUsd <= bandUsd / 2.0

    companion object {
        const val MAX_AGE_MILLIS = 5_000L
        const val BOUNDARY_WINDOW_SECONDS = 5L
        const val MAX_CONFIDENCE_USD = 60.0
    }
}

/** The three mutually exclusive $1 settlement shares in one pool. */
internal data class PredictionSharePrices(
    val above: Double,
    val range: Double,
    val below: Double,
) {
    operator fun get(direction: PredictionDirection): Double = when (direction) {
        PredictionDirection.ABOVE -> above
        PredictionDirection.AROUND -> range
        PredictionDirection.BELOW -> below
    }

    val total: Double get() = above + range + below
}

/** Illustrative motion and local interactions only. No wallet, order or settlement calls. */
internal data class PredictionPreviewRound(
    val anchor: Double,
    val points: List<PredictionPricePoint>,
    val elapsed: Int,
    val direction: PredictionDirection? = null,
    val stake: Int = 5,
    val settled: Boolean = false,
    val priceOrigin: Double = anchor,
    val started: Boolean = false,
    val roundId: Long? = null,
    val elapsedMillis: Int = elapsed * 1_000,
    val entryPrice: Double? = null,
    val entryQuote: Double? = null,
    val anchorOracleQuote: PredictionOracleQuote? = null,
    val latestOracleQuote: PredictionOracleQuote? = null,
    val btcMarket: Boolean = false,
    /** Locked at pool creation. All users in one pool must see the same settlement boundaries. */
    val bandUsd: Double = BAND,
    /** Locked 90-second USD standard deviation estimated from observations before pool creation. */
    val forecastSigmaUsd: Double? = null,
) {
    init {
        require(anchor.isFinite() && anchor > 0.0)
        require(bandUsd.isFinite() && bandUsd > 0.0 && bandUsd < anchor &&
            BigDecimal.valueOf(bandUsd).stripTrailingZeros().scale() <= 2) {
            "Prediction band must be a positive cent amount below the market anchor"
        }
        require(!btcMarket || bandUsd in BTC_MIN_BAND_USD..BTC_MAX_BAND_USD) {
            "BTC prediction band is outside the supported range"
        }
        require(forecastSigmaUsd == null || (forecastSigmaUsd.isFinite() &&
            forecastSigmaUsd > 0.0 && forecastSigmaUsd <= MAX_FORECAST_SIGMA_USD)) {
            "Prediction volatility forecast must be positive and finite"
        }
    }

    val isLive: Boolean get() = anchorOracleQuote != null
    val price: Double get() = points.last().price
    val remaining: Int get() = if (started) (DURATION - elapsed).coerceAtLeast(0) else DURATION
    val remainingMillis: Int get() = if (started) (DURATION_MILLIS.toInt() - elapsedMillis).coerceAtLeast(0) else DURATION_MILLIS.toInt()
    val band: Double get() = bandUsd
    val running: Boolean get() = started && !settled && (!isLive || remainingMillis > 0)
    val active: Boolean get() = direction != null && !settled
    val canStake: Boolean get() = !active && !settled && (!isLive || canEnterLiveAt(clockMillis))
    private val clockMillis: Long get() = (roundId ?: 0L) * DURATION_MILLIS + elapsedMillis
    val openingReportExecutable: Boolean get() = roundId?.let {
        anchorOracleQuote?.isBoundaryQuote(it * DURATION, bandUsd, btcMarket)
    } == true

    fun canTradeLiveAt(nowMillis: Long): Boolean = isLive && !settled &&
        roundId == Math.floorDiv(nowMillis, DURATION_MILLIS) &&
        openingReportExecutable &&
        latestOracleQuote?.isUsableAt(nowMillis, bandUsd, btcMarket) == true

    /** Let the opening price settle for two seconds; new entries still close at the center divider. */
    fun canEnterLiveAt(nowMillis: Long): Boolean = canTradeLiveAt(nowMillis) &&
        roundId?.let {
            val startMillis = it * DURATION_MILLIS
            nowMillis >= startMillis + ENTRY_OPEN_DELAY_MILLIS &&
                nowMillis < startMillis + ENTRY_WINDOW_MILLIS
        } == true
    val outcome: PredictionDirection get() = outcomeFor(anchor)
    val positionOutcome: PredictionDirection? get() = direction?.let { outcome }
    val currentQuote: Double? get() = direction?.let { selected ->
        entryQuote?.let { quoteFor(selected) }
    }
    val positionValue: Double? get() = currentQuote?.let { quote ->
        entryQuote?.takeIf { it.isFinite() && it > 0.0 }?.let { paid -> stake * (quote / paid) }
    }

    private fun outcomeFor(referencePrice: Double): PredictionDirection = when {
        price > referencePrice + band -> PredictionDirection.ABOVE
        price < referencePrice - band -> PredictionDirection.BELOW
        else -> PredictionDirection.AROUND
    }

    /** One live snapshot of three complementary share prices against the pool's locked targets. */
    val sharePrices: PredictionSharePrices get() = sharePricesFor(anchor)

    fun quoteFor(direction: PredictionDirection, referencePrice: Double = anchor): Double =
        sharePricesFor(referencePrice)[direction]

    private fun sharePricesFor(referencePrice: Double): PredictionSharePrices {
        if (settled || (!isLive && remainingMillis == 0)) {
            return when (outcomeFor(referencePrice)) {
                PredictionDirection.ABOVE -> PredictionSharePrices(1.0, 0.0, 0.0)
                PredictionDirection.AROUND -> PredictionSharePrices(0.0, 1.0, 0.0)
                PredictionDirection.BELOW -> PredictionSharePrices(0.0, 0.0, 1.0)
            }
        }
        // The pre-round volatility forecast is locked alongside the targets. Conditional on
        // the latest market observation, model the remaining price change as zero-drift normal
        // noise with variance proportional to time. Pyth confidence is a floor on uncertainty.
        // These are local practice indications, not externally executable order-flow prices.
        val uncertainty = maxOf(
            MIN_UNCERTAINTY,
            latestOracleQuote?.confidenceUsd ?: anchorOracleQuote?.confidenceUsd ?: 0.0,
            (forecastSigmaUsd ?: bandUsd) * sqrt(remainingMillis.coerceAtLeast(1).toDouble() / DURATION_MILLIS),
        )
        val upperZ = (referencePrice + band - price) / uncertainty
        val lowerZ = (referencePrice - band - price) / uncertainty
        val above = QUOTE_FLOOR + (1.0 - 3.0 * QUOTE_FLOOR) * (1.0 - standardNormalCdf(upperZ))
        val below = QUOTE_FLOOR + (1.0 - 3.0 * QUOTE_FLOOR) * standardNormalCdf(lowerZ)
        // Trade at the four-decimal prices shown in the sheet. The remainder keeps
        // the displayed three-way book at exactly $1, including after rounding.
        val aboveTicks = round(above * SHARE_PRICE_TICKS).toInt()
        val belowTicks = round(below * SHARE_PRICE_TICKS).toInt()
        val rangeTicks = SHARE_PRICE_TICKS - aboveTicks - belowTicks
        return PredictionSharePrices(
            aboveTicks.toDouble() / SHARE_PRICE_TICKS,
            rangeTicks.toDouble() / SHARE_PRICE_TICKS,
            belowTicks.toDouble() / SHARE_PRICE_TICKS,
        )
    }

    fun advance(): PredictionPreviewRound {
        if (settled) return this
        roundId?.let { id -> return syncTo(id * DURATION_MILLIS + (elapsed + 1L) * 1_000L) }
        val nextSecond = elapsed + 1
        val nextPrice = round((priceOrigin + sin(nextSecond * .16) * 4.7 +
            sin(nextSecond * .047) * 7.2 + cos(nextSecond * .39) * .8 - .8) * 100) / 100
        val next = PredictionPricePoint(nextSecond, nextPrice)
        return copy(points = (points + next).takeLast(DURATION + 2), elapsed = nextSecond,
            elapsedMillis = nextSecond * 1_000,
            settled = started && nextSecond >= DURATION)
    }

    /** A new position buys at current odds for the pool's fixed targets; its market entry price is recorded. */
    fun start(direction: PredictionDirection, stake: Int, lockedPrice: Double = price): PredictionPreviewRound {
        if (!canStake || stake <= 0) return this
        val market = if (started) this else PredictionPreviewRound(
            lockedPrice, listOf(PredictionPricePoint(0, price)), 0, priceOrigin = price, started = true,
            bandUsd = bandUsd, forecastSigmaUsd = forecastSigmaUsd,
        )
        val quote = market.quoteFor(direction)
        if (!quote.isFinite() || quote <= 0.0) return this
        return market.copy(direction = direction, stake = stake, entryPrice = price, entryQuote = quote)
    }

    fun tradeOut(): PredictionPreviewRound = if (active) copy(direction = null, entryPrice = null, entryQuote = null) else this

    /** Advances this shared pool to wall time, retaining its identity and settlement at the boundary. */
    fun syncTo(nowMillis: Long): PredictionPreviewRound {
        val id = roundId ?: return this
        if (settled) return this
        val millisNow = (nowMillis - id * DURATION_MILLIS).coerceIn(0L, DURATION_MILLIS).toInt()
        if (isLive) return if (millisNow <= elapsedMillis) this else
            copy(elapsed = millisNow / 1_000, elapsedMillis = millisNow)
        val elapsedNow = millisNow / 1_000
        if (elapsedNow == elapsed && millisNow == elapsedMillis) return this
        if (elapsedNow == elapsed) return copy(elapsedMillis = millisNow)
        val startSecond = id * DURATION.toLong()
        return copy(
            points = (0..elapsedNow).map { second ->
                PredictionPricePoint(second, sharedPriceAt(startSecond + second))
            },
            elapsed = elapsedNow,
            elapsedMillis = millisNow,
            settled = elapsedNow == DURATION,
        )
    }

    /** Keeps actual observation times; wall-clock catch-up never manufactures missing prices. */
    fun ingest(quote: PredictionOracleQuote, nowMillis: Long,
        allowWatchOnlyObservation: Boolean = false): PredictionPreviewRound {
        check(isLive) { "Only live rounds accept oracle observations" }
        val current = syncTo(nowMillis)
        val startSecond = requireNotNull(roundId) * DURATION
        val quoteIsFresh = if (allowWatchOnlyObservation) quote.isWatchableAt(nowMillis)
            else quote.isUsableAt(nowMillis, bandUsd, btcMarket)
        if (settled || !quoteIsFresh || quote.publishTime < startSecond ||
            quote.publishTime >= startSecond + DURATION ||
            quote.publishTime <= (latestOracleQuote?.publishTime ?: 0L)) return current
        return current.copy(
            points = (points + PredictionPricePoint((quote.publishTime - startSecond).toInt(), quote.priceUsd))
                .takeLast(DURATION + 2),
            latestOracleQuote = quote,
        )
    }

    /** Settlement requires an explicit historical boundary observation, even after a long app pause. */
    fun settleLive(finalQuote: PredictionOracleQuote, nowMillis: Long): PredictionPreviewRound {
        check(isLive) { "A live boundary observation requires a live pool" }
        val deadline = (requireNotNull(roundId) + 1L) * DURATION
        require(finalQuote.isBoundaryQuote(deadline, bandUsd, btcMarket) && finalQuote.publishTime <= nowMillis / 1_000L) {
            "Final observation does not cover this pool boundary"
        }
        if (settled) {
            require(latestOracleQuote == finalQuote) { "Settled observation cannot change" }
            return this
        }
        return copy(points = points + PredictionPricePoint(DURATION, finalQuote.priceUsd),
            elapsed = DURATION, elapsedMillis = DURATION_MILLIS.toInt(), settled = true,
            latestOracleQuote = finalQuote)
    }

    fun advanceBy(seconds: Int): PredictionPreviewRound {
        roundId?.let { id ->
            return syncTo(id * DURATION_MILLIS + (elapsed + seconds.coerceIn(0, DURATION)).coerceAtMost(DURATION) * 1_000L)
        }
        var next = this
        repeat(seconds.coerceIn(0, DURATION)) { next = next.advance() }
        return next
    }

    companion object {
        const val DURATION = 90
        const val BAND = 4.0
        const val BTC_MIN_BAND_USD = 0.01
        const val BTC_MAX_BAND_USD = 100.0
        const val MAX_FORECAST_SIGMA_USD = 240.0
        const val DURATION_MILLIS = DURATION * 1_000L
        const val ENTRY_WINDOW_MILLIS = DURATION_MILLIS / 2
        const val ENTRY_OPEN_DELAY_MILLIS = 2_000L
        private const val DAY_SECONDS = 86_400L
        private const val MIN_UNCERTAINTY = 0.01
        private const val QUOTE_FLOOR = 0.02
        private const val SHARE_PRICE_TICKS = 10_000

        /** Standard-normal CDF; the polynomial's absolute error is below 1.5e-7. */
        private fun standardNormalCdf(value: Double): Double {
            val magnitude = abs(value)
            val t = 1.0 / (1.0 + 0.2316419 * magnitude)
            val tail = 0.3989422804014327 * exp(-magnitude * magnitude / 2.0) * t *
                (0.319381530 + t * (-0.356563782 + t *
                    (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
            return (if (value >= 0.0) 1.0 - tail else tail).coerceIn(0.0, 1.0)
        }

        /** Opens the current fixed epoch using the historical first-after-start Pyth observation. */
        fun liveAt(nowMillis: Long, anchorQuote: PredictionOracleQuote, bandUsd: Double = BAND,
            forecastSigmaUsd: Double? = null): PredictionPreviewRound =
            liveForPool(Math.floorDiv(nowMillis, DURATION_MILLIS), anchorQuote, nowMillis, bandUsd, forecastSigmaUsd)

        /** Restores a past live pool without synthesizing its unknown price path or final outcome. */
        fun liveForPool(
            poolId: Long,
            anchorQuote: PredictionOracleQuote,
            nowMillis: Long,
            bandUsd: Double = BAND,
            forecastSigmaUsd: Double? = null,
            allowWatchOnlyAnchor: Boolean = false,
        ): PredictionPreviewRound {
            require(poolId >= 0 && poolId < Long.MAX_VALUE / DURATION_MILLIS)
            val startSecond = poolId * DURATION
            val anchorIsValid = if (allowWatchOnlyAnchor) anchorQuote.isWatchableBoundary(startSecond)
                else anchorQuote.isBoundaryQuote(startSecond, bandUsd)
            require(anchorIsValid && anchorQuote.publishTime <= nowMillis / 1_000L) {
                "Opening observation does not cover this pool boundary"
            }
            return PredictionPreviewRound(
                anchor = anchorQuote.priceUsd,
                points = listOf(PredictionPricePoint((anchorQuote.publishTime - startSecond).toInt(), anchorQuote.priceUsd)),
                elapsed = 0, started = true, roundId = poolId,
                anchorOracleQuote = anchorQuote, latestOracleQuote = anchorQuote,
                btcMarket = allowWatchOnlyAnchor, bandUsd = bandUsd,
                forecastSigmaUsd = forecastSigmaUsd,
            ).syncTo(nowMillis)
        }

        /** The same wall-clock window produces the same pool ID, anchor, and price path on every client. */
        fun sharedAt(nowMillis: Long): PredictionPreviewRound {
            val id = Math.floorDiv(nowMillis, DURATION_MILLIS)
            val anchor = sharedPriceAt(id * DURATION.toLong())
            return PredictionPreviewRound(
                anchor = anchor,
                points = listOf(PredictionPricePoint(0, anchor)),
                elapsed = 0,
                priceOrigin = anchor,
                started = true,
                roundId = id,
            ).syncTo(nowMillis)
        }

        private fun sharedPriceAt(epochSecond: Long): Double {
            val phase = Math.floorMod(epochSecond, DAY_SECONDS).toDouble() * 2.0 * PI / DAY_SECONDS
            val price = 3_482.64 + sin(phase * 2) * 28 + sin(phase * 11) * 9 +
                cos(phase * 131) * 2.4 + sin(phase * 683) * 1.3
            return round(price * 100) / 100
        }

        fun ready(price: Double = 3_482.64): PredictionPreviewRound =
            PredictionPreviewRound(price, listOf(PredictionPricePoint(0, price)), 0)
    }
}

internal fun predictionMoney(value: Double, decimals: Boolean = true): String =
    "$" + DecimalFormat(if (decimals) "#,##0.00" else "#,##0", DecimalFormatSymbols(Locale.US)).format(value)
