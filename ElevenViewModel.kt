package com.elevencapital.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elevencapital.app.data.FixtureStockData
import com.elevencapital.app.data.LiveCatalog
import com.elevencapital.app.data.LiveMarketDataClient
import com.elevencapital.app.data.LiveChart
import com.elevencapital.app.data.StartupMarketCache
import com.elevencapital.app.data.CachedStartupChart
import com.elevencapital.app.data.RobinhoodFeaturedSymbols
import com.elevencapital.app.data.mergeRobinhoodFeaturedCatalog
import com.elevencapital.app.data.robinhoodCompositeReceiptElapsed
import com.elevencapital.app.data.robinhoodCatalogReceiptElapsed
import com.elevencapital.app.data.freshChartReceiptMillis
import com.elevencapital.app.data.LiveInstrument
import com.elevencapital.app.data.OrderedMarketCatalog
import com.elevencapital.app.data.MarketSubscription
import com.elevencapital.app.data.MarketViewportSubscriptions
import com.elevencapital.app.data.alignChartObservation
import com.elevencapital.app.data.isChartBasisCompatible
import com.elevencapital.app.data.isChartDisplayCompatible
import com.elevencapital.app.data.MarketDataHttpException
import com.elevencapital.app.data.MarketStreamEvent
import com.elevencapital.app.data.LiveWalletPortfolioClient
import com.elevencapital.app.data.WalletPortfolioTracker
import com.elevencapital.app.data.marketNetworkChanges
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.core.market.recoveringMarketStream
import com.elevencapital.app.screens.DetailPeriod
import com.elevencapital.app.screens.DetailChartContext
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
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import coil3.imageLoader
import coil3.request.ImageRequest

