package com.elevencapital.app.data

import android.content.Context
import com.elevencapital.app.screens.SignalNewsArticle
import com.elevencapital.app.screens.SignalProfile
import com.elevencapital.app.screens.SignalTrade
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

enum class SignalTradeSource { BARGO, OPEN_SNAPSHOT, NONE }

data class SignalDirectoryState(
    val profiles: List<SignalProfile> = featuredSignalProfiles(),
    val selectedProfileId: String? = null,
    val trades: List<SignalTrade> = emptyList(),
    val news: List<SignalNewsArticle> = emptyList(),
    val notice: String? = "Filings are published after transactions occur.",
    val tradeSource: SignalTradeSource = SignalTradeSource.OPEN_SNAPSHOT,
)

data class SignalProfilesResult(val profiles: List<SignalProfile>, val notice: String?)
data class SignalTradesResult(
    val trades: List<SignalTrade>,
    val notice: String?,
    val source: SignalTradeSource = SignalTradeSource.NONE,
)

/**
 * Reads LuxAlgo's CC0 disclosure snapshots, which include links to the original House or Senate filing.
 * The actual snapshot publication date is shown because this source may pause or lag its target schedule.
 * https://github.com/LuxAlgo/market-trackers-data
 */
class SignalCongressClient(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("signal-disclosures-cc0-v1", Context.MODE_PRIVATE)
    private val previousPrefs = context.applicationContext.getSharedPreferences("signal-disclosures-v1", Context.MODE_PRIVATE)
    private val tradePrefs = context.applicationContext.getSharedPreferences("signal-bargo-trades-v1", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val loadMutex = Mutex()

    init { previousPrefs.edit().clear().apply() }

    suspend fun profiles(force: Boolean = false): SignalProfilesResult {
        val snapshot = loadSnapshot(force)
        return SignalProfilesResult(snapshot?.let(::profilesFrom) ?: featuredSignalProfiles(), notice(snapshot))
    }

    /** Called only from a profile detail. The directory never requests or displays Bargo data. */
    suspend fun trades(profile: SignalProfile): SignalTradesResult {
        require(MEMBER_ID.matches(profile.id)) { "Invalid Congress profile." }
        val now = System.currentTimeMillis()
        val key = "profile:${profile.id}"
        val cached = tradePrefs.getString(key, null)
        val cachedAt = tradePrefs.getLong("$key:at", 0)
        if (cached != null && now - cachedAt < BARGO_TRADE_TTL_MILLIS) {
            try {
                parseBargoTrades(JSONObject(cached), profile)?.let {
                    return SignalTradesResult(it, tradeNotice(it), SignalTradeSource.BARGO)
                }
            } catch (_: Exception) { /* Re-fetch a damaged cache entry. */ }
        }
        if (now < tradePrefs.getLong("blockedUntil", 0)) return cachedOrOpenSnapshot(cached, profile)
        return try {
            val payload = fetchBargoTrades(profile)
            val root = JSONObject(payload)
            val rows = parseBargoTrades(root, profile)
                ?: throw IOException("Congress profile did not match the returned trades")
            tradePrefs.edit().putString(key, payload).putLong("$key:at", now).apply()
            SignalTradesResult(rows, tradeNotice(rows), SignalTradeSource.BARGO)
        } catch (failure: Exception) {
            if (failure is BargoRateLimited) {
                tradePrefs.edit().putLong("blockedUntil", now + BARGO_RETRY_MILLIS).apply()
            }
            cachedOrOpenSnapshot(cached, profile)
        }
    }

    private suspend fun fetchBargoTrades(profile: SignalProfile): String {
        val directSlug = BARGO_SLUGS[profile.id]
        if (directSlug != null) {
            try {
                val payload = request("$BARGO_BASE/members/$directSlug?limit=$BARGO_TRADE_LIMIT")
                    .toString(Charsets.UTF_8)
                if (parseBargoTrades(JSONObject(payload), profile) != null) return payload
            } catch (failure: HttpStatus) {
                if (failure.code != 404) throw failure
            }
        }
        val surname = surname(profile.name)
        if (surname.length < 2) throw IOException("Congress profile has no searchable surname")
        val url = "$BARGO_BASE/trades".toHttpUrl().newBuilder()
            .addQueryParameter("member", surname).addQueryParameter("limit", BARGO_TRADE_LIMIT.toString())
            .build().toString()
        return request(url).toString(Charsets.UTF_8)
    }

    private suspend fun cachedOrOpenSnapshot(payload: String?, profile: SignalProfile): SignalTradesResult {
        val rows = try { payload?.let { parseBargoTrades(JSONObject(it), profile) } }
            catch (_: Exception) { null }
        if (rows != null) return SignalTradesResult(rows,
            "Showing saved trades; newer filings may be missing.", SignalTradeSource.BARGO)
        val snapshot = loadSnapshot()
        if (snapshot == null) return SignalTradesResult(emptyList(),
            "Trade feed unavailable. Try again later.", SignalTradeSource.NONE)
        val openRows = snapshotTrades(snapshot, profile.id)
        return SignalTradesResult(openRows,
            "Open snapshot updated ${readableDate(snapshot.publishedAt)}; newer filings may be missing.",
            SignalTradeSource.OPEN_SNAPSHOT)
    }

    private fun snapshotTrades(snapshot: Snapshot, memberId: String): List<SignalTrade> = buildList {
        for (index in 0 until snapshot.rows.length()) {
            val item = snapshot.rows.optJSONObject(index) ?: continue
            if (item.optJSONObject("member")?.optString("bioguideId") != memberId) continue
            if (item.isNull("ticker")) continue
            val ticker = item.optString("ticker").trim().uppercase(Locale.US)
            val date = item.optString("transactedAt").take(10)
            if (!TICKER.matches(ticker) || !DATE.matches(date)) continue
            val filed = item.optString("filedAt").take(10).takeIf { DATE.matches(it) }
            val side = item.optString("side").lowercase(Locale.US)
            add(SignalTrade(
                id = item.optString("id").ifBlank { "$memberId-$ticker-$date-$index" },
                symbol = ticker,
                companyName = item.optString("assetDescription").replace(Regex("[\\p{Cntrl}]"), " ")
                    .trim().take(90).takeIf(String::isNotBlank),
                action = when (side) { "buy" -> "Purchase"; "sell" -> "Sale"; else -> "Reported" },
                transactionDate = date,
                disclosureDate = filed,
                amountRange = item.optJSONObject("amountRange")?.optString("text")?.takeIf(String::isNotBlank),
                sourceUrl = officialFiling(item),
            ))
        }
    }.sortedWith(compareByDescending<SignalTrade> { it.disclosureDate ?: "" }
        .thenByDescending { it.transactionDate }).take(100)

    private fun parseBargoTrades(root: JSONObject, profile: SignalProfile): List<SignalTrade>? {
        val items = root.optJSONArray("trades") ?: return null
        val topName = root.optString("member")
        val topChamber = root.optString("chamber")
        val topState = root.optString("state")
        if (topName.isNotBlank() && !matchesProfile(topName, topChamber, topState, profile)) return null

        var matchedMember = false
        val matching = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val name = item.optString("member").ifBlank { topName }
                val chamber = item.optString("chamber").ifBlank { topChamber }
                val state = item.optString("state").ifBlank { topState }
                if (!matchesProfile(name, chamber, state, profile)) continue
                matchedMember = true
                if (item.isNull("ticker")) continue
                val ticker = item.optString("ticker").trim().uppercase(Locale.US)
                val date = item.optString("transaction_date").take(10)
                if (!TICKER.matches(ticker) || !DATE.matches(date)) continue
                val action = when (item.optString("type").lowercase(Locale.US)) {
                    "purchase" -> "Purchase"; "sale" -> "Sale"; "exchange" -> "Exchange"; else -> "Reported"
                }
                val filed = item.optString("disclosure_date").take(10).takeIf { DATE.matches(it) }
                val source = item.optString("filing_portal").takeIf {
                    it.startsWith("https://disclosures-clerk.house.gov/") ||
                        it.startsWith("https://efdsearch.senate.gov/")
                }
                add(SignalTrade(
                    id = "${profile.id}-$ticker-$date-$action-$index",
                    symbol = ticker,
                    companyName = item.optString("asset").replace(Regex("[\\p{Cntrl}]"), " ")
                        .trim().take(90).takeIf(String::isNotBlank),
                    action = action,
                    transactionDate = date,
                    disclosureDate = filed,
                    amountRange = item.optString("amount_range").takeIf(String::isNotBlank)?.take(50),
                    sourceUrl = source,
                ))
            }
        }.sortedWith(compareByDescending<SignalTrade> { it.disclosureDate ?: "" }
            .thenByDescending { it.transactionDate })
        if (items.length() > 0 && !matchedMember) return null
        return matching
    }

    private fun tradeNotice(rows: List<SignalTrade>): String = if (rows.isEmpty())
        "No published trades for this profile in the recent feed."
    else "Filings can appear weeks after transactions."

    private fun matchesProfile(name: String, chamber: String, state: String, profile: SignalProfile): Boolean {
        if (chamber.isNotBlank() && !chamber.equals(profile.role, true)) return false
        if (state.isNotBlank() && !state.uppercase(Locale.US).startsWith(profile.state.uppercase(Locale.US))) return false
        val expectedSurname = surname(profile.name)
        val actualSurname = surname(name)
        if (expectedSurname != actualSurname) return false
        val expectedFirst = normalizedWords(profile.name).firstOrNull() ?: return false
        val actual = normalizedWords(name)
        // Official/member feeds may use a formal first name where a profile uses a nickname.
        val actualFirst = actual.firstOrNull()
        return expectedFirst in actual || NAME_ALIASES[expectedFirst]?.let { it in actual } == true ||
            (actualFirst != null && NAME_ALIASES[actualFirst] == expectedFirst)
    }

    private fun surname(name: String): String = normalizedWords(name)
        .dropLastWhile { it in NAME_SUFFIXES }.lastOrNull().orEmpty()

    private fun normalizedWords(name: String): List<String> = Regex("[A-Za-z]+")
        .findAll(name.lowercase(Locale.US)).map { it.value }.filterNot { it == "hon" }.toList()

    private suspend fun loadSnapshot(force: Boolean = false): Snapshot? = loadMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedSnapshot()
        val fetchedAt = prefs.getLong("fetchedAt", 0)
        if (cached != null && (!force || now - fetchedAt < MIN_RETRY_MILLIS) &&
            now - fetchedAt < CACHE_TTL_MILLIS) return@withLock cached
        if (now < prefs.getLong("retryAfter", 0)) return@withLock cached
        return@withLock try {
            val manifest = JSONObject(request(MANIFEST_URL).toString(Charsets.UTF_8))
            val publishedAt = manifest.optString("generatedAt").take(10)
            if (!DATE.matches(publishedAt)) throw IOException("Invalid disclosure snapshot date")
            val compressed = request(SNAPSHOT_URL)
            val rowsText = GZIPInputStream(ByteArrayInputStream(compressed)).use(::readBounded)
                .toString(Charsets.UTF_8)
            val rows = JSONArray(rowsText)
            if (rows.length() == 0) throw IOException("Empty disclosure snapshot")
            prefs.edit().putString("rows", rowsText).putString("publishedAt", publishedAt)
                .putLong("fetchedAt", now).remove("retryAfter").apply()
            Snapshot(rows, publishedAt, false)
        } catch (_: Exception) {
            prefs.edit().putLong("retryAfter", now + RETRY_MILLIS).apply()
            cached?.copy(offline = true)
        }
    }

    private fun cachedSnapshot(): Snapshot? {
        return try {
            val rows = prefs.getString("rows", null) ?: return null
            val date = prefs.getString("publishedAt", null) ?: return null
            Snapshot(JSONArray(rows), date, false)
        } catch (_: Exception) { null }
    }

    private fun profilesFrom(snapshot: Snapshot): List<SignalProfile> {
        val members = linkedMapOf<String, MemberCount>()
        for (index in 0 until snapshot.rows.length()) {
            val row = snapshot.rows.optJSONObject(index) ?: continue
            val member = row.optJSONObject("member") ?: continue
            val id = member.optString("bioguideId")
            if (!MEMBER_ID.matches(id)) continue
            val name = member.optString("name").removePrefix("Hon. ").trim().take(80)
            if (name.isEmpty()) continue
            val current = members[id]
            val hasTicker = !row.isNull("ticker") && TICKER.matches(row.optString("ticker"))
            val count = (current?.count ?: 0) + if (hasTicker) 1 else 0
            members[id] = MemberCount(id, name, row.optString("chamber"), member.optString("state"),
                current?.filingUrl ?: officialFiling(row), count)
        }
        val ranked = members.values.sortedWith(compareByDescending<MemberCount> { it.count }.thenBy { it.name })
        val selected = (ranked.firstOrNull { it.id == "P000197" }?.let { listOf(it) }.orEmpty() +
            ranked.filterNot { it.id == "P000197" }).take(20)
        val live = selected.map { profile(it.id, it.name, it.chamber, it.state, it.filingUrl) }
        return (live + featuredSignalProfiles()).distinctBy { it.id }.take(20)
    }

    private fun notice(snapshot: Snapshot?): String = when {
        snapshot == null -> "Disclosure snapshot unavailable. Check the official House or Senate filings."
        snapshot.offline -> "Offline snapshot from ${readableDate(snapshot.publishedAt)}; newer filings may be missing."
        else -> {
            val daysOld = try { LocalDate.now(ZoneOffset.UTC).toEpochDay() - LocalDate.parse(snapshot.publishedAt).toEpochDay() }
                catch (_: Exception) { Long.MAX_VALUE }
            if (daysOld > 3) "Snapshot updated ${readableDate(snapshot.publishedAt)}; newer filings may be missing."
            else "Snapshot updated ${readableDate(snapshot.publishedAt)}. Filings can lag transactions."
        }
    }

    private fun readableDate(date: String): String = try {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
    } catch (_: Exception) { date }

    private fun officialFiling(row: JSONObject): String? = row.optJSONObject("provenance")
        ?.optString("sourceUrl")?.takeIf { it.startsWith("https://disclosures-clerk.house.gov/") ||
            it.startsWith("https://efdsearch.senate.gov/") }

    private suspend fun request(url: String): ByteArray = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bytes = response.use {
                        if (it.code == 429) throw BargoRateLimited()
                        if (!it.isSuccessful) throw HttpStatus(it.code)
                        requireNotNull(it.body).byteStream().use(::readBounded)
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) throw IOException("Disclosure snapshot too large")
            output.write(chunk, 0, count)
        }
        return output.toByteArray()
    }

    private data class Snapshot(val rows: JSONArray, val publishedAt: String, val offline: Boolean)
    private data class MemberCount(val id: String, val name: String, val chamber: String, val state: String,
        val filingUrl: String?, val count: Int)
    private class HttpStatus(val code: Int) : IOException("Congress feed HTTP $code")
    private class BargoRateLimited : IOException("Congress feed rate limited")

    private companion object {
        const val DATA_ROOT = "https://raw.githubusercontent.com/LuxAlgo/market-trackers-data/main"
        const val MANIFEST_URL = "$DATA_ROOT/manifest.json"
        const val SNAPSHOT_URL = "$DATA_ROOT/congress/trades/snapshot.json.gz"
        const val MAX_RESPONSE_BYTES = 3_000_000
        const val CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val MIN_RETRY_MILLIS = 30 * 60 * 1000L
        const val RETRY_MILLIS = 30 * 60 * 1000L
        const val BARGO_BASE = "https://www.bargo.ai/free-apis/congress/v1"
        const val BARGO_TRADE_LIMIT = 12
        const val BARGO_TRADE_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val BARGO_RETRY_MILLIS = 60 * 60 * 1000L
        val MEMBER_ID = Regex("[A-Z][0-9]{6}")
        val TICKER = Regex("[A-Z][A-Z0-9.\\-]{0,15}")
        val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        val NAME_SUFFIXES = setOf("jr", "sr", "ii", "iii", "iv")
        val NAME_ALIASES = mapOf(
            "chuck" to "charles", "dan" to "daniel", "don" to "donald",
            "rick" to "richard", "tim" to "timothy", "bill" to "william",
            "steve" to "stephen", "bob" to "robert", "mike" to "michael",
            "ro" to "rohit", "josh" to "joshua", "chris" to "christopher",
        )
        val BARGO_SLUGS = mapOf(
            "P000197" to "nancy-pelosi",
            "C001123" to "gilbert-cisneros",
            "M001232" to "april-mcclain-delaney",
            "K000389" to "rohit-khanna",
            "B001277" to "richard-blumenthal",
            "H001082" to "kevin-hern",
            "N000189" to "dan-newhouse",
            "B001236" to "john-boozman",
            "M001218" to "richard-dean-dr-mccormick",
            "T000490" to "david-j-taylor",
            "G000583" to "josh-gottheimer",
            "K000398" to "thomas-h-kean-jr",
            "S000168" to "maria-elvira-salazar",
            "R000619" to "michael-rulli",
            "P000608" to "scott-h-peters",
            "F000459" to "charles-j-chuck-fleischmann",
            "W000802" to "sheldon-whitehouse",
            "W000829" to "tony-wied",
            "F000472" to "scott-scott-franklin",
            "D000032" to "byron-donalds",
        )
    }
}

