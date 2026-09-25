package com.elevencapital.app.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.ReferenceMotion
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.StockIcon
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.StockMarketActivity
import com.elevencapital.core.stock.StockMetricData
import com.elevencapital.core.stock.StockMetricKey
import com.elevencapital.core.stock.StockPricePoint
import com.elevencapital.core.stock.StockStatisticsReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Optional, provider-owned facts needed by the terminal reference. */
data class DetailPresentation(
    val providerLabel: String? = null,
    val ageLabel: String? = null,
    val verified: Boolean = false,
    val headerLogo: StockLogoReference? = null,
    val periodReturns: Map<DetailPeriod, BigDecimal> = emptyMap(),
    val volume24h: BigDecimal? = null,
    val netVolume24h: BigDecimal? = null,
    val traders24h: Long? = null,
    val netBuyers24h: Long? = null,
    val sellVolumeFraction: Float? = null,
    val sellTraderFraction: Float? = null,
    val netBuyTrend: List<BigDecimal> = emptyList(),
    val volumeChangePercent: BigDecimal? = null,
    val liquidityChangePercent: BigDecimal? = null,
    val holdersChangePercent: BigDecimal? = null,
    val stockInfoCells: List<DetailInfoCell> = emptyList(),
    val chartBounds: Map<ChartRange, DetailChartBounds> = emptyMap(),
    val rangeChanges: Map<ChartRange, DetailRangeChange> = emptyMap(),
    val feedRows: List<DetailFeedRow> = emptyList(),
    val news: List<DetailNewsItem> = emptyList(),
)

enum class DetailPeriod(val label: String) {
    FIVE_MINUTES("5m"), ONE_HOUR("1h"), SIX_HOURS("6h"), ONE_DAY("24h"),
}

data class DetailInfoCell(val label: String, val value: String, val badge: String? = null)
data class DetailChartBounds(val low: BigDecimal, val high: BigDecimal)
data class DetailRangeChange(val amount: BigDecimal?, val percent: BigDecimal?)
data class DetailChartContext(
    val range: ChartRange,
    val currency: String,
    val sourceLabel: String? = null,
)

/** Display facts supplied by a stock-trade feed; no quote or participant is inferred. */
data class DetailFeedRow(
    val ageLabel: String,
    val side: DetailFeedSide,
    val price: BigDecimal,
    val volume: BigDecimal,
    val quantity: BigDecimal,
    val participantLabel: String? = null,
)

enum class DetailFeedSide { BUY, SELL }

/** Source-provided news content. Asset references resolve independently of stock IDs. */
data class DetailNewsItem(
    val author: String,
    val ageLabel: String,
    val body: String,
    val authorImageReference: String? = null,
    val imageReference: String? = null,
    val viewsLabel: String? = null,
    val likesLabel: String? = null,
)

enum class DetailSection(val label: String) {
    OVERVIEW("Overview"), ORDER_BOOK("Order book"),
}

