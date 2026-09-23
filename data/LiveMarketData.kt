package com.elevencapital.app.data

import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.StockPricePoint
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.StockMetricKey
import com.elevencapital.core.stock.StockMetricData
import com.elevencapital.core.stock.StockStatisticsReference
import com.elevencapital.core.stock.StockStatistics
import com.elevencapital.core.stock.StockMarketActivity
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class LiveInstrument(
    val stock: Stock,
    val provider: String,
    val providerLabel: String,
    val volume24h: BigDecimal?,
    val quoteReceivedAt: Instant?,
    val quoteBasis: String,
    val currencyBasis: String,
    val marketState: String? = null,
    val marketStateReason: String? = null,
)

data class LiveProviderStatus(val id: String, val status: String)

data class LiveCatalog(
    val instruments: List<LiveInstrument>,
    val providers: List<LiveProviderStatus>,
    val receivedAt: Instant,
)

data class LiveChart(
    val stockId: StockId,
    val range: ChartRange,
    val status: String,
    val currency: String,
    val points: List<StockPricePoint>,
    val basis: String? = null,
    val statusReason: String? = null,
)

class MarketDataHttpException(val statusCode: Int) : IOException("Market data service returned HTTP $statusCode.")

sealed interface MarketStreamEvent {
    data class Snapshot(val catalog: LiveCatalog, val sessionId: String, val revision: Long) : MarketStreamEvent
    data class Delta(
        val instruments: List<LiveInstrument>, val providers: List<LiveProviderStatus>,
        val removedIds: Set<StockId>, val receivedAt: Instant, val sessionId: String, val revision: Long,
    ) : MarketStreamEvent
    data class Chart(val chart: LiveChart, val sessionId: String, val revision: Long) : MarketStreamEvent
    data class Heartbeat(val serverTime: Instant) : MarketStreamEvent
    data class Status(val state: String, val message: String? = null) : MarketStreamEvent
}

/** One authoritative subscription for the viewport and the currently open detail. */
data class MarketSubscription(
    val ids: Set<StockId> = emptySet(),
    val detail: Pair<StockId, ChartRange>? = null,
) {
    init { require(ids.size <= 100) }
    fun toJson(): String = JSONObject().put("type", "subscribe")
        .put("ids", JSONArray(ids.map { it.value }.sorted()))
        .also { json -> detail?.let { (id, range) ->
            json.put("detail", JSONObject().put("id", id.value).put("range", range.name))
        } }.toString()
}

/** Animated screens may briefly overlap; each viewport releases only its own interests. */
class MarketViewportSubscriptions {
    private val owners = linkedMapOf<String, Set<StockId>>()
    fun update(owner: String, ids: Set<StockId>): Set<StockId> {
        require(owner.isNotBlank())
        if (ids.isEmpty()) owners.remove(owner) else owners[owner] = ids.take(100).toSet()
        return owners.values.asSequence().flatten().distinct().take(100).toSet()
    }
    fun clear() { owners.clear() }
}

