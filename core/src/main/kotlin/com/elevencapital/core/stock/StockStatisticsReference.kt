package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant

enum class StockMetricKey(val wireName: String, val unit: String) {
    MARKET_CAPITALIZATION("marketCapitalization", "USD"),
    LIQUIDITY("liquidity", "USD"),
    HOLDER_COUNT("holderCount", "count"),
    ORGANIC_SCORE("organicScore", "score"),
}

/** Exact-mint analytics, independent of the stock provider's quote denomination. */
data class StockMetricData(
    val value: BigDecimal?,
    val unit: String,
    val source: String?,
    val basis: String,
    val receivedAt: Instant?,
    val reason: String?,
) {
    init {
        require(value == null || value.signum() >= 0)
        require(value == null || (source != null && receivedAt != null && reason == null))
        require(value != null || !reason.isNullOrBlank())
    }
}

data class StockStatisticsReference(
    val network: String?,
    val mint: String?,
    val metrics: Map<StockMetricKey, StockMetricData>,
    val updatedAt: Instant? = null,
) {
    init {
        require(network == null || network == "solana")
        require((network == null) == (mint == null))
        require(metrics.keys == StockMetricKey.entries.toSet())
        metrics.forEach { (key, metric) -> require(metric.unit == key.unit) }
        metrics[StockMetricKey.HOLDER_COUNT]?.value?.longValueExact()
        metrics[StockMetricKey.ORGANIC_SCORE]?.value?.let { require(it <= BigDecimal("100")) }
    }

    fun toStatistics(): StockStatistics = StockStatistics(
        marketCapitalization = metrics.getValue(StockMetricKey.MARKET_CAPITALIZATION).value,
        liquidity = metrics.getValue(StockMetricKey.LIQUIDITY).value,
        holderCount = metrics.getValue(StockMetricKey.HOLDER_COUNT).value?.longValueExact(),
        organicScore = metrics.getValue(StockMetricKey.ORGANIC_SCORE).value,
        currencyCode = "USD",
        reference = this,
    )

    fun expire(snapshotAt: Instant, elapsedSinceSnapshotMillis: Long): StockStatisticsReference = copy(
        metrics = metrics.mapValues { (_, metric) ->
            val fresh = isReferenceDataFresh(metric.receivedAt, snapshotAt, elapsedSinceSnapshotMillis) &&
                (updatedAt == null || isReferenceDataFresh(updatedAt, snapshotAt, elapsedSinceSnapshotMillis))
            if (metric.value != null && !fresh) {
                metric.copy(value = null, reason = "Data expired. Updates resume automatically when the source recovers.")
            } else metric
        },
    )
}
