package com.elevencapital.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elevencapital.app.data.FixtureStockData
import com.elevencapital.app.data.LiveCatalog
import com.elevencapital.app.data.LiveMarketDataClient
import com.elevencapital.app.data.LiveChart
import com.elevencapital.app.data.LiveInstrument
import com.elevencapital.app.data.OrderedMarketCatalog
import com.elevencapital.app.data.MarketSubscription
import com.elevencapital.app.data.MarketViewportSubscriptions
import com.elevencapital.app.data.alignChartObservation
import com.elevencapital.app.data.isChartBasisCompatible
import com.elevencapital.app.data.MarketDataHttpException
import com.elevencapital.app.data.MarketStreamEvent
import com.elevencapital.app.data.LiveWalletPortfolioClient
import com.elevencapital.app.data.WalletPortfolioTracker
import com.elevencapital.app.data.SignalCongressClient
import com.elevencapital.app.data.SignalCuratedArticles
import com.elevencapital.app.data.SignalDirectoryState
import com.elevencapital.app.data.marketNetworkChanges
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.core.market.recoveringMarketStream
import com.elevencapital.app.screens.DetailPeriod
import com.elevencapital.app.screens.DetailPresentation
import com.elevencapital.app.screens.PrimaryStockRow
import com.elevencapital.app.screens.MarketQuery
import com.elevencapital.app.screens.MarketSource
import com.elevencapital.app.screens.selectMarketRows
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.InMemoryStockRepository
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.isReferenceDataFresh
import com.elevencapital.core.stock.flow.StockFlowController
import com.elevencapital.core.stock.flow.OrderSide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import coil3.imageLoader
import coil3.request.ImageRequest

data class MarketConnectionState(
    val catalog: LiveCatalog? = null,
    val message: String = "Connecting to market data…",
    val offline: Boolean = false,
    val refreshFailed: Boolean = false,
    val chartMessages: Map<Pair<StockId, ChartRange>, String> = emptyMap(),
)

private sealed interface OrderedMarketUpdate {
    data class Catalog(val value: LiveCatalog) : OrderedMarketUpdate
    data class Chart(val value: LiveChart) : OrderedMarketUpdate
    data class Transport(val value: MarketStreamEvent) : OrderedMarketUpdate
}

