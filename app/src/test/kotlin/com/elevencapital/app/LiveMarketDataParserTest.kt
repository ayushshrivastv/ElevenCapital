package com.elevencapital.app

import com.elevencapital.app.data.LiveMarketDataParser
import com.elevencapital.app.data.MarketStreamEvent
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Runs against a real org.json implementation; Android's stub android.jar is not a JSON test runtime. */
class LiveMarketDataParserTest {
    @Test
    fun `required activity preserves signed exact decimals and separate source times`() {
        val parsed = LiveMarketDataParser.catalog(catalog()).instruments.single()
        val activity = requireNotNull(parsed.stock.activity)
        assertEquals(BigDecimal("497.200000000000000001"), parsed.stock.quote.price)
        assertEquals(BigDecimal("200000000000000000000.2469135780246913578"), activity.volume24h)
        assertEquals(BigDecimal("-0.000000000000000000002"), activity.netVolume24h)
        assertEquals("USD", activity.currencyCode)
        assertEquals("Jupiter", activity.source)
        assertEquals("solana_token", activity.scope)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), activity.receivedAt)
        assertEquals(Instant.parse("2026-09-18T11:59:59.557Z"), activity.updatedAt)
        assertNull(activity.netVolumeReason)
    }

    @Test
    fun `Backpack net is unavailable while its own price and USDC volume remain valid`() {
        val source = catalog()
        val row = row(source)
        row.put("id", "backpack:MSFT.US").put("provider", "backpack").put("providerAssetId", "MSFT.US")
        row.getJSONObject("quote").put("currency", "USDC").put("currencyBasis", "market_symbol")
            .put("basis", "external_reference_non_executable")
        row.put("activity", JSONObject("""{
            "currency":"USDC", "source":"Backpack", "scope":"external_market",
            "volume24h":"1234567.890123456789", "netVolume24h":null,
            "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":"Backpack does not publish a buy/sell breakdown."
        }"""))
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals(BigDecimal("497.200000000000000001"), parsed.stock.quote.price)
        assertEquals("USDC", parsed.stock.quote.currencyCode)
        val activity = requireNotNull(parsed.stock.activity)
        assertEquals("Backpack", activity.source)
        assertEquals(BigDecimal("1234567.890123456789"), activity.volume24h)
        assertNull(activity.netVolume24h)
        assertNull(activity.updatedAt)
        assertEquals("Backpack does not publish a buy/sell breakdown.", activity.netVolumeReason)
    }

    @Test
    fun `activity cannot silently disappear from a live response`() {
        val missing = catalog()
        row(missing).remove("activity")
        assertThrows(JSONException::class.java) { LiveMarketDataParser.catalog(missing) }
        val explicitNull = catalog()
        row(explicitNull).put("activity", JSONObject.NULL)
        assertThrows(JSONException::class.java) { LiveMarketDataParser.catalog(explicitNull) }
    }

    @Test
    fun `activity from the wrong issuer is rejected even when internally valid`() {
        val source = catalog()
        row(source).put("activity", JSONObject("""{
            "currency":"USDC", "source":"Backpack", "scope":"external_market",
            "volume24h":"100", "netVolume24h":null,
            "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":"No split."
        }"""))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        val backpackWithJupiter = catalog()
        row(backpackWithJupiter).put("id", "backpack:MSFT.US").put("provider", "backpack").put("providerAssetId", "MSFT.US")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(backpackWithJupiter) }
    }

    @Test
    fun `PreStocks instruments retain their provider identity and Jupiter token provenance`() {
        val source = catalog()
        val row = row(source)
        row.put("id", "prestocks:openai").put("provider", "prestocks")
            .put("providerAssetId", "openai").put("providerLabel", "PreStocks · Pre-IPO")
            .put("symbol", "OPENAI").put("name", "OpenAI Pre-IPO")
            .put("description", "Provider-supplied product description.")
            .put("informationUrl", "https://prestocks.com/products/openai")
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals("prestocks", parsed.provider)
        assertEquals("PreStocks · Pre-IPO", parsed.providerLabel)
        assertEquals("Provider-supplied product description.", parsed.stock.description)
        assertEquals("https://prestocks.com/products/openai", parsed.stock.informationUrl)
        assertEquals("Jupiter", parsed.stock.activity?.source)
        assertEquals("solana_token", parsed.stock.activity?.scope)

        row.put("activity", JSONObject("""{
            "currency":"USDC", "source":"Backpack", "scope":"external_market",
            "volume24h":"100", "netVolume24h":null,
            "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":"No split."
        }"""))
        row.getJSONObject("quote").put("currency", "USDC").put("currencyBasis", "market_symbol")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
    }

    @Test
    fun `Robinhood token keeps exact chain contract and indicative USD reference`() {
        val parsed = LiveMarketDataParser.catalog(robinhoodCatalog()).instruments.single()
        assertEquals("robinhood", parsed.provider)
        assertEquals("Robinhood Stock Tokens", parsed.providerLabel)
        assertEquals("provider_indicative_token", parsed.quoteBasis)
        assertEquals("USD", parsed.stock.quote.currencyCode)
        assertEquals("Robinhood", parsed.stock.activity?.source)
        assertEquals("robinhood_chain_token", parsed.stock.activity?.scope)
        assertNull(parsed.stock.activity?.volume24h)
        assertNull(parsed.stock.activity?.netVolume24h)
        assertEquals("Robinhood Chain", parsed.stock.deployments.single().network)
        assertEquals("4663", parsed.stock.deployments.single().chainId)
        assertEquals(18, parsed.stock.deployments.single().decimals)
        assertEquals(ROBINHOOD_CONTRACT, parsed.stock.deployments.single().address)
        assertEquals("robinhood_chain", parsed.stock.statistics?.reference?.network)
        assertEquals(ROBINHOOD_CONTRACT, parsed.stock.statistics?.reference?.mint)
    }

    @Test
    fun `Robinhood pool quote pairs with actual USD percentage and directional token volume`() {
        val source = robinhoodCatalog()
        val stock = row(source)
        stock.getJSONObject("quote").put("basis", "onchain_token_market")
            .put("price", "510.519496673896").put("changePercent", "-0.793")
        stock.put("activity", JSONObject("""{
            "currency":"USD", "source":"GeckoTerminal", "scope":"robinhood_chain_token",
            "volume24h":"480262.21627722", "netVolume24h":"-65146.288885142",
            "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":null
        }"""))
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals("onchain_token_market", parsed.quoteBasis)
        assertEquals(BigDecimal("-0.793"), parsed.stock.quote.changePercent)
        assertEquals(BigDecimal("480262.21627722"), parsed.stock.activity?.volume24h)
        assertEquals(BigDecimal("-65146.288885142"), parsed.stock.activity?.netVolume24h)
        assertNull(parsed.stock.robinhoodReference)

        stock.getJSONObject("activity").put("source", "Robinhood")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        stock.getJSONObject("activity").put("source", "GeckoTerminal")
        stock.getJSONObject("quote").put("basis", "provider_indicative_token")
        assertEquals("GeckoTerminal", LiveMarketDataParser.catalog(source).instruments.single().stock.activity?.source)
    }

    @Test
    fun `fresh issuer quote and original share reference preserve independent Gecko token activity`() {
        val source = robinhoodReferenceCatalog()
        val item = row(source)
        item.put("activity", JSONObject("""{
            "currency":"USD", "source":"GeckoTerminal", "scope":"robinhood_chain_token",
            "volume24h":"480262.21627722", "netVolume24h":"-65146.288885142",
            "receivedAt":"2026-09-18T11:59:30Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":null
        }"""))
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals("provider_indicative_token", parsed.quoteBasis)
        assertEquals(BigDecimal("100.55025"), parsed.stock.quote.price)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), parsed.quoteReceivedAt)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), parsed.stock.robinhoodReference?.receivedAt)
        assertEquals(Instant.parse("2026-09-18T11:59:30Z"), parsed.stock.activity?.receivedAt)
        assertEquals(BigDecimal("480262.21627722"), parsed.stock.activity?.volume24h)
        assertEquals(BigDecimal("-65146.288885142"), parsed.stock.activity?.netVolume24h)
        for ((key, value) in listOf("source" to "Jupiter", "scope" to "solana_token", "currency" to "USDC")) {
            val invalid = JSONObject(source.toString())
            row(invalid).getJSONObject("activity").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(invalid) }
        }
        val wrongChain = JSONObject(source.toString())
        row(wrongChain).getJSONArray("deployments").getJSONObject(0).put("chainId", 1)
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(wrongChain) }
    }

    @Test
    fun `Robinhood contract cannot use a different chain mint or Solana activity`() {
        for ((key, value) in listOf("network" to "Ethereum", "address" to "invalid",
            "chainId" to "42161", "chainId" to "1", "chainId" to JSONObject.NULL,
            "decimals" to JSONObject.NULL, "decimals" to -1, "decimals" to 37, "decimals" to 18.5)) {
            val source = robinhoodCatalog()
            row(source).getJSONArray("deployments").getJSONObject(0).put(key, value)
            assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        }
        val mismatch = robinhoodCatalog()
        row(mismatch).getJSONObject("statistics").put("mint", "0x" + "2".repeat(40))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(mismatch) }
        val wrongActivity = robinhoodCatalog()
        row(wrongActivity).put("activity", row(catalog()).getJSONObject("activity"))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(wrongActivity) }
        val executablePrice = robinhoodCatalog()
        row(executablePrice).getJSONObject("trading").put("enabled", true)
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(executablePrice) }
    }

    @Test
    fun `issuer supported precision is retained without assuming every token has eighteen decimals`() {
        val source = robinhoodCatalog()
        row(source).getJSONArray("deployments").getJSONObject(0).put("decimals", 6)
        assertEquals(6, LiveMarketDataParser.catalog(source).instruments.single().stock.deployments.single().decimals)
    }

    @Test
    fun `provider information links must use HTTPS and legacy rows may omit them`() {
        assertNull(LiveMarketDataParser.catalog(catalog()).instruments.single().stock.informationUrl)

        val insecure = catalog()
        row(insecure).put("informationUrl", "http://example.com/product")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(insecure) }

        val credentialed = catalog()
        row(credentialed).put("informationUrl", "https://user:password@example.com/product")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(credentialed) }
    }

    @Test
    fun `catalog requires one status for every supported provider`() {
        val missing = catalog()
        missing.getJSONArray("providers").remove(2)
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(missing) }

        val duplicate = catalog()
        duplicate.getJSONArray("providers").getJSONObject(2).put("id", "backed")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(duplicate) }
    }

    @Test
    fun `unavailable activity preserves reasons and cannot replace a valid quote with zero`() {
        val source = catalog()
        row(source).getJSONObject("activity").put("volume24h", JSONObject.NULL).put("netVolume24h", JSONObject.NULL)
            .put("volumeReason", "Provider unavailable.").put("netVolumeReason", "Provider unavailable.")
            .put("receivedAt", JSONObject.NULL).put("updatedAt", JSONObject.NULL)
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals(BigDecimal("497.200000000000000001"), parsed.stock.quote.price)
        assertNull(parsed.stock.activity?.volume24h)
        assertNull(parsed.stock.activity?.netVolume24h)
        assertEquals("Provider unavailable.", parsed.stock.activity?.volumeReason)
    }

    @Test
    fun `numeric JSON volume is rejected to prevent precision loss`() {
        val source = catalog()
        row(source).getJSONObject("activity").put("volume24h", 123.5)
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
    }

    @Test
    fun `impossible net missing provenance and unit mismatch are rejected`() {
        for ((key, value) in listOf(
            "volume24h" to "-1",
            "netVolume24h" to "999999999999999999999999999999",
            "receivedAt" to JSONObject.NULL,
            "updatedAt" to JSONObject.NULL,
            "currency" to "USDC",
            "scope" to "external_market",
        )) {
            val source = catalog()
            row(source).getJSONObject("activity").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        }
    }

    @Test
    fun `actual zero is retained and missing activity needs a nonblank reason`() {
        val zero = catalog()
        row(zero).getJSONObject("activity").put("volume24h", "0").put("netVolume24h", "0")
        val activity = LiveMarketDataParser.catalog(zero).instruments.single().stock.activity
        assertNotNull(activity)
        assertEquals(BigDecimal.ZERO, activity?.volume24h)
        assertEquals(BigDecimal.ZERO, activity?.netVolume24h)
        val missing = catalog()
        row(missing).getJSONObject("activity").put("volume24h", JSONObject.NULL).put("netVolume24h", JSONObject.NULL)
            .put("volumeReason", " ").put("netVolumeReason", "Not reported.")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(missing) }
    }

    @Test
    fun `socket snapshot and delta share one strict instrument parser`() {
        val source = catalog()
        val snapshot = JSONObject().put("type", "snapshot").put("sessionId", "service-1")
            .put("revision", 0).put("catalog", source)
        val parsedSnapshot = LiveMarketDataParser.event(snapshot) as MarketStreamEvent.Snapshot
        val delta = JSONObject().put("type", "delta").put("sessionId", "service-1").put("revision", 7)
            .put("receivedAt", source.getString("receivedAt")).put("stocks", source.getJSONArray("stocks"))
            .put("providers", source.getJSONArray("providers")).put("removedIds", org.json.JSONArray())
        val parsedDelta = LiveMarketDataParser.event(delta) as MarketStreamEvent.Delta
        assertEquals(parsedSnapshot.catalog.instruments, parsedDelta.instruments)
        assertEquals(7L, parsedDelta.revision)
        assertEquals(0L, parsedSnapshot.revision)
        for (revision in listOf(-1, 0.5, "8")) {
            snapshot.put("revision", revision)
            assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.event(snapshot) }
        }
    }

    @Test
    fun `snapshot rejects duplicate identities and delta rejects simultaneous removal and upsert`() {
        val source = catalog()
        source.getJSONArray("stocks").put(JSONObject(row(source).toString()))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        val one = catalog()
        val delta = JSONObject().put("type", "delta").put("sessionId", "service-1").put("revision", 1)
            .put("receivedAt", one.getString("receivedAt")).put("stocks", one.getJSONArray("stocks"))
            .put("providers", one.getJSONArray("providers"))
            .put("removedIds", org.json.JSONArray().put(row(one).getString("id")))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.event(delta) }
    }

    @Test
    fun `chart retains explicit observed-series limitation without claiming historical candles`() {
        val chart = JSONObject("""{
            "stockId":"backed:test", "range":"ONE_DAY", "status":"ok", "currency":"USD",
            "basis":"observed_token_market", "statusReason":"Observed since service start; full history unavailable.",
            "points":[{"timestamp":"2026-09-19T00:00:00Z","price":"199.000000000000000001"}]
        }""")
        val event = JSONObject().put("type", "chart").put("sessionId", "service-1").put("revision", 8).put("chart", chart)
        val parsed = (LiveMarketDataParser.event(event) as MarketStreamEvent.Chart).chart
        assertEquals("observed_token_market", parsed.basis)
        assertEquals("Observed since service start; full history unavailable.", parsed.statusReason)
        assertEquals(BigDecimal("199.000000000000000001"), parsed.points.single().price)
    }

    @Test
    fun `market state object retains closed and halted labels without breaking the catalog`() {
        for (status in listOf("open", "closed", "halted", "unknown")) {
            val source = catalog()
            row(source).put("marketState", JSONObject().put("status", status)
                .put("label", "Underlying market $status").put("nextChangeAt", JSONObject.NULL))
            val parsed = LiveMarketDataParser.catalog(source).instruments.single()
            assertEquals(status, parsed.marketState)
            assertEquals("Underlying market $status", parsed.marketStateReason)
        }
    }

    @Test
    fun `labelled underlying share reference keeps external USDC provenance for a Backed product`() {
        val source = shareReferenceCatalog()
        val parsed = LiveMarketDataParser.catalog(source).instruments.single()
        assertEquals("backed", parsed.provider)
        assertEquals("underlying_share_reference", parsed.quoteBasis)
        assertEquals("Backed · Share reference", parsed.providerLabel)
        assertEquals("USDC", parsed.stock.quote.currencyCode)
        assertEquals("Backpack", parsed.stock.activity!!.source)
        assertEquals("external_market", parsed.stock.activity!!.scope)
        assertEquals("USDC", parsed.stock.activity!!.currencyCode)
        assertNull(parsed.stock.activity!!.netVolume24h)
    }

    @Test
    fun `share references cannot masquerade as token market quotes or silently change quote units`() {
        val tokenMasquerade = shareReferenceCatalog()
        row(tokenMasquerade).getJSONObject("quote").put("basis", "onchain_token_market")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(tokenMasquerade) }
        val wrongCurrency = shareReferenceCatalog()
        row(wrongCurrency).getJSONObject("quote").put("currency", "USD")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(wrongCurrency) }
        val wrongUnits = shareReferenceCatalog()
        row(wrongUnits).getJSONObject("quote").put("currencyBasis", "underlying_metadata")
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(wrongUnits) }
        val wrongSource = shareReferenceCatalog()
        row(wrongSource).put("activity", row(catalog()).getJSONObject("activity"))
        assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(wrongSource) }
    }

    private fun shareReferenceCatalog(): JSONObject = catalog().also { source ->
        val item = row(source)
        item.put("providerLabel", "Backed · Share reference")
        item.getJSONObject("quote").put("basis", "underlying_share_reference")
            .put("currency", "USDC").put("currencyBasis", "market_symbol")
        item.put("activity", JSONObject("""{
            "currency":"USDC", "source":"Backpack", "scope":"external_market",
            "volume24h":"1234567.890123456789", "netVolume24h":null,
            "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":null,
            "volumeReason":null, "netVolumeReason":"Backpack does not publish a buy/sell breakdown."
        }"""))
    }

    @Test
    fun `Robinhood original share data preserves its units ratio and source timestamps`() {
        val source = robinhoodReferenceCatalog()
        val stock = LiveMarketDataParser.catalog(source).instruments.single().stock
        val reference = requireNotNull(stock.robinhoodReference)
        assertEquals(BigDecimal("100"), reference.bid)
        assertEquals(BigDecimal("101"), reference.ask)
        assertEquals(BigDecimal("1.0005"), reference.currentMultiplier)
        assertEquals(BigDecimal("100.55025"), stock.quote.price)
        assertEquals(BigDecimal("4000000"), reference.dailyTradingVolume)
        assertNull(stock.activity?.volume24h)
        assertEquals(stock.quote.asOf, reference.asOf)
        assertEquals(Instant.parse("2026-09-18T12:00:00Z"), reference.receivedAt)
    }

    @Test
    fun `Robinhood share data rejects wrong units mismatched ratios and invalid values`() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { it.put("currency", "USDC") }, { it.put("basis", "onchain_token_market") },
            { it.put("volumeUnit", "USD") }, { it.put("currentMultiplier", "2") },
            { it.put("ask", "99") }, { it.put("dailyTradingVolume", "-1") },
            { it.put("dailyLow", "103") }, { it.put("tokenDecimals", 37) }, { it.put("tokenDecimals", 17) },
            { it.put("tokenDecimals", 18.5) }, { it.put("tokenDecimals", "18") },
            { it.put("asOf", "2026-09-18T11:59:58Z") },
            { it.put("receivedAt", "2026-09-18T12:00:01Z") },
        )
        for (mutate in mutations) {
            val source = robinhoodReferenceCatalog()
            mutate(row(source).getJSONObject("robinhoodReference"))
            assertThrows(IllegalArgumentException::class.java) { LiveMarketDataParser.catalog(source) }
        }
    }

    private fun robinhoodReferenceCatalog(): JSONObject = robinhoodCatalog().also { source ->
        val item = row(source)
        item.getJSONObject("quote").put("price", "100.55025")
            .put("asOf", "2026-09-18T11:59:59Z")
        item.put("robinhoodReference", JSONObject("""{
            "basis":"underlying_share_reference", "currency":"USD",
            "bid":"100", "ask":"101", "dailyHigh":"102", "dailyLow":"99",
            "dailyTradingVolume":"4000000", "volumeUnit":"underlying_shares",
            "currentMultiplier":"1.0005", "tokenDecimals":18,
            "asOf":"2026-09-18T11:59:59Z", "receivedAt":"2026-09-18T12:00:00Z",
            "isTradingHalt":false
        }"""))
    }

    private fun row(source: JSONObject) = source.getJSONArray("stocks").getJSONObject(0)

    private fun robinhoodCatalog(): JSONObject = catalog().also { source ->
        val item = row(source)
        item.put("id", "robinhood:MSFT").put("provider", "robinhood").put("providerAssetId", "MSFT")
            .put("providerLabel", "Robinhood Stock Tokens").put("symbol", "MSFT").put("name", "Microsoft Stock Token")
            .put("deployments", org.json.JSONArray().put(JSONObject().put("network", "Robinhood Chain")
                .put("address", ROBINHOOD_CONTRACT).put("chainId", 4663).put("decimals", 18)))
        item.getJSONObject("quote").put("currencyBasis", "quoted_currency")
        item.getJSONObject("statistics").put("scope", "robinhood_chain_token")
            .put("network", "robinhood_chain").put("mint", ROBINHOOD_CONTRACT)
        item.put("activity", JSONObject("""{
            "currency":"USD", "source":"Robinhood", "scope":"robinhood_chain_token",
            "volume24h":null, "netVolume24h":null, "receivedAt":null, "updatedAt":null,
            "volumeReason":"Robinhood price feed does not report token volume.",
            "netVolumeReason":"Robinhood price feed does not report buy/sell flow."
        }"""))
    }

    private companion object {
        const val ROBINHOOD_CONTRACT = "0x1111111111111111111111111111111111111111"
    }

    private fun catalog() = JSONObject("""{
        "schemaVersion":1, "mode":"live-read-only", "receivedAt":"2026-09-18T12:00:00Z",
        "providers":[{"id":"backed","status":"ok"},{"id":"backpack","status":"ok"},
            {"id":"prestocks","status":"ok"},{"id":"robinhood","status":"ok"}],
        "stocks":[{
            "id":"backed:example-msft", "provider":"backed", "providerAssetId":"example-msft",
            "providerLabel":"Backed xStocks", "symbol":"MSFTx", "name":"Microsoft xStock", "logoUrl":null,
            "quote":{"price":"497.200000000000000001", "currency":"USD", "currencyBasis":"underlying_metadata",
                "changeAmount":null,"changePercent":null,"asOf":null,"receivedAt":"2026-09-18T12:00:00Z",
                "basis":"provider_indicative_token"},
            "volume24h":null, "trading":{"enabled":false}, "deployments":[],
            "statistics":{"scope":"solana_token", "network":null, "mint":null, "updatedAt":null,
                "marketCapitalization":{"value":null,"unit":"USD","source":null,"status":"unavailable",
                    "basis":"token_market_cap","receivedAt":null,"reason":"No observation."},
                "liquidity":{"value":null,"unit":"USD","source":null,"status":"unavailable",
                    "basis":"reported_token_liquidity","receivedAt":null,"reason":"No observation."},
                "holderCount":{"value":null,"unit":"count","source":null,"status":"unavailable",
                    "basis":"token_holders","receivedAt":null,"reason":"No observation."},
                "organicScore":{"value":null,"unit":"score","source":null,"status":"unavailable",
                    "basis":"organic_activity_score","receivedAt":null,"reason":"No observation."}},
            "activity":{"currency":"USD", "source":"Jupiter", "scope":"solana_token",
                "volume24h":"200000000000000000000.2469135780246913578", "netVolume24h":"-0.000000000000000000002",
                "receivedAt":"2026-09-18T12:00:00Z", "updatedAt":"2026-09-18T11:59:59.557Z",
                "volumeReason":null, "netVolumeReason":null}
        }]
    }""")
}
