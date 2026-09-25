package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.R
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.data.PortfolioNetwork
import com.elevencapital.app.data.LiveWalletTokenHolding
import com.elevencapital.app.data.WalletBalancePhase
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.SolanaLogo
import com.elevencapital.app.ui.StockIcon
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import coil3.imageLoader
import coil3.request.ImageRequest
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/** Optional provider fields used by the original list and holding row layouts. */
data class PrimaryStockRow(
    val stock: Stock,
    val ageLabel: String? = null,
    val volume: BigDecimal? = null,
    val netVolume: BigDecimal? = null,
    val quantity: BigDecimal? = null,
    val holdingValue: BigDecimal? = null,
    val watched: Boolean = false,
    val verified: Boolean = false,
    val holdingValueCurrency: String? = null,
)

/** Quote fields are deliberately absent so a price tick cannot restart viewport/logo work. */
private data class MarketAssetInterest(val id: StockId, val logoUrl: String?)

private class MarketAssetInterestCache {
    private var previousRows: List<PrimaryStockRow>? = null
    private var assets: List<MarketAssetInterest> = emptyList()

    fun get(rows: List<PrimaryStockRow>): List<MarketAssetInterest> {
        if (rows === previousRows) return assets
        if (rows.size != assets.size || rows.indices.any { index ->
                rows[index].stock.id != assets[index].id || rows[index].stock.logo?.value != assets[index].logoUrl
            }) {
            assets = rows.map { MarketAssetInterest(it.stock.id, it.stock.logo?.value) }
        }
        previousRows = rows
        return assets
    }
}

/** Bounded per-screen lookahead history avoids resubmitting overlapping Coil requests on every row. */
internal class MarketLogoPrefetchHistory(private val capacity: Int = 256) {
    private val requested = LinkedHashSet<String>()

