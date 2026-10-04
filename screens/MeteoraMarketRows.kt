package com.elevencapital.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.elevencapital.app.data.MeteoraTokenChart
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.core.stock.ChartRange
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun MeteoraMarketHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText("Token", 15f, P.Muted, modifier = Modifier.weight(1f))
        Box(Modifier.width(rd(186f)), contentAlignment = Alignment.CenterEnd) {
            RefText("24h vol", 15f, P.Muted)
        }
    }
}

/** Filter only the loaded Meteora top 20; the default preserves the provider's volume order. */
internal fun selectMeteoraMarketTokens(
    tokens: List<MeteoraMarketToken>,
    search: String,
    movement: MarketMovement,
    sort: MarketSort,
): List<MeteoraMarketToken> {
    val terms = search.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val matching = tokens.filter { token ->
        terms.all { token.name.contains(it, ignoreCase = true) || token.symbol.contains(it, ignoreCase = true) } &&
            when (movement) {
                MarketMovement.ALL -> true
                MarketMovement.GAINERS -> token.change24hPercent?.signum() == 1
                MarketMovement.LOSERS -> token.change24hPercent?.signum() == -1
            }
    }
    val metric: ((MeteoraMarketToken) -> BigDecimal?)? = when (sort) {
        MarketSort.VOLUME_ASC, MarketSort.VOLUME_DESC -> { token -> token.volume24hUsd }
        MarketSort.CHANGE_ASC, MarketSort.CHANGE_DESC -> { token -> token.change24hPercent }
        else -> null
    }
    if (metric != null) {
        val descending = sort == MarketSort.VOLUME_DESC || sort == MarketSort.CHANGE_DESC
        return matching.sortedWith { left, right ->
            val a = metric(left)
            val b = metric(right)
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                descending -> b.compareTo(a)
                else -> a.compareTo(b)
            }
        }
    }
    return when (sort) {
        MarketSort.NAME_ASC -> matching.sortedBy { it.name.lowercase(Locale.ROOT) }
        MarketSort.NAME_DESC -> matching.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        else -> matching
    }
}

@Composable
internal fun MeteoraMarketNoMatches(onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = rd(18f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefText("No matching tokens", 14f, W.Muted, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(CircleShape).background(W.Circle)
                .clickable(role = Role.Button, onClick = onClear)
                .padding(horizontal = rd(14f), vertical = rd(10f)),
        ) { RefText("Clear filters", 13f, W.White, FontWeight.Medium) }
    }
}

