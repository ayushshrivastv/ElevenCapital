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

/**
 * In-memory source for development and future feed integration.
 * Catalog replacement is atomic; invalid replacements never change the current snapshot.
 * StateFlow represents current state, so slow collectors may skip intermediate updates.
 */
class InMemoryStockRepository(initialStocks: List<Stock> = emptyList()) : StockRepository {
    private val mutableCatalog = MutableStateFlow(snapshotOf(initialStocks))

    override val catalog: StateFlow<List<Stock>> = mutableCatalog.asStateFlow()

    private var stocksById = mutableCatalog.value.associateBy { it.id }

    override fun getStock(id: StockId): Stock? = stocksById[id]

    override fun observeStock(id: StockId): Flow<Stock?> = catalog
        .map { stocks -> stocks.firstOrNull { it.id == id } }
        .distinctUntilChanged()

    /** Publishes data in the supplied order without a fixed symbol list or stock-specific code. */
    fun replaceCatalog(stocks: List<Stock>) {
        val next = snapshotOf(stocks, stocksById)
        stocksById = next.associateBy { it.id }
        mutableCatalog.value = next
    }
}

private fun snapshotOf(stocks: List<Stock>, previous: Map<StockId, Stock> = emptyMap()): List<Stock> {
    val ids = HashSet<StockId>()
    val snapshot = stocks.map { stock ->
        require(ids.add(stock.id)) { "Duplicate stock ID: ${stock.id.value}" }
        previous[stock.id]?.takeIf { it == stock } ?: stock.copy(
            charts = Collections.unmodifiableMap(
                stock.charts.mapValues { (_, points) ->
                    Collections.unmodifiableList(points.toList())
                },
            ),
        )
    }
    return Collections.unmodifiableList(snapshot)
}