/** Public data only. All requests cancel immediately when their foreground coroutine stops. */
class LiveMarketDataClient internal constructor(
    baseUrl: String,
    private val http: OkHttpClient = defaultMarketHttpClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val finiteHttp = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val socketHttp = http.newBuilder().pingInterval(15, TimeUnit.SECONDS).build()
    private val subscriptions = MutableStateFlow(MarketSubscription())

    fun subscribe(subscription: MarketSubscription) { subscriptions.value = subscription }

    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost")))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
    }

    suspend fun catalog(): LiveCatalog = LiveMarketDataParser.catalog(request("/v1/stocks"))

    suspend fun chart(id: StockId, range: ChartRange): LiveChart {
        val encodedId = URLEncoder.encode(id.value, "UTF-8").replace("+", "%20")
        return LiveMarketDataParser.chart(request("/v1/stocks/$encodedId/charts?range=${range.name}"), id, range)
    }

    /** One multiplexed foreground socket; every reconnect sends the latest subscription. */
    fun stream(): Flow<MarketStreamEvent> = callbackFlow {
        val lastFrame = AtomicLong(System.nanoTime())
        var subscriptionJob: Job? = null
        val frameCoalescer = MarketStreamFrameCoalescer()
        val flushSignals = Channel<Unit>(Channel.CONFLATED)
        val flushJob = launch {
            for (ignored in flushSignals) {
                // A display cannot present more than one state per frame. Reduce absolute
                // stock patches before crossing into the UI instead of allowing a socket
                // burst to fill callbackFlow's channel and force a reconnect.
                delay(MARKET_FRAME_MILLIS)
                frameCoalescer.drain().forEach { send(it) }
            }
        }
        // A provider can close immediately after sending a final snapshot/delta. Do not
        // cancel the frame-delay job and silently discard that already-validated state;
        // publish the bounded pending batch before surfacing the interruption/reconnect.
        fun closeAfterDraining(failure: Throwable) {
            frameCoalescer.drain().forEach { trySend(it) }
            close(failure)
        }
        val socket = socketHttp.newWebSocket(
            Request.Builder().url(base + "/v1/market/ws").header("Cache-Control", "no-cache").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastFrame.set(System.nanoTime())
                    subscriptionJob = launch {
                        subscriptions.collect { subscription ->
                            if (!webSocket.send(subscription.toJson())) {
                                closeAfterDraining(IOException("Market subscription could not be sent."))
                            }
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        require(text.length <= MAX_MARKET_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_MARKET_BYTES) {
                            "Market frame too large."
                        }
                        val update = LiveMarketDataParser.event(JSONObject(text))
                        lastFrame.set(System.nanoTime())
                        frameCoalescer.offer(update)
                        flushSignals.trySend(Unit)
                    } catch (failure: Exception) {
                        closeAfterDraining(failure)
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closeAfterDraining(if (response != null) MarketDataHttpException(response.code) else t)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    closeAfterDraining(IOException("Market stream closed."))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closeAfterDraining(IOException("Market stream closed."))
                }
            },
        )
        val watchdog = launch {
            while (true) {
                delay(10_000)
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastFrame.get()) >= 35) {
                    closeAfterDraining(IOException("Market heartbeat timed out."))
                    break
                }
            }
        }
        awaitClose {
            subscriptionJob?.cancel()
            watchdog.cancel()
            flushJob.cancel()
            flushSignals.close()
            socket.cancel()
        }
    }

    private suspend fun request(path: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = finiteHttp.newCall(Request.Builder().url(base + path)
            .header("Accept", "application/json").header("Cache-Control", "no-cache").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = response.use {
                        if (it.code != 200) throw MarketDataHttpException(it.code)
                        require(it.header("Content-Type")?.substringBefore(';') == "application/json") { "Unexpected market data format." }
                        val bytes = requireNotNull(it.body).byteStream().use { stream ->
                            val buffer = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val count = stream.read(chunk)
                                if (count < 0) break
                                require(buffer.size() + count <= MAX_MARKET_BYTES) { "Market data response too large." }
                                buffer.write(chunk, 0, count)
                            }
                            buffer.toByteArray()
                        }
                        JSONObject(String(bytes, Charsets.UTF_8))
                    }
                    if (continuation.isActive) continuation.resume(json)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }
}

/**
 * Reduces only consecutive absolute deltas within one display frame. Snapshot barriers,
 * session changes, out-of-order revisions, and remove-then-reinsert ordering remain explicit.
 * Methods are synchronized because OkHttp supplies frames while the coroutine drains them.
 */
internal class MarketStreamFrameCoalescer {
    private val pending = mutableListOf<MarketStreamEvent>()

