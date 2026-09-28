package com.elevencapital.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Public issuer metadata and original observations only; never an account, balance, or order cache. */
class RobinhoodFeaturedCatalogCache(
    directory: File,
    backendUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val backend = validatedRobinhoodBackend(backendUrl)
    private val backendKey = MessageDigest.getInstance("SHA-256").digest(backend.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val file = File(directory, "robinhood-featured-$backendKey.json")
    private val mutex = Mutex()

    suspend fun read(): JSONObject? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withLock null
                val output = ByteArrayOutputStream()
                file.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = stream.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= MAX_BYTES)
                        output.write(buffer, 0, size)
                    }
                }
                val stored = JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                require(stored.getInt("cacheVersion") == 1 && stored.getString("backendKey") == backendKey)
                val snapshot = boundedRobinhoodFeaturedCatalog(stored.getJSONObject("catalog"))
                val age = now() - LiveMarketDataParser.catalog(snapshot).receivedAt.toEpochMilli()
                snapshot.takeIf { age in 0 until MAX_METADATA_AGE_MILLIS }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

    suspend fun write(source: JSONObject): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            var temporary: File? = null
            try {
                currentCoroutineContext().ensureActive()
                val snapshot = boundedRobinhoodFeaturedCatalog(source)
                val bytes = JSONObject().put("cacheVersion", 1).put("backendKey", backendKey)
                    .put("catalog", snapshot).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                val parent = requireNotNull(file.parentFile)
                require(parent.isDirectory || parent.mkdirs())
                temporary = File.createTempFile("robinhood-featured-", ".tmp", parent)
                temporary.writeBytes(bytes)
                currentCoroutineContext().ensureActive()
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A cache failure cannot prevent public market loading. */ }
            finally { temporary?.delete() }
        }
    }

    private companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_METADATA_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }
}

internal fun validatedRobinhoodBackend(value: String): String = value.trimEnd('/').also { backend ->
    val uri = URI(backend)
    require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
}

/** Keep the exact public wire fields so new issuer provenance survives process restarts. */
internal fun boundedRobinhoodFeaturedCatalog(source: JSONObject): JSONObject {
    val parsed = LiveMarketDataParser.catalog(source)
    val rows = source.getJSONArray("stocks")
    val eligible = parsed.instruments.filter { it.provider == "robinhood" && it.stock.symbol in RobinhoodFeaturedSymbols }
    require(eligible.map { it.stock.symbol }.distinct().size == eligible.size)
    val identities = eligible.associateBy { it.stock.symbol }
    require(identities.isNotEmpty())
    val selected = JSONArray()
    for (symbol in RobinhoodFeaturedSymbols) {
        val instrument = identities[symbol] ?: continue
        val row = (0 until rows.length()).asSequence().map(rows::getJSONObject)
            .single { it.getString("id") == instrument.stock.id.value }
        selected.put(JSONObject().also { public ->
            PUBLIC_STOCK_FIELDS.forEach { key -> if (row.has(key)) {
                val value = row.get(key)
                public.put(key, when {
                    value == JSONObject.NULL -> value
                    key == "deployments" -> JSONArray().also { clean ->
                        val deployments = row.getJSONArray(key)
                        for (index in 0 until deployments.length()) clean.put(publicObject(
                            deployments.getJSONObject(index), DEPLOYMENT_FIELDS))
                    }
                    key == "statistics" -> publicObject(row.getJSONObject(key), STATISTICS_FIELDS).also { clean ->
                        METRIC_KEYS.forEach { metric -> clean.put(metric,
                            publicObject(row.getJSONObject(key).getJSONObject(metric), METRIC_FIELDS)) }
                    }
                    key in NESTED_FIELDS -> publicObject(row.getJSONObject(key), NESTED_FIELDS.getValue(key))
                    else -> value
                })
            } }
        })
    }
    return JSONObject().put("schemaVersion", 1).put("mode", "live-read-only")
        .put("receivedAt", source.getString("receivedAt"))
        .put("providers", JSONArray().also { clean ->
            val providers = source.getJSONArray("providers")
            for (index in 0 until providers.length()) clean.put(publicObject(providers.getJSONObject(index),
                setOf("id", "status", "message")))
        }).put("stocks", selected)
        .also { LiveMarketDataParser.catalog(it) }
}

private fun publicObject(source: JSONObject, keys: Set<String>): JSONObject = JSONObject().also { clean ->
    keys.forEach { key -> if (source.has(key)) clean.put(key, source.get(key)) }
}

private val PUBLIC_STOCK_FIELDS = setOf(
    "id", "provider", "providerLabel", "providerAssetId", "symbol", "name", "logoUrl", "description",
    "informationUrl", "underlying", "quote", "volume24h", "volume24hBasis", "statistics", "activity",
    "deployments", "trading", "marketState", "robinhoodReference",
)
private val DEPLOYMENT_FIELDS = setOf("network", "address", "chainId", "decimals", "depositEnabled", "withdrawEnabled", "explorerUrl")
private val STATISTICS_FIELDS = setOf("scope", "network", "mint", "updatedAt")
private val METRIC_KEYS = setOf("marketCapitalization", "liquidity", "holderCount", "organicScore")
private val METRIC_FIELDS = setOf("value", "unit", "source", "status", "basis", "receivedAt", "reason")
private val NESTED_FIELDS = mapOf(
    "quote" to setOf("price", "currency", "currencyBasis", "changeAmount", "changePercent", "asOf", "receivedAt", "basis"),
    "underlying" to setOf("symbol", "isin", "cusip", "listingCountry", "currency"),
    "activity" to setOf("currency", "source", "scope", "volume24h", "netVolume24h", "receivedAt", "updatedAt", "volumeReason", "netVolumeReason"),
    "trading" to setOf("enabled", "reason"),
    "marketState" to setOf("status", "label", "nextChangeAt"),
    "robinhoodReference" to setOf("basis", "currency", "bid", "ask", "dailyHigh", "dailyLow", "dailyTradingVolume", "volumeUnit",
        "currentMultiplier", "tokenDecimals", "asOf", "receivedAt", "isTradingHalt"),
)