private data class FeaturedMember(val name: String, val chamber: String, val state: String, val bioguide: String)

/** Public member identities only; no static trades or holdings are bundled. */
private val FEATURED = listOf(
    FeaturedMember("Nancy Pelosi", "House", "CA", "P000197"),
    FeaturedMember("Gilbert Cisneros", "House", "CA", "C001123"),
    FeaturedMember("April McClain Delaney", "House", "MD", "M001232"),
    FeaturedMember("Ro Khanna", "House", "CA", "K000389"),
    FeaturedMember("Richard Blumenthal", "Senate", "CT", "B001277"),
    FeaturedMember("Kevin Hern", "House", "OK", "H001082"),
    FeaturedMember("Dan Newhouse", "House", "WA", "N000189"),
    FeaturedMember("John Boozman", "Senate", "AR", "B001236"),
    FeaturedMember("Richard McCormick", "House", "GA", "M001218"),
    FeaturedMember("David J. Taylor", "House", "OH", "T000490"),
    FeaturedMember("Josh Gottheimer", "House", "NJ", "G000583"),
    FeaturedMember("Thomas H. Kean Jr.", "House", "NJ", "K000398"),
    FeaturedMember("Maria Elvira Salazar", "House", "FL", "S000168"),
    FeaturedMember("Michael Rulli", "House", "OH", "R000619"),
    FeaturedMember("Scott Peters", "House", "CA", "P000608"),
    FeaturedMember("Chuck Fleischmann", "House", "TN", "F000459"),
    FeaturedMember("Sheldon Whitehouse", "Senate", "RI", "W000802"),
    FeaturedMember("Tony Wied", "House", "WI", "W000829"),
    FeaturedMember("Scott Franklin", "House", "FL", "F000472"),
    FeaturedMember("Byron Donalds", "House", "FL", "D000032"),
)

fun featuredSignalProfiles(): List<SignalProfile> = FEATURED.map {
    profile(it.bioguide, it.name, it.chamber, it.state, null)
}

private fun profile(id: String, name: String, chamber: String, state: String, filingUrl: String?): SignalProfile {
    val portrait = "https://unitedstates.github.io/images/congress/225x275/$id.jpg"
    val source = filingUrl ?: if (chamber.equals("Senate", true))
        "https://efdsearch.senate.gov/search/" else "https://disclosures-clerk.house.gov/FinancialDisclosure"
    return SignalProfile(id, name, chamber.replaceFirstChar { it.uppercase() }, state, portrait, source)
}