    @Synchronized
    fun offer(event: MarketStreamEvent) {
        val tail = pending.lastOrNull()
        if (event is MarketStreamEvent.Snapshot && tail is MarketStreamEvent.Snapshot &&
            event.sessionId == tail.sessionId
        ) {
            // A newer same-session catalog is authoritative; an older duplicate cannot
            // roll it back and does not need to occupy the bounded pending queue.
            if (event.revision > tail.revision) pending[pending.lastIndex] = event
            return
        }
        val replacement = when {
            event is MarketStreamEvent.Delta && tail is MarketStreamEvent.Delta -> merge(tail, event)
            event is MarketStreamEvent.Heartbeat && tail is MarketStreamEvent.Heartbeat -> event
            event is MarketStreamEvent.Status && tail is MarketStreamEvent.Status -> event
            event is MarketStreamEvent.Chart && tail is MarketStreamEvent.Chart &&
                event.sessionId == tail.sessionId && event.chart.stockId == tail.chart.stockId &&
                event.chart.range == tail.chart.range && event.revision > tail.revision -> event
            else -> null
        }
        if (replacement != null) {
            pending[pending.lastIndex] = replacement
        } else {
            check(pending.size < MAX_PENDING_MARKET_EVENTS) { "Market frame queue exceeded its safety bound." }
            pending += event
        }
    }

    @Synchronized
    fun drain(): List<MarketStreamEvent> = pending.toList().also { pending.clear() }

    private fun merge(
        first: MarketStreamEvent.Delta,
        next: MarketStreamEvent.Delta,
    ): MarketStreamEvent.Delta? {
        if (first.sessionId != next.sessionId || next.revision <= first.revision) return null
        val nextUpserts = next.instruments.mapTo(hashSetOf()) { it.stock.id }
        // OrderedMarketCatalog appends an ID that is removed and then reinserted. A single
        // patch cannot represent both operations without changing that catalog order.
        if (first.removedIds.any(nextUpserts::contains)) return null

        val instruments = linkedMapOf<StockId, LiveInstrument>()
        val removed = linkedSetOf<StockId>()
        fun apply(delta: MarketStreamEvent.Delta) {
            delta.removedIds.forEach { id -> instruments.remove(id); removed += id }
            delta.instruments.forEach { row -> removed.remove(row.stock.id); instruments[row.stock.id] = row }
        }
        apply(first)
        apply(next)
        return MarketStreamEvent.Delta(
            instruments = instruments.values.toList(),
            providers = next.providers,
            removedIds = removed,
            receivedAt = next.receivedAt,
            sessionId = next.sessionId,
            revision = next.revision,
        )
    }
}

/** Identity and units are validated before a response can enter the stock repository. */
object LiveMarketDataParser {
    fun catalog(source: JSONObject): LiveCatalog {
        require(source.getInt("schemaVersion") == 1 && source.getString("mode") == "live-read-only")
        val instruments = instruments(source.getJSONArray("stocks"))
        return LiveCatalog(instruments, providers(source.getJSONArray("providers")), Instant.parse(source.getString("receivedAt")))
    }

    private fun instruments(rows: JSONArray): List<LiveInstrument> {
        require(rows.length() <= 5_000) { "Catalog exceeds supported instrument count." }
        val instruments = rows.objects().map(::instrument)
        require(instruments.map { it.stock.id }.distinct().size == instruments.size)
        return instruments
    }

