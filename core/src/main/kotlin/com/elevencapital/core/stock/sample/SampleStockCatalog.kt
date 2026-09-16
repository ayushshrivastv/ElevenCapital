package com.elevencapital.core.stock.sample

import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal

/**
 * Sample development data transcribed from references/03-stock-overview.png.
 * This is not a live quote, a supported-instrument list, or a trading integration.
 * No timestamp, chart points, logo asset, or exact statistics have been invented.
 */
fun sampleStockCatalog(): List<Stock> = listOf(
    Stock(
        id = StockId("sample-msftx"),
        symbol = "MSFTx",
        name = "Microsoft xStock",
        quote = StockQuote(
            price = BigDecimal("497.68"),
            currencyCode = "USD",
            changeAmount = BigDecimal("3.59"),
            changePercent = BigDecimal("0.7"),
        ),
    ),
)