/** The toolbar and order actions stay fixed; the section strip pins below the toolbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StockDetailScreen(
    stock: Stock,
    onBack: () -> Unit,
    onBuy: () -> Unit,
    isWatched: Boolean,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
    detail: DetailPresentation = DetailPresentation(),
    initialSection: DetailSection = DetailSection.OVERVIEW,
    listState: LazyListState? = null,
    onRangeSelected: (ChartRange) -> Unit = {},
    priceNotice: String? = null,
    chartMessage: String? = null,
    chartContext: DetailChartContext? = null,
    useQuoteChangeForAllRanges: Boolean = true,
    onExit: () -> Unit = {},
    onSell: () -> Unit = {},
    onOpenExplorer: ((String) -> Unit)? = null,
) {
    var range by remember(stock.id) { mutableStateOf(ChartRange.ONE_DAY) }
    var showCandles by remember(stock.id) { mutableStateOf(!stock.id.value.startsWith("prestocks:")) }
    var section by remember(stock.id) { mutableStateOf(initialSection) }
    val resolvedListState = listState ?: key(stock.id) { rememberLazyListState() }
    val scrollScope = rememberCoroutineScope()
    val points = stock.charts[range].orEmpty()
    val currentRangeCallback by rememberUpdatedState(onRangeSelected)
    val currentExitCallback by rememberUpdatedState(onExit)
    val uriHandler = LocalUriHandler.current
    val openExplorer: (String) -> Unit = onOpenExplorer ?: { uriHandler.openUri(it) }
    DisposableEffect(stock.id) { onDispose { currentExitCallback() } }
    LaunchedEffect(stock.id, range) { currentRangeCallback(range) }

    BoxWithConstraints(modifier.fillMaxSize().background(P.Background)) {
        // Order book retains the viewport height while Overview ends at its listing row.
        val minimumSectionHeight = (maxHeight - rd(237f)).coerceAtLeast(rd(0f))
        Column(Modifier.fillMaxSize()) {
            DetailToolbar(stock, detail, isWatched, onBack, onWatch)
            LazyColumn(
                state = resolvedListState,
                contentPadding = PaddingValues(bottom = if (section == DetailSection.OVERVIEW) rd(28f) else rd(12f)),
                modifier = Modifier.fillMaxSize().padding(bottom = rd(78f)),
            ) {
                item(key = "quote") {
                    Column {
                        Spacer(Modifier.height(rd(30f)))
                        DetailQuote(
                            stock,
                            detail.rangeChanges[range],
                            allowQuoteChange = useQuoteChangeForAllRanges || range == ChartRange.ONE_DAY,
                        )
                        Spacer(Modifier.height(rd(24f)))
                        DetailRanges(range, showCandles, { range = it }) { showCandles = !showCandles }
                        DetailPriceChart(stock, range, points, detail.chartBounds[range],
                            chartMessage, chartContext, showCandles)
                        Spacer(Modifier.height(rd(30f)))
                    }
                }
                stickyHeader(key = "sections") {
                    DetailSections(section) {
                        section = it
                        scrollScope.launch { resolvedListState.animateScrollToItem(1) }
                    }
                }
                item(key = "body") {
                    Box(Modifier.fillMaxWidth()
                        .then(if (section == DetailSection.OVERVIEW) Modifier
                            else Modifier.heightIn(min = minimumSectionHeight))
                        .clipToBounds()) {
                        AnimatedContent(
                            targetState = section,
                            modifier = Modifier.fillMaxWidth(),
                            transitionSpec = {
                                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                (slideInHorizontally(
                                    tween(ReferenceMotion.SectionMillis, easing = ReferenceMotion.Easing),
                                ) { width -> direction * width } togetherWith slideOutHorizontally(
                                    tween(ReferenceMotion.SectionMillis, easing = ReferenceMotion.Easing),
                                ) { width -> -direction * width }).using(null)
                            },
                            label = "Detail section",
                        ) { visibleSection ->
                            when (visibleSection) {
                                DetailSection.OVERVIEW -> DetailOverview(stock, detail, openExplorer)
                                DetailSection.ORDER_BOOK -> DetailOrderBook()
                            }
                        }
                    }
                }
            }
        }
        DetailOrderActions(stock.symbol, onBuy, onSell, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DetailToolbar(
    stock: Stock,
    detail: DetailPresentation,
    watched: Boolean,
    onBack: () -> Unit,
    onWatch: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(rd(66f)).zIndex(1f).background(P.Background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(start = rd(17f), end = rd(26f)),
        ) {
            DetailIconButton("back", "Back", onBack, 39f, 27f)
            Spacer(Modifier.width(rd(13f)))
            StockIcon(stock, 52f, logoReference = detail.headerLogo ?: stock.logo)
            Spacer(Modifier.width(rd(12f)))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RefText(stock.symbol, 20f, weight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false))
                    if (detail.verified) {
                        Spacer(Modifier.width(rd(6f)))
                        RefIcon("verified", 19f, P.Lime)
                    }
                }
                Spacer(Modifier.height(rd(4f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RefText(stock.name,
                        14f, P.Muted, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(rd(7f)))
                    RefIcon("copy", 16f, P.Muted, Modifier.semantics {
                        contentDescription = "Copy"
                        disabled()
                    })
                }
            }
            Spacer(Modifier.width(rd(8f)))
            DetailIconButton(if (watched) "starFilled" else "star",
                if (watched) "Remove from watchlist" else "Add to watchlist",
                onWatch, 43f, 27f, if (watched) P.Lime else P.Muted)
            Spacer(Modifier.width(rd(8f)))
            DetailIconButton("share", "Share ${stock.symbol}", null,
                36f, 24f, background = Color.White.copy(alpha = .025f))
            Spacer(Modifier.width(rd(12f)))
            DetailIconButton("more", "More stock actions", null,
                36f, 24f, background = Color.White.copy(alpha = .025f))
        }
    }
}

@Composable
private fun DetailIconButton(
    icon: String,
    description: String,
    onClick: (() -> Unit)?,
    width: Float,
    iconSize: Float,
    color: Color = P.White,
    background: Color = Color.Transparent,
) {
    Box(Modifier.size(rd(width)).clip(CircleShape).background(background)
        .semantics { contentDescription = description }
        .clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() },
        contentAlignment = Alignment.Center) {
        RefIcon(icon, iconSize, color)
    }
}

@Composable
private fun DetailQuote(
    stock: Stock,
    rangeChange: DetailRangeChange?,
    allowQuoteChange: Boolean,
) {
    val change = rangeChange?.amount ?: if (allowQuoteChange) stock.quote.changeAmount else null
    val percentage = rangeChange?.percent ?: if (allowQuoteChange) stock.quote.changePercent else null
    val changeColor = if ((percentage ?: change)?.signum() == -1) P.Pink else P.Lime
    val quoteHasNativeUnit = stock.quote.currencyCode == "USDC"
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(18f))) {
        RefText(money(stock.quote.price, stock.quote.currencyCode, includeCurrency = !quoteHasNativeUnit),
            44f, weight = FontWeight.Bold)
        Spacer(Modifier.height(rd(7f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (change != null) {
                RefText(signedMoney(change, stock.quote.currencyCode, includeCurrency = !quoteHasNativeUnit),
                    19f, changeColor, FontWeight.Medium)
            }
            if (percentage != null) {
                Spacer(Modifier.width(rd(7f)))
                Box(Modifier.clip(RoundedCornerShape(rd(4f)))
                    .background(changeColor.copy(alpha = .075f))
                    .padding(horizontal = rd(9f), vertical = rd(5f))) {
                    RefText(signedPercent(percentage), 17f, changeColor, FontWeight.Medium)
                }
            }
            if (change == null && percentage == null && !allowQuoteChange) {
                Box(Modifier.height(rd(31f)), contentAlignment = Alignment.CenterStart) {
                    RefText("—", 19f, P.Muted)
                }
            }
        }
    }
}

@Composable
private fun DetailRanges(
    selectedRange: ChartRange,
    showCandles: Boolean,
    onSelect: (ChartRange) -> Unit,
    onToggleChartStyle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(rd(43f)).padding(horizontal = rd(22f)),
        verticalAlignment = Alignment.CenterVertically) {
        RefText("Time", 15f)
        Spacer(Modifier.width(rd(4f)))
        ChartRange.entries.forEach { range ->
            val active = selectedRange == range
            val label = when (range) {
                ChartRange.ONE_HOUR -> "1H"
                ChartRange.ONE_DAY -> "1D"
                ChartRange.ONE_WEEK -> "1W"
                ChartRange.ONE_MONTH -> "1M"
                ChartRange.YEAR_TO_DATE -> "YTD"
            }
            Box(Modifier.width(rd(if (range == ChartRange.YEAR_TO_DATE) 42f else 36f))
                .height(rd(29f)).clip(RoundedCornerShape(rd(9f)))
                .background(if (active) P.Card else Color.Transparent)
                .semantics { selected = active }
                .clickable(role = Role.Tab) { onSelect(range) },
                contentAlignment = Alignment.Center) {
                RefText(label, 15f, if (active) P.White else P.Muted)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(rd(34f)).clip(CircleShape)
            .background(if (showCandles) P.Card else Color.Transparent)
            .semantics { contentDescription = if (showCandles) "Show line chart" else "Show candlestick chart" }
            .clickable(role = Role.Button, onClick = onToggleChartStyle),
            contentAlignment = Alignment.Center) {
            RefIcon("candle", 22f, if (showCandles) P.White else P.Muted)
        }
    }
}

@Composable
private fun DetailPriceChart(
    stock: Stock,
    range: ChartRange,
    points: List<StockPricePoint>,
    suppliedBounds: DetailChartBounds?,
    chartMessage: String?,
    chartContext: DetailChartContext?,
    showCandles: Boolean,
) {
    val context = chartContext?.takeIf { it.range == range }
    val currency = context?.currency ?: stock.quote.currencyCode
    val timeDomain = remember(points, range, context) {
        chartTimeDomain(points, range, anchorToSelectedRange = context != null)
    }
    val visiblePoints = remember(points, timeDomain, context) {
        if (context == null) points else points.filter {
            !it.timestamp.isBefore(timeDomain.start) && !it.timestamp.isAfter(timeDomain.end)
        }
    }
    if (showCandles) {
        DetailCandlestickChart(stock, range, visiblePoints, suppliedBounds,
            chartMessage, currency, timeDomain)
    } else {
        DetailLinePriceChart(stock, range, visiblePoints, suppliedBounds,
            chartMessage, currency, timeDomain)
    }
    if (visiblePoints.isNotEmpty() && context?.sourceLabel != null) {
        RefText(context.sourceLabel, 11f, P.Muted,
            modifier = Modifier.padding(start = rd(22f), top = rd(3f)))
    }
}

internal data class ObservedCandle(
    val timeFraction: Float,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
)

internal data class ChartTimeDomain(val start: Instant, val end: Instant) {
    fun fraction(timestamp: Instant): Float {
        val span = (end.toEpochMilli() - start.toEpochMilli()).coerceAtLeast(1L)
        if (start == end) return .5f
        return ((timestamp.toEpochMilli() - start.toEpochMilli()).toDouble() / span)
            .toFloat().coerceIn(0f, 1f)
    }
}

internal fun chartTimeDomain(
    points: List<StockPricePoint>,
    range: ChartRange,
    anchorToSelectedRange: Boolean,
): ChartTimeDomain {
    val last = points.lastOrNull()?.timestamp ?: Instant.EPOCH
    if (!anchorToSelectedRange || points.isEmpty()) {
        return ChartTimeDomain(points.firstOrNull()?.timestamp ?: last, last)
    }
    val start = when (range) {
        ChartRange.ONE_HOUR -> last.minus(1, ChronoUnit.HOURS)
        ChartRange.ONE_DAY -> last.minus(1, ChronoUnit.DAYS)
        ChartRange.ONE_WEEK -> last.minus(7, ChronoUnit.DAYS)
        ChartRange.ONE_MONTH -> last.minus(30, ChronoUnit.DAYS)
        ChartRange.YEAR_TO_DATE -> last.atZone(ZoneOffset.UTC).withDayOfYear(1)
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
    }
    return ChartTimeDomain(start, last)
}

/** Each candle summarizes actual observations in a fixed time interval, not exchange OHLC. */
internal fun observedCandleBucketCount(observationCount: Int): Int =
    ((observationCount + 2) / 3).coerceIn(1, 48)

