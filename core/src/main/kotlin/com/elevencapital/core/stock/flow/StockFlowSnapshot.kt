package com.elevencapital.core.stock.flow

import com.elevencapital.core.stock.Stock

/** Stock data is resolved from the current repository catalog, never saved in navigation. */
data class StockFlowSnapshot(
    val destination: StockDestination,
    val stock: Stock?,
) {
    /** Markets has no selection; a removed selection remains on its route with no stock. */
    val isStockUnavailable: Boolean
        get() = destination.selectedStockId != null && stock == null
}
