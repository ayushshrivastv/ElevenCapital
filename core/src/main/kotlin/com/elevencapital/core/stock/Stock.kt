package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant

/** Stable data-source identity. Symbols and display names may change independently. */
@JvmInline
value class StockId(val value: String) {
    init {
        require(value.isNotBlank()) { "A stock ID must not be blank." }
    }
}

/** Opaque asset key or remote image reference; the presentation layer resolves it. */
@JvmInline
value class StockLogoReference(val value: String) {
    init {
        require(value.isNotBlank()) { "A stock logo reference must not be blank." }
    }
}

data class Stock(
    val id: StockId,
    val symbol: String,
    val name: String,
    val quote: StockQuote,
    val logo: StockLogoReference? = null,
    val statistics: StockStatistics? = null,
    val description: String? = null,
    val charts: Map<ChartRange, List<StockPricePoint>> = emptyMap(),
    val activity: StockMarketActivity? = null,
    /** Provider-owned product page. It is optional and must not be synthesized from the symbol. */
    val informationUrl: String? = null,
) {
    init {
        require(symbol.isNotBlank()) { "A stock symbol must not be blank." }
        require(name.isNotBlank()) { "A stock name must not be blank." }
        require(description == null || description.length <= 20_000) { "A stock description is too long." }
        require(informationUrl == null || informationUrl.isNotBlank()) { "A stock information URL must not be blank." }
    }
}

/**
 * Values are supplied as decimals, never converted through Double.
 * changePercent is percentage points: 0.7 means +0.7%, not +70%.
 * A missing change or timestamp means the source did not provide it.
 */
data class StockQuote(
    val price: BigDecimal?,
    val currencyCode: String,
    val changeAmount: BigDecimal? = null,
    val changePercent: BigDecimal? = null,
    val asOf: Instant? = null,
) {
    init {
        require(price == null || price.signum() >= 0) { "A stock price must not be negative." }
        require(currencyCode.matches(Regex("[A-Z]{3}")) || currencyCode == "USDC") {
            "A quote unit must be an uppercase fiat currency code or USDC."
        }
    }
}

/**
 * Optional detail fields visible in the supplied stock reference.
 * Their definitions and availability belong to the eventual data provider.
 * Monetary values use currencyCode when supplied; missing values must not become zero.
 */
data class StockStatistics(
    val marketCapitalization: BigDecimal? = null,
    val liquidity: BigDecimal? = null,
    val holderCount: Long? = null,
    val organicScore: BigDecimal? = null,
    val currencyCode: String? = null,
    val reference: StockStatisticsReference? = null,
)

/** Chart intervals from the reference, independent of which stocks exist. */
enum class ChartRange {
    ONE_HOUR,
    ONE_DAY,
    ONE_WEEK,
    ONE_MONTH,
    YEAR_TO_DATE,
}

/** Points are supplied in source order; timestamps are instants, not display labels. */
data class StockPricePoint(
    val timestamp: Instant,
    val price: BigDecimal,
) {
    init {
        require(price.signum() >= 0) { "A chart price must not be negative." }
    }
}