internal fun observedCandles(
    points: List<StockPricePoint>,
    bucketCount: Int = observedCandleBucketCount(points.size),
    timeDomain: ChartTimeDomain = chartTimeDomain(points, ChartRange.ONE_DAY, false),
): List<ObservedCandle> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) {
        val price = points.first().price.toDouble()
        return listOf(ObservedCandle(timeDomain.fraction(points.first().timestamp),
            price, price, price, price))
    }
    val buckets = points.groupBy { point ->
        (timeDomain.fraction(point.timestamp) * bucketCount).toInt().coerceIn(0, bucketCount - 1)
    }
    return buckets.toSortedMap().map { (index, bucket) ->
        val prices = bucket.map { it.price.toDouble() }
        ObservedCandle((index + .5f) / bucketCount,
            prices.first(), prices.maxOrNull()!!, prices.minOrNull()!!, prices.last())
    }
}

internal fun paddedChartBounds(low: Double, high: Double): Pair<Double, Double> {
    val span = (high - low).coerceAtLeast(0.0)
    val padding = if (span > 0.0) span * .08 else high.coerceAtLeast(0.0) * .005 + 1e-9
    return (low - padding).coerceAtLeast(0.0) to high + padding
}

private fun chartTimeLabels(points: List<StockPricePoint>, range: ChartRange,
    timeDomain: ChartTimeDomain): List<String> {
    if (points.isEmpty()) return emptyList()
    val start = timeDomain.start.toEpochMilli()
    val span = (timeDomain.end.toEpochMilli() - start).coerceAtLeast(0L)
    val pattern = when (range) {
        ChartRange.ONE_HOUR -> "HH:mm"
        ChartRange.ONE_DAY -> "d HH:mm"
        ChartRange.ONE_WEEK, ChartRange.ONE_MONTH, ChartRange.YEAR_TO_DATE -> "MMM d"
    }
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.systemDefault())
    return (0 until 4).map { tick ->
        formatter.format(Instant.ofEpochMilli(start + (span * (tick + .5f) / 4f).toLong()))
    }
}

