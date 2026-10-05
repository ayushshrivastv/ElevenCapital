package com.elevencapital.app.data

import com.elevencapital.app.BuildConfig
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

internal const val PYTH_ETH_FEED = "0xff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace"
internal const val PYTH_BTC_FEED = "0xe62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43"

internal enum class PredictionPriceMarket(val path: String, val feedId: String,
    val minBandUsd: BigDecimal, val maxBandUsd: BigDecimal, val maxSigmaUsd: BigDecimal) {
    ETH("eth", PYTH_ETH_FEED, BigDecimal("0.01"), BigDecimal("4.00"), BigDecimal("8.00")),
    BTC("btc", PYTH_BTC_FEED, BigDecimal("0.01"), BigDecimal("100.00"), BigDecimal("240.00")),
}

internal data class PythEthQuote(val priceUsd: BigDecimal, val confidenceUsd: BigDecimal, val publishTime: Long)

/** A fixed round is unavailable only when its required Pyth opening report cannot be obtained. */
internal class PredictionRoundUnavailableException : IOException("Prediction round unavailable")

/** Canonical, immutable targets for one shared 90-second market pool. */
internal data class PythEthRoundTerms(
    val poolId: Long,
    val bandUsd: BigDecimal,
    val anchor: PythEthQuote,
    /** Prestart estimate of the standard deviation of a 90-second market move. */
    val forecastSigmaUsd: BigDecimal,
)

/** Server time advances on a monotonic clock; changing the phone clock cannot restart a pool. */
internal data class PythEthSnapshot(
    val status: String = "unavailable",
    val quote: PythEthQuote? = null,
    val serverTimeMs: Long = System.currentTimeMillis(),
    val receivedAtNanos: Long = System.nanoTime(),
    val hasServerTime: Boolean = false,
) {
    fun nowMillis(): Long = serverTimeMs + ((System.nanoTime() - receivedAtNanos) / 1_000_000).coerceAtLeast(0)
    fun freshQuote(nowMillis: Long = nowMillis()): PythEthQuote? = quote?.takeIf {
        status == "live" && nowMillis - it.publishTime * 1_000L in -1_000L..5_000L
    }
}

