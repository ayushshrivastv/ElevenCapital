package com.elevencapital.app.data

import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId
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

/** The original local receipt is retained when a chart is restored or saved again. */
data class CachedStartupChart(val chart: LiveChart, val receivedAtMillis: Long)

/** A bounded, endpoint-specific cache of public history. Catalogs and wallet data never enter it. */
class StartupMarketCache(
    directory: File,
    backendUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val backend = backendUrl.trimEnd('/').also { value ->
        val uri = URI(value)
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))
    }
    private val backendKey = MessageDigest.getInstance("SHA-256").digest(backend.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val file = File(directory, "startup-market-$backendKey.json")
    private val mutex = Mutex()

    suspend fun read(): List<CachedStartupChart> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withLock emptyList()
                val bytes = file.inputStream().use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val source = JSONObject(String(bytes, Charsets.UTF_8))
                require(source.getInt("schemaVersion") == 1 && source.getString("backendKey") == backendKey)
                val rows = source.getJSONArray("charts")
                require(rows.length() <= MAX_CHARTS)
                val entries = (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val rawChart = row.getJSONObject("chart")
                    val id = StockId(rawChart.getString("stockId"))
                    val range = ChartRange.valueOf(rawChart.getString("range"))
                    val entry = CachedStartupChart(LiveMarketDataParser.chart(rawChart, id, range),
                        row.getLong("receivedAtMillis"))
                    require(validChart(entry.chart))
                    entry
                }
                require(entries.map { it.chart.stockId }.distinct().size == entries.size)
                val readAt = now()
                entries.filter { fresh(it.receivedAtMillis, readAt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun write(entries: List<CachedStartupChart>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            var temporary: File? = null
            try {
                currentCoroutineContext().ensureActive()
                val writtenAt = now()
                val retained = entries.asSequence()
                    .filter { fresh(it.receivedAtMillis, writtenAt) && validChart(it.chart) }
                    .sortedByDescending { it.receivedAtMillis }
                    .distinctBy { it.chart.stockId }
                    .take(MAX_CHARTS)
                    .toList()
                val rows = JSONArray()
                for (entry in retained) {
                    val chart = entry.chart
                    val points = JSONArray()
                    for (point in chart.points) points.put(JSONObject()
                        .put("timestamp", point.timestamp.toString()).put("price", point.price.toPlainString()))
                    rows.put(JSONObject().put("receivedAtMillis", entry.receivedAtMillis)
                        .put("chart", JSONObject().put("stockId", chart.stockId.value)
                            .put("range", chart.range.name).put("status", chart.status)
                            .put("currency", chart.currency).put("basis", chart.basis)
                            .put("statusReason", chart.statusReason ?: JSONObject.NULL).put("points", points)))
                }
                val bytes = JSONObject().put("schemaVersion", 1).put("backendKey", backendKey)
                    .put("charts", rows).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                val parent = requireNotNull(file.parentFile)
                require(parent.isDirectory || parent.mkdirs())
                temporary = File.createTempFile("startup-market-", ".tmp", parent)
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
                // A missing or unwritable cache never prevents live market data from loading.
            } finally {
                temporary?.delete()
            }
        }
    }

    private fun fresh(receivedAt: Long, checkedAt: Long): Boolean =
        receivedAt > 0 && receivedAt <= checkedAt && checkedAt - receivedAt < MAX_AGE_MILLIS

    private fun validChart(chart: LiveChart): Boolean =
        chart.stockId.value.matches(STOCK_ID) && chart.range == ChartRange.ONE_DAY && chart.status == "ok" &&
            chart.currency in setOf("USD", "USDC") && chart.basis in BASES &&
            chart.points.size in 3..MAX_POINTS && chart.points.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp } &&
            chart.points.all { it.price.signum() > 0 && it.price.precision() <= 128 && it.price.scale() in -100..100 } &&
            (chart.statusReason?.length ?: 0) <= 4000

    private companion object {
        const val MAX_AGE_MILLIS = 300_000L
        const val MAX_CHARTS = 64
        const val MAX_POINTS = 5000
        const val MAX_BYTES = 4 * 1024 * 1024
        val STOCK_ID = Regex("(?:backed|backpack|prestocks):[A-Za-z0-9.-]{1,90}")
        val BASES = setOf("onchain_token_market", "underlying_share_reference",
            "external_reference_non_executable", "provider_indicative_token")
    }
}