@Composable
private fun DetailChartTimeAxis(points: List<StockPricePoint>, range: ChartRange,
    timeDomain: ChartTimeDomain) {
    Row(Modifier.fillMaxWidth().height(rd(28f))
        .padding(start = rd(12f), end = rd(78f)),
        verticalAlignment = Alignment.CenterVertically) {
        chartTimeLabels(points, range, timeDomain).forEach { label ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                RefText(label, 11f, P.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DetailCandlestickChart(
    stock: Stock,
    range: ChartRange,
    points: List<StockPricePoint>,
    suppliedBounds: DetailChartBounds?,
    chartMessage: String?,
    currency: String,
    timeDomain: ChartTimeDomain,
) {
    val bucketCount = observedCandleBucketCount(points.size)
    val candles = remember(points, timeDomain, bucketCount) {
        observedCandles(points, bucketCount, timeDomain)
    }
    val observedLow = candles.minOfOrNull { it.low } ?: 0.0
    val observedHigh = candles.maxOfOrNull { it.high } ?: 1.0
    val rawLow = if (candles.isEmpty()) suppliedBounds?.low?.toDouble() ?: 0.0
        else minOf(suppliedBounds?.low?.toDouble() ?: observedLow, observedLow)
    val rawHigh = if (candles.isEmpty()) suppliedBounds?.high?.toDouble() ?: 1.0
        else maxOf(suppliedBounds?.high?.toDouble() ?: observedHigh, observedHigh)
    val (minimum, maximum) = paddedChartBounds(rawLow, rawHigh)
    val delta = maximum - minimum
    val levels = listOf(1f, .67f, .33f, 0f)
    val referenceDp = rd(1f)
    val referencePixel = with(LocalDensity.current) { referenceDp.toPx() }
    val leftInsetPx = 12f * referencePixel
    val rightInsetPx = 78f * referencePixel
    val topInsetPx = 24f * referencePixel
    val bottomInsetPx = 20f * referencePixel
    val gridStrokePx = .7f * referencePixel
    val maxBodyWidthPx = 40f * referencePixel
    val minBodyWidthPx = 1.5f * referencePixel
    val wickStrokePx = 1f * referencePixel
    val minBodyHeightPx = 1.4f * referencePixel
    val currentPriceStrokePx = .8f * referencePixel
    val dashPx = 2f * referencePixel
    val dashGapPx = 3f * referencePixel
    Column {
        Box(Modifier.fillMaxWidth().height(rd(374f))) {
            Canvas(Modifier.fillMaxSize()) {
                val left = leftInsetPx
                val right = size.width - rightInsetPx
                val top = topInsetPx
                val bottom = size.height - bottomInsetPx
                val plotHeight = bottom - top
                fun y(price: Double) = top + ((maximum - price) / delta).toFloat().coerceIn(0f, 1f) * plotHeight
                for (fraction in listOf(0f, .33f, .67f, 1f)) {
                    val lineY = top + fraction * plotHeight
                    drawLine(P.White.copy(alpha = .10f), Offset(left, lineY), Offset(right, lineY), gridStrokePx)
                }
                for (fraction in listOf(.25f, .5f, .75f)) {
                    val lineX = left + fraction * (right - left)
                    drawLine(P.White.copy(alpha = .07f), Offset(lineX, top), Offset(lineX, bottom), gridStrokePx)
                }
                if (candles.isNotEmpty()) {
                    val bodyWidth = ((right - left) / bucketCount * .92f)
                        .coerceAtMost(maxBodyWidthPx).coerceAtLeast(minBodyWidthPx)
                    candles.forEach { candle ->
                        val x = left + (right - left) * candle.timeFraction
                        val color = if (candle.close >= candle.open) P.Lime else P.Pink
                        val highY = y(candle.high)
                        val lowY = y(candle.low)
                        val openY = y(candle.open)
                        val closeY = y(candle.close)
                        drawLine(color, Offset(x, highY), Offset(x, lowY), wickStrokePx)
                        drawRect(color, Offset(x - bodyWidth / 2f, minOf(openY, closeY)),
                            Size(bodyWidth, (kotlin.math.abs(closeY - openY)).coerceAtLeast(minBodyHeightPx)))
                    }
                    val closeY = y(candles.last().close)
                    drawLine(P.White.copy(alpha = .62f), Offset(left, closeY), Offset(right, closeY),
                        currentPriceStrokePx, pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(dashPx, dashGapPx)))
                }
            }
            if (candles.isNotEmpty() || suppliedBounds != null) {
                Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    .padding(top = rd(12f), bottom = rd(10f), end = rd(6f)),
                    verticalArrangement = Arrangement.SpaceBetween) {
                    levels.forEach { level ->
                        val value = minimum + (maximum - minimum) * level
                        RefText(money(BigDecimal.valueOf(value), currency,
                            includeCurrency = false), 11f, P.Muted)
                    }
                }
            }
            Box(Modifier.align(Alignment.Center)) { SquareLogo(30f, .065f) }
            if (points.isEmpty()) {
                RefText(if (chartMessage?.contains("loading", ignoreCase = true) == true)
                    "Loading price history…" else "Price history unavailable", 14f, P.Muted,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = rd(30f)), maxLines = 3)
            }
        }
        DetailChartTimeAxis(points, range, timeDomain)
    }
}

@Composable
private fun DetailLinePriceChart(
    stock: Stock,
    range: ChartRange,
    points: List<StockPricePoint>,
    suppliedBounds: DetailChartBounds?,
    chartMessage: String?,
    currency: String,
    timeDomain: ChartTimeDomain,
) {
    val values = remember(points) { points.map { it.price.toDouble() } }
    val reveal = remember(stock.id) { Animatable(0f) }
    var firstReveal by remember(stock.id) { mutableStateOf(true) }
    LaunchedEffect(stock.id, range, points.isNotEmpty()) {
        reveal.snapTo(0f)
        if (points.isEmpty()) return@LaunchedEffect
        val entryDelay = if (firstReveal) ReferenceMotion.ChartDelayMillis else 0
        firstReveal = false
        if (entryDelay > 0) delay(entryDelay.toLong())
        reveal.animateTo(1f, tween(ReferenceMotion.ChartMillis, easing = ReferenceMotion.Easing))
    }
    val hasBounds = suppliedBounds != null || values.isNotEmpty()
    val rawLow = minOf(suppliedBounds?.low?.toDouble() ?: Double.POSITIVE_INFINITY,
        values.minOrNull() ?: Double.POSITIVE_INFINITY).takeIf { it.isFinite() } ?: 0.0
    val rawHigh = maxOf(suppliedBounds?.high?.toDouble() ?: Double.NEGATIVE_INFINITY,
        values.maxOrNull() ?: Double.NEGATIVE_INFINITY).takeIf { it.isFinite() } ?: 1.0
    val (minimum, maximum) = if (suppliedBounds != null && rawHigh > rawLow) rawLow to rawHigh
        else paddedChartBounds(rawLow, rawHigh)
    val delta = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
    val stroke = rd(2f)
    val inset = rd(8f)
    val topGap = rd(32f)
    val bottomGap = rd(29f)
    val endpoint = rd(4.5f)
    Column {
        Box(Modifier.fillMaxWidth().height(rd(374f))) {
            Canvas(Modifier.fillMaxSize()) {
                drawLine(P.White.copy(alpha = .07f), Offset.Zero, Offset(size.width, 0f), 1f)
                drawLine(P.White.copy(alpha = .035f), Offset(0f, size.height),
                    Offset(size.width, size.height), 1f)
                if (values.size > 1) {
                    val xInset = inset.toPx()
                    val top = topGap.toPx()
                    val plotHeight = size.height - top - bottomGap.toPx()
                    fun point(index: Int): Offset = Offset(
                        xInset + timeDomain.fraction(points[index].timestamp) * (size.width - xInset * 2),
                        top + (1f - ((values[index] - minimum) / delta).toFloat().coerceIn(0f, 1f)) * plotHeight,
                    )
                    val line = Path().apply {
                        val first = point(0)
                        moveTo(first.x, first.y)
                        for (index in 1..values.lastIndex) {
                            val next = point(index)
                            lineTo(next.x, next.y)
                        }
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(point(values.lastIndex).x, size.height)
                        lineTo(point(0).x, size.height)
                        close()
                    }
                    clipRect(right = size.width * reveal.value) {
                        drawPath(area, Brush.verticalGradient(listOf(P.Lime.copy(alpha = .16f),
                            P.Lime.copy(alpha = .005f)), top, size.height))
                        drawPath(line, P.Lime, style = Stroke(stroke.toPx(), cap = StrokeCap.Butt,
                            join = StrokeJoin.Miter))
                        val last = point(values.lastIndex)
                        drawCircle(P.White.copy(alpha = .42f), endpoint.toPx() * 1.9f, last)
                        drawCircle(P.White, endpoint.toPx(), last)
                    }
                } else if (values.size == 1) {
                    val current = Offset(
                        size.width - inset.toPx(),
                        topGap.toPx() + (size.height - topGap.toPx() - bottomGap.toPx()) / 2f,
                    )
                    drawCircle(P.White.copy(alpha = .42f), endpoint.toPx() * 1.9f, current)
                    drawCircle(P.White, endpoint.toPx(), current)
                }
            }
            if (hasBounds) {
                RefText(money(BigDecimal.valueOf(maximum), currency), 13f, P.Muted,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = rd(13f)))
                RefText(money(BigDecimal.valueOf(minimum), currency), 13f, P.Muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(bottom = rd(5f)))
            }
            Box(Modifier.align(Alignment.Center).padding(top = rd(36f))) {
                SquareLogo(30f, .055f)
            }
            if (points.isEmpty()) {
                RefText(if (chartMessage?.contains("loading", ignoreCase = true) == true)
                    "Loading price history…" else "Price history unavailable", 14f, P.Muted,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = rd(30f)), maxLines = 3)
            }
        }
        DetailChartTimeAxis(points, range, timeDomain)
    }
}

