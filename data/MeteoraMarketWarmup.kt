package com.elevencapital.app.data

import com.elevencapital.app.wallet.isSolanaPublicKey
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MeteoraWarmState(
    val tokens: List<MeteoraMarketToken> = emptyList(),
    val charts: List<CachedMeteoraChart> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val loadedAtMillis: Long = 0L,
)

/** One public top-token request starts at process launch, before login or device unlock. */
class MeteoraMarketWarmup(
    private val cache: MeteoraMarketSnapshotCache,
    private val fetchTokens: suspend () -> List<MeteoraMarketToken>,
    private val chartCache: MeteoraChartSnapshotCache? = null,
    private val fetchChart: (suspend (MeteoraMarketToken) -> MeteoraTokenChart)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(MeteoraWarmState())
    val state = mutableState.asStateFlow()
    private val lock = Any()
    private var started = false
    private var refreshTask: Deferred<Boolean>? = null
    private var chartTask: Job? = null
    private var chartTargets: Set<String> = emptySet()

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            mutableState.update { it.copy(loading = true) }
            scope.launch {
                val snapshot = cache.read()
                val savedCharts = chartCache?.read().orEmpty()
                mutableState.update { current ->
                    val restored = if (snapshot == null || current.loadedAtMillis >= snapshot.loadedAtMillis) current
                        else current.copy(tokens = snapshot.tokens, loadedAtMillis = snapshot.loadedAtMillis)
                    restored.copy(charts = (savedCharts + current.charts)
                        .sortedByDescending { it.receivedAtMillis }
                        .distinctBy(::chartKey)
                        .take(METEORA_LIST_LIMIT))
                }
                refreshCharts()
                refresh()
            }
        }
    }

    fun requestRefresh() {
        scope.launch { refresh() }
    }

    fun requestRefreshIfStale() {
        val current = state.value
        if (!current.loading && (current.loadedAtMillis == 0L ||
            now() - current.loadedAtMillis !in 0L until REFRESH_INTERVAL_MILLIS)) {
            requestRefresh()
        }
    }

    suspend fun refresh(): Boolean {
        val request = synchronized(lock) {
            refreshTask?.takeIf { it.isActive } ?: scope.async {
                mutableState.update { it.copy(loading = true, error = null) }
                try {
                    val rows = fetchTokens()
                    if (rows.isEmpty()) throw IOException("No token pools were returned.")
                    val loadedAt = now()
                    val currentKeys = rows.map(::chartKey).toSet()
                    mutableState.update { it.copy(tokens = rows, loadedAtMillis = loadedAt, error = null,
                        charts = it.charts.filter { chart -> chartKey(chart) in currentKeys }) }
                    cache.write(rows, loadedAt)
                    refreshCharts()
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = if (it.tokens.isEmpty())
                        "Tokens are unavailable right now" else null) }
                    false
                } finally {
                    mutableState.update { it.copy(loading = false) }
                }
            }.also { refreshTask = it }
        }
        return request.await()
    }

    fun updateToken(token: MeteoraMarketToken) {
        mutableState.update { current ->
            current.copy(tokens = current.tokens.map { row ->
                if (row.mintAddress == token.mintAddress) token else row
            })
        }
    }

    /** A validated saved 1D chart can paint the detail screen on its first frame. */
    fun cachedChartFor(token: MeteoraMarketToken): MeteoraTokenChart? = state.value.charts
        .firstOrNull { chartKey(it) == chartKey(token) &&
            now() - it.receivedAtMillis in 0L until CHART_MAX_AGE_MILLIS }
        ?.chart

    private fun refreshCharts() {
        val fetch = fetchChart ?: return
        val rows = state.value.tokens.take(METEORA_LIST_LIMIT)
        if (rows.isEmpty()) return
        val targets = rows.map(::chartKey).toSet()
        synchronized(lock) {
            if (chartTask?.isActive == true && chartTargets == targets) return
            chartTask?.cancel()
            chartTargets = targets
            chartTask = scope.launch {
                repeat(CHART_ATTEMPT_ROUNDS) { round ->
                    val pending = rows.filter { token ->
                        state.value.charts.none { entry -> chartKey(entry) == chartKey(token) &&
                            now() - entry.receivedAtMillis in 0L until CHART_REFRESH_MILLIS }
                    }
                    if (pending.isEmpty()) return@launch
                    val permits = Semaphore(2)
                    pending.map { token ->
                        async {
                            permits.withPermit {
                                try {
                                    val chart = fetch(token)
                                    if (chart.points.isEmpty()) return@withPermit
                                    val entry = CachedMeteoraChart(token.poolAddress, token.mintAddress, chart, now())
                                    mutableState.update { current ->
                                        val activeKeys = current.tokens.map(::chartKey).toSet()
                                        if (chartKey(entry) !in activeKeys) current else current.copy(charts =
                                            (current.charts.filter { chartKey(it) != chartKey(entry) } + entry)
                                                .filter { chartKey(it) in activeKeys }.take(METEORA_LIST_LIMIT))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // One pool may have no candles or be rate-limited; keep other charts.
                                }
                            }
                        }
                    }.awaitAll()
                    state.value.charts.takeIf { it.isNotEmpty() }?.let { chartCache?.write(it) }
                    if (round == CHART_ATTEMPT_ROUNDS - 1 ||
                        rows.all { cachedChartFor(it) != null }) return@launch
                    delay(CHART_RETRY_MILLIS)
                }
            }
        }
    }

    private fun chartKey(token: MeteoraMarketToken) = "${token.poolAddress}:${token.mintAddress}"
    private fun chartKey(chart: CachedMeteoraChart) = "${chart.poolAddress}:${chart.mintAddress}"

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 60_000L
        const val CHART_REFRESH_MILLIS = 30_000L
        const val CHART_MAX_AGE_MILLIS = 10 * 60_000L
        const val CHART_RETRY_MILLIS = 65_000L
        const val CHART_ATTEMPT_ROUNDS = 3
    }
}