    fun instrument(row: JSONObject): LiveInstrument {
            val provider = row.getString("provider")
            require(provider in supportedProviders)
            val id = StockId(row.getString("id"))
            require(id.value == "$provider:${row.getString("providerAssetId")}")
            require(!row.getJSONObject("trading").getBoolean("enabled"))
            val quote = row.getJSONObject("quote")
            val marketState = row.optJSONObject("marketState")
            val currency = quote.getString("currency")
            require(currency in setOf("USD", "USDC"))
            val shareReference = quote.getString("basis") == "underlying_share_reference"
            if (shareReference) {
                require(provider == "backed" && ((currency == "USDC" && quote.getString("currencyBasis") == "market_symbol") ||
                    (currency == "USD" && quote.getString("currencyBasis") == "underlying_metadata"))) {
                    "Underlying share references must retain their source unit and explicit basis."
                }
            }
            val marketActivity = activity(row, provider)
            require(marketActivity.currencyCode == currency) { "Quote and activity units must agree." }
            val price = quote.decimalOrNull("price")
            val logo = row.stringOrNull("logoUrl")?.takeIf { URI(it).scheme == "https" }
            val description = row.stringOrNull("description")?.also {
                require(it.length <= 20_000) { "Stock description exceeds the supported length." }
            }?.takeIf(String::isNotBlank)
            val informationUrl = row.stringOrNull("informationUrl")?.let(::validatedInformationUrl)
            return LiveInstrument(
                stock = Stock(
                    id = id,
                    symbol = row.getString("symbol"),
                    name = row.getString("name"),
                    logo = logo?.let(::StockLogoReference),
                    quote = StockQuote(
                        price, currency,
                        quote.decimalOrNull("changeAmount"),
                        quote.decimalOrNull("changePercent"),
                        quote.stringOrNull("asOf")?.let(Instant::parse),
                    ),
                    statistics = statistics(row),
                    description = description,
                    activity = marketActivity,
                    informationUrl = informationUrl,
                ),
                provider = provider,
                providerLabel = row.getString("providerLabel").also {
                    require(it.isNotBlank() && it.length <= 120) { "Invalid provider label." }
                },
                volume24h = row.decimalOrNull("volume24h"),
                quoteReceivedAt = quote.stringOrNull("receivedAt")?.let(Instant::parse),
                quoteBasis = quote.getString("basis"),
                currencyBasis = quote.getString("currencyBasis"),
                marketState = marketState?.stringOrNull("status")?.also {
                    require(it in setOf("open", "closed", "halted", "unknown"))
                },
                marketStateReason = marketState?.stringOrNull("label"),
            )
    }

    private fun providers(rows: JSONArray): List<LiveProviderStatus> {
        val providers = rows.objects().map { provider ->
            val id = provider.getString("id")
            val status = provider.getString("status")
            require(id in supportedProviders && status in setOf("ok", "stale", "unavailable"))
            LiveProviderStatus(id, status)
        }
        require(providers.size == supportedProviders.size && providers.map { it.id }.toSet() == supportedProviders)
        return providers
    }

    fun event(source: JSONObject): MarketStreamEvent = when (source.getString("type")) {
        "snapshot" -> MarketStreamEvent.Snapshot(catalog(source.getJSONObject("catalog")), session(source), revision(source))
        "delta" -> {
            val removed = source.getJSONArray("removedIds")
            require(removed.length() <= 5_000)
            val removedIds = (0 until removed.length()).map { StockId(removed.getString(it)) }
            require(removedIds.distinct().size == removedIds.size)
            val rows = instruments(source.getJSONArray("stocks"))
            require(rows.none { it.stock.id in removedIds })
            MarketStreamEvent.Delta(rows, providers(source.getJSONArray("providers")), removedIds.toSet(),
                Instant.parse(source.getString("receivedAt")), session(source), revision(source))
        }
        "chart" -> {
            val chart = source.getJSONObject("chart")
            MarketStreamEvent.Chart(chart(chart, StockId(chart.getString("stockId")), ChartRange.valueOf(chart.getString("range"))),
                session(source), revision(source))
        }
        "heartbeat" -> MarketStreamEvent.Heartbeat(Instant.parse(source.getString("serverTime")))
        "status" -> {
            val state = source.getString("state")
            require(state in setOf("loading", "reconnecting"))
            MarketStreamEvent.Status(state, source.stringOrNull("message"))
        }
        else -> throw IllegalArgumentException("Unknown market frame.")
    }

    private fun session(source: JSONObject): String = source.getString("sessionId").also { require(it.isNotBlank() && it.length <= 256) }
    private fun revision(source: JSONObject): Long {
        val raw = source.get("revision")
        require(raw is Number && raw.toString().matches(Regex("[0-9]+"))) { "Revision must be a nonnegative integer." }
        return raw.toString().toLong().also { require(it >= 0) }
    }