@Composable
private fun DetailSections(section: DetailSection, onSelect: (DetailSection) -> Unit) {
    Row(Modifier.fillMaxWidth().height(rd(67f)).background(P.Background)
        .padding(start = rd(22f), end = rd(21f)),
        horizontalArrangement = Arrangement.spacedBy(rd(18f)),
        verticalAlignment = Alignment.Top) {
        DetailSection.entries.forEach { item ->
            val active = section == item
            Column(Modifier.height(rd(55f))
                .semantics { selected = active }
                .clickable(role = Role.Tab) { onSelect(item) }
                .padding(top = rd(10f)), horizontalAlignment = Alignment.Start) {
                RefText(item.label, 20f, if (active) P.White else P.Muted,
                    FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DetailOrderBook() {
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(20f))) {
        RefText("Order book unavailable", 20f, weight = FontWeight.Bold)
        Spacer(Modifier.height(rd(9f)))
        RefText("Live bid and ask prices are unavailable for this listing.", 15f, P.Muted,
            maxLines = Int.MAX_VALUE)
    }
}

@Composable
private fun DetailOverview(
    stock: Stock,
    detail: DetailPresentation,
    onOpenExplorer: (String) -> Unit,
) {
    val reference = stock.statistics?.reference
    val explorer = exactStockExplorerLink(reference?.network, reference?.mint)
    val informationUrl = safeStockInformationUrl(stock.informationUrl)
    Column(Modifier.fillMaxWidth().padding(start = rd(18f), end = rd(18f), top = rd(13f))) {
        RefText("About stocks", 20f, weight = FontWeight.Bold)
        Spacer(Modifier.height(rd(12f)))
        RefText(stock.description?.takeIf(String::isNotBlank) ?: stock.name,
            18f, P.Muted, maxLines = Int.MAX_VALUE)
        Spacer(Modifier.height(rd(21f)))
        DetailListingSource(detail.providerLabel?.takeIf(String::isNotBlank) ?: "Market listing",
            stock.symbol, explorer, informationUrl, onOpenExplorer)
    }
}

@Composable
private fun DetailListingSource(
    providerLabel: String,
    symbol: String,
    explorer: StockExplorerLink?,
    informationUrl: String?,
    onOpenExplorer: (String) -> Unit,
) {
    RefText("Listing source", 16f, weight = FontWeight.Medium)
    Spacer(Modifier.height(rd(8f)))
    val sourceUrl = explorer?.url ?: informationUrl
    val rowModifier = Modifier.fillMaxWidth().heightIn(min = rd(58f))
        .clip(RoundedCornerShape(rd(16f))).background(P.Card)
        .then(if (sourceUrl == null) Modifier else Modifier
            .semantics {
                contentDescription = explorer?.let {
                    "Open $symbol exact ${it.networkLabel} mint ${it.address} on ${it.explorerLabel}"
                } ?: "Open $symbol listing information from $providerLabel"
            }
            .clickable(role = Role.Button) { onOpenExplorer(sourceUrl) })
        .padding(horizontal = rd(15f), vertical = rd(10f))
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        RefIcon("globe", 22f, if (sourceUrl == null) P.Muted else P.Lime)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(providerLabel, 16f, weight = FontWeight.Medium, maxLines = 2)
            Spacer(Modifier.height(rd(3f)))
            RefText(
                explorer?.let { "${it.networkLabel} · ${compactAddress(it.address)}" }
                    ?: if (sourceUrl != null) "Product information" else "Source link unavailable",
                13f,
                P.Muted,
                maxLines = 2,
            )
        }
        if (sourceUrl != null) {
            Spacer(Modifier.width(rd(8f)))
            RefIcon("chevronRight", 18f, P.Muted)
        }
    }
}

@Composable
private fun DetailActivityReferences(activity: StockMarketActivity) {
    RefText("24h trading activity", 20f, weight = FontWeight.Bold)
    Spacer(Modifier.height(rd(10f)))
    RefText(activityScope(activity), 14f, P.Muted, maxLines = Int.MAX_VALUE)
    Spacer(Modifier.height(rd(21f)))
    RefText("Trading volume", 16f, weight = FontWeight.Medium)
    Spacer(Modifier.height(rd(4f)))
    RefText(activity.volume24h?.let { activityAmount(it, activity.currencyCode) } ?: "—",
        21f, if (activity.volume24h == null) P.Muted else P.White, maxLines = 2)
    Spacer(Modifier.height(rd(5f)))
    RefText(when (activity.source) {
        "Jupiter" -> "Total reported buy and sell turnover over 24 hours."
        "Yahoo" -> "Underlying-share 24-hour turnover, converted to the displayed USD reference."
        else -> "Reported 24-hour turnover in the market’s USDC quote currency."
    },
        13f, P.Muted, maxLines = Int.MAX_VALUE)
    activity.volumeReason?.let {
        Spacer(Modifier.height(rd(5f)))
        RefText(it, 13f, P.Muted, maxLines = Int.MAX_VALUE)
    }
    Spacer(Modifier.height(rd(21f)))
    RefText("Net buy volume", 16f, weight = FontWeight.Medium)
    Spacer(Modifier.height(rd(4f)))
    RefText(activity.netVolume24h?.let { activityAmount(it, activity.currencyCode, signed = true) } ?: "—",
        21f, activityNetColor(activity.netVolume24h), maxLines = 2)
    Spacer(Modifier.height(rd(5f)))
    RefText("Buy turnover minus sell turnover over 24 hours. Positive means net buying; negative means net selling.",
        13f, P.Muted, maxLines = Int.MAX_VALUE)
    activity.netVolumeReason?.let {
        Spacer(Modifier.height(rd(5f)))
        RefText(it, 13f, P.Muted, maxLines = Int.MAX_VALUE)
    }
    Spacer(Modifier.height(rd(16f)))
    RefText("Source: ${activity.source} · ${activity.currencyCode}", 12f, P.Muted, maxLines = 2)
    RefText("Provider updated: ${metricTime(activity.updatedAt)}", 12f, P.Muted, maxLines = 2)
    RefText("Fetched: ${metricTime(activity.receivedAt)}", 12f, P.Muted, maxLines = 2)
}

private fun activityScope(activity: StockMarketActivity): String = when (activity.scope) {
    "solana_token" -> "Activity for this tokenized stock’s Solana token; not trading in the underlying company’s shares."
    "underlying_share" -> "Activity for the underlying share reference; not this token’s onchain activity."
    else -> "Activity for this Backpack external market; not xStock onchain activity."
}

private fun activityAmount(value: BigDecimal, currency: String, signed: Boolean = false): String =
    (if (signed && value.signum() > 0) "+" else "") + money(value, currency, includeCurrency = false) + " $currency"

private fun activityNetColor(value: BigDecimal?): Color = when (value?.signum()) {
    1 -> P.Lime
    -1 -> P.Pink
    else -> P.Muted
}

@Composable
private fun DetailMetricReferences(reference: StockStatisticsReference) {
    RefText("Market metrics", 20f, weight = FontWeight.Bold)
    Spacer(Modifier.height(rd(10f)))
    RefText("These figures describe this stock’s Solana token, not the underlying company.",
        14f, P.Muted, maxLines = Int.MAX_VALUE)
    val definitions = listOf(
        Triple(StockMetricKey.MARKET_CAPITALIZATION, "Token market capitalization", "Provider-reported market value of the circulating token supply, in USD."),
        Triple(StockMetricKey.LIQUIDITY, "Token liquidity", "Jupiter-reported token liquidity in USD; not daily turnover."),
        Triple(StockMetricKey.HOLDER_COUNT, "Token holders", "Reported onchain holders; not company shareholders or unique investors."),
        Triple(StockMetricKey.ORGANIC_SCORE, "Organic Score", "Jupiter’s 0–100 measure of organic token activity; this is not a company or investment rating."),
    )
    val fetchTimes = reference.metrics.values.map { it.receivedAt }.distinct()
    definitions.forEach { (key, label, definition) ->
        Spacer(Modifier.height(rd(21f)))
        DetailMetricReference(key, label, definition, reference.metrics.getValue(key), showFetchTime = fetchTimes.size != 1)
    }
    if (fetchTimes.size == 1 || reference.updatedAt != null) {
        Spacer(Modifier.height(rd(16f)))
        reference.updatedAt?.let {
            RefText("Provider updated: ${metricTime(it)}", 12f, P.Muted, maxLines = 2)
        }
        if (fetchTimes.size == 1) {
            RefText("Fetched: ${metricTime(fetchTimes.single())}", 12f, P.Muted, maxLines = 2)
        }
    }
}

@Composable
private fun DetailMetricReference(
    key: StockMetricKey,
    label: String,
    definition: String,
    metric: StockMetricData,
    showFetchTime: Boolean,
) {
    val value = metric.value?.let {
        when (key) {
            StockMetricKey.MARKET_CAPITALIZATION, StockMetricKey.LIQUIDITY -> "${money(it, "USD")} USD"
            StockMetricKey.HOLDER_COUNT -> NumberFormat.getIntegerInstance(Locale.US).format(it)
            StockMetricKey.ORGANIC_SCORE -> "${organicScore(it)} / 100"
        }
    } ?: "—"
    RefText(label, 16f, weight = FontWeight.Medium, maxLines = 2)
    Spacer(Modifier.height(rd(4f)))
    RefText(value, 21f, if (metric.value == null) P.Muted else P.White, maxLines = 2)
    Spacer(Modifier.height(rd(5f)))
    RefText(definition, 13f, P.Muted, maxLines = Int.MAX_VALUE)
    metric.reason?.let { reason ->
        Spacer(Modifier.height(rd(5f)))
        RefText(reason, 13f, P.Muted, maxLines = Int.MAX_VALUE)
    }
    Spacer(Modifier.height(rd(5f)))
    RefText("Source: ${metric.source ?: "Unavailable"}", 12f, P.Muted, maxLines = 2)
    if (showFetchTime) {
        RefText("Fetched: ${metricTime(metric.receivedAt)}", 12f, P.Muted, maxLines = 2)
    }
}

private fun metricTime(value: Instant?): String = value?.let {
    DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss 'UTC'", Locale.US)
        .withZone(ZoneOffset.UTC).format(it)
} ?: "Unavailable"

@Composable
private fun DetailNews(items: List<DetailNewsItem>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        RefText("Verified News", 20f, weight = FontWeight.Bold)
        Row(Modifier.width(rd(77f)).height(rd(31f))
            .clip(RoundedCornerShape(rd(5f))).background(P.Card)
            .semantics { contentDescription = "TLDR"; disabled() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            val tint = Color(0xFFA8DCD3)
            RefIcon("sparkle", 19f, tint)
            Spacer(Modifier.width(rd(4f)))
            RefText("TLDR", 15f, tint)
        }
    }
    Spacer(Modifier.height(rd(10f)))
    Row(Modifier.fillMaxWidth().height(rd(69f))
        .border(rd(1f), P.Card, RoundedCornerShape(rd(16f)))
        .semantics { disabled() }
        .padding(horizontal = rd(17f)),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RefText("Powered by VRFD", 17f, weight = FontWeight.Bold)
            Spacer(Modifier.height(rd(2f)))
            RefText("Contribute tweets, or report bad data.", 15f, P.Muted)
        }
        RefIcon("chevronRight", 18f, P.Muted)
    }
    Spacer(Modifier.height(rd(13f)))
    items.forEach { item ->
        DetailNewsCard(item)
        Spacer(Modifier.height(rd(12f)))
    }
}

