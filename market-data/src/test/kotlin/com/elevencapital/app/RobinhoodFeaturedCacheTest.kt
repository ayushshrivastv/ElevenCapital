package com.elevencapital.app

import com.elevencapital.app.data.LiveMarketDataParser
import com.elevencapital.app.data.RobinhoodFeaturedCatalogCache
import com.elevencapital.app.data.RobinhoodFeaturedSymbols
import com.elevencapital.app.data.boundedRobinhoodFeaturedCatalog
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RobinhoodFeaturedCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val backend = "http://127.0.0.1:8787"
    private val capturedAt = Instant.parse(fixture().getString("receivedAt"))
    private var now = capturedAt.toEpochMilli()
    private fun fixture(): JSONObject = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-catalog.json"))
        .bufferedReader().use { JSONObject(it.readText()) }

    @Test fun `cached public metadata retains exact prices contracts and original source times`() = runBlocking {
        val directory = temporary.newFolder()
        val original = boundedRobinhoodFeaturedCatalog(fixture())
        RobinhoodFeaturedCatalogCache(directory, backend) { now }.write(original)
        now += 6 * 60 * 60 * 1000
        val restored = requireNotNull(RobinhoodFeaturedCatalogCache(directory, backend) { now }.read())
        assertEquals(original.toString(), restored.toString())
        val parsed = LiveMarketDataParser.catalog(restored)
        assertEquals(capturedAt, parsed.receivedAt)
        assertTrue(parsed.instruments.size <= 20)
        assertTrue(parsed.instruments.all { it.provider == "robinhood" && it.stock.symbol in RobinhoodFeaturedSymbols })
        assertTrue(parsed.instruments.all { it.stock.deployments.single().chainId == "4663" })
        assertEquals(RobinhoodFeaturedSymbols.filter { symbol -> parsed.instruments.any { it.stock.symbol == symbol } },
            parsed.instruments.map { it.stock.symbol })
        assertEquals(LiveMarketDataParser.catalog(original).instruments, parsed.instruments)
    }

    @Test fun `optional indicative share reference retains decimal strings units and original receipts independently of live quotes`() = runBlocking {
        val source = fixture()
        val rows = source.getJSONArray("stocks")
        val row = (0 until rows.length()).map(rows::getJSONObject).first {
            it.getString("provider") == "robinhood" && it.getString("symbol") in RobinhoodFeaturedSymbols
        }
        val asOf = capturedAt.minusSeconds(10).toString()
        val receipt = capturedAt.toString()
        val price = "100.125000000000000000"
        val quote = JSONObject().put("price", price).put("currency", "USD")
            .put("currencyBasis", "quoted_currency").put("basis", "provider_indicative_token")
            .put("changeAmount", JSONObject.NULL).put("changePercent", JSONObject.NULL)
            .put("asOf", asOf).put("receivedAt", receipt)
        val reference = JSONObject().put("basis", "underlying_share_reference").put("currency", "USD")
            .put("bid", "100.000000000000000000").put("ask", "100.250000000000000000")
            .put("dailyHigh", "101.0000").put("dailyLow", "99.0000").put("dailyTradingVolume", "12345.0000")
            .put("volumeUnit", "underlying_shares").put("currentMultiplier", "1.000000000000000000")
            .put("tokenDecimals", 18).put("asOf", asOf).put("receivedAt", receipt).put("isTradingHalt", false)
        row.put("quote", quote).put("robinhoodReference", reference)
            .put("volume24h", JSONObject.NULL).put("volume24hBasis", JSONObject.NULL)
        row.getJSONObject("activity").put("source", "Robinhood").put("scope", "robinhood_chain_token")
            .put("currency", "USD").put("volume24h", JSONObject.NULL).put("netVolume24h", JSONObject.NULL)
            .put("receivedAt", receipt).put("updatedAt", JSONObject.NULL)
            .put("volumeReason", "Issuer indicative prices do not report token-market turnover.")
            .put("netVolumeReason", "Issuer indicative prices do not report token-market buy/sell flow.")
        val cache = RobinhoodFeaturedCatalogCache(temporary.newFolder(), backend) { now }
        cache.write(source)
        now += 120_000
        val restored = requireNotNull(cache.read())
        val restoredRows = restored.getJSONArray("stocks")
        val restoredRow = (0 until restoredRows.length()).map(restoredRows::getJSONObject)
            .single { it.getString("id") == row.getString("id") }
        val restoredQuote = restoredRow.getJSONObject("quote")
        val restoredReference = restoredRow.getJSONObject("robinhoodReference")
        assertEquals(quote.length(), restoredQuote.length())
        assertEquals(reference.length(), restoredReference.length())
        for (key in quote.keys()) assertEquals(quote.get(key), restoredQuote.get(key))
        for (key in reference.keys()) assertEquals(reference.get(key), restoredReference.get(key))
        val parsed = LiveMarketDataParser.catalog(restored).instruments.single { it.stock.id.value == row.getString("id") }
        assertEquals("provider_indicative_token", parsed.quoteBasis)
        assertEquals(BigDecimal(price), parsed.stock.quote.price)
        assertEquals(capturedAt, parsed.quoteReceivedAt)
        assertEquals(BigDecimal("12345.0000"), parsed.stock.robinhoodReference?.dailyTradingVolume)
        assertEquals(capturedAt, parsed.stock.robinhoodReference?.receivedAt)
    }

    @Test fun `endpoint isolation and original metadata age survive repeated writes`() = runBlocking {
        val directory = temporary.newFolder()
        val cache = RobinhoodFeaturedCatalogCache(directory, backend) { now }
        cache.write(fixture())
        assertNull(RobinhoodFeaturedCatalogCache(directory, "http://localhost:8787") { now }.read())
        now += 7 * 24 * 60 * 60 * 1000L - 1
        val retained = requireNotNull(cache.read())
        cache.write(retained)
        now++
        assertNull(cache.read())
    }

    @Test fun `future malformed and oversized snapshots cannot replace a usable public cache`() = runBlocking {
        val directory = temporary.newFolder()
        val cache = RobinhoodFeaturedCatalogCache(directory, backend) { now }
        cache.write(fixture())
        cache.write(JSONObject("{\"wallets\":[\"private\"]}"))
        assertNotNull(cache.read())
        now = capturedAt.toEpochMilli() - 1
        assertNull(cache.read())
        now = capturedAt.toEpochMilli()
        val file = requireNotNull(directory.listFiles()).single()
        file.writeText("{broken")
        assertNull(cache.read())
        file.writeBytes(ByteArray(1024 * 1024 + 1))
        assertNull(cache.read())
    }

    @Test fun `cache stores only selected public issuer fields and never account-shaped extras`() = runBlocking {
        val source = fixture().put("wallets", "PRIVATE_ACCOUNT")
        val row = source.getJSONArray("stocks").getJSONObject(0)
        row.put("privateAccount", "PRIVATE_ACCOUNT")
        row.getJSONObject("quote").put("walletBalance", "PRIVATE_BALANCE")
        val directory = temporary.newFolder()
        val cache = RobinhoodFeaturedCatalogCache(directory, backend) { now }
        cache.write(source)
        val stored = requireNotNull(directory.listFiles()).single().readText()
        assertFalse(stored.contains("PRIVATE_ACCOUNT"))
        assertFalse(stored.contains("PRIVATE_BALANCE"))
        assertNotNull(cache.read())
    }

    @Test fun `invalid endpoint forms are rejected before a disk or network operation`() {
        for (url in listOf("http://remote.example", "https://user:pass@example.com", "https://example.com?token=key",
            "https://example.com#section", "file:///tmp/cache")) {
            assertThrows(IllegalArgumentException::class.java) { RobinhoodFeaturedCatalogCache(temporary.root, url) }
        }
    }
}