data class MarketConnectionState(
    val catalog: LiveCatalog? = null,
    val message: String = "Connecting to market data…",
    val offline: Boolean = false,
    val refreshFailed: Boolean = false,
    val chartMessages: Map<Pair<StockId, ChartRange>, String> = emptyMap(),
    val chartContexts: Map<Pair<StockId, ChartRange>, DetailChartContext> = emptyMap(),
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
    private val walletTracker = WalletPortfolioTracker(fetch = { wallets ->
        requireNotNull(walletClient).portfolio(wallets)
    })
    private var walletWarmJob: Job? = null
    private var stopWalletWarmupAfterCurrentRead = false
    private var walletWarmReadJob: Job? = null
    val walletPortfolio = walletTracker.state
    private val purchaseCoordinator = if (isLive) (application as? ElevenCapitalApplication)?.purchases else null
    private val previewPurchase = MutableStateFlow(PurchaseUiState(phase = PurchasePhase.UNAVAILABLE,
        message = "Purchases are disabled in the design preview."))
    val purchaseState = purchaseCoordinator?.state ?: previewPurchase.asStateFlow()
    private var stockIdentities: Map<StockId, Stock> = emptyMap()
    private val prefetchedMarketLogos = mutableSetOf<String>()
    private var marketLogosPrepared = false
    private val startupChartCache = if (isLive) StartupMarketCache(application.cacheDir, BuildConfig.MARKET_DATA_URL) else null
    private val featuredMarketWarmup = if (isLive) (application as? ElevenCapitalApplication)?.robinhoodMarketWarmup else null
    private var featuredSnapshotOnly = false
    private var cachedFeaturedQuoteIds: Set<StockId> = emptySet()
    private var lastFeaturedCatalog: LiveCatalog? = null
    private var lastFullCatalog: LiveCatalog? = null
    private var fullCatalogReceivedElapsed = 0L
    private var featuredCatalogReceivedElapsed = 0L
    private var restoredStartupCharts: List<CachedStartupChart> = emptyList()
    private var startupMarketRunning = false
    private var startupMarketStarted = false
    private var startupMarketJob: Job? = null
    private var chartCacheWriteJob: Job? = null
    private val reconnectSignals = Channel<Unit>(Channel.CONFLATED)
    private var visibleScope: CoroutineScope? = null
    private var catalogReceivedElapsed = 0L
    private val chartJobs = mutableMapOf<Pair<StockId, ChartRange>, Job>()
    private val chartReceivedElapsed = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    private val chartPrewarmRequests = mutableMapOf<Pair<StockId, ChartRange>, Deferred<LiveChart>>()
    private val chartPrewarmAttempts = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    private val chartPrewarmWakeups = Channel<Unit>(Channel.CONFLATED)
    private var chartPrewarmJob: Job? = null
    private var chartPrewarmSelectionJob: Job? = null
    private var chartPrewarmCatalog: List<LiveInstrument> = emptyList()
    private var chartPrewarmTargets: List<Pair<StockId, ChartRange>> = emptyList()
    private var activeChart: Pair<StockId, ChartRange>? = null
    private var visibleStockIds: Set<StockId> = emptySet()
    private val viewportSubscriptions = MarketViewportSubscriptions()
    private val orderedCatalog = OrderedMarketCatalog()
    private val catalogExpiryCache = MarketCatalogExpiryCache()
    private val catalogPublicationMutex = Mutex()
    private var chartProjectionRevision = 0L
    private val liveRowsCache = LiveMarketRowsCache()
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
    private val mutableWatched = MutableStateFlow(
        if (isLive) preferences?.getStringSet("stockIds", emptySet()).orEmpty().map(::StockId).toSet()
        else fixtures.initialWatchedIds,
    )
    val watched = mutableWatched.asStateFlow()
    init {
        if (isLive) {
            observeFeaturedMarketWarmup()
            ensureMarketWarmup()
        }
    }

    private fun observeFeaturedMarketWarmup() {
        val warmup = featuredMarketWarmup ?: return
        warmup.start()
        viewModelScope.launch {
            warmup.state.collect { warm ->
                restoredStartupCharts = (restoredStartupCharts + warm.charts)
                    .sortedByDescending { it.receivedAtMillis }.distinctBy { it.chart.stockId }
                val snapshot = warm.catalog
                if (snapshot != null && snapshot !== lastFeaturedCatalog) {
                    lastFeaturedCatalog = snapshot
                    // Application warm state can outlive this ViewModel. Its dated snapshot must
                    // not acquire a new age merely because an Activity subscribes again.
                    featuredCatalogReceivedElapsed = robinhoodCatalogReceiptElapsed(snapshot.receivedAt,
                        System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    val full = lastFullCatalog
                    if (full != null) {
                        acceptCatalog(full, refreshFeatured = true)
                    } else if (mutableConnection.value.catalog == null || featuredSnapshotOnly) {
                        val accepted = acceptCatalog(snapshot, featuredOnly = true, replaceFeaturedOnly = true)
                        if (accepted && warm.cached) {
                            mutableConnection.value = mutableConnection.value.copy(
                                refreshFailed = true, message = "Updating saved prices",
                            )
                        }
                        if (accepted) expireOldData()
                    }
                }
                restoreStartupCharts()
                if (warm.refreshFailed && (featuredSnapshotOnly || cachedFeaturedQuoteIds.isNotEmpty())) {
                    mutableConnection.value = mutableConnection.value.copy(refreshFailed = true,
                        message = "Prices will update when connected")
                }
            }
        }
    }

    /** Public data starts with the launch animation and survives Android's full-screen PIN prompt.
     * The one startup pass is bounded; ongoing streams remain owned by the visible Activity. */
    private fun ensureMarketWarmup() {
        val dataClient = client ?: return
        if (startupMarketStarted) return
        startupMarketStarted = true
        startupMarketRunning = true
        startupMarketJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(180_000L) {
                    restoredStartupCharts = (restoredStartupCharts + startupChartCache?.read().orEmpty())
                        .sortedByDescending { it.receivedAtMillis }.distinctBy { it.chart.stockId }
                    if (mutableConnection.value.catalog == null || featuredSnapshotOnly) {
                        val initial = dataClient.catalog()
                        if (mutableConnection.value.catalog == null || featuredSnapshotOnly)
                            acceptCatalog(initial, replaceFeaturedOnly = true)
                    }
                    restoreStartupCharts()
                    val instruments = mutableConnection.value.catalog?.instruments.orEmpty()
                    val targets = withContext(Dispatchers.Default) { marketPrewarmTargets(instruments) }
                    chartPrewarmCatalog = instruments
                    chartPrewarmTargets = targets
                    updateSubscription()
                    prefetchMarketLogos(this)
                    if (BuildConfig.DEBUG) {
                        val restored = targets.count(::hasFreshPrewarmedChart)
                        Log.i("ElevenMarketData", "Startup preload: $restored/${targets.size} charts restored; " +
                            targets.groupingBy { it.first.value.substringBefore(':') }.eachCount())
                    }
                    do {
                        prewarmChartBatch(targets)
                        val missing = targets.filterNot(::isApplicationWarmTarget).any { !hasFreshPrewarmedChart(it) }
                        if (missing) delay(15_000)
                    } while (missing)
                    if (BuildConfig.DEBUG) {
                        Log.i("ElevenMarketData", "Startup preload finished: ${targets.size}/${targets.size} default charts ready")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The foreground stream and normal prefetch retry remain independent.
            } finally {
                startupMarketRunning = false
                if (BuildConfig.DEBUG) {
                    Log.i("ElevenMarketData", "Startup preload retained: " +
                        "${chartPrewarmTargets.count(::hasFreshPrewarmedChart)}/${chartPrewarmTargets.size} charts")
                }
                scheduleChartPrewarm()
            }
        }
    }

    /** repeatOnLifecycle owns this scope: streams, callbacks, retries and charts stop in background. */
    suspend fun refreshWhileVisible() {
        val dataClient = client ?: return
        coroutineScope {
            visibleScope = this
            expireOldData()
            scheduleChartPrewarm()
            // REST gives first paint while the socket establishes its authoritative snapshot.
            if (mutableConnection.value.catalog == null && !startupMarketRunning) launch {
                try {
                    val initial = dataClient.catalog()
                    if (mutableConnection.value.catalog == null) {
                        if (acceptCatalog(initial, initialOnly = true)) {
                            mutableConnection.value = mutableConnection.value.copy(
                                message = "Market snapshot · connecting to updates", refreshFailed = true,
                            )
                        }
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
                                        repository.catalog.value.isEmpty() -> "Loading prices…"
                                        else -> "Prices updating automatically"
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
                chartPrewarmSelectionJob?.cancel()
                chartPrewarmSelectionJob = null
                chartPrewarmCatalog = emptyList()
                // The initial public-data pass belongs to the VM, like wallet warmup. Android
                // may stop this Activity while the user types their PIN; keep that pass alive.
                if (!startupMarketRunning) {
                    chartPrewarmRequests.values.forEach { it.cancel() }
                    chartPrewarmRequests.clear()
                }
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
        stopWalletWarmupAfterCurrentRead = false
        walletWarmJob = viewModelScope.launch(Dispatchers.Main) {
            val deadline = SystemClock.elapsedRealtime() + 30_000L
            var retryDelay = 2_000L
            do {
                // Transient backend or RPC failures during PIN entry should recover before Home
                // appears. A successful but partial scan is not a usable wallet balance.
                val readJob = currentCoroutineContext()[Job]
                val ready = try {
                    walletWarmReadJob = readJob
                    walletTracker.refreshOnce() && walletTracker.state.value.balanceUsd != null
                } finally {
                    if (walletWarmReadJob === readJob) walletWarmReadJob = null
                }
                if (ready) break
                // Unlocking must not cancel an in-flight balance read and throw away its result.
                // The visible polling loop takes over as soon as this read completes.
                if (stopWalletWarmupAfterCurrentRead) break
                if (SystemClock.elapsedRealtime() >= deadline) break
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(8_000L)
            } while (true)
        }
    }

    /** The foreground refresh takes over immediately after the phone credential succeeds. */
    fun finishWalletWarmup() {
        stopWalletWarmupAfterCurrentRead = true
        // A sleeping retry has no result to preserve. Wake the visible loop immediately.
        if (walletWarmReadJob !== walletWarmJob) walletWarmJob?.cancel()
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

    /** Prepare immutable catalogs off Main, then publish only against the captured chart/catalog state. */
    private suspend fun acceptCatalog(
        incoming: LiveCatalog,
        featuredOnly: Boolean = false,
        initialOnly: Boolean = false,
        replaceFeaturedOnly: Boolean = false,
        refreshFeatured: Boolean = false,
    ): Boolean = catalogPublicationMutex.withLock {
        // HTTP and featured warmups can wait behind a newer stream publication.
        if (initialOnly && mutableConnection.value.catalog != null) return@withLock false
        if (replaceFeaturedOnly && mutableConnection.value.catalog != null && !featuredSnapshotOnly)
            return@withLock false
        val candidate = if (refreshFeatured) lastFullCatalog ?: return@withLock false else incoming
        val warm = featuredMarketWarmup?.state?.value
        val fullReceipt = if (candidate === lastFullCatalog) fullCatalogReceivedElapsed else SystemClock.elapsedRealtime()
        // The Application may publish before its observer has recorded this snapshot's anchor.
        val featuredReceipt = warm?.catalog?.let { snapshot ->
            if (snapshot === lastFeaturedCatalog) featuredCatalogReceivedElapsed else
                robinhoodCatalogReceiptElapsed(snapshot.receivedAt,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime())
        } ?: featuredCatalogReceivedElapsed
        val previousIndex = instrumentIndex
        val warmListings = chartPrewarmCatalog
        val merged = withContext(Dispatchers.Default) {
            if (featuredOnly) com.elevencapital.app.data.RobinhoodCatalogMerge(candidate,
                if (warm?.cached == true) candidate.instruments.map { it.stock.id }.toSet() else emptySet())
            else mergeRobinhoodFeaturedCatalog(candidate, warm?.catalog, warm?.cached == true, Instant.now())
        }
        val catalog = merged.catalog
        val (nextIndex, changedBasis, sameWarmListings) = withContext(Dispatchers.Default) {
            Triple(catalog.instruments.associateBy { it.stock.id }, catalog.instruments.filter { item ->
                val previous = previousIndex[item.stock.id]
                previous != null && (previous.quoteBasis != item.quoteBasis ||
                    previous.stock.quote.currencyCode != item.stock.quote.currencyCode)
            }.map { it.stock.id }.toSet(), hasSameMarketListings(warmListings, catalog.instruments))
        }
        var committed = false
        while (!committed) {
            val base = repository.capturePreparationBase()
            val chartRevision = chartProjectionRevision
            val charts = chartMetadata.toMap()
            val receipts = chartReceivedElapsed.toMap()
            val identities = stockIdentities
            val elapsedNow = SystemClock.elapsedRealtime()
            val wallNow = System.currentTimeMillis()
            val (prepared, nextIdentities) = withContext(Dispatchers.Default) {
                val projected = catalog.instruments.map { instrument ->
                    projectCatalogStock(instrument, base.getStock(instrument.stock.id),
                        instrument.stock.id in changedBasis, instrument.stock.id in merged.cachedIds,
                        catalog.receivedAt, elapsedNow, wallNow, charts, receipts, catalogExpiryCache)
                }
                catalogExpiryCache.retain(nextIndex.keys)
                val prepared = repository.prepareCatalog(projected, base)
                prepared to (identities + prepared.stocks.associateBy { it.id })
            }
            // A foreground chart or hidden warmup may finish while Default is preparing.
            // Reprepare this same catalog against the latest state; never discard the event.
            if (chartRevision != chartProjectionRevision || !repository.adoptPreparedCatalog(prepared)) continue

            featuredSnapshotOnly = featuredOnly
            cachedFeaturedQuoteIds = merged.cachedIds
            if (!featuredOnly) {
                lastFullCatalog = candidate
                fullCatalogReceivedElapsed = fullReceipt
            }
            instrumentIndex = nextIndex
            stockIdentities = nextIdentities
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
            val removedCharts = chartMetadata.keys.removeAll { it.first !in nextIndex }
            if (affectedCharts.isNotEmpty() || removedCharts)
                chartProjectionRevision++
            restoreStartupCharts()
            catalogReceivedElapsed = if (featuredOnly) featuredReceipt else
                robinhoodCompositeReceiptElapsed(catalog.receivedAt, candidate.receivedAt,
                    fullReceipt, warm?.catalog?.receivedAt, featuredReceipt)
            mutableConnection.value = mutableConnection.value.copy(
                catalog = catalog,
                offline = false,
                refreshFailed = cachedFeaturedQuoteIds.isNotEmpty(),
                chartContexts = mutableConnection.value.chartContexts.filterKeys { key ->
                    key.first in nextIndex && key.first !in changedBasis
                },
                message = when {
                    cachedFeaturedQuoteIds.isNotEmpty() -> "Updating saved prices"
                    catalog.instruments.isEmpty() -> "Waiting for market sources · updates automatically"
                    catalog.providers.all { it.status == "unavailable" } -> "Market sources unavailable · automatic recovery"
                    catalog.providers.any { it.status == "stale" || it.status == "unavailable" } -> "Some prices delayed · updates automatically"
                    else -> "Market data · updates automatically"
                },
            )
            prefetchMarketLogos()
            scheduleChartPrewarm(if (chartPrewarmCatalog === warmListings) !sameWarmListings else null)
            chartPrewarmAttempts.keys.removeAll { it.first !in nextIndex }
            if (stockFlow.snapshot().destination != com.elevencapital.core.stock.flow.StockDestination.Markets && stockFlow.snapshot().stock == null) {
                while (stockFlow.goBack()) { }
                mutableTab.value = RootTab.Markets
            }
            if (activeChart?.first?.let { repository.getStock(it) == null } == true) clearActiveChart()
            activeChart?.takeIf { it.first in changedBasis }?.let { (id, range) -> loadChart(id, range) }
            committed = true
        }
        true
    }

    /** Warm the first Stocks screen while the user is still at device unlock. */
    private fun prefetchMarketLogos(scope: CoroutineScope? = visibleScope) {
        if (marketLogosPrepared || repository.catalog.value.isEmpty()) return
        if (scope == null || chartPrewarmTargets.isEmpty()) return
        marketLogosPrepared = true
        val application = getApplication<Application>()
        val stocks = chartPrewarmTargets.mapNotNull { repository.getStock(it.first) }
        scope.launch {
            try {
                val urls = withContext(Dispatchers.Default) {
                    stocks.filterNot { it.id.value.startsWith("robinhood:") }
                        .mapNotNull { it.logo?.value?.takeIf { url -> url.startsWith("https://") } }.distinct()
                }.filter { it !in prefetchedMarketLogos }
                withContext(Dispatchers.IO) {
                    urls.forEach { url ->
                        application.imageLoader.enqueue(ImageRequest.Builder(application).data(url).size(128, 128).build())
                    }
                }
                prefetchedMarketLogos.addAll(urls)
            } catch (cancelled: CancellationException) {
                marketLogosPrepared = false
                throw cancelled
            }
        }
    }

    /** Warm all twenty selected rows in each source's own launch order. */
    private fun scheduleChartPrewarm(listingsChanged: Boolean? = null) {
        if (client == null || startupMarketRunning) return
        val scope = visibleScope ?: return
        val instruments = mutableConnection.value.catalog?.instruments.orEmpty()
        // Quotes and statistics do not change the default ordering. Sorting the entire
        // catalog three times on every price tick makes background warming visible as jank.
        if (listingsChanged ?: !hasSameMarketListings(chartPrewarmCatalog, instruments)) {
            chartPrewarmCatalog = instruments
            chartPrewarmSelectionJob?.cancel()
            chartPrewarmSelectionJob = scope.launch {
                val targets = withContext(Dispatchers.Default) { marketPrewarmTargets(instruments) }
                chartPrewarmTargets = targets
                marketLogosPrepared = false
                prefetchMarketLogos()
                updateSubscription()
                chartPrewarmWakeups.trySend(Unit)
            }
        }
        if (chartPrewarmJob?.isActive != true) {
            chartPrewarmJob = scope.launch {
                launch {
                    while (currentCoroutineContext().isActive) {
                        delay(30_000)
                        chartPrewarmWakeups.trySend(Unit)
                    }
                }
                for (ignored in chartPrewarmWakeups) {
                    prewarmChartBatch(chartPrewarmTargets)
                }
            }
        }
        chartPrewarmWakeups.trySend(Unit)
    }

    private fun hasFreshPrewarmedChart(key: Pair<StockId, ChartRange>): Boolean =
        chartMetadata[key]?.let { it.status == "ok" && it.points.isNotEmpty() } == true &&
            chartReceivedElapsed[key]?.let { SystemClock.elapsedRealtime() - it < 240_000 } == true

    private fun isApplicationWarmTarget(key: Pair<StockId, ChartRange>): Boolean =
        featuredMarketWarmup != null && instrumentIndex[key.first]?.let {
            it.provider == "robinhood" && it.stock.symbol in RobinhoodFeaturedSymbols
        } == true

    private suspend fun prewarmChartBatch(targets: List<Pair<StockId, ChartRange>>) = coroutineScope {
        // One lane per provider keeps slow token-history requests from starving Backpack.
        // At most three requests run together; each lane retains its launch priority order.
        for (lane in targets.groupBy { it.first.value.substringBefore(':') }.values) {
            launch {
                for (key in lane) {
                    while (activeChart != null) delay(150)
                    prewarmChart(key)
                }
            }
        }
    }

    private suspend fun prewarmChart(key: Pair<StockId, ChartRange>) {
        val dataClient = client ?: return
        if (isApplicationWarmTarget(key)) {
            featuredMarketWarmup?.refreshCharts()
            return
        }
        if (key !in chartPrewarmTargets || key.first !in instrumentIndex) return
        val now = SystemClock.elapsedRealtime()
        if (hasFreshPrewarmedChart(key) ||
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

    private suspend fun streamInterrupted(failure: Exception) {
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

    private suspend fun expireOldData(): Unit = catalogPublicationMutex.withLock {
        val snapshot = mutableConnection.value.catalog ?: return@withLock
        while (true) {
            val base = repository.capturePreparationBase()
            val chartRevision = chartProjectionRevision
            val index = instrumentIndex
            val cachedIds = cachedFeaturedQuoteIds
            val receipts = chartReceivedElapsed.toMap()
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - catalogReceivedElapsed
            val wallNow = System.currentTimeMillis()
            val prepared = withContext(Dispatchers.Default) {
                val next = base.stockList.map { stock ->
                    val receivedAt = index[stock.id]?.quoteReceivedAt
                    val expired = expireMarketStock(stock, receivedAt, snapshot.receivedAt, elapsed, now, receipts)
                    if (stock.id in cachedIds) restoreCachedRobinhoodQuote(stock, expired, receivedAt, wallNow) else expired
                }
                if (next.indices.all { next[it] === base.stockList[it] }) null
                else repository.prepareCatalog(next, base)
            }
            if (chartRevision != chartProjectionRevision) continue
            if (prepared == null || repository.adoptPreparedCatalog(prepared)) return@withLock
        }
    }

    /** Viewport subscriptions share one socket with the selected detail. */
    fun setVisibleStockIds(ids: Set<StockId>) = setVisibleStockIds("markets", ids)

    fun setVisibleStockIds(owner: String, ids: Set<StockId>) {
        visibleStockIds = viewportSubscriptions.update(owner, ids)
        updateSubscription()
    }

    private fun updateSubscription() {
        client?.subscribe(MarketSubscription(marketWarmSubscriptionIds(visibleStockIds, chartPrewarmTargets), activeChart))
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
            // A visible detail takes priority over speculative HTTP work. Reuse its own
            // in-flight warmup, while the other workers wait until detail closes.
            chartPrewarmRequests.filterKeys { it != key }.values.forEach { it.cancel() }
            updateSubscription()
        }
        val receivedAt = chartReceivedElapsed[key]
        if (receivedAt != null && SystemClock.elapsedRealtime() - receivedAt < 300_000) {
            chartMetadata[key]?.let { chart ->
                instrumentIndex[id]?.let { instrument -> publishChart(chart, instrument) }
            }
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
                val chart = awaitChartPrewarmOrLoad(chartPrewarmRequests[key]) { dataClient.chart(id, range) }
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
        if (repository.getStock(chart.stockId) == null) return
        val instrument = instrumentIndex[chart.stockId] ?: return
        if (!isChartDisplayCompatible(chart, instrument)) return
        val wallNow = System.currentTimeMillis()
        val receipt = freshChartReceiptMillis(chart, wallNow)
        if (receipt == null) {
            if (activeChart == key) mutableConnection.value = mutableConnection.value.copy(
                chartMessages = mutableConnection.value.chartMessages + (key to "Price history updating automatically…"),
            )
            return
        }
        chartReceivedElapsed[key] = SystemClock.elapsedRealtime() - (wallNow - receipt)
        chartMetadata[key] = chart
        chartProjectionRevision++
        persistStartupCharts()
        // Keep hidden warmups private: each published graph would otherwise invalidate the
        // full market catalog and root UI while the user scrolls or uses their wallet.
        if (prewarmed && activeChart != key) return
        publishChart(chart, instrument)
    }

    private fun restoreStartupCharts() {
        if (restoredStartupCharts.isEmpty()) return
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        restoredStartupCharts = restoredStartupCharts.filter { entry ->
            val chart = entry.chart
            val instrument = instrumentIndex[chart.stockId] ?: return@filter true
            val originalReceipt = freshChartReceiptMillis(chart, wallNow, entry.receivedAtMillis)
            if (originalReceipt != null && isChartDisplayCompatible(chart, instrument)) {
                val key = chart.stockId to chart.range
                val receipt = elapsedNow - (wallNow - originalReceipt)
                if (chartReceivedElapsed[key]?.let { it >= receipt } != true) {
                    chartMetadata[key] = chart
                    chartReceivedElapsed[key] = receipt
                    chartProjectionRevision++
                }
            }
            false
        }
    }

    private fun persistStartupCharts() {
        val cache = startupChartCache ?: return
        if (chartPrewarmTargets.isEmpty()) return
        chartCacheWriteJob?.cancel()
        chartCacheWriteJob = viewModelScope.launch {
            delay(500)
            val wallNow = System.currentTimeMillis()
            val elapsedNow = SystemClock.elapsedRealtime()
            val entries = chartPrewarmTargets.mapNotNull { key ->
                val chart = chartMetadata[key] ?: return@mapNotNull null
                val receipt = chartReceivedElapsed[key] ?: return@mapNotNull null
                CachedStartupChart(chart, wallNow - (elapsedNow - receipt))
            }
            cache.write(entries)
            featuredMarketWarmup?.persistCharts(entries)
        }
    }

    private fun publishChart(chart: LiveChart, instrument: LiveInstrument) {
        val key = chart.stockId to chart.range
        if (!isChartDisplayCompatible(chart, instrument)) return
        val aligned = alignChartObservation(chart, instrument)
        chartMetadata[key] = aligned
        chartProjectionRevision++
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
            chartContexts = if (chart.status == "ok" && chart.points.isNotEmpty()) {
                val sourceLabel = when {
                    instrument.provider == "robinhood" && chart.basis == "underlying_share_reference" ->
                        "Underlying share price · USD"
                    chart.basis == "underlying_share_reference" ->
                        "Underlying share price · ${chart.currency}"
                    !isChartBasisCompatible(chart, instrument) && chart.basis == "onchain_token_market" ->
                        "Token market history · ${chart.currency}"
                    else -> null
                }
                mutableConnection.value.chartContexts +
                    (key to DetailChartContext(chart.range, chart.currency, sourceLabel))
            } else mutableConnection.value.chartContexts - key,
        )
    }

    fun liveRows(stocks: List<Stock>, watchedIds: Set<StockId>): List<PrimaryStockRow> {
        return liveRowsCache.select(stocks, instrumentIndex, watchedIds, cachedFeaturedQuoteIds)
    }

    fun liveHoldingRows(stocks: List<Stock>, watchedIds: Set<StockId>): List<PrimaryStockRow> {
        val positions = walletPortfolio.value.holdings
        if (positions.isEmpty()) return emptyList()
        // Normal UI calls use the repository's indexed snapshot. Preserve custom snapshot
        // semantics without merging thousands of identities for a handful of holdings.
        val supplied = if (stocks === repository.catalog.value) null else stocks.associateBy { it.id }
        return positions.mapNotNull { position ->
            val stock = (if (supplied == null) repository.getStock(position.stockId) else supplied[position.stockId])
                ?: stockIdentities[position.stockId] ?: return@mapNotNull null
            PrimaryStockRow(stock = stock, quantity = position.quantity, holdingValue = position.valueUsd,
                holdingValueCurrency = "USD", watched = stock.id in watchedIds)
        }
    }

    fun liveDetail(stock: Stock): DetailPresentation {
        val metadata = instrumentIndex[stock.id]
        return DetailPresentation(
            providerLabel = metadata?.providerLabel,
            ageLabel = when {
                metadata?.quoteBasis == "underlying_share_reference" -> "Share price"
                metadata?.provider == "backpack" -> "Backpack"
                metadata?.provider == "prestocks" -> "PreStocks"
                metadata?.provider == "robinhood" -> "Robinhood"
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
            "robinhood" -> "Robinhood"
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
    fun purchaseNow(amount: String) { purchaseCoordinator?.requestAndExecuteBuy(amount) }
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
        // MainActivity can bind the newly verified wallet before this account-scoped UI effect
        // runs. That binding has already cleared the former owner; do not cancel its warm read.
        val walletAlreadyBoundToUser = userId != null && walletTracker.boundUserId == userId
        if (authenticatedUserId != userId) {
            if (!walletAlreadyBoundToUser) {
                walletWarmJob?.cancel()
                walletWarmJob = null
            }
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
        if (!walletAlreadyBoundToUser) walletTracker.bind(null, emptyList())
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

internal suspend fun awaitChartPrewarmOrLoad(
    prewarm: Deferred<LiveChart>?,
    load: suspend () -> LiveChart,
): LiveChart {
    // A canceled worker removes its map entry in finally, which may run after the user
    // opens that listing again. Its startup deadline may also expire during this await.
    currentCoroutineContext().ensureActive()
    val pending = prewarm?.takeUnless { it.isCancelled } ?: return load()
    return try {
        pending.await()
    } catch (cancelled: CancellationException) {
        // Retry only when the shared warmup was canceled; leaving detail must still stop work.
        currentCoroutineContext().ensureActive()
        load()
    }
}

/** Only catalog membership, order and listing identity can change the featured warmup rows. */
internal fun hasSameMarketListings(previous: List<LiveInstrument>, next: List<LiveInstrument>): Boolean =
    previous.size == next.size && previous.indices.all { index ->
        val before = previous[index].stock
        val after = next[index].stock
        before.id == after.id && before.symbol == after.symbol && before.name == after.name
    }

internal fun marketPrewarmTargets(instruments: List<LiveInstrument>): List<Pair<StockId, ChartRange>> {
    val rows = instruments.map { PrimaryStockRow(it.stock) }
    val groups = MarketSource.entries.filter { it != MarketSource.ALL }.map { source ->
        selectMarketRows(rows, MarketQuery(source = source), false).take(MARKET_PREWARM_PER_PROVIDER)
            .map { it.stock.id to ChartRange.ONE_DAY }
    }
    // Keep launch traffic bounded: detail charts still load on demand, while a small
    // interleaved set makes the first rows feel instant without flooding the backend.
    return (0 until MARKET_PREWARM_PER_PROVIDER)
        .flatMap { position -> groups.mapNotNull { it.getOrNull(position) } }.distinct()
}

private const val MARKET_PREWARM_PER_PROVIDER = 5

/** Keep the selected stocks' quotes live on Home as well as in the Stocks viewport. */
internal fun marketWarmSubscriptionIds(
    visible: Set<StockId>, targets: List<Pair<StockId, ChartRange>>,
): Set<StockId> = (visible.asSequence() + targets.asSequence().map { it.first }).distinct().take(100).toSet()

/** Pure per-row projection over captured immutable chart inputs; suitable for Default. */
internal fun projectCatalogStock(
    instrument: LiveInstrument,
    previous: Stock?,
    changedBasis: Boolean,
    cachedQuote: Boolean,
    snapshotAt: Instant,
    nowElapsed: Long,
    nowWallMillis: Long,
    chartMetadata: Map<Pair<StockId, ChartRange>, LiveChart>,
    chartReceipts: Map<Pair<StockId, ChartRange>, Long>,
    expiryCache: MarketCatalogExpiryCache,
): Stock {
    val id = instrument.stock.id
    val retained = if (changedBasis) emptyMap() else previous?.charts.orEmpty()
    val charts = if (retained.isEmpty()) emptyMap() else retained.filterKeys { range ->
        chartReceipts[id to range]?.let { nowElapsed - it < 300_000 } == true
    }.mapValues { (range, points) ->
        val metadata = chartMetadata[id to range]
        if (metadata == null) points else alignChartObservation(metadata.copy(points = points), instrument).points
    }
    val expired = expiryCache.expire(instrument, snapshotAt)
    val stock = if (cachedQuote)
        restoreCachedRobinhoodQuote(instrument.stock, expired, instrument.quoteReceivedAt, nowWallMillis)
        else expired
    return if (charts.isEmpty()) stock else stock.copy(charts = charts)
}

/** Retain unchanged row objects so receipt-only catalog updates do not invalidate the whole list. */
internal class LiveMarketRowsCache {
    private var previousRows: List<PrimaryStockRow> = emptyList()
    private var rowsById: Map<StockId, PrimaryStockRow> = emptyMap()

    fun select(
        stocks: List<Stock>, metadata: Map<StockId, LiveInstrument>,
        watchedIds: Set<StockId>, cachedQuoteIds: Set<StockId>,
    ): List<PrimaryStockRow> {
        var unchanged = stocks.size == previousRows.size
        val next = stocks.mapIndexed { index, stock ->
            val item = metadata[stock.id]
            val label = when {
                item?.quoteBasis == "underlying_share_reference" -> "Share price"
                item?.provider == "backpack" -> "USDC · BP"
                item?.provider == "prestocks" -> "PreStocks"
                item?.provider == "robinhood" -> "Robinhood"
                else -> "xStocks"
            }
            val watched = stock.id in watchedIds
            val previous = rowsById[stock.id]
            val row = if (previous != null && previous.stock === stock &&
                previous.ageLabel == label && previous.watched == watched) previous else
                PrimaryStockRow(stock, ageLabel = label, volume = stock.activity?.volume24h,
                    netVolume = stock.activity?.netVolume24h, watched = watched)
            if (unchanged && previousRows[index] !== row) unchanged = false
            row
        }
        if (unchanged) return previousRows
        previousRows = next
        rowsById = next.associateBy { it.stock.id }
        return next
    }
}

/** Unchanged stale rows must not recreate their expired quote and statistics on every delta. */
internal class MarketCatalogExpiryCache {
    private data class Entry(val source: Stock, val expiry: Int, val stock: Stock)
    private val entries = mutableMapOf<StockId, Entry>()

    fun retain(ids: Set<StockId>) { entries.keys.retainAll(ids) }

    fun expire(instrument: LiveInstrument, snapshotAt: Instant): Stock {
        val source = instrument.stock
        var expiry = if (isReferenceDataFresh(instrument.quoteReceivedAt, snapshotAt, 0)) 0 else 1
        source.statistics?.reference?.let { reference ->
            val sourceFresh = reference.updatedAt == null || isReferenceDataFresh(reference.updatedAt, snapshotAt, 0)
            reference.metrics.forEach { (key, metric) ->
                if (metric.value != null && (!sourceFresh || !isReferenceDataFresh(metric.receivedAt, snapshotAt, 0))) {
                    expiry = expiry or (1 shl (key.ordinal + 1))
                }
            }
        }
        source.activity?.let { activity ->
            if ((activity.volume24h != null || activity.netVolume24h != null) &&
                (!isReferenceDataFresh(activity.receivedAt, snapshotAt, 0) ||
                    (activity.updatedAt != null && !isReferenceDataFresh(activity.updatedAt, snapshotAt, 0)))) {
                expiry = expiry or (1 shl 8)
            }
        }
        val previous = entries[source.id]
        if (previous != null && previous.source === source && previous.expiry == expiry) return previous.stock
        val stock = expireMarketStock(source, instrument.quoteReceivedAt, snapshotAt, 0, 0, emptyMap())
        entries[source.id] = Entry(source, expiry, stock)
        return stock
    }
}

/** Same freshness rules as the source models, without reallocating fresh or already empty rows. */
internal fun expireMarketStock(
    stock: Stock,
    quoteReceivedAt: Instant?,
    snapshotAt: Instant,
    elapsedMillis: Long,
    nowElapsed: Long,
    chartReceivedElapsed: Map<Pair<StockId, ChartRange>, Long>,
): Stock {
    val quote = stock.quote.let { quote ->
        if ((quote.price != null || quote.changeAmount != null || quote.changePercent != null) &&
            !isReferenceDataFresh(quoteReceivedAt, snapshotAt, elapsedMillis)) {
            quote.copy(price = null, changeAmount = null, changePercent = null)
        } else quote
    }
    val statistics = stock.statistics?.let { statistics ->
        val reference = statistics.reference
        val expired = reference != null && reference.metrics.values.any { metric ->
            metric.value != null && (!isReferenceDataFresh(metric.receivedAt, snapshotAt, elapsedMillis) ||
                (reference.updatedAt != null && !isReferenceDataFresh(reference.updatedAt, snapshotAt, elapsedMillis)))
        }
        if (expired) requireNotNull(reference).expire(snapshotAt, elapsedMillis).toStatistics() else statistics
    }
    val activity = stock.activity?.let { activity ->
        if (activity.volume24h == null && activity.netVolume24h == null) activity
        else activity.expire(snapshotAt, elapsedMillis)
    }
    val robinhoodReference = stock.robinhoodReference?.takeIf { quote.price != null }
    fun chartFresh(range: ChartRange): Boolean =
        chartReceivedElapsed[stock.id to range]?.let { nowElapsed - it < 300_000 } == true
    val charts = if (stock.charts.keys.all(::chartFresh)) stock.charts else stock.charts.filterKeys(::chartFresh)
    return if (quote === stock.quote && statistics === stock.statistics && activity === stock.activity &&
        charts === stock.charts && robinhoodReference === stock.robinhoodReference) stock else
        stock.copy(quote = quote, statistics = statistics, activity = activity, charts = charts,
            robinhoodReference = robinhoodReference)
}

/** Read-only restored references have a separate, explicit cached display window. */
internal fun restoreCachedRobinhoodQuote(
    original: Stock,
    expired: Stock,
    receivedAt: Instant?,
    nowWallMillis: Long,
): Stock {
    if (!original.id.value.startsWith("robinhood:")) return expired
    val age = receivedAt?.let { nowWallMillis - it.toEpochMilli() }
    if (original.quote.price != null && age != null && age in 0 until 24 * 60 * 60 * 1000L) {
        return if (expired.quote === original.quote && expired.robinhoodReference === original.robinhoodReference)
            expired else expired.copy(quote = original.quote, robinhoodReference = original.robinhoodReference)
    }
    return if (expired.quote.price == null && expired.robinhoodReference == null) expired else
        expired.copy(quote = expired.quote.copy(price = null, changeAmount = null, changePercent = null),
            robinhoodReference = null)
}

enum class RootTab(val icon: String) {
    Home("home"), Markets("markets"), Predictions("predictions"), Community("community"), Account("account")
}