@Composable
private fun DetailNewsCard(item: DetailNewsItem) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(17f)))
        .background(P.Card).padding(rd(11f))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(36f))) {
                item.authorImageReference?.let { reference ->
                    DetailNewsImage(reference, item.author, Modifier.fillMaxSize().clip(CircleShape), true)
                }
            }
            Spacer(Modifier.width(rd(8f)))
            RefText(item.author, 18f, weight = FontWeight.Bold,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(rd(8f)))
            RefText(item.ageLabel, 14f, P.Muted)
        }
        Spacer(Modifier.height(rd(5f)))
        RefText(item.body, 15.5f, maxLines = Int.MAX_VALUE)
        item.imageReference?.let { reference ->
            Spacer(Modifier.height(rd(10f)))
            DetailNewsImage(reference, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(6f))))
        }
        if (item.viewsLabel != null || item.likesLabel != null) {
            Spacer(Modifier.height(rd(8f)))
            Row(Modifier.height(rd(19f)), verticalAlignment = Alignment.CenterVertically) {
                item.viewsLabel?.let { views ->
                    RefIcon("eye", 17f, P.Muted)
                    Spacer(Modifier.width(rd(4f)))
                    RefText(views, 14f, P.Muted)
                }
                item.likesLabel?.let { likes ->
                    if (item.viewsLabel != null) Spacer(Modifier.width(rd(7f)))
                    RefIcon("heart", 17f, P.Muted)
                    Spacer(Modifier.width(rd(4f)))
                    RefText(likes, 14f, P.Muted)
                }
            }
        }
    }
}

@Composable
private fun DetailNewsImage(
    reference: String,
    description: String?,
    modifier: Modifier,
    avatar: Boolean = false,
) {
    val context = LocalContext.current
    val resource = remember(reference) {
        if (reference.startsWith("reference/")) {
            val name = "reference_" + reference.removePrefix("reference/").replace('/', '_')
            context.resources.getIdentifier(name, "drawable", context.packageName)
        } else 0
    }
    val scale = if (avatar) ContentScale.Crop else ContentScale.FillWidth
    if (resource != 0) {
        val painter = painterResource(resource)
        val imageModifier = if (avatar) modifier else modifier.aspectRatio(
            painter.intrinsicSize.width / painter.intrinsicSize.height,
        )
        Image(painter, description, imageModifier, contentScale = scale)
    } else if (reference.startsWith("https://")) {
        AsyncImage(reference, description, modifier, contentScale = scale)
    }
}

