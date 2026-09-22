package com.elevencapital.app.data

import com.elevencapital.core.stock.StockPricePoint

/**
 * Append a genuine quote observation only to the same instrument, units and price basis.
 * This updates a line-series endpoint, never fabricates OHLC candles or historic trades.
 */
fun isChartBasisCompatible(chart: LiveChart, instrument: LiveInstrument): Boolean =
    chart.stockId == instrument.stock.id && chart.currency == instrument.stock.quote.currencyCode &&
        chart.basis != null && chart.basis == instrument.quoteBasis

fun alignChartObservation(chart: LiveChart, instrument: LiveInstrument): LiveChart {
    if (!isChartBasisCompatible(chart, instrument) || chart.status != "ok" || chart.points.isEmpty()) return chart
    val price = instrument.stock.quote.price ?: return chart
    val timestamp = instrument.stock.quote.asOf ?: instrument.quoteReceivedAt ?: return chart
    val last = chart.points.last()
    if (timestamp < last.timestamp || (timestamp == last.timestamp && price == last.price)) return chart
    val point = StockPricePoint(timestamp, price)
    val next = if (timestamp == last.timestamp) chart.points.dropLast(1) + point else chart.points + point
    // The next canonical server chart restores its complete historical sampling.
    return chart.copy(points = next.takeLast(5_000))
}
