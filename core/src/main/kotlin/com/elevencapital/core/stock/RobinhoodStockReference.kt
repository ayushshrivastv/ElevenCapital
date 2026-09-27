package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant

/** Robinhood's original-share bid/ask and daily data; volume is a count of shares, never USD turnover. */
data class RobinhoodStockReference(
    val bid: BigDecimal,
    val ask: BigDecimal,
    val dailyHigh: BigDecimal?,
    val dailyLow: BigDecimal?,
    val dailyTradingVolume: BigDecimal?,
    val currentMultiplier: BigDecimal,
    val tokenDecimals: Int,
    val asOf: Instant,
    val receivedAt: Instant,
    val isTradingHalt: Boolean,
) {
    init {
        fun valid(value: BigDecimal): Boolean = value.signum() > 0 && value.precision() <= 128 &&
            value.scale() in -100..100 && value <= BigDecimal("1e40")
        require(valid(bid) && valid(ask) && ask >= bid)
        require(valid(currentMultiplier))
        require(tokenDecimals in 0..36)
        require(dailyHigh == null || valid(dailyHigh))
        require(dailyLow == null || valid(dailyLow))
        require(dailyHigh == null || dailyLow == null || dailyHigh >= dailyLow)
        require(dailyTradingVolume == null || valid(dailyTradingVolume))
        require(!receivedAt.isBefore(asOf))
    }
}
