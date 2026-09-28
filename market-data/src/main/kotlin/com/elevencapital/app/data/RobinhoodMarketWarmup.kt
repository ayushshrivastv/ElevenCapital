package com.elevencapital.app.data

import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class RobinhoodWarmState(
    val catalog: LiveCatalog? = null,
    val charts: List<CachedStartupChart> = emptyList(),
    val cached: Boolean = false,
    val refreshFailed: Boolean = false,
    val initialAttemptComplete: Boolean = false,
)

/** Starts before the login UI and shares one public preload across Activity/ViewModel instances. */
class RobinhoodMarketWarmup(
    private val cache: RobinhoodFeaturedCatalogCache,
    private val chartCache: StartupMarketCache,
    private val fetchCatalog: suspend () -> JSONObject,
    private val fetchChart: (suspend (StockId, ChartRange) -> LiveChart)? = null,
    private val seedCatalog: suspend () -> JSONObject? = { null },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(RobinhoodWarmState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var startup: Job? = null
    private var refreshTask: Deferred<Boolean>? = null
    private var chartTask: Job? = null
    private val chartAttempts = mutableMapOf<StockId, Long>()

    fun start() {
        synchronized(lock) {
            if (startup != null) return
            startup = scope.launch {
                val stored = cache.read() ?: try { seedCatalog()?.let(::boundedRobinhoodFeaturedCatalog) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val restoredCharts = chartCache.read()
                if (stored != null) {
                    val restored = LiveMarketDataParser.catalog(stored).asCachedRobinhoodCatalog()
                    mutableState.value = RobinhoodWarmState(restored, restoredCharts, cached = true)
                }
                refresh()
            }
        }
    }

    suspend fun refresh(): Boolean {
        val request = synchronized(lock) {
            refreshTask?.takeIf { it.isActive } ?: scope.async {
                try {
                    val raw = boundedRobinhoodFeaturedCatalog(fetchCatalog())
                    val snapshot = LiveMarketDataParser.catalog(raw)
                    cache.write(raw)
                    mutableState.update { it.copy(catalog = snapshot, cached = false,
                        refreshFailed = false, initialAttemptComplete = true) }
                    refreshCharts()
                    true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(refreshFailed = true, initialAttemptComplete = true) }
                    false
                }
            }.also { refreshTask = it }
        }
        return request.await()
    }

    fun refreshCharts() {
        val fetch = fetchChart ?: return
        val catalog = state.value.catalog ?: return
        synchronized(lock) {
            if (chartTask?.isActive == true) return
            chartTask = scope.launch {
                val permits = Semaphore(2)
                catalog.instruments.map { instrument ->
                    async {
                        val existing = state.value.charts.firstOrNull { it.chart.stockId == instrument.stock.id }
                        if (existing != null && now() - existing.receivedAtMillis in 0 until 240_000) return@async
                        val attemptedAt = synchronized(lock) { chartAttempts[instrument.stock.id] }
                        if (attemptedAt != null && now() - attemptedAt in 0 until 300_000) return@async
                        permits.withPermit {
                            synchronized(lock) { chartAttempts[instrument.stock.id] = now() }
                            try {
                                val chart = fetch(instrument.stock.id, ChartRange.ONE_DAY)
                                if (chart.status != "ok" || chart.points.isEmpty() || chart.currency != "USD" ||
                                    chart.basis != "underlying_share_reference") return@withPermit
                                val receipt = freshChartReceiptMillis(chart, now()) ?: return@withPermit
                                val entry = CachedStartupChart(chart, receipt)
                                mutableState.update { previous -> previous.copy(charts =
                                    (previous.charts.filter { it.chart.stockId != chart.stockId } + entry)
                                        .filter { it.chart.stockId in catalog.instruments.map { row -> row.stock.id } }) }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* One unavailable history never delays another issuer row. */ }
                        }
                    }
                }.awaitAll()
                chartCache.write(state.value.charts)
            }
        }
    }

    suspend fun persistCharts(charts: List<CachedStartupChart>) {
        val featuredIds = state.value.catalog?.instruments.orEmpty().map { it.stock.id }.toSet()
        val accepted = charts.filter { it.chart.stockId in featuredIds &&
            it.chart.status == "ok" && it.chart.basis == "underlying_share_reference" }
        if (accepted.isNotEmpty()) chartCache.write(accepted.take(RobinhoodFeaturedSymbols.size))
    }
}

internal fun LiveCatalog.asCachedRobinhoodCatalog(): LiveCatalog = copy(providers = providers.map {
    if (it.id == "robinhood") it.copy(status = "stale") else it
})

/** A small public request with cancellation, no account headers, and a bounded response body. */
class RobinhoodFeaturedClient(
    backendUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val backend = validatedRobinhoodBackend(backendUrl)

    suspend fun catalog(): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url("$backend/v1/stocks/featured?provider=robinhood")
            .header("Accept", "application/json").header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        require(it.isSuccessful && it.header("Content-Type")?.substringBefore(';') == "application/json")
                        val output = ByteArrayOutputStream()
                        requireNotNull(it.body).byteStream().use { stream ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 1024 * 1024)
                                output.write(buffer, 0, count)
                            }
                        }
                        JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
}