    private fun activity(row: JSONObject, provider: String): StockMarketActivity {
        val source = row.getJSONObject("activity")
        val shareReference = row.getJSONObject("quote").getString("basis") == "underlying_share_reference"
        val expectedSources = when {
            provider == "backpack" -> setOf("Backpack")
            shareReference -> setOf("Backpack", "Yahoo")
            else -> setOf("Jupiter") // Backed and PreStocks Solana-token observations.
        }
        require(source.getString("source") in expectedSources) {
            "Activity provenance must match the token or explicitly labelled underlying-share reference."
        }
        return StockMarketActivity(
            currencyCode = source.getString("currency"),
            source = source.getString("source"),
            scope = source.getString("scope"),
            volume24h = source.decimalOrNull("volume24h"),
            netVolume24h = source.decimalOrNull("netVolume24h"),
            receivedAt = source.stringOrNull("receivedAt")?.let(Instant::parse),
            updatedAt = source.stringOrNull("updatedAt")?.let(Instant::parse),
            volumeReason = source.stringOrNull("volumeReason"),
            netVolumeReason = source.stringOrNull("netVolumeReason"),
        )
    }

    private fun statistics(row: JSONObject): StockStatistics {
        val source = row.getJSONObject("statistics")
        require(source.getString("scope") == "solana_token")
        val network = source.stringOrNull("network")
        val mint = source.stringOrNull("mint")
        if (mint != null) {
            require(network == "solana")
            require(row.getJSONArray("deployments").objects().any {
                it.getString("network").equals("solana", ignoreCase = true) && it.getString("address") == mint
            }) { "Analytics mint must match the issuer's deployment." }
        }
        val metrics = StockMetricKey.entries.associateWith { key ->
            val metric = source.getJSONObject(key.wireName)
            val value = metric.decimalOrNull("value")
            require(metric.getString("status") == if (value == null) "unavailable" else "available")
            val provider = metric.stringOrNull("source")
            require(provider == null || provider == "Jupiter" || provider == "DEX Screener")
            StockMetricData(
                value = value,
                unit = metric.getString("unit"),
                source = provider,
                basis = metric.getString("basis"),
                receivedAt = metric.stringOrNull("receivedAt")?.let(Instant::parse),
                reason = metric.stringOrNull("reason"),
            )
        }
        return StockStatisticsReference(network, mint, metrics,
            source.stringOrNull("updatedAt")?.let(Instant::parse)).toStatistics()
    }

    fun chart(source: JSONObject, id: StockId, range: ChartRange): LiveChart {
        require(source.getString("stockId") == id.value && source.getString("range") == range.name)
        val status = source.getString("status")
        require(status in setOf("ok", "unavailable", "unsupported"))
        val currency = source.getString("currency")
        require(currency in setOf("USD", "USDC"))
        val points = source.getJSONArray("points").objects().map {
            StockPricePoint(Instant.parse(it.getString("timestamp")), requireNotNull(it.decimalOrNull("price")))
        }
        require(points.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
        require(status == "ok" || points.isEmpty())
        return LiveChart(id, range, status, currency, points, source.stringOrNull("basis"), source.stringOrNull("statusReason"))
    }

    private val supportedProviders = setOf("backed", "backpack", "prestocks")
}

private fun JSONObject.decimalOrNull(key: String): BigDecimal? {
    if (isNull(key)) return null
    val value = get(key)
    require(value is String) { "Decimal values must be transported as strings." }
    return BigDecimal(value)
}

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

private fun validatedInformationUrl(value: String): String {
    require(value.length <= 2_048) { "Stock information URL exceeds the supported length." }
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Stock information URL must be an HTTPS provider page."
    }
    return value
}

private const val MAX_MARKET_BYTES = 16 * 1024 * 1024
private const val MARKET_FRAME_MILLIS = 16L
private const val MAX_PENDING_MARKET_EVENTS = 256

private fun defaultMarketHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    // Read-only GETs may try another resolved IP when one route fails. The outer stream owns backoff.
    .retryOnConnectionFailure(true)
    .build()