/** Public backend transport. The Pyth API key is never distributed to Android. */
internal class PredictionPythClient(
    baseUrl: String,
    private val market: PredictionPriceMarket = PredictionPriceMarket.ETH,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val base = baseUrl.trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    /** Reconnect with bounded backoff; stale or interrupted streams never become synthetic prices. */
    fun stream() = flow {
        var backoff = 500L
        var lastPublishTime = 0L
        var lastSnapshot = PythEthSnapshot()
        while (currentCoroutineContext().isActive) {
            val call = http.newCall(Request.Builder().url("$base/v1/prediction/${market.path}/stream")
                .header("Accept", "text/event-stream").build())
            val cancellation = call.cancelWithCollector()
            try {
                call.await().use { response ->
                    checkResponse(response)
                    require(response.header("Content-Type").orEmpty().startsWith("text/event-stream"))
                    val source = requireNotNull(response.body).source()
                    var event = ""
                    val data = StringBuilder()
                    while (currentCoroutineContext().isActive) {
                        val line = withContext(Dispatchers.IO) { source.readUtf8LineStrict(65_536) }
                        when {
                            line.isEmpty() -> {
                                if (event == "price" && data.isNotEmpty()) {
                                    val snapshot = parseSnapshot(data.toString(), market)
                                    val timestamp = snapshot.quote?.publishTime ?: lastPublishTime
                                    require(timestamp >= lastPublishTime) { "Out-of-order price" }
                                    lastPublishTime = timestamp
                                    lastSnapshot = snapshot
                                    emit(snapshot)
                                    backoff = 500L
                                }
                                event = ""
                                data.setLength(0)
                            }
                            line.startsWith("event:") -> event = line.substringAfter(':').trim()
                            line.startsWith("data:") -> {
                                require(data.length + line.length <= 65_536) { "Oversized price event" }
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substringAfter(':').trimStart())
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emit(lastSnapshot.copy(status = "unavailable"))
            } finally {
                cancellation.cancel()
                call.cancel()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }
    }

    /** Only the first report in the fixed target window is admissible for an anchor or settlement. */
    suspend fun boundary(timestamp: Long): PythEthQuote {
        require(timestamp > 0)
        val call = http.newCall(Request.Builder().url("$base/v1/prediction/${market.path}/at/$timestamp").build())
        val cancellation = call.cancelWithCollector()
        try {
            return call.await().use { response ->
                checkResponse(response)
                val body = withContext(Dispatchers.IO) { requireNotNull(response.body).source().readUtf8Bounded() }
                val json = JSONObject(body)
                require(json.getInt("schemaVersion") == 1 && isExpectedFeed(json.getString("feedId"), market))
                require(json.getLong("targetTimestamp") == timestamp)
                parseQuote(json.getJSONObject("quote")).also {
                    require(it.publishTime in timestamp..timestamp + 5)
                }
            }
        } finally { cancellation.cancel(); call.cancel() }
    }

    /** The backend fixes the range from reports published before this pool starts. */
    suspend fun roundTerms(poolId: Long): PythEthRoundTerms {
        require(poolId > 0 && poolId < Long.MAX_VALUE / 90L)
        val call = http.newCall(Request.Builder().url("$base/v1/prediction/${market.path}/round/$poolId").build())
        val cancellation = call.cancelWithCollector()
        try {
            return call.await().use { response ->
                if (response.code == 503) {
                    val body = withContext(Dispatchers.IO) {
                        requireNotNull(response.body).source().readUtf8Bounded()
                    }
                    val errorCode = try { JSONObject(body).optString("error") }
                    catch (_: JSONException) { null }
                    if (errorCode == "pyth_round_unavailable") throw PredictionRoundUnavailableException()
                }
                checkResponse(response)
                val body = withContext(Dispatchers.IO) { requireNotNull(response.body).source().readUtf8Bounded() }
                val json = JSONObject(body)
                require(json.getInt("schemaVersion") == 1 && isExpectedFeed(json.getString("feedId"), market))
                require(json.getLong("poolId") == poolId)
                val bandText = json.getString("bandUsd")
                require(bandText.length <= 16 && bandText.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")))
                val band = bandText.toBigDecimal()
                require(band >= market.minBandUsd && band <= market.maxBandUsd)
                val sigmaText = json.getString("forecastSigmaUsd")
                require(sigmaText.length <= 18 && sigmaText.matches(Regex("[0-9]+(?:\\.[0-9]{1,6})?")))
                val sigma = sigmaText.toBigDecimal()
                require(sigma > BigDecimal.ZERO && sigma <= market.maxSigmaUsd)
                val anchor = parseQuote(json.getJSONObject("quote"))
                val start = poolId * 90L
                require(anchor.publishTime in start..start + 5L)
                // ETH retains its narrower confidence rule. BTC checks the authenticated
                // report's confidence ratio independently of the adaptive range width.
                if (market == PredictionPriceMarket.ETH)
                    require(anchor.confidenceUsd <= band.divide(BigDecimal(2)))
                PythEthRoundTerms(poolId, band, anchor, sigma)
            }
        } finally { cancellation.cancel(); call.cancel() }
    }

    companion object {
        internal fun parseSnapshot(raw: String,
            market: PredictionPriceMarket = PredictionPriceMarket.ETH): PythEthSnapshot {
            require(raw.length <= 65_536)
            val json = JSONObject(raw)
            require(json.getInt("schemaVersion") == 1 && isExpectedFeed(json.getString("feedId"), market))
            val status = json.getString("status")
            require(status in setOf("live", "stale", "unavailable", "unconfigured"))
            val time = json.getLong("serverTimeMs")
            require(time in 1..253_402_300_799_999L)
            val quote = json.optJSONObject("quote")?.let(::parseQuote)
            require(quote == null || quote.publishTime * 1_000L <= time + 1_000L)
            require(status != "live" || (quote != null && time - quote.publishTime * 1_000L <= 5_000L))
            return PythEthSnapshot(status, quote, time, hasServerTime = true)
        }

        private fun parseQuote(json: JSONObject): PythEthQuote {
            fun decimal(key: String): BigDecimal {
                val value = json.getString(key)
                require(value.length <= 64 && value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")))
                return value.toBigDecimal().also { require(it.scale() <= 18 && it.precision() <= 36) }
            }
            val price = decimal("priceUsd")
            val confidence = decimal("confidenceUsd")
            val time = json.getLong("publishTime")
            require(price.signum() > 0 && confidence.signum() >= 0 && confidence <= price.movePointLeft(2))
            require(time in 1..253_402_300_799L)
            return PythEthQuote(price, confidence, time)
        }

        private fun isExpectedFeed(value: String, market: PredictionPriceMarket): Boolean =
            value.removePrefix("0x").lowercase() == market.feedId.removePrefix("0x")
    }
}

private fun checkResponse(response: Response) {
    if (!response.isSuccessful) throw IOException("Price service unavailable (${response.code})")
}

/** Cancels a blocking body read too, not only the initial HTTP response wait. */
private suspend fun Call.cancelWithCollector() = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
    try { awaitCancellation() } finally { cancel() }
}

private fun okio.BufferedSource.readUtf8Bounded(): String {
    require(!request(65_537)) { "Oversized price response" }
    return readUtf8()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
        }
    })
}

/** Keeps one price connection and one set of round targets warm across app screens. */
internal class PredictionPythFeedSession(
    val client: PredictionPythClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val stopGraceMs: Long = 25_000L,
    private val subscriberStopGraceMs: Long = 5_000L,
    snapshotStream: Flow<PythEthSnapshot> = client.stream(),
) {
    val snapshots = snapshotStream.stateIn(scope, SharingStarted.WhileSubscribed(subscriberStopGraceMs), PythEthSnapshot())
    val currentSnapshot: PythEthSnapshot get() = snapshots.value

    @Volatile private var cachedTerms: PythEthRoundTerms? = null
    @Volatile private var omittedPoolId: Long? = null
    private val termsMutex = Mutex()
    private var foregroundHolders = 0
    private var foregroundJob: Job? = null
    private var pendingStop: Job? = null

    /** Targets are safe to reuse only for the current server-time pool. */
    val currentRoundTerms: PythEthRoundTerms?
        get() = currentSnapshot.takeIf { it.hasServerTime }?.let { snapshot ->
            cachedTerms?.takeIf { it.poolId == Math.floorDiv(snapshot.nowMillis(), 90_000L) }
        }
    val unavailableRoundId: Long?
        get() = currentSnapshot.takeIf { it.hasServerTime }?.let { snapshot ->
            omittedPoolId?.takeIf { it == Math.floorDiv(snapshot.nowMillis(), 90_000L) }
        }

    /** Share an in-flight target request when the prediction screen opens during warmup. */
    suspend fun roundTerms(poolId: Long): PythEthRoundTerms {
        cachedTerms?.takeIf { it.poolId == poolId }?.let { return it }
        if (omittedPoolId == poolId) throw PredictionRoundUnavailableException()
        return termsMutex.withLock {
            cachedTerms?.takeIf { it.poolId == poolId } ?: run {
                if (omittedPoolId == poolId) throw PredictionRoundUnavailableException()
                try { client.roundTerms(poolId).also { cachedTerms = it } }
                catch (omitted: PredictionRoundUnavailableException) {
                    omittedPoolId = poolId
                    throw omitted
                }
            }
        }
    }

    /** Keep the stream subscribed and prefetch fixed targets while any Activity is foregrounded. */
    @Synchronized fun startForeground() {
        foregroundHolders++
        pendingStop?.cancel()
        pendingStop = null
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            // stateIn owns the sole network connection; this collector keeps it active across tabs.
            launch { snapshots.collect {} }
            var failedPoolId = -1L
            var failures = 0
            while (isActive) {
                val snapshot = currentSnapshot
                if (snapshot.hasServerTime) {
                    val poolId = Math.floorDiv(snapshot.nowMillis(), 90_000L)
                    if (poolId != failedPoolId) { failedPoolId = poolId; failures = 0 }
                    if (poolId > 0 && currentRoundTerms?.poolId != poolId && unavailableRoundId != poolId) {
                        try {
                            roundTerms(poolId)
                            failures = 0
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: PredictionRoundUnavailableException) {
                            // Fixed source reports make this pool impossible; try only the next one.
                        } catch (_: Exception) {
                            failures++
                            // Give short outages a prompt retry without repeatedly spending trial quota.
                            delay(if (failures <= 3) 1_000L shl (failures - 1) else 30_000L)
                        }
                    }
                }
                delay(250)
            }
        }
    }

    /** Retain the feed briefly across the phone credential prompt or Activity recreation. */
    @Synchronized fun stopForeground() {
        if (foregroundHolders == 0) return
        foregroundHolders--
        if (foregroundHolders != 0) return
        pendingStop?.cancel()
        pendingStop = scope.launch {
            delay(stopGraceMs)
            synchronized(this@PredictionPythFeedSession) {
                if (foregroundHolders == 0) {
                    foregroundJob?.cancel()
                    foregroundJob = null
                }
                pendingStop = null
            }
        }
    }

    /** Releases a session's coroutines; primarily useful for deterministic tests. */
    fun close() = scope.cancel()
}

internal object PredictionPythFeed {
    internal val session by lazy { PredictionPythFeedSession(PredictionPythClient(BuildConfig.MARKET_DATA_URL)) }
    val client get() = session.client
    val snapshots get() = session.snapshots
    val currentSnapshot get() = session.currentSnapshot
    val currentRoundTerms get() = session.currentRoundTerms
    val unavailableRoundId get() = session.unavailableRoundId
    suspend fun roundTerms(poolId: Long) = session.roundTerms(poolId)
    fun startForeground() = session.startForeground()
    fun stopForeground() = session.stopForeground()
}

/** BTC/USD prediction rounds use a separate session from the ETH/USD wallet conversion quote. */
internal object PredictionBtcFeed {
    internal val session by lazy { PredictionPythFeedSession(PredictionPythClient(
        BuildConfig.MARKET_DATA_URL, PredictionPriceMarket.BTC)) }
    fun startForeground() = session.startForeground()
    fun stopForeground() = session.stopForeground()
}
