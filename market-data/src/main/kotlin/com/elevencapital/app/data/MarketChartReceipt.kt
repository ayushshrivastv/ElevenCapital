package com.elevencapital.app.data

/** Legacy providers can use their local receipt; upstream cache timestamps retain their deadline. */
internal fun originalChartReceiptMillis(chart: LiveChart, localReceiptMillis: Long): Long? =
    try { chart.receivedAt?.toEpochMilli() ?: localReceiptMillis }
    catch (_: ArithmeticException) { null }

fun freshChartReceiptMillis(
    chart: LiveChart,
    nowMillis: Long,
    localReceiptMillis: Long = nowMillis,
): Long? = originalChartReceiptMillis(chart, localReceiptMillis)?.takeIf {
    it > 0 && it <= nowMillis && nowMillis - it < 300_000L
}
