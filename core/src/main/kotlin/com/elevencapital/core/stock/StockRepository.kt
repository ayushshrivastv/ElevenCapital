package com.elevencapital.core.stock

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface StockRepository {
    /** The current ordered catalog, with one entry per stable stock ID. */
    val catalog: StateFlow<List<Stock>>

    /** Reads the same snapshot used by the catalog. Unknown or removed IDs return null. */
    fun getStock(id: StockId): Stock?

    /** Emits the current detail and subsequent changes, including null on removal. */
    fun observeStock(id: StockId): Flow<Stock?>
}

/** Immutable input for preparation. Capturing it performs no catalog traversal. */
class StockCatalogPreparationBase internal constructor(
    internal val repositoryKey: Any,
    internal val generation: Long,
    val stockList: List<Stock>,
    internal val index: Map<StockId, Stock>,
) {
    fun getStock(id: StockId): Stock? = index[id]
}

/** Validated, immutable output; only its originating repository can adopt it. */
class PreparedStockCatalog internal constructor(
    internal val repositoryKey: Any,
    internal val generation: Long,
    internal val baseList: List<Stock>,
    val stocks: List<Stock>,
    internal val index: Map<StockId, Stock>,
)

/**
 * In-memory source for development and future feed integration.
 * Catalog replacement is atomic; invalid replacements never change the current snapshot.
 * StateFlow represents current state, so slow collectors may skip intermediate updates.
 */
class InMemoryStockRepository(initialStocks: List<Stock> = emptyList()) : StockRepository {
    private val mutableCatalog = MutableStateFlow(snapshotOf(initialStocks))

    override val catalog: StateFlow<List<Stock>> = mutableCatalog.asStateFlow()

    private val repositoryKey = Any()
    private var generation = 0L
    @Volatile private var stocksById = indexOf(mutableCatalog.value)

    override fun getStock(id: StockId): Stock? = stocksById[id]

    override fun observeStock(id: StockId): Flow<Stock?> = catalog
        .map { stocks -> stocks.firstOrNull { it.id == id } }
        .distinctUntilChanged()

    /** Capture on the publication owner before starting work on another dispatcher. */
    @Synchronized
    fun capturePreparationBase(): StockCatalogPreparationBase =
        StockCatalogPreparationBase(repositoryKey, generation, mutableCatalog.value, stocksById)

    /** Validation, defensive copying and indexing use only the captured immutable base.
     * Callers may run this on a background dispatcher without reading live repository state. */
    fun prepareCatalog(stocks: List<Stock>, base: StockCatalogPreparationBase): PreparedStockCatalog {
        require(base.repositoryKey === repositoryKey) { "Preparation base belongs to another repository." }
        val next = snapshotOf(stocks, base.index)
        val unchanged = next.size == base.stockList.size && next.indices.all { next[it] === base.stockList[it] }
        return PreparedStockCatalog(repositoryKey, base.generation, base.stockList,
            if (unchanged) base.stockList else next, if (unchanged) base.index else indexOf(next))
    }

    /** Publish on the same owner as chart/catalog edits. A stale result changes nothing.
     * Every accepted publication retires its base, including a result with unchanged rows. */
    @Synchronized
    fun adoptPreparedCatalog(prepared: PreparedStockCatalog): Boolean {
        if (prepared.repositoryKey !== repositoryKey || prepared.generation != generation ||
            prepared.baseList !== mutableCatalog.value) return false
        stocksById = prepared.index
        generation++
        if (prepared.stocks !== mutableCatalog.value) mutableCatalog.value = prepared.stocks
        return true
    }

    /** Publishes data in the supplied order without a fixed symbol list or stock-specific code. */
    fun replaceCatalog(stocks: List<Stock>) {
        while (true) {
            val prepared = prepareCatalog(stocks, capturePreparationBase())
            if (adoptPreparedCatalog(prepared)) return
        }
    }
}

private fun indexOf(stocks: List<Stock>): Map<StockId, Stock> =
    Collections.unmodifiableMap(stocks.associateBy { it.id })

private fun snapshotOf(stocks: List<Stock>, previous: Map<StockId, Stock> = emptyMap()): List<Stock> {
    val ids = HashSet<StockId>()
    val snapshot = stocks.map { stock ->
        require(ids.add(stock.id)) { "Duplicate stock ID: ${stock.id.value}" }
        previous[stock.id]?.takeIf { it == stock } ?: stock.copy(
            charts = if (stock.charts.isEmpty()) emptyMap() else Collections.unmodifiableMap(
                stock.charts.mapValues { (_, points) ->
                    Collections.unmodifiableList(points.toList())
                },
            ),
            deployments = if (stock.deployments.isEmpty()) emptyList() else
                Collections.unmodifiableList(stock.deployments.toList()),
            statistics = stock.statistics?.let { statistics ->
                statistics.reference?.let { reference -> statistics.copy(reference = reference.copy(
                    metrics = Collections.unmodifiableMap(reference.metrics.toMap()),
                )) } ?: statistics
            },
        )
    }
    return Collections.unmodifiableList(snapshot)
}