@Composable
private fun DetailTerminal(stock: Stock, detail: DetailPresentation, expanded: Boolean, onExpand: () -> Unit) {
    val activity = stock.activity
    val isLive = activity != null || stock.statistics?.reference != null
    val volume = if (activity != null) activity.volume24h else detail.volume24h
    val netVolume = if (activity != null) activity.netVolume24h else detail.netVolume24h
    val missingValue = "—"
    val activityCurrency = activity?.currencyCode ?: stock.quote.currencyCode
    Column(Modifier.fillMaxWidth().padding(start = rd(22f), end = rd(22f), top = rd(6f))) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(15f))).background(P.Card)) {
            Row(Modifier.fillMaxWidth().height(rd(35f))) {
                DetailPeriod.entries.forEach { period ->
                    Row(Modifier.weight(1f).fillMaxSize()
                        .background(if (period == DetailPeriod.ONE_DAY) Color.White.copy(alpha = .025f) else Color.Transparent),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        RefText(period.label, 13f, P.Muted)
                        Spacer(Modifier.width(rd(4f)))
                        val change = detail.periodReturns[period]
                        RefText(change?.let { "${it.stripTrailingZeros().toPlainString()}%" } ?: "—", 13f,
                            if (change?.signum() == -1) P.Pink else P.Lime)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(rd(.5f)).background(P.White.copy(alpha = .035f)))
            if (activity != null) {
                RefText("24h turnover · $activityCurrency · ${activity.source}", 12f, P.Muted,
                    modifier = Modifier.padding(start = rd(13f), end = rd(13f), top = rd(10f)), maxLines = 2)
            }
            Row(Modifier.fillMaxWidth().padding(start = rd(13f), end = rd(13f), top = rd(10f))) {
                Column(Modifier.weight(1.07f)) {
                    DetailMetric("24h Vol", volume?.let { compactMoney(it, activityCurrency, includeCurrency = activity == null) } ?: missingValue)
                    Spacer(Modifier.height(rd(10f)))
                    DetailMetric("24h Traders", compactCount(detail.traders24h))
                }
                Column(Modifier.weight(if (isLive) 1.07f else .72f)) {
                    DetailMetric("Net Vol", netVolume?.let {
                        if (isLive) (if (it.signum() > 0) "+" else "") + compactMoney(it, activityCurrency, includeCurrency = activity == null)
                        else compactMoney(it.abs(), stock.quote.currencyCode)
                    } ?: missingValue, if (isLive) activityNetColor(netVolume)
                        else if (netVolume?.signum() == -1) P.Pink else P.Lime)
                    Spacer(Modifier.height(rd(10f)))
                    DetailMetric("Net Bu…", compactCount(detail.netBuyers24h?.let { kotlin.math.abs(it) }),
                        if ((detail.sellTraderFraction ?: .5f) > .5f) P.Pink else P.Lime)
                }
                Column(Modifier.weight(if (isLive) 1.15f else 1.5f).padding(top = rd(19f))) {
                    DetailSellBar(detail.sellVolumeFraction)
                    Spacer(Modifier.height(rd(12f)))
                    DetailSellBar(detail.sellTraderFraction)
                }
            }
            if (activity != null) {
                Column(Modifier.fillMaxWidth().padding(start = rd(13f), end = rd(13f), top = rd(12f))) {
                    RefText(activityScope(activity), 12f, P.Muted, maxLines = Int.MAX_VALUE)
                    listOfNotNull(activity.volumeReason, activity.netVolumeReason).distinct().forEach { reason ->
                        Spacer(Modifier.height(rd(5f)))
                        RefText(reason, 12f, P.Muted, maxLines = Int.MAX_VALUE)
                    }
                }
            }
            if (expanded) {
                Spacer(Modifier.height(rd(24f)))
                DetailNetBuyTrend(stock, detail)
                Spacer(Modifier.height(rd(16f)))
                Row(Modifier.fillMaxWidth().padding(horizontal = rd(11f))) {
                    DetailDelta("Vol %Δ", detail.volumeChangePercent, Modifier.weight(1f))
                    DetailDelta("Liquidity %Δ", detail.liquidityChangePercent, Modifier.weight(1.12f))
                    DetailDelta("Holders %Δ", detail.holdersChangePercent, Modifier.weight(1f))
                }
                Spacer(Modifier.height(rd(22f)))
            } else {
                Spacer(Modifier.height(rd(11f)))
            }
            Row(Modifier.fillMaxWidth().height(rd(42f))
                .clickable(role = Role.Button, onClick = onExpand),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RefText(if (expanded) "Hide info" else "Show more info", 14f)
                Spacer(Modifier.width(rd(6f)))
                RefIcon(if (expanded) "chevronUp" else "chevronDown", 15f)
            }
        }
        if (detail.stockInfoCells.isNotEmpty()) {
            Spacer(Modifier.height(rd(30f)))
            RefText("Stock Info", 20f, weight = FontWeight.Bold)
            Spacer(Modifier.height(rd(12f)))
            detail.stockInfoCells.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(13f))) {
                    row.forEach { cell -> DetailInfoCard(cell, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(rd(13f)))
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String, color: Color = P.White) {
    RefText(label, 13f, P.Muted)
    RefText(value, 16f, color)
}

@Composable
private fun DetailSellBar(fraction: Float?) {
    Row(Modifier.fillMaxWidth().height(rd(17f)).clip(RoundedCornerShape(rd(9f)))
        .background(P.Pink.copy(alpha = .06f)).padding(horizontal = rd(8f)),
        verticalAlignment = Alignment.CenterVertically) {
        val amount = fraction?.coerceIn(0f, 1f)
        Box(Modifier.weight(1f).height(rd(4f)).clip(CircleShape).background(P.Pink.copy(alpha = .1f))) {
            if (amount != null) Box(Modifier.fillMaxWidth(amount).height(rd(4f))
                .clip(CircleShape).background(P.Pink))
        }
        Spacer(Modifier.width(rd(7f)))
        RefText(amount?.let { "${(it * 100).roundToInt()}% Sell" } ?: "—", 13f, P.Pink)
    }
}

@Composable
private fun DetailNetBuyTrend(stock: Stock, detail: DetailPresentation) {
    val values = remember(detail.netBuyTrend) { detail.netBuyTrend.map { it.toDouble() } }
    val low = values.minOrNull() ?: 0.0
    val high = values.maxOrNull() ?: 1.0
    val span = (high - low).takeIf { it > 0.0 } ?: 1.0
    val stroke = rd(1.9f)
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(13f))) {
        RefText("24h Net Buy Trend", 14f, P.Muted)
        Spacer(Modifier.height(rd(16f)))
        Row(Modifier.fillMaxWidth().height(rd(76f))) {
            Column(Modifier.width(rd(77f)).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                RefText(if (values.isEmpty()) "—" else compactMoney(BigDecimal.valueOf(high), stock.quote.currencyCode), 16f)
                RefText(if (values.isEmpty()) "—" else compactMoney(BigDecimal.valueOf(low), stock.quote.currencyCode), 16f)
            }
            Canvas(Modifier.weight(1f).fillMaxSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
                drawLine(P.Muted.copy(alpha = .3f), Offset.Zero, Offset(size.width, 0f), 1f, pathEffect = dash)
                drawLine(P.Muted.copy(alpha = .3f), Offset(0f, size.height), Offset(size.width, size.height), 1f, pathEffect = dash)
                if (values.size > 1) {
                    val line = Path().apply {
                        values.forEachIndexed { index, value ->
                            val x = index.toFloat() / values.lastIndex * size.width
                            val y = (1f - ((value - low) / span).toFloat()) * (size.height - 8f) + 4f
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    val area = Path().apply {
                        addPath(line)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(area, Brush.verticalGradient(listOf(P.Pink.copy(alpha = .08f), Color.Transparent)))
                    drawPath(line, P.Pink, style = Stroke(stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun DetailDelta(label: String, value: BigDecimal?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        RefText(label, 13f, P.Muted)
        Spacer(Modifier.height(rd(2f)))
        RefText(value?.let(::signedPercent) ?: "—", 17f,
            if (value?.signum() == -1) P.Pink else P.Lime, FontWeight.Medium)
    }
}

@Composable
private fun DetailInfoCard(cell: DetailInfoCell, modifier: Modifier) {
    Column(modifier.height(rd(79f)).clip(RoundedCornerShape(rd(19f))).background(P.Card)
        .padding(horizontal = rd(17f), vertical = rd(13f))) {
        RefText(cell.label, 15f, P.Muted)
        Spacer(Modifier.height(rd(10f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefText(cell.value, 18f, modifier = Modifier.weight(1f, fill = false))
            cell.badge?.let { badge ->
                Spacer(Modifier.width(rd(10f)))
                Box(Modifier.background(P.White.copy(alpha = .025f)).padding(horizontal = rd(7f), vertical = rd(3f))) {
                    RefText(badge, 16f)
                }
            }
        }
    }
}

@Composable
private fun DetailLiveFeed(stock: Stock, rows: List<DetailFeedRow>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(15f))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Live Feed", 20f, weight = FontWeight.Bold)
            Row(Modifier.width(rd(100f)).height(rd(35f)).clip(CircleShape)
                .background(P.Lime.copy(alpha = .025f)).semantics {
                    contentDescription = "Pause live feed"
                    disabled()
                }, horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                RefIcon("pause", 16f, P.Lime)
                Spacer(Modifier.width(rd(8f)))
                RefText("Pause", 16f, P.Lime)
            }
        }
        Spacer(Modifier.height(rd(23f)))
        Row(Modifier.fillMaxWidth().height(rd(20f)), verticalAlignment = Alignment.CenterVertically) {
            RefText("Time", 13f, P.Muted, modifier = Modifier.width(rd(70f)))
            Row(Modifier.width(rd(74f)), verticalAlignment = Alignment.CenterVertically) {
                RefText("Price", 13f, P.Muted)
                Spacer(Modifier.width(rd(5f)))
                RefIcon("sort", 14f, P.Muted, Modifier.semantics { disabled() })
            }
            Box(Modifier.width(rd(64f)), contentAlignment = Alignment.CenterEnd) {
                RefText("Volume", 13f, P.Muted)
            }
            Box(Modifier.width(rd(72f)), contentAlignment = Alignment.CenterEnd) {
                RefText(stock.symbol, 13f, P.Muted)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                RefIcon("person", 15f, P.Muted)
            }
        }
        rows.forEach { row -> DetailTradeRow(stock, row) }
    }
}

@Composable
private fun DetailTradeRow(stock: Stock, row: DetailFeedRow) {
    val color = if (row.side == DetailFeedSide.BUY) P.Lime else P.Pink
    Column(Modifier.fillMaxWidth().height(rd(63f))) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(rd(70f)), verticalAlignment = Alignment.CenterVertically) {
                RefText(row.ageLabel, 13f, color, modifier = Modifier.width(rd(28f)))
                Box(Modifier.width(rd(24f)).height(rd(27f))
                    .clip(RoundedCornerShape(rd(3f))).background(color.copy(alpha = .045f)),
                    contentAlignment = Alignment.Center) {
                    RefText(if (row.side == DetailFeedSide.BUY) "B" else "S", 14f, color)
                }
            }
            RefText(money(row.price, stock.quote.currencyCode), 13f, color,
                modifier = Modifier.width(rd(74f)))
            Box(Modifier.width(rd(64f)), contentAlignment = Alignment.CenterEnd) {
                RefText(money(row.volume, stock.quote.currencyCode), 13f, color)
            }
            Box(Modifier.width(rd(72f)), contentAlignment = Alignment.CenterEnd) {
                RefText(row.quantity.stripTrailingZeros().toPlainString(), 13f, color)
            }
            Row(Modifier.weight(1f).padding(start = rd(7f)),
                verticalAlignment = Alignment.CenterVertically) {
                RefText(row.participantLabel ?: "—", 13f,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(rd(3f)))
                RefIcon("copy", 13f, P.Muted, Modifier.semantics {
                    contentDescription = "Copy participant"
                    disabled()
                })
            }
        }
        Box(Modifier.fillMaxWidth().height(rd(.5f)).background(P.White.copy(alpha = .04f)))
    }
}

@Composable
private fun DetailOrderActions(
    symbol: String,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.padding(bottom = rd(18f))
            .shadow(rd(10f), CircleShape)
            .clip(CircleShape)
            .background(WalletStyle.Panel)
            .padding(rd(5f)),
        horizontalArrangement = Arrangement.spacedBy(rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.width(rd(122f)).height(rd(58f))
                .clip(CircleShape).background(P.Lime)
                .clickable(role = Role.Button, onClick = onBuy)
                .semantics { contentDescription = "Buy $symbol" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefIcon("plus", 24f, WalletStyle.Ink)
            Spacer(Modifier.width(rd(8f)))
            RefText("Buy", 18f, WalletStyle.Ink, FontWeight.Medium)
        }
        Row(
            Modifier.width(rd(122f)).height(rd(58f))
                .clip(CircleShape).background(Color(0xFFF05D62))
                .clickable(role = Role.Button, onClick = onSell)
                .semantics { contentDescription = "Sell $symbol" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefIcon("minus", 24f, WalletStyle.Ink)
            Spacer(Modifier.width(rd(8f)))
            RefText("Sell", 18f, WalletStyle.Ink, FontWeight.Medium)
        }
    }
}

private fun currencyPrefix(code: String): String = if (code == "USD") "$" else "$code "

private fun money(value: BigDecimal?, currency: String, includeCurrency: Boolean = true): String {
    if (value == null) return "—"
    val format = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 4
        roundingMode = RoundingMode.HALF_UP
    }
    return (if (value.signum() < 0) "-" else "") +
        (if (includeCurrency) currencyPrefix(currency) else "") + format.format(value.abs())
}

private fun signedMoney(value: BigDecimal, currency: String, includeCurrency: Boolean = true): String =
    (if (value.signum() >= 0) "+" else "") + money(value, currency, includeCurrency)

private fun signedPercent(value: BigDecimal): String {
    val rounded = value.setScale(2, RoundingMode.HALF_UP)
    return (if (rounded.signum() >= 0) "+" else "") + rounded.toPlainString() + "%"
}

private fun compactMoney(value: BigDecimal?, currency: String, includeCurrency: Boolean = true): String {
    if (value == null) return "—"
    return (if (value.signum() < 0) "-" else "") +
        (if (includeCurrency) currencyPrefix(currency) else "") + compactDecimal(value.abs())
}

private fun compactCount(value: Long?): String = value?.let { compactDecimal(BigDecimal.valueOf(it)) } ?: "—"

private fun organicScore(value: BigDecimal): String = value.setScale(1, RoundingMode.HALF_UP).toPlainString()

private fun compactDecimal(value: BigDecimal): String {
    val absolute = value.abs()
    val units = listOf(BigDecimal("1000000000000") to "T", BigDecimal("1000000000") to "B",
        BigDecimal("1000000") to "M", BigDecimal("1000") to "K")
    val unit = units.firstOrNull { absolute >= it.first }
    return if (unit == null) value.stripTrailingZeros().toPlainString()
    else value.divide(unit.first, 2, RoundingMode.HALF_UP).toPlainString() + unit.second
}
