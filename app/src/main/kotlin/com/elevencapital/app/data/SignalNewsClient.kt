package com.elevencapital.app.data

import com.elevencapital.app.screens.SignalNewsArticle
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** TickerTick's public, keyless stock-news feed. Article links open at their publishers. */
class SignalNewsClient(http: OkHttpClient = OkHttpClient()) {
    private val client = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val recent = linkedMapOf<String, Pair<Long, List<SignalNewsArticle>>>()

    suspend fun newsForTickers(tickers: List<String>): List<SignalNewsArticle> {
        val symbols = tickers.asSequence()
            .map { it.trim().uppercase() }
            .filter { SYMBOL.matches(it) }
            .distinct()
            .take(3)
            .toList()
        if (symbols.isEmpty()) return emptyList()
        val cacheKey = symbols.sorted().joinToString(",")
        val now = System.nanoTime()
        synchronized(recent) {
            recent[cacheKey]?.takeIf { now - it.first in 0 until CACHE_NANOS }?.let { return it.second }
        }

        val query = if (symbols.size == 1) "tt:${symbols[0].lowercase()}" else
            symbols.joinToString(prefix = "(or ", postfix = ")", separator = " ") { "tt:${it.lowercase()}" }
        val url = FEED_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("n", "10")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val result = try { fetch(request, symbols.toSet()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        synchronized(recent) {
            recent[cacheKey] = System.nanoTime() to result
            while (recent.size > 8) recent.remove(recent.keys.first())
        }
        return result
    }

    private suspend fun fetch(request: Request, symbols: Set<String>): List<SignalNewsArticle> =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }

                override fun onResponse(call: Call, response: Response) {
                    val articles = try {
                        response.use {
                            if (it.code != 200 || it.body?.contentType()?.subtype != "json") emptyList()
                            else parse(readBounded(it), symbols)
                        }
                    } catch (_: Exception) { emptyList() }
                    if (continuation.isActive) continuation.resume(articles)
                }
            })
        }

    private fun readBounded(response: Response): String {
        val body = response.body ?: throw IOException("Missing news response")
        if (body.contentLength() > MAX_BYTES) throw IOException("News response is too large")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw IOException("News response is too large")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parse(source: String, symbols: Set<String>): List<SignalNewsArticle> {
        val stories = JSONObject(source).optJSONArray("stories") ?: return emptyList()
        val articles = ArrayList<SignalNewsArticle>(minOf(stories.length(), 10))
        val seenUrls = HashSet<String>()
        for (index in 0 until minOf(stories.length(), 30)) {
            val story = stories.optJSONObject(index) ?: continue
            val title = story.optString("title").trim().take(240)
            val link = safeHttpsUrl(story.optString("url")) ?: continue
            val millis = story.optLong("time", -1)
            if (title.isEmpty() || millis !in MIN_STORY_MILLIS..(System.currentTimeMillis() + DAY_MILLIS)) continue
            if (!seenUrls.add(link)) continue
            val related = story.optJSONArray("tags")?.let { tags ->
                (0 until minOf(tags.length(), 20)).asSequence()
                    .mapNotNull { tags.optString(it).uppercase().takeIf(symbols::contains) }
                    .firstOrNull()
            }
            val publisher = story.optString("site").trim().take(80).ifEmpty { URI(link).host.orEmpty() }
            articles += SignalNewsArticle(
                id = story.optString("id").trim().take(128).ifEmpty { link },
                title = title,
                publisher = publisher,
                publishedAt = NEWS_TIME.format(Instant.ofEpochMilli(millis)),
                url = link,
                relatedSymbol = related,
            )
            if (articles.size == 10) break
        }
        return articles
    }

    private fun safeHttpsUrl(value: String): String? = try {
        val url = URI(value.trim())
        val host = url.host?.lowercase()
        if (url.scheme != "https" || host.isNullOrBlank() || url.userInfo != null ||
            host == "localhost" || host == "127.0.0.1" || host == "[::1]" || value.length > 2_048
        ) null else url.toString()
    } catch (_: Exception) { null }

    private companion object {
        const val FEED_URL = "https://api.tickertick.com/feed"
        const val MAX_BYTES = 512 * 1_024
        const val CACHE_NANOS = 60_000_000_000L
        const val DAY_MILLIS = 86_400_000L
        const val MIN_STORY_MILLIS = 1_262_304_000_000L // 2010-01-01
        val SYMBOL = Regex("[A-Z][A-Z0-9.-]{0,9}")
        val NEWS_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC)
    }
}
