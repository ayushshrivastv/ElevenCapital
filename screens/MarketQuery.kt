package com.elevencapital.app.screens

import java.math.BigDecimal
import kotlin.random.Random

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
    "JPM", "V", "MA", "LLY", "WMT", "KO", "DIS", "COIN", "AMD", "NKE",
)

/** One shuffle per launch; quote updates and screen changes must share this order. */
internal class MarketFeaturedOrder(random: Random = Random.Default) {
    private val ranksByProvider: Map<String, Map<String, Int>>

    init {
        val backpack = shuffledCompanies(random)
        val backed = shuffledCompanies(random)
        // Keep the providers distinct even if a deterministic random source repeats its shuffle.
        if (backed == backpack) backed.swap(backed.lastIndex, backed.lastIndex - 1)
        ranksByProvider = mapOf("backpack" to backpack, "backed" to backed).mapValues { (_, symbols) ->
            symbols.withIndex().associate { (index, symbol) -> symbol to index }
        }
    }

    fun rank(row: PrimaryStockRow): Int {
        val symbol = row.stock.symbol.removeSuffix(".US").removeSuffix("x").uppercase()
        val provider = row.stock.id.value.substringBefore(':')
        return ranksByProvider[provider]?.get(symbol) ?: Int.MAX_VALUE
    }

    private fun shuffledCompanies(random: Random): MutableList<String> {
        val symbols = FeaturedCompanies.shuffled(random).toMutableList()
        val priority = setOf("NKE", "SPCX")
        for (symbol in priority) {
            val index = symbols.indexOf(symbol)
            // At most two providers occupy each rank in All sources, so the first five
            // provider ranks put both required listings within the first ten combined rows.
            if (index >= 5) {
                val destination = (0 until 5).filter { symbols[it] !in priority }.random(random)
                symbols.swap(index, destination)
            }
        }
        return symbols
    }

    private fun MutableList<String>.swap(first: Int, second: Int) {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }
}

private val LaunchMarketFeaturedOrder = MarketFeaturedOrder()

data class MarketQuery(
    val text: String = "",
    val source: MarketSource = MarketSource.ALL,
    val movement: MarketMovement = MarketMovement.ALL,
    val sort: MarketSort = MarketSort.DEFAULT,
) {
    val filtered: Boolean get() = source != MarketSource.ALL || movement != MarketMovement.ALL || sort != MarketSort.DEFAULT
}

/** Query the full catalog, retaining independent provider identities and missing values. */
fun selectMarketRows(rows: List<PrimaryStockRow>, query: MarketQuery, watchedOnly: Boolean): List<PrimaryStockRow> =
    selectMarketIndices(rows, query, watchedOnly).map { rows[it] }

/**
 * Quotes change much more often than catalog membership. Retain only the previous catalog and
 * selection, and reuse its index order when none of this query's inputs changed. Selected rows
 * still come from the newest catalog, so price, artwork, watch state, and accessibility stay live.
 */
internal class MarketRowSelector(private val featuredOrder: MarketFeaturedOrder = LaunchMarketFeaturedOrder) {
    private var previousRows: List<PrimaryStockRow>? = null
    private var previousQuery: MarketQuery? = null
    private var previousWatchedOnly = false
    private var selectedIndices: List<Int> = emptyList()
    private var selectedRows: List<PrimaryStockRow> = emptyList()

    fun select(rows: List<PrimaryStockRow>, query: MarketQuery, watchedOnly: Boolean): List<PrimaryStockRow> {
        val previous = previousRows
        val sameQuery = query == previousQuery && watchedOnly == previousWatchedOnly
        if (sameQuery && rows === previous) return selectedRows
        val sameOrder = sameQuery && previous != null && previous.size == rows.size &&
            rows.indices.all { index -> sameSelectionInputs(previous[index], rows[index], query, watchedOnly) }
        if (!sameOrder) selectedIndices = selectMarketIndices(rows, query, watchedOnly, featuredOrder)
        // An update outside the filtered result should not invalidate the LazyColumn at all.
        if (selectedIndices.size != selectedRows.size || selectedIndices.indices.any { index ->
                rows[selectedIndices[index]] !== selectedRows[index]
            }) {
            selectedRows = selectedIndices.map { rows[it] }
        }
        previousRows = rows
        previousQuery = query
        previousWatchedOnly = watchedOnly
        return selectedRows
    }
}

private fun sameSelectionInputs(
    previous: PrimaryStockRow,
    current: PrimaryStockRow,
    query: MarketQuery,
    watchedOnly: Boolean,
): Boolean {
    if (previous === current) return true
    val before = previous.stock
    val after = current.stock
    if (before.id != after.id || before.name != after.name || before.symbol != after.symbol ||
        (watchedOnly && previous.watched != current.watched)) return false
    if (query.movement != MarketMovement.ALL &&
        before.quote.changePercent?.signum() != after.quote.changePercent?.signum()) return false
    return when (query.sort) {
        MarketSort.PRICE_ASC, MarketSort.PRICE_DESC -> before.quote.price == after.quote.price
        MarketSort.CHANGE_ASC, MarketSort.CHANGE_DESC -> before.quote.changePercent == after.quote.changePercent
        MarketSort.VOLUME_ASC, MarketSort.VOLUME_DESC -> marketVolume(previous) == marketVolume(current)
        else -> true
    }
}

private val MarketSearchWhitespace = Regex("\\s+")

private fun marketVolume(row: PrimaryStockRow): BigDecimal? {
    val activity = row.stock.activity
    return if (activity != null) activity.volume24h else row.volume
}

private fun selectMarketIndices(
    rows: List<PrimaryStockRow>,
    query: MarketQuery,
    watchedOnly: Boolean,
    featuredOrder: MarketFeaturedOrder = LaunchMarketFeaturedOrder,
): List<Int> {
    val terms = query.text.trim().split(MarketSearchWhitespace).filter(String::isNotBlank)
    val sourcePrefix = query.source.provider?.let { "$it:" }
    val selected = rows.indices.filter { index ->
        val row = rows[index]
        (!watchedOnly || row.watched) &&
            (sourcePrefix == null || row.stock.id.value.startsWith(sourcePrefix)) &&
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
        MarketSort.VOLUME_ASC, MarketSort.VOLUME_DESC -> ::marketVolume
        else -> null
    }
    if (value != null) {
        val descending = query.sort == MarketSort.PRICE_DESC || query.sort == MarketSort.CHANGE_DESC ||
            query.sort == MarketSort.VOLUME_DESC
        return selected.sortedWith { a, b ->
            val left = value(rows[a])
            val right = value(rows[b])
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
        MarketSort.NAME_ASC -> selected.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { rows[it].stock.name })
        MarketSort.NAME_DESC -> selected.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { rows[it].stock.name })
        MarketSort.DEFAULT -> {
            // There are only 20 featured ranks. Bucket them once, preserving provider/catalog
            // order within each rank, instead of normalizing symbols inside an n log n sort.
            val featured = Array(FeaturedCompanies.size) { mutableListOf<Int>() }
            val remaining = ArrayList<Int>(selected.size)
            for (index in selected) {
                val rank = featuredOrder.rank(rows[index])
                if (rank in featured.indices) featured[rank].add(index) else remaining.add(index)
            }
            buildList(selected.size) {
                featured.forEach { addAll(it) }
                addAll(remaining)
            }
        }
        else -> selected
    }
}
