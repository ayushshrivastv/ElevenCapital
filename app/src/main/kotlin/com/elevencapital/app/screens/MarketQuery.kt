package com.elevencapital.app.screens

import java.math.BigDecimal

enum class MarketSource(val label: String, val provider: String?) {
    ALL("All sources", null),
    BACKED("Backed · xStocks", "backed"),
    BACKPACK("Backpack", "backpack"),
    PRESTOCKS("PreStocks · Pre-IPO", "prestocks"),
}

enum class MarketMovement(val label: String) {
    ALL("All changes"), GAINERS("Gainers"), LOSERS("Losers"),
}

enum class MarketSort(val label: String) {
    DEFAULT("Featured first"), NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"),
    PRICE_DESC("Price: high to low"), PRICE_ASC("Price: low to high"),
    CHANGE_DESC("Change: high to low"), CHANGE_ASC("Change: low to high"),
    VOLUME_DESC("Volume: high to low"), VOLUME_ASC("Volume: low to high"),
}

// Shared across Backpack and xStocks. Provider-specific suffixes are removed before matching,
// so each catalog presents the same recognizable companies first without hiding any listings.
private val FeaturedCompanies = listOf(
    "MSFT", "SPCX", "TSLA", "GOOGL", "META", "NFLX", "AAPL", "NVDA", "AMZN", "BRK.B",
    "JPM", "V", "MA", "LLY", "WMT", "KO", "DIS", "COIN", "AMD", "AVGO",
)

private fun shuffledFeaturedRank() = FeaturedCompanies.shuffled().withIndex()
    .associate { (index, symbol) -> symbol to index }

/** Stable during live updates, new on each app process, and deliberately different per provider. */
private val FeaturedCompanyRankByProvider: Map<String, Map<String, Int>> = run {
    val backpack = shuffledFeaturedRank()
    var backed = shuffledFeaturedRank()
    if (backed == backpack) {
        val rotated = FeaturedCompanies.drop(1) + FeaturedCompanies.first()
        backed = rotated.withIndex().associate { (index, symbol) -> symbol to index }
    }
    mapOf("backpack" to backpack, "backed" to backed)
}

private fun featuredRank(row: PrimaryStockRow): Int {
    val symbol = row.stock.symbol.removeSuffix(".US").removeSuffix("x").uppercase()
    val provider = row.stock.id.value.substringBefore(':')
    return FeaturedCompanyRankByProvider[provider]?.get(symbol) ?: Int.MAX_VALUE
}

data class MarketQuery(
    val text: String = "",
    val source: MarketSource = MarketSource.ALL,
    val movement: MarketMovement = MarketMovement.ALL,
    val sort: MarketSort = MarketSort.DEFAULT,
) {
    val filtered: Boolean get() = source != MarketSource.ALL || movement != MarketMovement.ALL || sort != MarketSort.DEFAULT
}

/** Query the full catalog, retaining independent provider identities and missing values. */
fun selectMarketRows(rows: List<PrimaryStockRow>, query: MarketQuery, watchedOnly: Boolean): List<PrimaryStockRow> {
    val terms = query.text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val selected = rows.filter { row ->
        (!watchedOnly || row.watched) &&
            (query.source.provider == null || row.stock.id.value.startsWith("${query.source.provider}:")) &&
            terms.all { row.stock.name.contains(it, ignoreCase = true) || row.stock.symbol.contains(it, ignoreCase = true) } &&
            when (query.movement) {
                MarketMovement.ALL -> true
                MarketMovement.GAINERS -> row.stock.quote.changePercent?.signum() == 1
                MarketMovement.LOSERS -> row.stock.quote.changePercent?.signum() == -1
            }
    }
    val value: ((PrimaryStockRow) -> BigDecimal?)? = when (query.sort) {
        MarketSort.PRICE_ASC, MarketSort.PRICE_DESC -> { row -> row.stock.quote.price }
        MarketSort.CHANGE_ASC, MarketSort.CHANGE_DESC -> { row -> row.stock.quote.changePercent }
        MarketSort.VOLUME_ASC, MarketSort.VOLUME_DESC -> { row ->
            val activity = row.stock.activity
            if (activity != null) activity.volume24h else row.volume
        }
        else -> null
    }
    if (value != null) {
        val descending = query.sort in setOf(MarketSort.PRICE_DESC, MarketSort.CHANGE_DESC, MarketSort.VOLUME_DESC)
        return selected.sortedWith { a, b ->
            val left = value(a)
            val right = value(b)
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                descending -> right.compareTo(left)
                else -> left.compareTo(right)
            }
        }
    }
    return when (query.sort) {
        MarketSort.NAME_ASC -> selected.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.stock.name })
        MarketSort.NAME_DESC -> selected.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.stock.name })
        MarketSort.DEFAULT -> selected.sortedBy(::featuredRank)
        else -> selected
    }
}