    fun shouldRequest(url: String): Boolean {
        if (!url.startsWith("https://") || !requested.add(url)) return false
        if (requested.size > capacity) {
            val oldest = requested.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}

/** Keeps the filtered lead rows fresh without dropping rows that are actually on screen. */
internal fun marketSubscriptionIds(
    displayedIds: List<StockId>,
    visibleIds: Set<StockId>,
): Set<StockId> = (displayedIds.take(20) + visibleIds).take(100).toSet()

/** The stock table keeps its compact two-line columns below the shared header. */
@Composable
fun MarketsScreen(
    rows: List<PrimaryStockRow>,
    onStock: (StockId) -> Unit,
    onWatch: (StockId) -> Unit,
    onFilter: () -> Unit,
    statusMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    query: MarketQuery = MarketQuery(),
    watchlistOnly: Boolean = false,
    onQueryChange: (MarketQuery) -> Unit = {},
    onVisibleStockIds: (Set<StockId>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val visibleCallback by rememberUpdatedState(onVisibleStockIds)
    val rowSelector = remember { MarketRowSelector() }
    val displayedRows = rowSelector.select(rows, query, watchlistOnly)
    val assetInterest = remember { MarketAssetInterestCache() }
    val displayedAssets = assetInterest.get(displayedRows)
    val prefetchedLogos = remember(context) { MarketLogoPrefetchHistory() }
    val displayedIds = remember(displayedAssets) { displayedAssets.map { it.id } }
    LaunchedEffect(listState, displayedAssets) {
        snapshotFlow {
            val items = listState.layoutInfo.visibleItemsInfo
            val ids = items.mapNotNull { item ->
                (item.key as? String)?.takeIf { it.startsWith("stock:") }
                    ?.removePrefix("stock:")?.let(::StockId)
            }.toSet()
            val lastStockIndex = items.lastOrNull { (it.key as? String)?.startsWith("stock:") == true }
                ?.index?.minus(1)?.coerceAtLeast(0) ?: 0
            ids to lastStockIndex
        }.distinctUntilChanged().collect { (ids, lastStockIndex) ->
            // Keep the actual first 20 filtered rows live as well as the current viewport.
            // This is derived after query/watchlist filtering, so no provider ordering is guessed.
            visibleCallback(marketSubscriptionIds(displayedIds, ids))
            // One viewport of lookahead is enough to warm scrolling. Re-enqueuing an
            // overlapping 80-logo window used to create dozens of requests per row crossed.
            val prefetchStart = (lastStockIndex + 1).coerceIn(0, displayedAssets.size)
            val prefetchEnd = minOf(displayedAssets.size, prefetchStart + 12)
            for (index in prefetchStart until prefetchEnd) {
                val url = displayedAssets[index].logoUrl ?: continue
                if (prefetchedLogos.shouldRequest(url)) {
                    context.imageLoader.enqueue(ImageRequest.Builder(context).data(url).size(128, 128).build())
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { visibleCallback(emptySet()) } }
    // Queries reset scroll position, whereas quote patches preserve the user's place.
    LaunchedEffect(query, watchlistOnly) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(W.Background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = rd(22f)),
        ) {
            item(key = "market-controls", contentType = "controls") {
                Column {
                    Spacer(Modifier.height(rd(8f)))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MarketSourceSelector(query.source) { source ->
                            onQueryChange(query.copy(source = source))
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.height(rd(38f)).clip(CircleShape)
                                .background(if (query.source == MarketSource.ALL) W.Cream else W.Circle)
                                .clickable(role = Role.Button, onClick = onFilter)
                                .semantics { contentDescription = "Search and filter stocks" }
                                .padding(horizontal = rd(13f)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(rd(7f)),
                        ) {
                            val color = if (query.source == MarketSource.ALL) W.Ink else W.White
                            RefText(if (watchlistOnly || query.filtered || query.text.isNotBlank()) "Filters" else "All", 14f, color)
                            RefIcon("chevronDown", 11f, color)
                        }
                    }
                    Spacer(Modifier.height(rd(14f)))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RefText("Stock", 15f, P.Muted, modifier = Modifier.weight(1f)
                            .semantics { contentDescription = "Sort stocks by name" }
                            .clickable(role = Role.Button) {
                                onQueryChange(query.copy(sort = if (query.sort == MarketSort.NAME_ASC) MarketSort.NAME_DESC else MarketSort.NAME_ASC))
                            })
                        Box(Modifier.width(rd(82f)).semantics { contentDescription = "Sort stocks by price" }
                            .clickable(role = Role.Button) {
                                onQueryChange(query.copy(sort = if (query.sort == MarketSort.PRICE_DESC) MarketSort.PRICE_ASC else MarketSort.PRICE_DESC))
                            }, contentAlignment = Alignment.CenterEnd) {
                            RefText("Price/Δ%", 15f, P.Muted)
                        }
                        Row(
                            Modifier.width(rd(88f)).semantics { contentDescription = "Sort stocks by 24-hour volume" }
                                .clickable(role = Role.Button) {
                                    onQueryChange(query.copy(sort = if (query.sort == MarketSort.VOLUME_DESC) MarketSort.VOLUME_ASC else MarketSort.VOLUME_DESC))
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            RefText("Vol/Net", 15f, P.Muted, modifier = Modifier.semantics {
                                contentDescription = "24-hour trading volume and net buy volume"
                            })
                            Spacer(Modifier.width(rd(5f)))
                            RefIcon("chevronDown", 10f, P.Muted)
                        }
                    }
                    Spacer(Modifier.height(rd(9f)))
                }
            }
            items(displayedRows, key = { "stock:${it.stock.id.value}" }, contentType = { "stock" }) { item ->
                PrimaryMarketRow(item, onStock, onWatch)
            }
            val emptyMessage = when {
                displayedRows.isNotEmpty() -> null
                rows.isEmpty() -> statusMessage?.takeIf(String::isNotBlank)
                    ?: if (statusMessage == null) "No stocks available" else null
                watchlistOnly && query == MarketQuery() -> "No stocks in your watchlist"
                else -> "No matching stocks"
            }
            if (emptyMessage != null) {
                item(key = "empty", contentType = "message") {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = rd(20f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RefText(emptyMessage, 15f, P.Muted, maxLines = 3)
                        if (rows.isEmpty() && onRetry != null) {
                            Spacer(Modifier.height(rd(8f)))
                            Box(
                                Modifier.clip(CircleShape).background(P.Card)
                                    .clickable(role = Role.Button, onClick = onRetry)
                                    .padding(horizontal = rd(16f), vertical = rd(10f)),
                            ) {
                                RefText("Retry", 14f)
                            }
                        }
                    }
                }
            }
            item(key = "dock-space", contentType = "spacer") { Spacer(Modifier.height(rd(116f))) }
        }
    }
}

/** Mirrors the home dock at a smaller size; the source query also drives the All filter dialog. */
@Composable
private fun MarketSourceSelector(selectedSource: MarketSource, onSelect: (MarketSource) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(W.Panel).padding(rd(4f)),
        horizontalArrangement = Arrangement.spacedBy(rd(5f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            MarketSource.BACKPACK to R.drawable.market_source_backpack,
            MarketSource.BACKED to R.drawable.market_source_backed,
            MarketSource.PRESTOCKS to R.drawable.market_source_prestocks,
        ).forEach { (source, artwork) ->
            val active = selectedSource == source
            Box(
                Modifier.size(rd(42f)).clip(CircleShape)
                    .background(if (active) W.Cream else W.Circle)
                    .clickable(role = Role.Tab) { onSelect(source) }
                    .semantics {
                        contentDescription = "${source.label} stocks"
                        selected = active
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(artwork), contentDescription = null,
                    modifier = Modifier.size(rd(34f)).clip(CircleShape),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

@Composable
fun AccountScreen(
    balance: BigDecimal?,
    tokenHoldings: List<LiveWalletTokenHolding>,
    holdings: List<PrimaryStockRow>,
    onStock: (StockId) -> Unit,
    previewSpcxxHolding: PreviewStockHolding? = null,
    onPreviewSpcxxOpenTransaction: () -> Unit = {},
    onPreviewSpcxxRemove: () -> Unit = {},
    previewHoldings: List<PreviewStockHolding> = emptyList(),
    onPreviewOpenTransaction: (PreviewStockHolding) -> Unit = {},
    onPreviewRemoveAll: () -> Unit = {},
    previewSolBalance: BigDecimal? = null,
    previewSolValueUsd: BigDecimal? = null,
    onPreviewSolRemove: () -> Unit = {},
    devnetSol: BigDecimal? = null,
    onToken: (LiveWalletTokenHolding) -> Unit = {},
    onDevnetSol: () -> Unit = {},
    accountNotice: String? = null,
    balancePlaceholder: String = "—",
    portfolioPhase: WalletBalancePhase = WalletBalancePhase.LOADING,
    walletConnected: Boolean = true,
    compactTopSpacing: Boolean = false,
) {
    val ownedHoldings = holdings.filter { (it.quantity?.signum() ?: 0) > 0 }
    val ownedTokens = tokenHoldings.filter { it.quantity.signum() > 0 }
        .sortedWith(compareByDescending<LiveWalletTokenHolding> { it.valueUsd }.thenBy { it.symbol })
    val duplicateSymbols = ownedTokens.groupingBy { it.symbol.uppercase(Locale.US) }.eachCount()
    val visibleDevnetSol = devnetSol?.takeIf { it.signum() > 0 }
    val visiblePreviewSol = previewSolBalance?.takeIf { BuildConfig.DEBUG && it.signum() > 0 }
    val previewSpcxx = previewSpcxxHolding?.takeIf {
        BuildConfig.DEBUG && it.stock.id.value == "backed:eba060bd-f7b3-49e3-8ef1-99869351b434" &&
            it.quantity.signum() > 0 && it.valueUsd.signum() > 0
    }
    val visiblePreviewHoldings = if (BuildConfig.DEBUG) {
        (previewHoldings + listOfNotNull(previewSpcxx))
            .filter { it.quantity.signum() > 0 && it.valueUsd.signum() > 0 }
            .distinctBy { it.stock.id.value }
    } else emptyList()
    Column(Modifier.fillMaxSize().background(W.Background)) {
        Spacer(Modifier.height(rd(if (compactTopSpacing) 6f else 32f)))
        Box(Modifier.fillMaxWidth().padding(horizontal = rd(26f))) {
            PortfolioBalanceCard(balance, balancePlaceholder)
        }
        Spacer(Modifier.height(rd(26f)))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(topStart = rd(30f), topEnd = rd(30f)))
                .background(W.Panel),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = rd(26f), end = rd(26f), top = rd(22f), bottom = rd(112f)),
        ) {
            item(key = "tokens-heading") {
                RefText("Tokens", 20f, W.White, FontWeight.Medium)
                Spacer(Modifier.height(rd(12f)))
            }
            if (ownedTokens.isEmpty() && visibleDevnetSol == null && visiblePreviewSol == null &&
                visiblePreviewHoldings.isEmpty()) {
                item(key = "tokens-empty") {
                    val emptyText = when {
                        !walletConnected -> "Connect a wallet to see your tokens"
                        portfolioPhase == WalletBalancePhase.LOADING -> null
                        portfolioPhase == WalletBalancePhase.READY -> "No tokens held yet"
                        else -> "Token balances unavailable"
                    }
                    if (emptyText != null) RefText(emptyText, 14f, W.Muted)
                    Spacer(Modifier.height(rd(14f)))
                }
            } else {
                items(ownedTokens, key = { "token:${it.chain}:${it.assetId}" }) { token ->
                    PortfolioTokenRow(token, duplicateSymbols.getValue(token.symbol.uppercase(Locale.US)) > 1, onToken)
                }
            }
            if (visibleDevnetSol != null) {
                item(key = "devnet-sol") { PortfolioDevnetSolRow(visibleDevnetSol, onDevnetSol) }
            }
            if (visiblePreviewSol != null) {
                item(key = "preview-sol") {
                    PortfolioPreviewSolRow(
                        visiblePreviewSol,
                        previewSolValueUsd ?: BigDecimal.ZERO,
                        onPreviewSolRemove,
                    )
                }
            }
            items(visiblePreviewHoldings, key = { "local:${it.stock.id.value}" }) { holding ->
                PortfolioPreviewStockRow(
                    holding = holding,
                    onOpenTransaction = {
                        if (holding.stock.id.value == "backed:eba060bd-f7b3-49e3-8ef1-99869351b434" &&
                            previewHoldings.none { it.stock.id == holding.stock.id }) {
                            onPreviewSpcxxOpenTransaction()
                        } else onPreviewOpenTransaction(holding)
                    },
                    onRemove = {
                        if (previewHoldings.isEmpty()) onPreviewSpcxxRemove()
                        else onPreviewRemoveAll()
                    },
                )
            }
            if (ownedHoldings.isNotEmpty()) {
                item(key = "stocks-heading") {
                    Spacer(Modifier.height(rd(24f)))
                    RefText("Stocks", 20f, W.White, FontWeight.Medium)
                    Spacer(Modifier.height(rd(12f)))
                }
                items(ownedHoldings, key = { "holding:${it.stock.id.value}" }) { holding ->
                    PortfolioStockRow(holding, onStock)
                }
            }
        }
    }
}

@Composable
private fun PortfolioBalanceCard(balance: BigDecimal?, placeholder: String) {
    val amount = balance?.let { primaryBalance(it) } ?: placeholder
    val amountSize = when {
        balance == null -> 32f
        amount.length > 13 -> 22f
        amount.length > 11 -> 26f
        amount.length > 9 -> 30f
        else -> 36f
    }
    val holderShape = RoundedCornerShape(rd(28f))
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(1.08f)
            .clip(holderShape)
            .background(Color(0xFF080808))
            .border(rd(3f), Color(0xFF252525), holderShape),
    ) {
        val inset = maxWidth * 0.045f
        Box(
            Modifier.fillMaxWidth().padding(horizontal = inset).padding(top = inset)
                .height(maxWidth * 0.49f)
                .clip(RoundedCornerShape(topStart = rd(23f), topEnd = rd(23f)))
                .background(Brush.linearGradient(listOf(
                    Color(0xFFFFD9AE), Color(0xFFFF9B77), Color(0xFFEA70C9),
                ))),
        )
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.66f).align(Alignment.BottomCenter)
                .drawBehind {
                    val shoulder = size.height * 0.13f
                    val front = Path().apply {
                        moveTo(0f, shoulder)
                        lineTo(size.width * 0.16f, shoulder)
                        cubicTo(size.width * 0.21f, shoulder, size.width * 0.22f, 0f,
                            size.width * 0.29f, 0f)
                        lineTo(size.width * 0.68f, 0f)
                        cubicTo(size.width * 0.75f, 0f, size.width * 0.76f, shoulder,
                            size.width * 0.82f, shoulder)
                        lineTo(size.width, shoulder)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(front, Brush.verticalGradient(listOf(Color(0xFF222222), Color(0xFF101010))))
                    drawPath(front, Color.White.copy(alpha = 0.045f), style = Stroke(width = 2f))
                },
        ) {
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = rd(25f), end = rd(22f), bottom = rd(25f)),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    RefText(amount, amountSize, W.White, FontWeight.Medium,
                        modifier = Modifier.semantics {
                            contentDescription = balance?.let { "Total portfolio balance ${it.toPlainString()} US dollars" }
                                ?: "Total portfolio balance $placeholder"
                        })
                    Spacer(Modifier.height(rd(3f)))
                    RefText("Balance", 13f, W.Muted)
                }
                Spacer(Modifier.width(rd(10f)))
                RefText("Eleven Capital", 12f, W.White, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PortfolioTokenRow(
    token: LiveWalletTokenHolding,
    showNetwork: Boolean,
    onToken: (LiveWalletTokenHolding) -> Unit,
) {
    val symbol = token.symbol.ifBlank { "Token" }
    val network = when (token.chain) {
        PortfolioNetwork.SOLANA -> "Solana"
        PortfolioNetwork.ETHEREUM -> "Ethereum"
        PortfolioNetwork.ARBITRUM -> "Arbitrum"
    }
    val clickModifier = if (token.chain == PortfolioNetwork.SOLANA) Modifier.clickable(role = Role.Button) { onToken(token) }
        .semantics { contentDescription = "Open $symbol on Solscan" } else Modifier
    Row(Modifier.fillMaxWidth().height(rd(77f)).then(clickModifier), verticalAlignment = Alignment.CenterVertically) {
        PortfolioTokenIcon(symbol)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(symbol, 18f, W.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            val unitPrice = token.unitPriceUsd?.let(::portfolioUnitPrice)
            val detail = listOfNotNull(unitPrice, network.takeIf { showNetwork || unitPrice == null })
                .joinToString(" · ")
            RefText(detail, 13f, W.Muted)
        }
        Spacer(Modifier.width(rd(10f)))
        Column(horizontalAlignment = Alignment.End) {
            RefText(token.valueUsd?.let { primaryBalance(it) } ?: "—", 18f, W.White)
            Spacer(Modifier.height(rd(3f)))
            RefText(portfolioTokenQuantity(token.quantity), 13f, W.Muted)
        }
    }
}

@Composable
private fun PortfolioDevnetSolRow(quantity: BigDecimal, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(rd(77f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Open Solana Devnet wallet on Solscan" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortfolioTokenIcon("SOL")
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText("SOL", 18f, W.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            RefText("Solana Devnet", 13f, W.Muted)
        }
        Spacer(Modifier.width(rd(10f)))
        Column(horizontalAlignment = Alignment.End) {
            RefText("—", 18f, W.White)
            Spacer(Modifier.height(rd(3f)))
            RefText(portfolioTokenQuantity(quantity), 13f, W.Muted)
        }
    }
}

@Composable
private fun PortfolioPreviewSolRow(
    quantity: BigDecimal,
    valueUsd: BigDecimal,
    onRemove: () -> Unit,
) {
    val currentRemove by rememberUpdatedState(onRemove)
    Row(
        Modifier.fillMaxWidth().height(rd(77f))
            .pointerInput(Unit) {
                detectPreviewRowPress(onTap = null, onHold = { currentRemove() })
            }
            .semantics {
                contentDescription = "Preview SOL, ${portfolioTokenQuantity(quantity)}, ${primaryBalance(valueUsd)}"
                onLongClick(label = "Remove local SOL preview from this device") {
                    onRemove()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PortfolioTokenIcon("SOL")
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText("SOL", 18f, W.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            val unitPrice = valueUsd.takeIf { it.signum() > 0 }
                ?.divide(quantity, 8, RoundingMode.HALF_UP)
                ?.let(::portfolioUnitPrice)
            RefText(unitPrice ?: "Solana", 13f, W.Muted)
        }
        Spacer(Modifier.width(rd(10f)))
        Column(horizontalAlignment = Alignment.End) {
            RefText(primaryBalance(valueUsd), 18f, W.White)
            Spacer(Modifier.height(rd(3f)))
            RefText(portfolioTokenQuantity(quantity), 13f, W.Muted)
        }
    }
}

private suspend fun PointerInputScope.detectPreviewRowPress(
    onTap: (() -> Unit)?,
    onHold: () -> Unit,
) {
    awaitEachGesture {
        // Observe before the list's scroll detector consumes motion. A small amount of finger
        // drift keeps the hold alive; a deliberate swipe remains available for scrolling.
        val down = awaitFirstDown(requireUnconsumed = false)
        val maxHoldDrift = viewConfiguration.touchSlop * 3f
        var cancelled = false
        val endedBeforeHold = withTimeoutOrNull(4_000L) {
            while (true) {
                val changes = awaitPointerEvent(PointerEventPass.Initial).changes
                val change = changes.firstOrNull { it.id == down.id }
                if (change == null || changes.any { it.id != down.id && it.pressed } ||
                    (change.position - down.position).getDistance() > maxHoldDrift) {
                    cancelled = true
                    break
                }
                if (!change.pressed) break
            }
            true
        }
        when {
            endedBeforeHold == null -> onHold()
            !cancelled -> onTap?.invoke()
        }
    }
}

@Composable
private fun PortfolioPreviewStockRow(
    holding: PreviewStockHolding,
    onOpenTransaction: () -> Unit,
    onRemove: () -> Unit,
) {
    val currentOpenTransaction by rememberUpdatedState(onOpenTransaction)
    val currentRemove by rememberUpdatedState(onRemove)
    Row(
        Modifier.fillMaxWidth().height(rd(77f))
            .pointerInput(Unit) {
                detectPreviewRowPress(
                    onTap = { currentOpenTransaction() },
                    onHold = { currentRemove() },
                )
            }
            .semantics {
                contentDescription = "${holding.stock.symbol}, ${portfolioTokenQuantity(holding.quantity)}, ${primaryBalance(holding.valueUsd)}"
                onClick(label = "Open reference transaction on Solscan") {
                    onOpenTransaction()
                    true
                }
                onLongClick(label = "Remove local positions from this device") {
                    onRemove()
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockIcon(holding.stock, 46f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(holding.stock.symbol, 18f, W.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val quote = holding.stock.quote
                val quoteLabel = quote.price?.let { primaryQuotedValue(it, quote.currencyCode, true) }
                RefText(quoteLabel ?: "Solana", 13f, W.Muted)
                quote.changePercent?.let { change ->
                    Spacer(Modifier.width(rd(8f)))
                    RefText(primaryChange(change), 13f, primaryChangeColor(change))
                }
            }
        }
        Spacer(Modifier.width(rd(10f)))
        Column(horizontalAlignment = Alignment.End) {
            RefText(primaryBalance(holding.valueUsd), 18f, P.Lime)
            Spacer(Modifier.height(rd(3f)))
            RefText(portfolioTokenQuantity(holding.quantity), 13f, W.Muted)
        }
    }
}

@Composable
private fun PortfolioTokenIcon(symbol: String) {
    Box(Modifier.size(rd(46f)).clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
        when (symbol) {
            "SOL", "WSOL" -> SolanaLogo(46f)
            "USDC" -> Image(
                painterResource(R.drawable.token_usdc_official), contentDescription = null,
                modifier = Modifier.size(rd(46f)), contentScale = ContentScale.Fit,
            )
            "ETH" -> Image(
                painterResource(R.drawable.token_eth_official), contentDescription = null,
                modifier = Modifier.size(rd(27f)), contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(W.White),
            )
            else -> RefText(symbol.take(2).uppercase(Locale.US), 13f, W.Cream, FontWeight.Medium)
        }
    }
}

@Composable
private fun PortfolioStockRow(item: PrimaryStockRow, onStock: (StockId) -> Unit) {
    Row(Modifier.fillMaxWidth().height(rd(77f))
        .clickable(role = Role.Button) { onStock(item.stock.id) },
        verticalAlignment = Alignment.CenterVertically) {
        StockIcon(item.stock, 46f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(item.stock.symbol, 18f, W.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RefText(item.stock.quote.price?.let { primaryQuotedValue(it, item.stock.quote.currencyCode, true) }
                    ?: item.stock.name, 13f, W.Muted)
                item.stock.quote.changePercent?.let { change ->
                    Spacer(Modifier.width(rd(7f)))
                    RefText(primaryChange(change), 12f, primaryChangeColor(change))
                }
            }
        }
        Spacer(Modifier.width(rd(10f)))
        Column(horizontalAlignment = Alignment.End) {
            RefText(item.holdingValue?.let { primaryBalance(it, item.holdingValueCurrency ?: "USD") } ?: "—",
                18f, W.White)
            Spacer(Modifier.height(rd(3f)))
            RefText(item.quantity?.let(::portfolioQuantity) ?: "—", 13f, W.Muted)
        }
    }
}

private fun portfolioUnitPrice(price: BigDecimal): String {
    val decimals = when {
        price >= BigDecimal.ONE -> 2
        price >= BigDecimal("0.01") -> 4
        else -> 6
    }
    return "$" + primaryDecimal(price, maximumDecimals = decimals, minimumDecimals = 2)
}

private fun portfolioTokenQuantity(quantity: BigDecimal): String = when {
    quantity.signum() > 0 && quantity < BigDecimal("0.000000001") -> "<0.000000001"
    else -> primaryDecimal(quantity, maximumDecimals = 9)
}

private fun portfolioQuantity(quantity: BigDecimal): String = when {
    quantity.signum() > 0 && quantity < BigDecimal("0.000001") -> "<0.000001"
    else -> primaryDecimal(quantity, maximumDecimals = 6)
}

@Composable
private fun PrimaryCardHeading(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RefText(title, 21f, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        RefIcon("chevronRight", 21f, P.Muted)
    }
}

@Composable
private fun PrimaryWatchlistRow(
    item: PrimaryStockRow,
    onStock: (StockId) -> Unit,
    onWatch: (StockId) -> Unit,
) {
    val statistics = item.stock.statistics
    val secondaryLabel = statistics?.marketCapitalization?.let {
        val currency = statistics.currencyCode ?: item.stock.quote.currencyCode
        val label = if (statistics.reference != null) "token MC" else "MC"
        "${primaryCompactMoney(it, currency)} $label"
    } ?: item.ageLabel ?: "— MC"
    Row(
        Modifier.fillMaxWidth().height(rd(71f))
            .clickable(role = Role.Button) { onStock(item.stock.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryStar(item, onWatch, 20f)
        Spacer(Modifier.width(rd(12f)))
        StockIcon(item.stock, 44f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            PrimarySymbol(item, 18f)
            RefText(
                primaryUnitLabel(item.stock, secondaryLabel),
                15f, P.Muted,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            RefText(primaryQuote(item.stock), 18f)
            RefText(primaryChange(item.stock.quote.changePercent), 16f, primaryChangeColor(item.stock.quote.changePercent))
        }
    }
}

@Composable
private fun PrimaryMarketRow(
    item: PrimaryStockRow,
    onStock: (StockId) -> Unit,
    onWatch: (StockId) -> Unit,
) {
    val stock = item.stock
    val quote = stock.quote
    val activity = stock.activity
    val isLive = activity != null || stock.statistics?.reference != null
    val currency = activity?.currencyCode ?: quote.currencyCode
    val showCurrency = currency != "USDC" || currency != quote.currencyCode
    val volume = if (activity != null) activity.volume24h else item.volume
    val net = if (activity != null) activity.netVolume24h else item.netVolume
    // Provider timestamps change even when displayed amounts do not. Keep formatting tied
    // to actual values, and keep quote changes out of the image subcomposition entirely.
    val priceLabel = remember(quote.price, quote.currencyCode) { primaryQuote(stock) }
    val changeLabel = remember(quote.changePercent) { primaryChange(quote.changePercent) }
    val volumeLabel = remember(volume, currency, showCurrency) {
        volume?.let { primaryCompactMoney(it, currency, showCurrency) } ?: "—"
    }
    val netLabel = remember(net, currency, showCurrency, isLive) {
        net?.let {
            if (isLive) primarySignedCompactMoney(it, currency, showCurrency)
            else primaryCompactMoney(it, currency, showCurrency)
        } ?: "—"
    }
    val artworkStock = remember(stock.id, stock.symbol, stock.name, stock.logo) { stock }
    Row(
        Modifier.fillMaxWidth().height(rd(70f))
            .clickable(role = Role.Button) { onStock(item.stock.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryStar(item, onWatch, 19f)
        Spacer(Modifier.width(rd(10f)))
        StockIcon(artworkStock, 44f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            PrimarySymbol(item, 18f)
            RefText(primaryUnitLabel(item.stock, item.ageLabel ?: "—"), 15f, P.Muted)
        }
        Column(Modifier.width(rd(82f)).semantics {
            if (isLive) contentDescription = "Price: " + (item.stock.quote.price?.let {
                "${it.toPlainString()} ${item.stock.quote.currencyCode}"
            } ?: "Not available")
        }, horizontalAlignment = Alignment.End) {
            RefText(priceLabel, 18f)
            RefText(changeLabel, 16f, primaryChangeColor(quote.changePercent))
        }
        Column(Modifier.width(rd(88f)).semantics {
            if (activity != null) {
                val fullVolume = activity.volume24h?.let { "${it.toPlainString()} ${activity.currencyCode}" }
                    ?: "Not available. ${activity.volumeReason}"
                val fullNet = activity.netVolume24h?.let { "${it.toPlainString()} ${activity.currencyCode}" }
                    ?: "Not available. ${activity.netVolumeReason}"
                contentDescription = "24-hour volume: $fullVolume. Net buy volume: $fullNet. Source: ${activity.source}."
            }
        }, horizontalAlignment = Alignment.End) {
            RefText(volumeLabel, 18f)
            RefText(netLabel, 15f, if (isLive) primaryNetColor(net) else P.Muted)
        }
    }
}

@Composable
private fun PrimaryHoldingRow(item: PrimaryStockRow, onStock: (StockId) -> Unit) {
    val holdingCurrency = item.holdingValueCurrency
    val holdingUnitPrice = if (holdingCurrency != null) {
        val quantity = item.quantity?.takeIf { it.signum() > 0 }
        if (quantity != null) item.holdingValue?.divide(quantity, 18, RoundingMode.HALF_UP) else null
    } else null
    Row(
        Modifier.fillMaxWidth().height(rd(69f))
            .clickable(role = Role.Button) { onStock(item.stock.id) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StockIcon(item.stock, 44f)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            PrimarySymbol(item, 18f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val unitPriceLabel = if (holdingCurrency != null) {
                    holdingUnitPrice?.let { primaryQuotedValue(it, holdingCurrency, showCurrency = true) } ?: "N/A"
                } else primaryQuote(item.stock, includeNativeUnit = true)
                RefText(unitPriceLabel, 15f, P.Muted)
                if (holdingCurrency == null) {
                    Spacer(Modifier.width(rd(5f)))
                    val color = primaryChangeColor(item.stock.quote.changePercent)
                    Box(
                        Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(rd(3f)))
                            .padding(horizontal = rd(4f), vertical = rd(1f)),
                    ) {
                        RefText(primaryChange(item.stock.quote.changePercent), 12f, color)
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            RefText(item.holdingValue?.let { primaryBalance(it, holdingCurrency ?: item.stock.quote.currencyCode) } ?: "—", 18f)
            RefText(item.quantity?.stripTrailingZeros()?.toPlainString() ?: "—", 15f, P.Muted)
        }
    }
}

@Composable
private fun PrimarySymbol(item: PrimaryStockRow, size: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RefText(item.stock.symbol, size, modifier = Modifier.weight(1f, fill = false))
        if (item.verified) {
            Spacer(Modifier.width(rd(4f)))
            RefIcon("verified", 16f, P.Lime)
        }
    }
}

@Composable
private fun PrimaryStar(item: PrimaryStockRow, onWatch: (StockId) -> Unit, size: Float) {
    Box(
        Modifier.width(rd(size)).height(rd(44f))
            .semantics { contentDescription = if (item.watched) "Remove ${item.stock.symbol} from watchlist" else "Add ${item.stock.symbol} to watchlist" }
            .clickable(role = Role.Checkbox) { onWatch(item.stock.id) },
        contentAlignment = Alignment.Center,
    ) {
        RefIcon(if (item.watched) "starFilled" else "star", size, if (item.watched) P.Amber else P.Muted)
    }
}

private fun primaryDecimal(value: BigDecimal, maximumDecimals: Int = 2, minimumDecimals: Int = 0): String =
    DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).apply {
        minimumFractionDigits = minimumDecimals
        maximumFractionDigits = maximumDecimals
        roundingMode = RoundingMode.HALF_UP
    }.format(value)

private fun primaryCurrency(currencyCode: String): String = if (currencyCode == "USD") "$" else "$currencyCode "

private fun primaryBalance(value: BigDecimal?, currencyCode: String = "USD"): String {
    if (value == null) return "—"
    val decimals = if (value.signum() != 0 && value.abs() < BigDecimal.ONE) 3 else 2
    return primaryCurrency(currencyCode) + primaryDecimal(value, decimals, decimals)
}

private fun primarySignedAmount(value: BigDecimal): String =
    (if (value.signum() >= 0) "+" else "−") + "$" + primaryDecimal(value.abs(), 4)

private fun primaryChange(value: BigDecimal?): String = value?.let {
    (if (it.signum() >= 0) "+" else "−") + primaryDecimal(it.abs(), 2) + "%"
} ?: "—"

private fun primaryChangeColor(value: BigDecimal?): Color = when {
    value == null -> P.Muted
    value.signum() < 0 -> P.Pink
    else -> P.Lime
}

private fun primaryUnitLabel(stock: Stock, label: String): String =
    if (stock.quote.currencyCode == "USDC" && !label.contains("USDC")) "USDC · $label" else label

private fun primaryQuote(stock: Stock, includeNativeUnit: Boolean = false): String {
    val price = stock.quote.price ?: return "—"
    val showCurrency = stock.quote.currencyCode != "USDC" || includeNativeUnit
    return primaryQuotedValue(price, stock.quote.currencyCode, showCurrency)
}

private fun primaryQuotedValue(price: BigDecimal, currencyCode: String, showCurrency: Boolean): String {
    if (price >= BigDecimal("1000")) {
        return primaryCompactMoney(price, currencyCode, showCurrency)
    }
    val decimals = if (price < BigDecimal.ONE) 3 else 2
    val prefix = if (showCurrency) primaryCurrency(currencyCode) else ""
    return prefix + primaryDecimal(price, decimals, decimals)
}

private fun primaryNetColor(value: BigDecimal?): Color = when (value?.signum()) {
    1 -> P.Lime
    -1 -> P.Pink
    else -> P.Muted
}

private fun primarySignedCompactMoney(value: BigDecimal, currencyCode: String, showCurrency: Boolean): String =
    (when (value.signum()) { 1 -> "+"; -1 -> "−"; else -> "" }) +
        primaryCompactMoney(value.abs(), currencyCode, showCurrency)

private val PrimaryMoneyUnits = listOf(
    "T" to BigDecimal("1000000000000"), "B" to BigDecimal("1000000000"),
    "M" to BigDecimal("1000000"), "K" to BigDecimal("1000"),
)

private fun primaryCompactMoney(value: BigDecimal?, currencyCode: String, showCurrency: Boolean = true): String {
    if (value == null) return "—"
    val unit = PrimaryMoneyUnits.firstOrNull { value.abs() >= it.second }
    val number = unit?.let { value.divide(it.second, 2, RoundingMode.HALF_UP) } ?: value
    val prefix = if (showCurrency) primaryCurrency(currencyCode) else ""
    return prefix + primaryDecimal(number, 2, 2) + (unit?.first ?: "")
}
