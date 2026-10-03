package com.elevencapital.app.data

import com.elevencapital.app.wallet.isSolanaPublicKey
import com.elevencapital.core.stock.ChartRange
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** A Solana token market row. Never treat its presence as proof of wallet ownership. */
data class MeteoraMarketToken(
    val mintAddress: String,
    val poolAddress: String,
    val name: String,
    val symbol: String,
    val priceUsd: BigDecimal?,
    val volume24hUsd: BigDecimal?,
    val imageUrl: String? = null,
    val change24hPercent: BigDecimal? = null,
    val marketCapUsd: BigDecimal? = null,
    val liquidityUsd: BigDecimal? = null,
)

data class MeteoraTokenChartPoint(val timestampSeconds: Long, val priceUsd: BigDecimal)

data class MeteoraTokenChart(val points: List<MeteoraTokenChartPoint>)

/** Meteora's public DAMM v2 feed contains pools that graduated from its DBC launchpad. */
class MeteoraTokenFeed internal constructor(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val endpoint: String = METEORA_DAMM_V2_FEED,
    private val geckoEndpoint: String? = GECKO_SOLANA_API,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class CachedChart(val loadedAtNanos: Long, val chart: MeteoraTokenChart)
    private val chartCache = object : LinkedHashMap<String, CachedChart>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChart>?) = size > 32
    }
    // Count the holder and all waiters so a key is removed only after everyone releases it.
    private data class ActiveChartRequest(val mutex: Mutex = Mutex(), var callers: Int = 0)
    private val chartRequestLocks = mutableMapOf<String, ActiveChartRequest>()
    private data class CachedEnrichment(
        val mintAddress: String,
        val imageUrl: String?,
        val change24hPercent: BigDecimal?,
        val loadedAtNanos: Long,
    )
    private val enrichmentCache = object : LinkedHashMap<String, CachedEnrichment>(40, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEnrichment>?) = size > 40
    }
    private data class CachedDescription(val value: String?, val loadedAtNanos: Long)
    private val descriptionCache = object : LinkedHashMap<String, CachedDescription>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDescription>?) = size > 20
    }
    private val selectedEnrichmentAttempts = object : LinkedHashMap<String, Long>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 20
    }
    @Volatile private var lastGeckoRateLimitNanos: Long? = null

    suspend fun topTokens(): List<MeteoraMarketToken> = withContext(Dispatchers.IO) {
        val baseRows = parseMeteoraTokenFeed(readJson(endpoint, MAX_METEORA_BYTES, "Meteora feed"))
        val gecko = geckoEndpoint ?: return@withContext baseRows
        // Artwork and price changes are supplementary. A rate-limited metadata service must not
        // hide live prices and volume already returned by Meteora itself.
        val byPool = mutableMapOf<String, GeckoPoolData>()
        for (chunk in baseRows.chunked(GECKO_BATCH_SIZE)) {
            if (inGeckoBackoff()) break
            try {
                val addresses = chunk.joinToString(",") { it.poolAddress }
                val url = "${gecko.trimEnd('/')}/pools/multi/$addresses?include=base_token,quote_token"
                byPool += parseGeckoPoolBatch(readJson(url, MAX_GECKO_BYTES, "Token market data"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rateLimited: HttpStatusException) {
                if (rateLimited.statusCode == 429) {
                    backoffGecko()
                    break
                }
            } catch (_: Exception) {
                // Keep the previous successful batches, and use Meteora values for this one.
            }
        }
        baseRows.map { row ->
            val fresh = byPool[row.poolAddress]
            if (fresh != null) {
                fresh.enrich(row).also { enriched ->
                    synchronized(enrichmentCache) {
                        enrichmentCache[row.poolAddress] = CachedEnrichment(
                            row.mintAddress, enriched.imageUrl, enriched.change24hPercent,
                            nowNanos(),
                        )
                    }
                }
            } else {
                withCachedArtworkAndChange(row)
            }
        }
    }

    /** Refreshes a selected pool even when it is no longer in the top-token discovery page. */
    suspend fun currentToken(selected: MeteoraMarketToken): MeteoraMarketToken = withContext(Dispatchers.IO) {
        if (!isSolanaPublicKey(selected.poolAddress) || !isSolanaPublicKey(selected.mintAddress)) {
            throw IOException("Token address is invalid.")
        }
        val poolEndpoint = endpoint.substringBefore('?').trimEnd('/')
        val json = readJson("$poolEndpoint/${selected.poolAddress}", MAX_METEORA_BYTES, "Token market data")
        val current = parseExactMeteoraPool(json, selected)
        val cached = refreshSelectedEnrichment(current)
        // The selected row was created from our validated CDN metadata. Keep its artwork if
        // the selected pool has since rotated out of the bounded discovery cache.
        if (cached.imageUrl == null) cached.copy(imageUrl = selected.imageUrl?.let(::safeTokenImageUrl))
        else cached
    }

    private fun refreshSelectedEnrichment(row: MeteoraMarketToken): MeteoraMarketToken {
        val fallback = withCachedArtworkAndChange(row)
        val gecko = geckoEndpoint ?: return fallback
        if (inGeckoBackoff()) return fallback
        val now = nowNanos()
        val cached = synchronized(enrichmentCache) { enrichmentCache[row.poolAddress] }
        if (cached?.mintAddress == row.mintAddress &&
            now - cached.loadedAtNanos in 0L until SELECTED_ENRICHMENT_INTERVAL_NANOS) return fallback
        val lastAttempt = synchronized(selectedEnrichmentAttempts) { selectedEnrichmentAttempts[row.poolAddress] }
        if (lastAttempt != null && now - lastAttempt in 0L until SELECTED_ENRICHMENT_INTERVAL_NANOS) {
            return fallback
        }
        synchronized(selectedEnrichmentAttempts) { selectedEnrichmentAttempts[row.poolAddress] = now }
        try {
            val url = "${gecko.trimEnd('/')}/pools/multi/${row.poolAddress}?include=base_token,quote_token"
            val data = parseGeckoPoolBatch(readJson(url, MAX_GECKO_BYTES, "Token market data"))[row.poolAddress]
                ?: return fallback
            if (row.mintAddress != data.baseMint && row.mintAddress != data.quoteMint) return fallback
            val enriched = data.enrich(row)
            synchronized(enrichmentCache) {
                enrichmentCache[row.poolAddress] = CachedEnrichment(
                    row.mintAddress, enriched.imageUrl, enriched.change24hPercent, nowNanos())
            }
            return enriched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rateLimited: HttpStatusException) {
            if (rateLimited.statusCode == 429) backoffGecko()
        } catch (_: Exception) {
            // Meteora's current figures and previously validated artwork remain available.
        }
        return fallback
    }

    /** Optional, plain-text About copy from the exact Solana mint's public token metadata. */
    suspend fun descriptionFor(token: MeteoraMarketToken): String? = withContext(Dispatchers.IO) {
        if (!isSolanaPublicKey(token.mintAddress)) return@withContext null
        val gecko = geckoEndpoint ?: return@withContext null
        val cached = synchronized(descriptionCache) { descriptionCache[token.mintAddress] }
        val age = cached?.let { nowNanos() - it.loadedAtNanos }
        if (cached != null && age != null && age in 0L..DESCRIPTION_CACHE_NANOS) {
            return@withContext cached.value
        }
        val stale = cached?.value.takeIf { age != null && age in 0L..DESCRIPTION_STALE_NANOS }
        if (inGeckoBackoff()) return@withContext stale
        try {
            val url = "${gecko.trimEnd('/')}/tokens/${token.mintAddress}/info"
            val value = parseMeteoraTokenDescription(
                readJson(url, MAX_GECKO_BYTES, "Token description"), token.mintAddress)
            synchronized(descriptionCache) {
                descriptionCache[token.mintAddress] = CachedDescription(value, nowNanos())
            }
            value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rateLimited: HttpStatusException) {
            if (rateLimited.statusCode == 429) backoffGecko()
            stale
        } catch (_: Exception) {
            stale
        }
    }

    private fun withCachedArtworkAndChange(row: MeteoraMarketToken): MeteoraMarketToken {
        // On an API outage, retain only artwork and briefly-valid % change. Current price,
        // volume, market cap and TVL always come from the fresh Meteora response.
        val cached = synchronized(enrichmentCache) { enrichmentCache[row.poolAddress] }
        val age = cached?.let { nowNanos() - it.loadedAtNanos }
        return if (cached != null && cached.mintAddress == row.mintAddress &&
            age != null && age in 0L..IMAGE_CACHE_NANOS) {
            row.copy(
                imageUrl = cached.imageUrl,
                change24hPercent = cached.change24hPercent.takeIf { age <= CHANGE_CACHE_NANOS },
            )
        } else row
    }

    /** USD close prices from actual Solana pool candles; never synthesizes a chart from a quote. */
    suspend fun chartFor(
        token: MeteoraMarketToken,
        range: ChartRange = ChartRange.ONE_DAY,
    ): MeteoraTokenChart = withContext(Dispatchers.IO) {
        if (!isSolanaPublicKey(token.poolAddress) || !isSolanaPublicKey(token.mintAddress)) {
            throw IOException("Token address is invalid.")
        }
        val gecko = geckoEndpoint ?: throw IOException("Token chart is unavailable.")
        val cacheKey = "${token.poolAddress}:${token.mintAddress}:$range"
        // A detail tap may race the startup prefetch. Share that pool's request instead of
        // sending a second candle call and exhausting the public chart API's rate limit.
        val request = synchronized(chartRequestLocks) {
            chartRequestLocks.getOrPut(cacheKey) { ActiveChartRequest() }.also { it.callers++ }
        }
        try {
            request.mutex.withLock {
                val cached = synchronized(chartCache) { chartCache[cacheKey] }
                val cacheAge = cached?.let { nowNanos() - it.loadedAtNanos }
                if (cached != null && cacheAge != null && cacheAge in 0L..CHART_CACHE_NANOS) {
                    return@withLock cached.chart
                }
                val staleChart = cached?.chart.takeIf {
                    cacheAge != null && cacheAge in 0L..CHART_STALE_NANOS
                }
                if (inGeckoBackoff()) return@withLock staleChart
                    ?: throw IOException("Token chart is temporarily unavailable.")
                val spec = chartSpec(range)
                val url = "${gecko.trimEnd('/')}/pools/${token.poolAddress}/ohlcv/${spec.timeframe}" +
                    "?aggregate=${spec.aggregate}&limit=${spec.limit}&currency=usd&token=${token.mintAddress}"
                val now = nowEpochSeconds()
                val chart = try {
                    parseMeteoraTokenChart(
                        readJson(url, MAX_GECKO_BYTES, "Token chart"),
                        cutoffSeconds = spec.cutoff(now),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (rateLimited: HttpStatusException) {
                    if (rateLimited.statusCode == 429) backoffGecko()
                    return@withLock staleChart ?: throw rateLimited
                } catch (error: Exception) {
                    return@withLock staleChart ?: throw error
                }
                if (chart.points.isNotEmpty()) {
                    synchronized(chartCache) { chartCache[cacheKey] = CachedChart(nowNanos(), chart) }
                }
                chart
            }
        } finally {
            synchronized(chartRequestLocks) {
                request.callers--
                if (request.callers == 0 && chartRequestLocks[cacheKey] === request) {
                    chartRequestLocks.remove(cacheKey)
                }
            }
        }
    }

    private fun inGeckoBackoff(): Boolean = lastGeckoRateLimitNanos?.let { last ->
        nowNanos() - last in 0L until GECKO_BACKOFF_NANOS
    } == true

    private fun backoffGecko() {
        lastGeckoRateLimitNanos = nowNanos()
    }

    private fun readJson(url: String, maxBytes: Int, label: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(label, response.code)
            val body = response.body ?: throw IOException("$label returned no data.")
            if (body.contentLength() > maxBytes) throw IOException("$label is too large.")
            val output = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxBytes) throw IOException("$label is too large.")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}

private const val MAX_METEORA_BYTES = 1_048_576
private const val MAX_GECKO_BYTES = 2_097_152
private const val GECKO_BATCH_SIZE = 20 // within the public /multi limit
internal const val METEORA_LIST_LIMIT = 20
private val CHART_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30)
private val CHART_STALE_NANOS = TimeUnit.MINUTES.toNanos(10)
private val IMAGE_CACHE_NANOS = TimeUnit.HOURS.toNanos(1)
private val CHANGE_CACHE_NANOS = TimeUnit.MINUTES.toNanos(2)
private val SELECTED_ENRICHMENT_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1)
private val DESCRIPTION_CACHE_NANOS = TimeUnit.MINUTES.toNanos(30)
private val DESCRIPTION_STALE_NANOS = TimeUnit.HOURS.toNanos(1)
private val GECKO_BACKOFF_NANOS = TimeUnit.MINUTES.toNanos(1)
private const val METEORA_DAMM_V2_FEED =
    "https://damm-v2.datapi.meteora.ag/pools?page=1&page_size=100&sort_by=volume_24h:desc"
private const val GECKO_SOLANA_API = "https://api.geckoterminal.com/api/v2/networks/solana"
private class HttpStatusException(label: String, val statusCode: Int) :
    IOException("$label returned HTTP $statusCode.")
private val METEORA_QUOTES = setOf(
    "So11111111111111111111111111111111111111112", // wrapped SOL
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
)

internal fun parseMeteoraTokenFeed(json: String): List<MeteoraMarketToken> {
    val pools = JSONObject(json).optJSONArray("data") ?: throw IOException("Meteora feed is malformed.")
    val byMint = linkedMapOf<String, MeteoraMarketToken>()
    for (index in 0 until pools.length()) {
        val pool = pools.optJSONObject(index) ?: continue
        if (pool.optString("launchpad") != "met-dbc" || pool.opt("is_blacklisted") != false) continue
        val poolAddress = pool.optString("address")
        if (!isSolanaPublicKey(poolAddress)) continue
        val x = pool.optJSONObject("token_x") ?: continue
        val y = pool.optJSONObject("token_y") ?: continue
        val token = when {
            y.optString("address") in METEORA_QUOTES -> x
            x.optString("address") in METEORA_QUOTES -> y
            else -> continue
        }
        val mint = token.optString("address")
        val name = token.optString("name").trim()
        val symbol = token.optString("symbol").trim()
        if (!isSolanaPublicKey(mint) || mint in METEORA_QUOTES ||
            name.isEmpty() || name.length > 64 || symbol.isEmpty() || symbol.length > 16) continue
        val row = MeteoraMarketToken(
            mintAddress = mint,
            poolAddress = poolAddress,
            name = name,
            symbol = symbol,
            priceUsd = token.optPositiveDecimal("price"),
            volume24hUsd = pool.optJSONObject("volume")?.optPositiveDecimal("24h"),
            marketCapUsd = token.optPositiveDecimal("market_cap"),
            liquidityUsd = pool.optPositiveDecimal("tvl"),
        )
        val prior = byMint[mint]
        if (prior == null || (row.volume24hUsd ?: BigDecimal.ZERO) >
            (prior.volume24hUsd ?: BigDecimal.ZERO)) byMint[mint] = row
    }
    return byMint.values.sortedWith(compareByDescending<MeteoraMarketToken> {
        it.volume24hUsd ?: BigDecimal.ZERO
    }.thenBy { it.mintAddress }).take(METEORA_LIST_LIMIT)
}

/** Applies the same discovery safety checks to an exact-pool response. */
internal fun parseExactMeteoraPool(json: String, selected: MeteoraMarketToken): MeteoraMarketToken {
    val pool = runCatching { JSONObject(json) }.getOrElse {
        throw IOException("Token market data is malformed.", it)
    }
    if (pool.optString("address") != selected.poolAddress) {
        throw IOException("Token pool does not match the selected token.")
    }
    val document = JSONObject().put("data", JSONArray().put(pool)).toString()
    return parseMeteoraTokenFeed(document).singleOrNull { it.mintAddress == selected.mintAddress }
        ?: throw IOException("Token mint does not match the selected pool.")
}

internal fun parseMeteoraTokenDescription(json: String, expectedMint: String): String? {
    val data = JSONObject(json).optJSONObject("data") ?: throw IOException("Token description is malformed.")
    if (data.optString("type") != "token") throw IOException("Token description is malformed.")
    val id = data.optString("id")
    if (id.isNotEmpty() && id != "solana_$expectedMint") {
        throw IOException("Token description does not match the selected mint.")
    }
    val attributes = data.optJSONObject("attributes") ?: throw IOException("Token description is malformed.")
    val address = attributes.optString("address")
    if (address.isNotEmpty() && address != expectedMint) {
        throw IOException("Token description does not match the selected mint.")
    }
    val raw = attributes.opt("description") as? String ?: return null
    return sanitizeTokenDescription(raw)
}

private val scriptOrStyle = Regex("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)>")
private val markdownLink = Regex("(?i)\\[([^]]+)]\\((?:[a-z][a-z0-9+.-]*://|www\\.)[^)]+\\)")
private val htmlTag = Regex("<[^>]*>")
private val link = Regex("(?i)\\b(?:[a-z][a-z0-9+.-]*://|www\\.|mailto:)\\S+")
private val whitespace = Regex("\\s+")
private val beforePunctuation = Regex("\\s+([.,!?;:])")

private fun sanitizeTokenDescription(raw: String): String? = raw.take(4_096)
    .replace(scriptOrStyle, " ")
    .replace(markdownLink, "$1")
    .replace(htmlTag, " ")
    .replace(link, " ")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
    .replace(whitespace, " ")
    .replace(beforePunctuation, "$1")
    .trim()
    .take(500)
    .trim()
    .ifEmpty { null }

private fun JSONObject.optPositiveDecimal(key: String): BigDecimal? {
    val decimal = optDecimal(key) ?: return null
    return decimal.takeIf { it.signum() > 0 }
}

private fun JSONObject.optDecimal(key: String): BigDecimal? {
    val value = opt(key) ?: return null
    return runCatching { BigDecimal(value.toString()) }.getOrNull()
}

internal data class GeckoPoolData(
    val baseMint: String?,
    val quoteMint: String?,
    val imageByMint: Map<String, String>,
    val baseChange24hPercent: BigDecimal?,
) {
    fun enrich(row: MeteoraMarketToken): MeteoraMarketToken {
        return row.copy(
            imageUrl = imageByMint[row.mintAddress],
            // GeckoTerminal's pool percentage is for the base token. For reversed pools, leave
            // the change unavailable rather than mislabeling the quote token's performance.
            change24hPercent = baseChange24hPercent.takeIf { row.mintAddress == baseMint },
        )
    }
}

/** Joins GeckoTerminal artwork/market data to the exact Meteora pool and token mint. */
internal fun parseGeckoPoolBatch(json: String): Map<String, GeckoPoolData> {
    val document = JSONObject(json)
    val pools = document.optJSONArray("data") ?: throw IOException("Token market data is malformed.")
    val images = mutableMapOf<String, String>()
    document.optJSONArray("included")?.let { included ->
        for (index in 0 until included.length()) {
            val item = included.optJSONObject(index) ?: continue
            if (item.optString("type") != "token") continue
            val attributes = item.optJSONObject("attributes") ?: continue
            val mint = attributes.optString("address")
            val url = safeTokenImageUrl(attributes.optString("image_url"))
            if (isSolanaPublicKey(mint) && url != null) images[mint] = url
        }
    }
    val byPool = mutableMapOf<String, GeckoPoolData>()
    for (index in 0 until pools.length()) {
        val pool = pools.optJSONObject(index) ?: continue
        if (pool.optString("type") != "pool") continue
        val attributes = pool.optJSONObject("attributes") ?: continue
        val address = attributes.optString("address")
        if (!isSolanaPublicKey(address)) continue
        val relationships = pool.optJSONObject("relationships")
        val base = relationships?.optJSONObject("base_token")?.optJSONObject("data")
            ?.optString("id")?.removePrefix("solana_")?.takeIf(::isSolanaPublicKey)
        val quote = relationships?.optJSONObject("quote_token")?.optJSONObject("data")
            ?.optString("id")?.removePrefix("solana_")?.takeIf(::isSolanaPublicKey)
        byPool[address] = GeckoPoolData(
            baseMint = base,
            quoteMint = quote,
            imageByMint = images,
            baseChange24hPercent = attributes.optJSONObject("price_change_percentage")?.optDecimal("h24"),
        )
    }
    return byPool
}

internal fun safeTokenImageUrl(raw: String): String? {
    if (raw.length !in 10..2048) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
    return raw.takeIf { host in setOf(
        "assets.geckoterminal.com", "assets.coingecko.com", "coin-images.coingecko.com",
    ) }
}

private data class ChartSpec(
    val timeframe: String,
    val aggregate: Int,
    val limit: Int,
    val cutoff: (Long) -> Long,
)

private fun chartSpec(range: ChartRange): ChartSpec = when (range) {
    ChartRange.ONE_HOUR -> ChartSpec("minute", 1, 65) { it - 3_600 }
    ChartRange.ONE_DAY -> ChartSpec("minute", 15, 100) { it - 86_400 }
    ChartRange.ONE_WEEK -> ChartSpec("hour", 4, 45) { it - 7 * 86_400 }
    ChartRange.ONE_MONTH -> ChartSpec("day", 1, 33) { it - 30 * 86_400 }
    ChartRange.YEAR_TO_DATE -> ChartSpec("day", 1, 366) {
        Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).withDayOfYear(1)
            .toLocalDate().atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }
}

/** GeckoTerminal returns newest-first [timestamp, open, high, low, close, volume] candles. */
internal fun parseMeteoraTokenChart(json: String, cutoffSeconds: Long): MeteoraTokenChart {
    val candles = JSONObject(json).optJSONObject("data")?.optJSONObject("attributes")
        ?.optJSONArray("ohlcv_list") ?: throw IOException("Token chart is malformed.")
    val byTime = sortedMapOf<Long, MeteoraTokenChartPoint>()
    for (index in 0 until candles.length()) {
        val candle: JSONArray = candles.optJSONArray(index) ?: continue
        if (candle.length() < 5) continue
        val timestamp = candle.optLong(0, 0)
        val close = runCatching { BigDecimal(candle.opt(4).toString()) }.getOrNull()
        if (timestamp >= cutoffSeconds && close != null && close.signum() > 0) {
            byTime[timestamp] = MeteoraTokenChartPoint(timestamp, close)
        }
    }
    return MeteoraTokenChart(byTime.values.toList())
}