/** Reference preview or public data mode; neither can submit a purchase or sign a transaction. */
class ElevenViewModel @JvmOverloads constructor(
    application: Application,
    val isLive: Boolean = BuildConfig.LIVE_MARKET_DATA,
) : AndroidViewModel(application) {
    val fixtures = FixtureStockData.load(application.assets)
    private var watchlistNamespace = "guest"
    private var authenticatedUserId: String? = null
    private val refreshedPurchaseQuotes = LinkedHashSet<String>()
    private var preferences = if (isLive) application.getSharedPreferences("live-watchlist-guest", 0).also { guest ->
        // Preserve the pre-auth preview watchlist for the guest only; signed-in users start isolated.
        if (!guest.contains("stockIds")) {
            val legacy = application.getSharedPreferences("live-watchlist", 0).getStringSet("stockIds", emptySet()).orEmpty()
            guest.edit().putStringSet("stockIds", legacy.toSet()).apply()
        }
    } else null
    private val client = if (isLive) LiveMarketDataClient(BuildConfig.MARKET_DATA_URL) else null
    private val walletClient = if (isLive) LiveWalletPortfolioClient(BuildConfig.MARKET_DATA_URL) else null
    private val signalClient = if (isLive) SignalCongressClient(application) else null
    private val mutableSignal = MutableStateFlow(SignalDirectoryState())
    val signal = mutableSignal.asStateFlow()
    private var signalDirectoryJob: Job? = null
    private var signalDetailJob: Job? = null
    private val walletTracker = WalletPortfolioTracker(fetch = { wallets ->
        requireNotNull(walletClient).portfolio(wallets)
    })
    private var walletWarmJob: Job? = null
    val walletPortfolio = walletTracker.state
    private val purchaseCoordinator = if (isLive) (application as? ElevenCapitalApplication)?.purchases else null
    private val previewPurchase = MutableStateFlow(PurchaseUiState(phase = PurchasePhase.UNAVAILABLE,
        message = "Purchases are disabled in the design preview."))
    val purchaseState = purchaseCoordinator?.state ?: previewPurchase.asStateFlow()
    private val stockIdentities = mutableMapOf<StockId, Stock>()
    private val prefetchedMarketLogos = mutableSetOf<String>()
    private var marketLogosPrepared = false
    private val reconnectSignals = Channel<Unit>(Channel.CONFLATED)
    private var visibleScope: CoroutineScope? = null
    private var catalogReceivedElapsed = 0L
    private val chartJobs = mutableMapOf<Pair<StockId, ChartRange>, Job>()
    private val chartReceivedElapsed = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    private val chartPrewarmRequests = mutableMapOf<Pair<StockId, ChartRange>, Deferred<LiveChart>>()
    private val chartPrewarmAttempts = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    private val chartPrewarmWakeups = Channel<Unit>(Channel.CONFLATED)
    private var chartPrewarmJob: Job? = null
    private var chartPrewarmTargets: List<Pair<StockId, ChartRange>> = emptyList()
    private var activeChart: Pair<StockId, ChartRange>? = null
    private var visibleStockIds: Set<StockId> = emptySet()
    private val viewportSubscriptions = MarketViewportSubscriptions()
    private val orderedCatalog = OrderedMarketCatalog()
    private var instrumentIndex: Map<StockId, LiveInstrument> = emptyMap()
    private val chartGeneration = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    private val chartMetadata = mutableMapOf<Pair<StockId, ChartRange>, LiveChart>()
    private val mutableConnection = MutableStateFlow(MarketConnectionState())
    val connection = mutableConnection.asStateFlow()
    private val mutableAccountCenter = MutableStateFlow(false)
    val accountCenter = mutableAccountCenter.asStateFlow()
    fun showAccountCenter() { if (!isLive) mutableAccountCenter.value = true }
    fun hideAccountCenter() { mutableAccountCenter.value = false }
    val repository = if (isLive) InMemoryStockRepository() else fixtures.repository
    val stockFlow = StockFlowController(repository)
    private val mutableTab = MutableStateFlow(RootTab.Home)
    val tab = mutableTab.asStateFlow()
    init { if (isLive) refreshSignalDirectory() }
    private val mutableWatched = MutableStateFlow(
        if (isLive) preferences?.getStringSet("stockIds", emptySet()).orEmpty().map(::StockId).toSet()
        else fixtures.initialWatchedIds,
    )
    val watched = mutableWatched.asStateFlow()
    /** repeatOnLifecycle owns this scope: streams, callbacks, retries and charts stop in background. */
    suspend fun refreshWhileVisible() {
        val dataClient = client ?: return
        coroutineScope {
            visibleScope = this
            expireOldData()
            scheduleChartPrewarm()
            // REST gives first paint while the socket establishes its authoritative snapshot.
            if (mutableConnection.value.catalog == null) launch {
                try {
                    val initial = dataClient.catalog()
                    if (mutableConnection.value.catalog == null) {
                        acceptCatalog(initial)
                        mutableConnection.value = mutableConnection.value.copy(
                            message = "Market snapshot · connecting to updates", refreshFailed = true,
                        )
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The independent socket keeps recovering automatically. */ }
            }
            launch {
                marketNetworkChanges(getApplication()).collect { reconnectSignals.trySend(Unit) }
            }
            launch {
                while (currentCoroutineContext().isActive) {
                    delay(5_000)
                    expireOldData()
                }
            }
            try {
                reconnectSignals.receiveAsFlow().onStart { emit(Unit) }.collectLatest {
                    recoveringMarketStream(
                        connect = { orderedCatalog.beginConnection(); dataClient.stream() },
                        onInterrupted = { failure ->
                            withContext(Dispatchers.Main.immediate) { streamInterrupted(failure) }
                        },
                        isRecovery = { it !is MarketStreamEvent.Status },
                    ).mapNotNull(::orderMarketEvent).flowOn(Dispatchers.Default).collect { update ->
                        when (update) {
                            is OrderedMarketUpdate.Catalog -> acceptCatalog(update.value)
                            is OrderedMarketUpdate.Chart -> {
                                val chart = update.value
                                val key = chart.stockId to chart.range
                                chartGeneration[key] = (chartGeneration[key] ?: 0) + 1
                                acceptChart(chart)
                            }
                            is OrderedMarketUpdate.Transport -> when (val event = update.value) {
                                is MarketStreamEvent.Heartbeat -> {
                                    // A heartbeat proves transport health, not provider freshness.
                                    mutableConnection.value = mutableConnection.value.copy(offline = false)
                                }
                                is MarketStreamEvent.Status -> mutableConnection.value = mutableConnection.value.copy(
                                    offline = false,
                                    refreshFailed = event.state == "reconnecting",
                                    message = when {
                                        event.state == "reconnecting" -> "Market source updating · automatic recovery"
                                        repository.catalog.value.isEmpty() -> "Loading reference prices…"
                                        else -> "Reference prices · updating automatically"
                                    },
                                )
                                else -> error("Ordered market event escaped its reducer: $event")
                            }
                        }
                    }
                }
            } finally {
                visibleScope = null
                chartPrewarmJob?.cancel()
                chartPrewarmJob = null
                chartPrewarmRequests.values.forEach { it.cancel() }
                chartPrewarmRequests.clear()
                chartJobs.values.forEach { it.cancel() }
                chartJobs.clear()
            }
        }
    }

    /** Compatibility hook only; normal operation never requires a tap to recover. */
    fun retryMarketData() { reconnectSignals.trySend(Unit) }

    fun setWalletSession(userId: String?, wallets: List<UserWallet>) {
        if (!isLive || !walletTracker.bind(userId, wallets)) return
        // This bounded first request survives a full-screen device credential prompt, which can
        // stop the Activity and cancel its repeatOnLifecycle refresh. It never survives this VM.
        walletWarmJob?.cancel()
        walletWarmJob = null
        ensureWalletWarmup()
    }

    /** Refresh once on every launcher return, before Android covers the app with its PIN prompt. */
    fun ensureWalletWarmup() {
        if (!isLive || !walletTracker.hasBoundSession || walletWarmJob?.isActive == true) return
        walletWarmJob = viewModelScope.launch(Dispatchers.Main) {
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            var retryDelay = 2_000L
            do {
                // Transient backend or RPC failures during PIN entry should recover before Home
                // appears. A successful but partial scan is not a usable wallet balance.
                if (walletTracker.refreshOnce() && walletTracker.state.value.balanceUsd != null) break
                if (SystemClock.elapsedRealtime() >= deadline) break
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
            } while (true)
        }
    }

    /** The foreground refresh takes over immediately after the phone credential succeeds. */
    fun finishWalletWarmup() {
        walletWarmJob?.takeIf { it.isActive }?.cancel()
    }

    suspend fun refreshWalletPortfolioWhileVisible() {
        if (!isLive) return
        val warm = walletWarmJob
        warm?.join()
        walletTracker.refreshWhileVisible(marketNetworkChanges(getApplication()), skipInitialIfReady = warm != null)
    }

    /** Stateful revision work stays on the upstream Default dispatcher; UI publication stays on Main. */
    private fun orderMarketEvent(event: MarketStreamEvent): OrderedMarketUpdate? = when (event) {
        is MarketStreamEvent.Snapshot -> orderedCatalog.accept(event)?.let { OrderedMarketUpdate.Catalog(it) }
        is MarketStreamEvent.Delta -> orderedCatalog.accept(event)?.let { OrderedMarketUpdate.Catalog(it) }
        is MarketStreamEvent.Chart -> orderedCatalog.accept(event)?.let { OrderedMarketUpdate.Chart(it) }
        is MarketStreamEvent.Heartbeat, is MarketStreamEvent.Status -> OrderedMarketUpdate.Transport(event)
    }

    private fun acceptCatalog(catalog: LiveCatalog) {
        val existing = repository.catalog.value.associateBy { it.id }
        val changedBasis = catalog.instruments.filter { incoming ->
            val previous = instrumentIndex[incoming.stock.id]
            previous != null && (previous.quoteBasis != incoming.quoteBasis ||
                previous.stock.quote.currencyCode != incoming.stock.quote.currencyCode)
        }.map { it.stock.id }.toSet()
        val affectedCharts = (chartMetadata.keys + chartReceivedElapsed.keys + chartJobs.keys +
            chartPrewarmRequests.keys + chartPrewarmAttempts.keys + listOfNotNull(activeChart))
            .filter { it.first in changedBasis }.toSet()
        for (key in affectedCharts) {
            chartMetadata.remove(key)
            chartReceivedElapsed.remove(key)
            chartPrewarmAttempts.remove(key)
            chartPrewarmRequests.remove(key)?.cancel()
            chartJobs.remove(key)?.cancel()
            chartGeneration[key] = (chartGeneration[key] ?: 0) + 1
        }
        instrumentIndex = catalog.instruments.associateBy { it.stock.id }
        catalogReceivedElapsed = SystemClock.elapsedRealtime()
        mutableConnection.value = mutableConnection.value.copy(
            catalog = catalog,
            offline = false,
            refreshFailed = false,
            message = when {
                catalog.instruments.isEmpty() -> "Waiting for market sources · updates automatically"
                catalog.providers.all { it.status == "unavailable" } -> "Market sources unavailable · automatic recovery"
                catalog.providers.any { it.status == "stale" || it.status == "unavailable" } -> "Some prices delayed · updates automatically"
                else -> "Market data · updates automatically"
            },
        )
        repository.replaceCatalog(catalog.instruments.map { instrument ->
            val id = instrument.stock.id
            val retained = if (id in changedBasis) emptyMap() else existing[id]?.charts.orEmpty()
            val charts = retained.mapValues { (range, points) ->
                val key = id to range
                val metadata = chartMetadata[key]
                if (metadata == null) points else alignChartObservation(metadata.copy(points = points), instrument).points
            }
            expireStock(instrument.stock.copy(charts = charts), catalog.receivedAt, 0L)
        })
        prefetchMarketLogos()
        scheduleChartPrewarm()
        chartMetadata.keys.removeAll { it.first !in instrumentIndex }
        chartPrewarmAttempts.keys.removeAll { it.first !in instrumentIndex }
        // Identity survives a temporary quote/catalog outage; financial values never use this cache.
        stockIdentities.putAll(repository.catalog.value.associateBy { it.id })
        if (stockFlow.snapshot().destination != com.elevencapital.core.stock.flow.StockDestination.Markets && stockFlow.snapshot().stock == null) {
            while (stockFlow.goBack()) { }
            mutableTab.value = RootTab.Markets
        }
        if (activeChart?.first?.let { repository.getStock(it) == null } == true) clearActiveChart()
        activeChart?.takeIf { it.first in changedBasis }?.let { (id, range) -> loadChart(id, range) }
    }

    /** Warm the first Stocks screen while the user is still at device unlock. */
    private fun prefetchMarketLogos() {
        if (marketLogosPrepared || repository.catalog.value.isEmpty()) return
        val scope = visibleScope ?: return
        marketLogosPrepared = true
        val application = getApplication<Application>()
        val urls = selectMarketRows(liveRows(repository.catalog.value, mutableWatched.value), MarketQuery(), false)
            .take(32)
            .mapNotNull { it.stock.logo?.value?.takeIf { url -> url.startsWith("https://") } }
            .filter(prefetchedMarketLogos::add)
        if (urls.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            urls.forEach { url ->
                application.imageLoader.enqueue(ImageRequest.Builder(application).data(url).size(128, 128).build())
            }
        }
    }

    /** Warm the default detail graph for the first ten rows in each source's own list. */
    private fun scheduleChartPrewarm() {
        if (client == null) return
        val rows = liveRows(repository.catalog.value, emptySet())
        val groups = listOf(MarketSource.BACKED, MarketSource.BACKPACK, MarketSource.PRESTOCKS).map { source ->
            selectMarketRows(rows, MarketQuery(source = source), false).take(10)
                .map { it.stock.id to ChartRange.ONE_DAY }
        }
        // Interleave sources so the first ten of one provider cannot starve the others.
        val targets = (0 until 10).flatMap { position -> groups.mapNotNull { it.getOrNull(position) } }.distinct()
        if (targets != chartPrewarmTargets) chartPrewarmTargets = targets
        val scope = visibleScope ?: return
        if (chartPrewarmJob?.isActive != true) {
            chartPrewarmJob = scope.launch {
                launch {
                    while (currentCoroutineContext().isActive) {
                        delay(30_000)
                        chartPrewarmWakeups.trySend(Unit)
                    }
                }
                for (ignored in chartPrewarmWakeups) {
                    val batch = chartPrewarmTargets
                    coroutineScope {
                        val nextIndex = AtomicInteger()
                        repeat(3) {
                            launch {
                                while (currentCoroutineContext().isActive) {
                                    val key = batch.getOrNull(nextIndex.getAndIncrement()) ?: break
                                    while (activeChart != null && currentCoroutineContext().isActive) delay(150)
                                    prewarmChart(key)
                                }
                            }
                        }
                    }
                }
            }
        }
        chartPrewarmWakeups.trySend(Unit)
    }

    private suspend fun prewarmChart(key: Pair<StockId, ChartRange>) {
        val dataClient = client ?: return
        if (key !in chartPrewarmTargets || key.first !in instrumentIndex) return
        val now = SystemClock.elapsedRealtime()
        if (chartReceivedElapsed[key]?.let { now - it < 240_000 } == true ||
            chartPrewarmAttempts[key]?.let { now - it < 60_000 } == true ||
            chartPrewarmRequests[key]?.isActive == true) return
        chartPrewarmAttempts[key] = now
        val generation = chartGeneration[key] ?: 0
        supervisorScope {
            // A failed provider request must not cancel the stream or the other prewarm worker.
            val request = async { dataClient.chart(key.first, key.second) }
            chartPrewarmRequests[key] = request
            try {
                val chart = request.await()
                if (activeChart != key && (chartGeneration[key] ?: 0) == generation) acceptChart(chart, prewarmed = true)
            } catch (cancelled: CancellationException) {
                chartPrewarmAttempts.remove(key)
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (_: Exception) {
                // A failed warm-up never puts an error in the visible stock detail.
            } finally {
                if (chartPrewarmRequests[key] === request) chartPrewarmRequests.remove(key)
            }
        }
    }

    private fun streamInterrupted(failure: Exception) {
        val offline = failure is IOException && failure !is MarketDataHttpException
        if (BuildConfig.DEBUG) Log.w("ElevenMarketData", "Stream reconnecting: ${failure.javaClass.simpleName}", failure)
        mutableConnection.value = mutableConnection.value.copy(
            message = when {
                offline && repository.catalog.value.isEmpty() -> "Connecting to market data · automatic recovery"
                offline -> "Reconnecting · keeping recent prices"
                failure is MarketDataHttpException -> "Market service recovering · updates automatically"
                else -> "Market update interrupted · automatic recovery"
            },
            offline = offline,
            refreshFailed = true,
        )
        expireOldData()
    }

    private fun expireOldData() {
        val snapshot = mutableConnection.value.catalog ?: return
        val elapsed = SystemClock.elapsedRealtime() - catalogReceivedElapsed
        repository.replaceCatalog(repository.catalog.value.map { stock -> expireStock(stock, snapshot.receivedAt, elapsed) })
    }

    private fun expireStock(stock: Stock, snapshotAt: java.time.Instant, elapsedMillis: Long): Stock {
        val fetchedAt = instrumentIndex[stock.id]?.quoteReceivedAt
        val expired = !isReferenceDataFresh(fetchedAt, snapshotAt, elapsedMillis)
        val now = SystemClock.elapsedRealtime()
        return stock.copy(
            quote = if (expired) stock.quote.copy(price = null, changeAmount = null, changePercent = null) else stock.quote,
            statistics = stock.statistics?.reference?.expire(snapshotAt, elapsedMillis)?.toStatistics() ?: stock.statistics,
            activity = stock.activity?.expire(snapshotAt, elapsedMillis),
            charts = stock.charts.filterKeys { range ->
                chartReceivedElapsed[stock.id to range]?.let { now - it < 300_000 } == true
            },
        )
    }

    /** Viewport subscriptions share one socket with the selected detail. */
    fun setVisibleStockIds(ids: Set<StockId>) = setVisibleStockIds("markets", ids)

    fun setVisibleStockIds(owner: String, ids: Set<StockId>) {
        visibleStockIds = viewportSubscriptions.update(owner, ids)
        updateSubscription()
    }

    private fun updateSubscription() {
        client?.subscribe(MarketSubscription(visibleStockIds, activeChart))
    }

    /** An outgoing animated detail must not unsubscribe a newer screen. */
    fun clearActiveChart(expectedId: StockId) {
        if (activeChart?.first == expectedId) clearActiveChart()
    }

    fun clearActiveChart() {
        activeChart = null
        chartJobs.values.forEach { it.cancel() }
        chartJobs.clear()
        updateSubscription()
    }

    fun loadChart(id: StockId, range: ChartRange) {
        val dataClient = client ?: return
        val key = id to range
        if (activeChart != key) {
            chartJobs.values.forEach { it.cancel() }
            chartJobs.clear()
            activeChart = key
            updateSubscription()
        }
        if (chartJobs[key]?.isActive == true) return
        if (chartReceivedElapsed[key]?.let { SystemClock.elapsedRealtime() - it < 60_000 } == true) return
        val scope = visibleScope ?: return
        val generation = chartGeneration[key] ?: 0
        chartJobs[key] = scope.launch {
            mutableConnection.value = mutableConnection.value.copy(
                chartMessages = mutableConnection.value.chartMessages + (key to "Loading price history…"),
            )
            try {
                val chart = chartPrewarmRequests[key]?.await() ?: dataClient.chart(id, range)
                // A slower HTTP response must never replace a newer socket chart.
                if (activeChart == key && (chartGeneration[key] ?: 0) == generation) acceptChart(chart)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (activeChart == key && (chartGeneration[key] ?: 0) == generation) {
                    val hasRecentChart = repository.getStock(id)?.charts?.get(range)?.isNotEmpty() == true
                    mutableConnection.value = mutableConnection.value.copy(
                        chartMessages = mutableConnection.value.chartMessages + (key to
                            if (hasRecentChart) "Recent history · reconnecting automatically" else "Price history updating automatically…"),
                    )
                }
            }
        }
    }

    private fun acceptChart(chart: LiveChart, prewarmed: Boolean = false) {
        val key = chart.stockId to chart.range
        if (activeChart != key && !prewarmed) return
        val stock = repository.getStock(chart.stockId) ?: return
        val instrument = instrumentIndex[chart.stockId] ?: return
        if (stock.quote.currencyCode != chart.currency || !isChartBasisCompatible(chart, instrument)) return
        chartReceivedElapsed[key] = SystemClock.elapsedRealtime()
        val aligned = instrumentIndex[chart.stockId]?.let { alignChartObservation(chart, it) } ?: chart
        chartMetadata[key] = aligned
        repository.replaceCatalog(repository.catalog.value.map {
            if (it.id == chart.stockId) it.copy(charts = it.charts + (chart.range to aligned.points)) else it
        })
        val message = chart.statusReason?.takeIf { it.isNotBlank() } ?: when {
            chart.status == "unsupported" -> "This provider does not publish price history."
            chart.status != "ok" -> "Price history is unavailable."
            chart.points.isEmpty() -> "No prices for this period."
            else -> ""
        }
        mutableConnection.value = mutableConnection.value.copy(
            chartMessages = mutableConnection.value.chartMessages + (key to message),
        )
    }

    fun liveRows(stocks: List<Stock>, watchedIds: Set<StockId>): List<PrimaryStockRow> {
        val metadata = instrumentIndex
        return stocks.map { stock ->
            val item = metadata[stock.id]
            PrimaryStockRow(stock = stock,
                ageLabel = when {
                    item?.quoteBasis == "underlying_share_reference" -> "Share ref · USDC"
                    item?.provider == "backpack" -> "USDC · BP"
                    item?.provider == "prestocks" -> "PreStocks"
                    else -> "xStocks"
                },
                volume = stock.activity?.volume24h,
                netVolume = stock.activity?.netVolume24h,
                watched = stock.id in watchedIds,
            )
        }
    }

    fun liveHoldingRows(stocks: List<Stock>, watchedIds: Set<StockId>): List<PrimaryStockRow> {
        val known = stockIdentities + stocks.associateBy { it.id }
        return walletPortfolio.value.holdings.mapNotNull { position ->
            val stock = known[position.stockId] ?: return@mapNotNull null
            PrimaryStockRow(stock = stock, quantity = position.quantity, holdingValue = position.valueUsd,
                holdingValueCurrency = "USD", watched = stock.id in watchedIds)
        }
    }

    fun liveDetail(stock: Stock): DetailPresentation {
        val metadata = instrumentIndex[stock.id]
        return DetailPresentation(
            providerLabel = metadata?.providerLabel,
            ageLabel = when {
                metadata?.quoteBasis == "underlying_share_reference" -> "Share reference"
                metadata?.provider == "backpack" -> "Backpack"
                metadata?.provider == "prestocks" -> "PreStocks"
                else -> "xStocks"
            },
            volume24h = stock.activity?.volume24h,
            netVolume24h = stock.activity?.netVolume24h,
            periodReturns = stock.quote.changePercent?.let { mapOf(DetailPeriod.ONE_DAY to it) }.orEmpty(),
        )
    }

    fun priceNotice(stock: Stock): String {
        val metadata = instrumentIndex[stock.id]
        val qualifier = when {
            mutableConnection.value.offline -> "offline / cached"
            mutableConnection.value.refreshFailed -> "cached / update unavailable"
            mutableConnection.value.catalog?.providers?.any { it.id == metadata?.provider && it.status == "stale" } == true -> "cached"
            stock.quote.price == null -> "price unavailable"
            else -> "reference only"
        }
        val basis = when (metadata?.quoteBasis) {
            "underlying_share_reference" -> "underlying share reference"
            "onchain_token_market" -> "on-chain token market"
            "external_reference_non_executable" -> "reference only"
            else -> "indicative reference"
        }
        val state = metadata?.marketState?.takeIf { it !in setOf("open", "unknown") }?.let {
            " · ${metadata.marketStateReason?.takeIf(String::isNotBlank) ?: it}"
        }.orEmpty()
        val provider = when (metadata?.provider) {
            "backpack" -> "Backpack"
            "prestocks" -> "PreStocks"
            else -> "xStocks"
        }
        val source = "$provider · ${stock.quote.currencyCode}"
        val observation = stock.quote.asOf?.let { "Source time ${it.toString()}" }
            ?: metadata?.quoteReceivedAt?.let { "Fetched ${it.toString()} · source time unavailable" }
            ?: "Source time unavailable"
        val freshness = if (qualifier == "reference only") "" else " · $qualifier"
        return "$source · $basis$freshness$state\n$observation"
    }

    fun selectTab(next: RootTab) {
        while (stockFlow.goBack()) { }
        mutableTab.value = next
        if (next == RootTab.Trade) refreshSignalDirectory()
    }

    fun refreshSignalDirectory(force: Boolean = false) {
        val feed = signalClient ?: return
        if (signalDirectoryJob?.isActive == true) return
        signalDirectoryJob = viewModelScope.launch {
            try {
                val result = feed.profiles(force)
                val current = mutableSignal.value
                mutableSignal.value = current.copy(profiles = result.profiles,
                    notice = if (current.selectedProfileId == null) result.notice else current.notice)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableSignal.value = mutableSignal.value.copy(notice =
                    "Congress data is temporarily unavailable.")
            }
        }
    }

    fun openSignalProfile(id: String) {
        val profile = mutableSignal.value.profiles.firstOrNull { it.id == id } ?: return
        signalDetailJob?.cancel()
        mutableSignal.value = mutableSignal.value.copy(selectedProfileId = id, trades = emptyList(),
            news = SignalCuratedArticles.forProfile(profile, emptyList()), notice = "Checking reported trades…",
            tradeSource = com.elevencapital.app.data.SignalTradeSource.NONE)
        val feed = signalClient ?: return
        signalDetailJob = viewModelScope.launch {
            try {
                val result = feed.trades(profile)
                if (mutableSignal.value.selectedProfileId != id) return@launch
                mutableSignal.value = mutableSignal.value.copy(trades = result.trades, tradeSource = result.source,
                    news = SignalCuratedArticles.forProfile(profile, result.trades), notice = result.notice)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mutableSignal.value.selectedProfileId == id) mutableSignal.value = mutableSignal.value.copy(
                    notice = "Recent disclosures are unavailable.",
                    tradeSource = com.elevencapital.app.data.SignalTradeSource.NONE)
            }
        }
    }

    fun closeSignalProfile() {
        signalDetailJob?.cancel()
        mutableSignal.value = mutableSignal.value.copy(selectedProfileId = null, trades = emptyList(),
            news = emptyList(), tradeSource = com.elevencapital.app.data.SignalTradeSource.NONE)
    }

    fun openStock(id: StockId) {
        // Start the authenticated wallet options while the stock detail animates in.
        // Buy and Sell can then reuse the same Privy session and in-flight result.
        purchaseCoordinator?.prewarm(id.value, OrderSide.BUY)
        purchaseCoordinator?.prewarm(id.value, OrderSide.SELL)
        while (stockFlow.goBack()) { }
        stockFlow.openStock(id)
    }

    fun openOrder(side: OrderSide) { stockFlow.openOrder(side) }

    fun bindPurchase(stock: Stock?, side: OrderSide = OrderSide.BUY) {
        purchaseCoordinator?.bind(stock?.id?.value, side)
    }

    fun selectPurchasePaymentAsset(id: String) { purchaseCoordinator?.selectPaymentAsset(id) }
    fun selectPurchaseDestination(id: String?) { purchaseCoordinator?.selectDestination(id) }
    fun requestPurchaseQuote(amount: String) { purchaseCoordinator?.requestQuote(amount) }
    fun executePurchase() { purchaseCoordinator?.executeReviewedQuote() }
    fun editPurchaseQuote() { purchaseCoordinator?.editQuote() }
    fun refreshPurchaseOptions() { purchaseCoordinator?.refreshOptions() }

    suspend fun refreshPortfolioAfterPurchase(expectedUserId: String, quoteId: String) {
        if (!isLive || expectedUserId != authenticatedUserId || quoteId.isBlank() ||
            !refreshedPurchaseQuotes.add(quoteId)) return
        while (refreshedPurchaseQuotes.size > 64) refreshedPurchaseQuotes.remove(refreshedPurchaseQuotes.first())
        walletTracker.refreshOnce()
    }

    /** Public market data is shared; personal watchlists are isolated by authenticated account. */
    fun setAuthenticatedUser(userId: String?) {
        if (!isLive) return
        if (authenticatedUserId != userId) {
            walletWarmJob?.cancel()
            walletWarmJob = null
            purchaseCoordinator?.bind(null)
            refreshedPurchaseQuotes.clear()
            authenticatedUserId = userId
        }
        val namespace = userId?.takeIf { it.isNotBlank() }?.let { id ->
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } ?: "guest"
        if (namespace == watchlistNamespace) return
        // Remove the former account's balances/quote before the new identity can render.
        walletTracker.bind(null, emptyList())
        watchlistNamespace = namespace
        viewportSubscriptions.clear()
        visibleStockIds = emptySet()
        hideAccountCenter()
        selectTab(RootTab.Home)
        clearActiveChart()
        preferences = getApplication<Application>().getSharedPreferences("live-watchlist-$namespace", 0)
        mutableWatched.value = preferences?.getStringSet("stockIds", emptySet()).orEmpty().map(::StockId).toSet()
    }

    fun toggleWatch(id: StockId) {
        if (repository.getStock(id) == null) return
        mutableWatched.value = mutableWatched.value.let { if (id in it) it - id else it + id }
        preferences?.edit()?.putStringSet("stockIds", mutableWatched.value.map { it.value }.toSet())?.apply()
    }
}

enum class RootTab(val icon: String) {
    Home("home"), Markets("markets"), Trade("trade"), Account("account")
}
