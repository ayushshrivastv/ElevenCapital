package com.elevencapital.app.data

import com.elevencapital.app.wallet.isSolanaPublicKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A recent, public one day chart for the exact Meteora pool and token mint. */
data class CachedMeteoraChart(
    val poolAddress: String,
    val mintAddress: String,
    val chart: MeteoraTokenChart,
    val receivedAtMillis: Long,
)

/** Bounded on disk chart snapshots for the top Meteora rows shown before token selection. */
class MeteoraChartSnapshotCache(
    directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(directory, "meteora-charts-1d-v1.json")
    private val mutex = Mutex()

    suspend fun read(): List<CachedMeteoraChart> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return@withLock emptyList()
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
                val document = JSONObject(String(output.toByteArray(), Charsets.UTF_8))
                require(document.getInt("schemaVersion") == 1)
                val stored = document.getJSONArray("entries")
                require(stored.length() <= MAX_CHARTS)
                val time = now()
                (0 until stored.length()).mapNotNull { index ->
                    runCatching { parseEntry(stored.getJSONObject(index)) }.getOrNull()
                        ?.takeIf { validEntry(it, time) }
                }.distinctBy { it.poolAddress to it.mintAddress }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun write(entries: List<CachedMeteoraChart>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            var temporary: File? = null
            try {
                currentCoroutineContext().ensureActive()
                val time = now()
                val selected = entries.asSequence()
                    .filter { validEntry(it, time) }
                    .distinctBy { it.poolAddress to it.mintAddress }
                    .take(MAX_CHARTS)
                    .map { it.copy(chart = MeteoraTokenChart(it.chart.points.takeLast(MAX_POINTS))) }
                    .toList()
                val rows = JSONArray()
                selected.forEach { rows.put(serializeEntry(it)) }
                val bytes = JSONObject().put("schemaVersion", 1).put("entries", rows)
                    .toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                val parent = requireNotNull(file.parentFile)
                require(parent.isDirectory || parent.mkdirs())
                temporary = File.createTempFile("meteora-charts-", ".tmp", parent)
                temporary.writeBytes(bytes)
                currentCoroutineContext().ensureActive()
                // Temp and destination share a directory. If the filesystem cannot make the
                // replacement atomic, leave the previous complete snapshot in place.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Chart preloading still works in memory if the optional disk cache fails.
            } finally {
                temporary?.delete()
            }
        }
    }

    private fun serializeEntry(entry: CachedMeteoraChart): JSONObject = JSONObject()
        .put("pool", entry.poolAddress)
        .put("mint", entry.mintAddress)
        .put("receivedAtMillis", entry.receivedAtMillis)
        .put("points", JSONArray().also { points ->
            entry.chart.points.forEach { point ->
                points.put(JSONArray().put(point.timestampSeconds).put(point.priceUsd.toPlainString()))
            }
        })

    private fun parseEntry(json: JSONObject): CachedMeteoraChart {
        val points = json.getJSONArray("points")
        require(points.length() in 1..MAX_POINTS)
        return CachedMeteoraChart(
            poolAddress = json.getString("pool"),
            mintAddress = json.getString("mint"),
            receivedAtMillis = json.getLong("receivedAtMillis"),
            chart = MeteoraTokenChart((0 until points.length()).map { index ->
                val point = points.getJSONArray(index)
                require(point.length() == 2)
                MeteoraTokenChartPoint(point.getLong(0), BigDecimal(point.getString(1)))
            }),
        )
    }

    private fun validEntry(entry: CachedMeteoraChart, time: Long): Boolean {
        if (!isSolanaPublicKey(entry.poolAddress) || !isSolanaPublicKey(entry.mintAddress)) return false
        if (entry.receivedAtMillis <= 0 || entry.receivedAtMillis > time ||
            time - entry.receivedAtMillis >= MAX_AGE_MILLIS) return false
        if (entry.chart.points.isEmpty()) return false
        val nowSeconds = time / 1_000
        var previous = 0L
        for (point in entry.chart.points) {
            val price = point.priceUsd
            if (point.timestampSeconds <= previous ||
                point.timestampSeconds < nowSeconds - MAX_POINT_AGE_SECONDS ||
                point.timestampSeconds > nowSeconds || price.signum() <= 0 ||
                price.precision() > MAX_PRICE_PRECISION || price.scale() !in MIN_PRICE_SCALE..MAX_PRICE_SCALE) {
                return false
            }
            previous = point.timestampSeconds
        }
        return true
    }

    private companion object {
        const val MAX_CHARTS = 20
        const val MAX_POINTS = 300
        const val MAX_BYTES = 1024 * 1024
        const val MAX_AGE_MILLIS = 10 * 60 * 1000L
        const val MAX_POINT_AGE_SECONDS = 2 * 24 * 60 * 60L
        const val MAX_PRICE_PRECISION = 40
        const val MIN_PRICE_SCALE = -20
        const val MAX_PRICE_SCALE = 40
    }
}