internal data class CachedMeteoraMarket(val tokens: List<MeteoraMarketToken>, val loadedAtMillis: Long)

/** A bounded public snapshot; a stale or malformed file is never mistaken for current data. */
class MeteoraMarketSnapshotCache(
    directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(directory, "meteora-top20-v1.json")
    private val mutex = Mutex()

    internal suspend fun read(): CachedMeteoraMarket? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withLock null
                val document = JSONObject(file.readText(Charsets.UTF_8))
                require(document.getInt("schemaVersion") == 1)
                val loadedAt = document.getLong("loadedAtMillis")
                require(loadedAt > 0 && loadedAt <= now() && now() - loadedAt < MAX_AGE_MILLIS)
                val array = document.getJSONArray("tokens")
                require(array.length() in 1..METEORA_LIST_LIMIT)
                val rows = (0 until array.length()).map { index -> parseRow(array.getJSONObject(index)) }
                require(rows.map { it.mintAddress }.distinct().size == rows.size)
                CachedMeteoraMarket(rows, loadedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    internal suspend fun write(tokens: List<MeteoraMarketToken>, loadedAtMillis: Long): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var temporary: File? = null
                try {
                    currentCoroutineContext().ensureActive()
                    require(loadedAtMillis > 0 && loadedAtMillis <= now())
                    require(tokens.size in 1..METEORA_LIST_LIMIT)
                    require(tokens.map { it.mintAddress }.distinct().size == tokens.size)
                    val rows = JSONArray()
                    tokens.forEach { token -> rows.put(serializeRow(token)) }
                    val bytes = JSONObject().put("schemaVersion", 1)
                        .put("loadedAtMillis", loadedAtMillis).put("tokens", rows)
                        .toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_BYTES)
                    val parent = requireNotNull(file.parentFile)
                    require(parent.isDirectory || parent.mkdirs())
                    temporary = File.createTempFile("meteora-top20-", ".tmp", parent)
                    temporary.writeBytes(bytes)
                    currentCoroutineContext().ensureActive()
                    try {
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Public cache failure must never prevent live market data from loading.
                } finally {
                    temporary?.delete()
                }
            }
        }

    private fun serializeRow(row: MeteoraMarketToken): JSONObject {
        validateRow(row)
        return JSONObject().put("mint", row.mintAddress).put("pool", row.poolAddress)
            .put("name", row.name).put("symbol", row.symbol)
            .put("price", row.priceUsd?.toPlainString() ?: JSONObject.NULL)
            .put("volume24h", row.volume24hUsd?.toPlainString() ?: JSONObject.NULL)
            .put("image", row.imageUrl ?: JSONObject.NULL)
            .put("change24h", row.change24hPercent?.toPlainString() ?: JSONObject.NULL)
            .put("marketCap", row.marketCapUsd?.toPlainString() ?: JSONObject.NULL)
            .put("liquidity", row.liquidityUsd?.toPlainString() ?: JSONObject.NULL)
    }

    private fun parseRow(json: JSONObject): MeteoraMarketToken = MeteoraMarketToken(
        mintAddress = json.getString("mint"),
        poolAddress = json.getString("pool"),
        name = json.getString("name"),
        symbol = json.getString("symbol"),
        priceUsd = json.cachedDecimal("price"),
        volume24hUsd = json.cachedDecimal("volume24h"),
        imageUrl = json.optString("image").takeIf { it.isNotEmpty() && it != "null" },
        change24hPercent = json.cachedDecimal("change24h"),
        marketCapUsd = json.cachedDecimal("marketCap"),
        liquidityUsd = json.cachedDecimal("liquidity"),
    ).also(::validateRow)

    private fun validateRow(row: MeteoraMarketToken) {
        require(isSolanaPublicKey(row.mintAddress) && isSolanaPublicKey(row.poolAddress))
        require(row.name.length in 1..64 && row.symbol.length in 1..16)
        require(row.imageUrl == null || safeTokenImageUrl(row.imageUrl) == row.imageUrl)
        require(listOfNotNull(row.priceUsd, row.volume24hUsd, row.change24hPercent,
            row.marketCapUsd, row.liquidityUsd).all { it.precision() <= 40 && it.scale() in -20..40 })
        require(listOfNotNull(row.priceUsd, row.volume24hUsd, row.marketCapUsd,
            row.liquidityUsd).all { it.signum() > 0 })
    }

    private fun JSONObject.cachedDecimal(key: String): BigDecimal? =
        if (isNull(key)) null else BigDecimal(getString(key))

    private companion object {
        const val MAX_BYTES = 64 * 1024
        val MAX_AGE_MILLIS: Long = TimeUnit.MINUTES.toMillis(10)
    }
}