@Composable
internal fun MeteoraMarketControlsDialog(
    search: String,
    onSearchChange: (String) -> Unit,
    movement: MarketMovement,
    onMovementChange: (MarketMovement) -> Unit,
    sort: MarketSort,
    onSortChange: (MarketSort) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = rd(610f))
                .clip(RoundedCornerShape(rd(22f))).background(P.Card).padding(rd(20f)),
        ) {
            RefText("Search & filters", 20f, weight = FontWeight.Bold)
            Spacer(Modifier.height(rd(18f)))
            BasicTextField(
                value = search,
                onValueChange = onSearchChange,
                singleLine = true,
                textStyle = TextStyle(color = P.White, fontSize = rs(17f), fontFamily = LocalReferenceFont.current),
                cursorBrush = SolidColor(P.Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Search by token name or symbol" }
                    .clip(CircleShape).background(P.Chip)
                    .padding(horizontal = rd(16f), vertical = rd(14f)),
                decorationBox = { field ->
                    Box { if (search.isEmpty()) RefText("Name or symbol", 17f, P.Muted); field() }
                },
            )
            Spacer(Modifier.height(rd(12f)))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                RefText("Tokens", 14f, P.Muted)
                MarketMovement.entries.forEach { option ->
                    MeteoraFilterOption(option.label, movement == option) { onMovementChange(option) }
                }
                Spacer(Modifier.height(rd(12f)))
                RefText("Sort", 14f, P.Muted)
                listOf(
                    MarketSort.DEFAULT to "Top 24h volume",
                    MarketSort.VOLUME_DESC to "Volume: high to low",
                    MarketSort.VOLUME_ASC to "Volume: low to high",
                    MarketSort.CHANGE_DESC to "Change: high to low",
                    MarketSort.CHANGE_ASC to "Change: low to high",
                    MarketSort.NAME_ASC to "Name A–Z",
                    MarketSort.NAME_DESC to "Name Z–A",
                ).forEach { (option, label) ->
                    MeteoraFilterOption(label, sort == option) { onSortChange(option) }
                }
            }
            Spacer(Modifier.height(rd(18f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                Box(
                    Modifier.weight(1f).height(rd(44f)).clip(CircleShape).background(P.Chip)
                        .clickable(role = Role.Button, onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) { RefText("Reset", 16f) }
                Box(
                    Modifier.weight(1f).height(rd(44f)).clip(CircleShape).background(P.Lime)
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { RefText("Done", 16f, P.Background) }
            }
        }
    }
}

@Composable
private fun MeteoraFilterOption(label: String, active: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(rd(44f)).clip(RoundedCornerShape(rd(10f)))
            .background(if (active) P.Chip else P.Card)
            .semantics { selected = active }
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = rd(10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefText(label, 16f, if (active) P.Lime else P.White, modifier = Modifier.weight(1f))
        if (active) Box(Modifier.size(rd(6f)).background(P.Lime, CircleShape))
    }
}

@Composable
internal fun MeteoraMarketStatus(
    empty: Boolean,
    loading: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
) {
    val message = when {
        error != null -> "Tokens unavailable"
        loading -> if (empty) "Loading tokens…" else "Updating tokens…"
        empty -> "No tokens available"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = rd(18f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefText(message, 14f, if (error == null) W.Muted else P.Pink,
            modifier = Modifier.weight(1f), maxLines = 3)
        if (!loading && (error != null || empty) && onRetry != null) {
            Spacer(Modifier.width(rd(12f)))
            Box(
                Modifier.clip(CircleShape).background(W.Circle)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = rd(14f), vertical = rd(9f)),
            ) { RefText(if (error != null) "Retry" else "Refresh", 13f, W.White, FontWeight.Medium) }
        }
    }
}

@Composable
internal fun MeteoraMarketTokenRow(
    token: MeteoraMarketToken,
    onToken: ((MeteoraMarketToken) -> Unit)?,
) {
    val volumeLabel = meteoraUsdVolume(token.volume24hUsd)
    val movement = when (token.change24hPercent?.signum()) {
        1 -> "up"
        -1 -> "down"
        0 -> "unchanged"
        else -> "change unavailable"
    }
    val rowModifier = Modifier.fillMaxWidth().height(rd(70f)).then(
        if (onToken == null) Modifier else Modifier.clickable(role = Role.Button) { onToken(token) }
            .semantics { contentDescription = "Open ${token.name} token details. 24-hour volume $volumeLabel, $movement" },
    )
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        MeteoraTokenArtwork(token, 44f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(token.symbol, 18f, W.White, FontWeight.SemiBold, maxLines = 1)
            RefText(token.name, 15f, P.Muted, maxLines = 1)
        }
        Box(Modifier.width(rd(186f)), contentAlignment = Alignment.CenterEnd) {
            RefText(volumeLabel, 18f,
                meteoraChangeColor(token.change24hPercent), maxLines = 1)
        }
    }
}

@Composable
internal fun MeteoraTokenArtwork(token: MeteoraMarketToken, size: Float) {
    var imageReady by remember(token.imageUrl) { mutableStateOf(false) }
    Box(Modifier.size(rd(size)).clip(CircleShape).background(W.Circle), contentAlignment = Alignment.Center) {
        if (!imageReady) {
            RefText(token.symbol.trim().take(2).uppercase(Locale.US).ifBlank { "?" },
                if (size > 48f) 22f else 13f, W.Cream, FontWeight.SemiBold)
        }
        val image = token.imageUrl?.takeIf { it.startsWith("https://") }
        if (image != null) AsyncImage(
            model = image,
            contentDescription = "${token.name} artwork",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onSuccess = { imageReady = true },
            onError = { imageReady = false },
        )
    }
}

private fun meteoraPercent(value: BigDecimal?): String = value?.let {
    (if (it.signum() >= 0) "+" else "−") + it.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
} ?: "—"

private fun meteoraChangeColor(value: BigDecimal?): Color = when {
    value == null -> P.Muted
    value.signum() < 0 -> P.Pink
    value.signum() == 0 -> P.Muted
    else -> P.Lime
}

/** The token detail mirrors the stock quote/chart rhythm without presenting a trade action. */
@Composable
fun MeteoraTokenDetailScreen(
    token: MeteoraMarketToken,
    chart: MeteoraTokenChart?,
    chartLoading: Boolean,
    chartError: String?,
    selectedRange: ChartRange,
    onRangeSelected: (ChartRange) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    description: String? = null,
    descriptionLoading: Boolean = false,
) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(rd(66f)).padding(start = rd(17f), end = rd(22f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(39f)).clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back to Markets" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 27f) }
            Spacer(Modifier.width(rd(13f)))
            MeteoraTokenArtwork(token, 52f)
            Spacer(Modifier.width(rd(12f)))
            Column(Modifier.weight(1f)) {
                RefText(token.symbol, 20f, W.White, FontWeight.Bold)
                Spacer(Modifier.height(rd(4f)))
                RefText(token.name, 14f, P.Muted)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "token-volume") {
                Column(Modifier.fillMaxWidth().padding(horizontal = rd(18f))) {
                    Spacer(Modifier.height(rd(30f)))
                    val volumeLabel = meteoraUsdVolume(token.volume24hUsd)
                    val volumeColor = if (token.change24hPercent == null) W.White
                        else meteoraChangeColor(token.change24hPercent)
                    RefText(volumeLabel, if (volumeLabel.length > 10) 36f else 44f,
                        volumeColor, FontWeight.Bold)
                    Spacer(Modifier.height(rd(7f)))
                    RefText("24h volume", 15f, P.Muted)
                    Spacer(Modifier.height(rd(24f)))
                }
            }
            item(key = "token-chart") {
                MeteoraChartRanges(selectedRange, onRangeSelected)
                MeteoraTrendChart(chart, chartLoading, chartError, selectedRange, onRetry)
            }
            item(key = "token-stats") {
                Column(Modifier.fillMaxWidth().padding(horizontal = rd(18f), vertical = rd(25f))) {
                    RefText("Market overview", 20f, W.White, FontWeight.Bold)
                    Spacer(Modifier.height(rd(16f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                        MeteoraMetric("24h volume", meteoraUsdVolume(token.volume24hUsd), Modifier.weight(1f))
                        MeteoraMetric("Market cap", meteoraUsdVolume(token.marketCapUsd), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(rd(10f)))
                    Row(horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                        MeteoraMetric("Liquidity", meteoraUsdVolume(token.liquidityUsd), Modifier.weight(1f))
                        MeteoraMetric("24h change", meteoraPercent(token.change24hPercent), Modifier.weight(1f),
                            meteoraChangeColor(token.change24hPercent))
                    }
                }
            }
            item(key = "token-about") {
                Column(Modifier.fillMaxWidth().padding(start = rd(18f), end = rd(18f),
                    bottom = rd(28f))) {
                    RefText("About ${token.symbol}", 20f, W.White, FontWeight.Bold)
                    Spacer(Modifier.height(rd(12f)))
                    RefText(when {
                        descriptionLoading -> "Loading description…"
                        description.isNullOrBlank() -> "No description available"
                        else -> description.trim()
                    }, 17f, P.Muted, maxLines = Int.MAX_VALUE)
                }
            }
        }
    }
}

@Composable
private fun MeteoraMetric(label: String, value: String, modifier: Modifier, valueColor: Color = W.White) {
    Column(modifier.clip(RoundedCornerShape(rd(16f))).background(P.Card)
        .padding(horizontal = rd(15f), vertical = rd(16f))) {
        RefText(label, 14f, P.Muted)
        Spacer(Modifier.height(rd(9f)))
        RefText(value, 20f, valueColor, FontWeight.Medium)
    }
}

@Composable
private fun MeteoraChartRanges(selectedRange: ChartRange, onSelect: (ChartRange) -> Unit) {
    val rangeLabels = listOf(
        ChartRange.ONE_HOUR to "1H",
        ChartRange.ONE_DAY to "1D",
        ChartRange.ONE_WEEK to "1W",
        ChartRange.ONE_MONTH to "1M",
        ChartRange.YEAR_TO_DATE to "YTD",
    )
    Row(Modifier.fillMaxWidth().height(rd(43f)).padding(horizontal = rd(22f)),
        verticalAlignment = Alignment.CenterVertically) {
        RefText("Time", 15f)
        Spacer(Modifier.width(rd(4f)))
        rangeLabels.forEach { (range, label) ->
            val active = selectedRange == range
            Box(Modifier.width(rd(if (range == ChartRange.YEAR_TO_DATE) 42f else 36f))
                .height(rd(29f)).clip(RoundedCornerShape(rd(9f)))
                .background(if (active) P.Card else Color.Transparent)
                .semantics { selected = active; contentDescription = "$label token trend" }
                .clickable(role = Role.Tab) { onSelect(range) },
                contentAlignment = Alignment.Center) {
                RefText(label, 15f, if (active) P.White else P.Muted)
            }
        }
    }
}

@Composable
private fun MeteoraTrendChart(
    chart: MeteoraTokenChart?,
    loading: Boolean,
    error: String?,
    range: ChartRange,
    onRetry: () -> Unit,
) {
    val points = remember(chart) { chart?.points?.sortedBy { it.timestampSeconds }.orEmpty() }
    val values = remember(points) { points.map { it.priceUsd.toDouble() } }
    val low = values.minOrNull()?.takeIf { it.isFinite() }
    val high = values.maxOrNull()?.takeIf { it.isFinite() }
    val bounds = if (low != null && high != null) paddedChartBounds(low, high) else null
    val firstTime = points.firstOrNull()?.timestampSeconds ?: 0L
    val lastTime = points.lastOrNull()?.timestampSeconds ?: firstTime
    val span = (lastTime - firstTime).coerceAtLeast(1L)
    val horizontalInset = rd(8f)
    val chartTop = rd(32f)
    val chartBottom = rd(29f)
    val chartStroke = rd(2f)
    val endpointRadius = rd(3f)
    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        Box(Modifier.fillMaxWidth().height(rd(374f))) {
            Box(Modifier.align(Alignment.Center).padding(top = rd(36f))) { SquareLogo(46f, .075f) }
            Canvas(Modifier.fillMaxSize()) {
                drawLine(P.White.copy(alpha = .07f), Offset.Zero, Offset(size.width, 0f), 1f)
                drawLine(P.White.copy(alpha = .035f), Offset(0f, size.height),
                    Offset(size.width, size.height), 1f)
                if (bounds != null && points.size > 1) {
                    val left = horizontalInset.toPx()
                    val top = chartTop.toPx()
                    val bottom = size.height - chartBottom.toPx()
                    val plotWidth = size.width - left * 2f
                    val denominator = (bounds.second - bounds.first).takeIf { it > 0.0 } ?: 1.0
                    val path = Path()
                    points.forEachIndexed { index, point ->
                        val x = left + ((point.timestampSeconds - firstTime).toDouble() / span)
                            .toFloat().coerceIn(0f, 1f) * plotWidth
                        val y = bottom - ((values[index] - bounds.first) / denominator)
                            .toFloat().coerceIn(0f, 1f) * (bottom - top)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, P.Amber, style = Stroke(chartStroke.toPx(), cap = StrokeCap.Round))
                } else if (bounds != null && points.size == 1) {
                    drawCircle(P.Amber, endpointRadius.toPx(), Offset(size.width - horizontalInset.toPx(), size.height / 2f))
                }
            }
            if (points.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    RefText(when {
                        loading -> "Loading token trend…"
                        error != null -> "Trend unavailable"
                        else -> "No trend for this range"
                    }, 14f, P.Muted)
                    if (!loading) {
                        Spacer(Modifier.height(rd(12f)))
                        Box(Modifier.clip(CircleShape).background(P.Card)
                            .clickable(role = Role.Button, onClick = onRetry)
                            .padding(horizontal = rd(17f), vertical = rd(9f))) {
                            RefText("Retry", 14f, W.White)
                        }
                    }
                }
            }
        }
        if (points.size > 1) {
            val formatter = remember(range) { DateTimeFormatter.ofPattern(
                if (range == ChartRange.ONE_HOUR || range == ChartRange.ONE_DAY) "HH:mm" else "MMM d",
                Locale.US).withZone(ZoneId.systemDefault()) }
            Row(Modifier.fillMaxWidth().height(rd(28f)).padding(horizontal = rd(16f)),
                verticalAlignment = Alignment.CenterVertically) {
                (0..3).forEach { tick ->
                    val timestamp = firstTime + (span * tick / 3)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        RefText(formatter.format(Instant.ofEpochSecond(timestamp)), 11f, P.Muted)
                    }
                }
            }
        }
    }
}

private fun meteoraUsdVolume(value: BigDecimal?): String {
    if (value == null) return "—"
    val units = listOf(
        BigDecimal("1000000000") to "B",
        BigDecimal("1000000") to "M",
        BigDecimal("1000") to "K",
    )
    val unit = units.firstOrNull { value >= it.first }
    val amount = if (unit == null) value else value.divide(unit.first, 1, RoundingMode.HALF_UP)
    val places = if (unit == null && amount < BigDecimal.ONE) 2 else if (unit == null) 0 else 1
    return "\$" + amount.setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() +
        (unit?.second ?: "")
}
