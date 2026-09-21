package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant

/** Same-listing 24h turnover and buy-minus-sell flow; never inferred from price change. */
data class StockMarketActivity(
    val currencyCode: String,
    val source: String,
    val scope: String,
    val volume24h: BigDecimal?,
    val netVolume24h: BigDecimal?,
    val receivedAt: Instant?,
    val updatedAt: Instant?,
    val volumeReason: String?,
    val netVolumeReason: String?,
) {
    init {
        require(currencyCode in setOf("USD", "USDC"))
        require(source in setOf("Jupiter", "Backpack", "Yahoo"))
        require(scope in setOf("solana_token", "external_market", "underlying_share"))
        require(source != "Jupiter" || (scope == "solana_token" && currencyCode == "USD"))
        require(source != "Backpack" || (scope == "external_market" && currencyCode == "USDC"))
        require(source != "Yahoo" || (scope == "underlying_share" && currencyCode == "USD"))
        require(volume24h == null || volume24h.signum() >= 0)
        require(netVolume24h == null || (volume24h != null && netVolume24h.abs() <= volume24h))
        require(if (volume24h == null) !volumeReason.isNullOrBlank() else volumeReason == null && receivedAt != null)
        require(if (netVolume24h == null) !netVolumeReason.isNullOrBlank() else netVolumeReason == null && receivedAt != null)
        require(source != "Jupiter" || volume24h == null || updatedAt != null)
    }

    fun expire(snapshotAt: Instant, elapsedSinceSnapshotMillis: Long): StockMarketActivity {
        val fresh = isReferenceDataFresh(receivedAt, snapshotAt, elapsedSinceSnapshotMillis) &&
            (updatedAt == null || isReferenceDataFresh(updatedAt, snapshotAt, elapsedSinceSnapshotMillis))
        return if (fresh) this else copy(
            volume24h = null, netVolume24h = null,
            volumeReason = if (volume24h == null) volumeReason else "Volume data expired. Updates resume automatically when the source recovers.",
            netVolumeReason = if (netVolume24h == null) netVolumeReason else "Net volume data expired. Updates resume automatically when the source recovers.",
        )
    }
}
