package com.elevencapital.core.stock.flow

import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Markets -> detail -> order, with explicit reverse transitions and no history stack.
 * Invoke navigation actions from one UI owner. The repository remains the source of stock data.
 */
class StockFlowController(private val repository: StockRepository) {
    private val mutableDestination = MutableStateFlow<StockDestination>(StockDestination.Markets)

    val destination: StateFlow<StockDestination> = mutableDestination.asStateFlow()

    /** Opens a catalog selection from Markets. Missing IDs leave the destination unchanged. */
    fun openStock(stockId: StockId): Boolean {
        if (destination.value != StockDestination.Markets || repository.getStock(stockId) == null) {
            return false
        }
        mutableDestination.value = StockDestination.Detail(stockId)
        return true
    }

    /** Opens stock-specific Buy or Sell entry only while its detail is selected and available. */
    fun openOrder(side: OrderSide): Boolean {
        val detail = destination.value as? StockDestination.Detail ?: return false
        if (repository.getStock(detail.stockId) == null) return false
        mutableDestination.value = StockDestination.Order(detail.stockId, side)
        return true
    }

    /** Order entry returns to its detail, detail returns to Markets, and Markets has no local back. */
    fun goBack(): Boolean {
        mutableDestination.value = when (val current = destination.value) {
            StockDestination.Markets -> return false
            is StockDestination.Detail -> StockDestination.Markets
            is StockDestination.Order -> StockDestination.Detail(current.stockId)
        }
        return true
    }

    /** A synchronous read of current navigation and current catalog data. */
    fun snapshot(): StockFlowSnapshot = resolve(destination.value, repository.catalog.value)

    /** Observes route changes and changes or removal of the selected stock. */
    fun observeSnapshots(): Flow<StockFlowSnapshot> = combine(destination, repository.catalog) { currentDestination, stocks ->
        resolve(currentDestination, stocks)
    }.distinctUntilChanged()

    private fun resolve(
        currentDestination: StockDestination,
        stocks: List<Stock>,
    ): StockFlowSnapshot = StockFlowSnapshot(
        destination = currentDestination,
        stock = currentDestination.selectedStockId?.let { id ->
            stocks.firstOrNull { it.id == id }
        },
    )
}
