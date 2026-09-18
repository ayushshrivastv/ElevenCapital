package com.elevencapital.core.stock.flow

import com.elevencapital.core.stock.StockId

enum class OrderSide { BUY, SELL }

/** Only the stock routes established by the supplied flow. */
sealed interface StockDestination {
    data object Markets : StockDestination

    data class Detail(val stockId: StockId) : StockDestination

    /** Stock-specific order entry. Navigation alone never submits an order. */
    data class Order(val stockId: StockId, val side: OrderSide) : StockDestination
}

val StockDestination.selectedStockId: StockId?
    get() = when (this) {
        StockDestination.Markets -> null
        is StockDestination.Detail -> stockId
        is StockDestination.Order -> stockId
    }
