package com.elevencapital.app

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.data.LiveWalletPortfolioClient
import com.elevencapital.app.data.LiveWalletPortfolioParser
import com.elevencapital.app.data.WalletPortfolioHttpException
import com.elevencapital.app.data.WalletPortfolioStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveWalletPortfolioTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test fun `verified empty wallets produce actual zero and no fabricated stocks`() {
        val result = parse(emptyWallet())
        assertEquals(WalletPortfolioStatus.OK, result.status)
        assertEquals(BigDecimal.ZERO, result.balanceUsd)
        assertTrue(result.holdings.isEmpty())
        assertTrue(result.holdingsComplete)
        assertEquals(2, result.networks.size)
    }

    @Test fun `network failure cannot masquerade as zero or an empty successful wallet`() {
        val partial = emptyWallet().put("status", "partial").put("balanceUsd", JSONObject.NULL).put("holdingsComplete", false)
        network(partial, 1).put("status", "unavailable").put("observedAt", JSONObject.NULL)
        val result = parse(partial)
        assertNull(result.balanceUsd)
        assertEquals(WalletPortfolioStatus.PARTIAL, result.status)
        assertThrows(IllegalArgumentException::class.java) { parse(partial.put("balanceUsd", "0")) }
        partial.put("status", "ok")
        assertThrows(IllegalArgumentException::class.java) { parse(partial) }
    }

    @Test fun `unavailable response preserves known holding without guessing missing price`() {
        val response = emptyWallet().put("status", "unavailable").put("balanceUsd", JSONObject.NULL)
            .put("holdingsComplete", false).put("unpricedAssets", 1).put("holdings", JSONArray().put(holding(value = JSONObject.NULL)))
        network(response, 0).put("status", "unavailable").put("observedAt", JSONObject.NULL)
        network(response, 1).put("status", "unavailable").put("observedAt", JSONObject.NULL)
        val result = parse(response)
        assertEquals(WalletPortfolioStatus.UNAVAILABLE, result.status)
        assertNull(result.balanceUsd)
        assertNull(result.holdings.single().valueUsd)
        assertEquals(BigDecimal("0.000000000000000001"), result.holdings.single().quantity)
    }

    @Test fun `exact decimal totals and subunit stock quantities retain all digits`() {
        val amount = "200000000000000000000.123456789123456789"
        val response = emptyWallet().put("balanceUsd", amount)
            .put("holdings", JSONArray().put(holding(value = amount)))
        val result = parse(response)
        assertEquals(BigDecimal(amount), result.balanceUsd)
        assertEquals(BigDecimal(amount), result.holdings.single().valueUsd)
        assertEquals(BigDecimal("0.000000000000000001"), result.holdings.single().quantity)
    }

    @Test fun `numeric exponent negative and unbounded decimal forms are rejected`() {
        for (invalid in listOf<Any>(0, 0.01, "1e30", "NaN", "-1", "+1", "01", "1." , "1".repeat(101))) {
            assertThrows(IllegalArgumentException::class.java) { parse(emptyWallet().put("balanceUsd", invalid)) }
        }
        for (invalid in listOf("0", "0.000", "-1")) {
            val response = emptyWallet().put("holdings", JSONArray().put(holding().put("quantity", invalid)))
            assertThrows(IllegalArgumentException::class.java) { parse(response) }
        }
    }

    @Test fun `missing required nullable fields cannot silently become null`() {
        for (key in listOf("balanceUsd", "message", "receivedAt", "networks", "holdings", "tokenHoldings", "holdingsComplete", "unpricedAssets")) {
            val response = emptyWallet().apply { remove(key) }
            assertThrows(JSONException::class.java) { parse(response) }
        }
        val missingObservation = emptyWallet()
        network(missingObservation, 0).remove("observedAt")
        assertThrows(JSONException::class.java) { parse(missingObservation) }
    }

    @Test fun `missing duplicate and unknown networks are rejected`() {
        val missing = emptyWallet()
        missing.getJSONArray("networks").remove(1)
        assertThrows(IllegalArgumentException::class.java) { parse(missing) }
        val duplicate = emptyWallet()
        network(duplicate, 1).put("chain", "SOLANA")
        assertThrows(IllegalArgumentException::class.java) { parse(duplicate) }
        val unknown = emptyWallet()
        network(unknown, 1).put("chain", "ARBITRUM")
        assertThrows(IllegalArgumentException::class.java) { parse(unknown) }
    }

    @Test fun `stale future and malformed receive or chain timestamps are rejected`() {
        for (invalid in listOf("2026-09-18T11:54:59Z", "2026-09-18T12:00:31Z", "not-a-time")) {
            assertThrows(RuntimeException::class.java) { parse(emptyWallet().put("receivedAt", invalid)) }
            val observation = emptyWallet()
            network(observation, 0).put("observedAt", invalid)
            assertThrows(RuntimeException::class.java) { parse(observation) }
        }
        val missing = emptyWallet()
        network(missing, 0).put("observedAt", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { parse(missing) }
    }

    @Test fun `five minute observation boundary is accepted without resetting source timestamp`() {
        val atBoundary = emptyWallet().put("receivedAt", "2026-09-18T11:55:00Z")
        network(atBoundary, 0).put("observedAt", "2026-09-18T11:55:00Z")
        network(atBoundary, 1).put("observedAt", "2026-09-18T11:55:00Z")
        assertEquals(now.minusSeconds(300), parse(atBoundary).receivedAt)
    }

    @Test fun `duplicate holdings cannot double count while different provider identities stay separate`() {
        val response = emptyWallet().put("balanceUsd", "4")
            .put("holdings", JSONArray().put(holding(value = "2")).put(holding(value = "2")))
        assertThrows(IllegalArgumentException::class.java) { parse(response) }
        response.getJSONArray("holdings").getJSONObject(1).put("stockId", "backpack:MSFT.US")
        assertEquals(2, parse(response).holdings.size)
    }

    @Test fun `prestocks holding identity is accepted without colliding with other providers`() {
        val response = emptyWallet().put("balanceUsd", "6")
            .put("holdings", JSONArray()
                .put(holding(value = "2"))
                .put(holding(value = "4").put("stockId", "prestocks:openai")))
        val result = parse(response)
        assertEquals(listOf("backed:microsoft", "prestocks:openai"),
            result.holdings.map { it.stockId.value })
    }

    @Test fun `ok requires full valuation and cannot omit valued stock amounts from total`() {
        assertThrows(IllegalArgumentException::class.java) { parse(emptyWallet().put("holdingsComplete", false)) }
        assertThrows(IllegalArgumentException::class.java) { parse(emptyWallet().put("unpricedAssets", 1)) }
        val unvalued = emptyWallet().put("holdings", JSONArray().put(holding(value = JSONObject.NULL)))
        assertThrows(IllegalArgumentException::class.java) { parse(unvalued) }
        val omitted = emptyWallet().put("balanceUsd", "1").put("holdings", JSONArray().put(holding(value = "2")))
        assertThrows(IllegalArgumentException::class.java) { parse(omitted) }
    }

    @Test fun `token rows require exact observed valuation and cannot hide value from total`() {
        val token = JSONObject().put("chain", "SOLANA").put("assetId", "SOLANA:native")
            .put("symbol", "SOL").put("quantity", "0.25").put("unitPriceUsd", "100")
            .put("valueUsd", "25")
        val response = emptyWallet().put("balanceUsd", "25").put("tokenHoldings", JSONArray().put(token))
        val parsed = parse(response).tokenHoldings.single()
        assertEquals(BigDecimal("0.25"), parsed.quantity)
        assertEquals(BigDecimal("100"), parsed.unitPriceUsd)
        assertEquals(BigDecimal("25"), parsed.valueUsd)
        assertThrows(IllegalArgumentException::class.java) { parse(response.put("balanceUsd", "24")) }
        response.put("balanceUsd", "25")
        assertThrows(IllegalArgumentException::class.java) { parse(response.put("tokenHoldings", JSONArray().put(token).put(token))) }
        response.put("tokenHoldings", JSONArray().put(token))
        token.put("unitPriceUsd", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { parse(response) }
    }

    @Test fun `complete quantities can retain unpriced stocks while incomplete reads cannot claim completeness`() {
        val response = emptyWallet().put("status", "partial").put("balanceUsd", JSONObject.NULL)
            .put("unpricedAssets", 1).put("holdings", JSONArray().put(holding(value = JSONObject.NULL)))
        val unpriced = parse(response)
        assertTrue(unpriced.holdingsComplete)
        assertEquals(1, unpriced.holdings.size)
        assertNull(unpriced.balanceUsd)
        network(response, 1).put("status", "unavailable").put("observedAt", JSONObject.NULL)
        assertThrows(IllegalArgumentException::class.java) { parse(response) }
        response.put("holdingsComplete", false)
        assertFalse(parse(response).holdingsComplete)
        response.put("holdingsComplete", "false")
        assertThrows(IllegalArgumentException::class.java) { parse(response) }
    }

    @Test fun `invalid schema unit scope and fractional counts are rejected`() {
        for ((field, value) in listOf("schemaVersion" to 2, "schemaVersion" to "1", "currency" to "USDC",
            "scope" to "all-assets", "status" to "cached", "unpricedAssets" to 0.5, "unpricedAssets" to -1)) {
            assertThrows(IllegalArgumentException::class.java) { parse(emptyWallet().put(field, value)) }
        }
    }

    @Test fun `public addresses are posted in body without URL parameters or auth headers`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(emptyWallet().toString()))
            server.start()
            val result = LiveWalletPortfolioClient(server.url("/").toString(), now = { now }).portfolio(wallets())
            assertEquals(BigDecimal.ZERO, result.balanceUsd)
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/v1/wallet/portfolio", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            val sent = JSONObject(request.body.readUtf8()).getJSONArray("wallets")
            assertEquals("SOLANA", sent.getJSONObject(0).getString("chain"))
            assertEquals(wallets()[1].address, sent.getJSONObject(1).getString("address"))
        }
    }

    @Test fun `multiple owned wallets are retained but EVM case variants cannot count twice`() = runBlocking {
        val extra = UserWallet(WalletChain.ETHEREUM, "0xabcdef0000000000000000000000000000000001")
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(emptyWallet().toString()))
            server.start()
            val client = LiveWalletPortfolioClient(server.url("/").toString(), now = { now })
            client.portfolio(wallets() + extra)
            val sent = JSONObject(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).body.readUtf8())
            assertEquals(3, sent.getJSONArray("wallets").length())
            val duplicate = extra.copy(address = "0xABCDEF0000000000000000000000000000000001")
            val failure = runCatching { client.portfolio(wallets() + extra + duplicate) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `oversized and non-json responses fail instead of exposing a balance`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(" ".repeat(256 * 1024 + 1)))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>offline</html>"))
            server.start()
            val client = LiveWalletPortfolioClient(server.url("/").toString(), now = { now })
            repeat(2) {
                val failure = runCatching { client.portfolio(wallets()) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
            }
        }
    }

    @Test fun `HTTP failure is status only and redirects never forward wallet addresses`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("sensitive upstream details"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
            val client = LiveWalletPortfolioClient(server.url("/").toString(), now = { now })
            for (status in listOf(503, 307)) {
                val failure = runCatching { client.portfolio(wallets()) }.exceptionOrNull()
                assertTrue(failure is WalletPortfolioHttpException)
                assertEquals(status, (failure as WalletPortfolioHttpException).statusCode)
                assertFalse(failure.message.orEmpty().contains("sensitive"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `cancelling foreground request cancels the underlying call without waiting for timeout`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val http = OkHttpClient()
            val client = LiveWalletPortfolioClient(server.url("/").toString(), http, now = { now })
            var returned = false
            val request = launch { client.portfolio(wallets()); returned = true }
            withTimeout(5_000) { while (server.requestCount == 0) delay(10) }
            request.cancelAndJoin()
            withTimeout(5_000) { while (http.dispatcher.runningCallsCount() != 0) delay(10) }
            assertFalse(returned)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `remote cleartext and missing duplicate or malformed wallet identities are rejected`() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { LiveWalletPortfolioClient("http://example.com") }
        assertThrows(IllegalArgumentException::class.java) { LiveWalletPortfolioClient("https://user:pass@example.com") }
        val client = LiveWalletPortfolioClient("http://127.0.0.1:9")
        for (invalid in listOf(emptyList(), listOf(wallets()[0]), listOf(wallets()[0], wallets()[0]),
            listOf(wallets()[0], UserWallet(WalletChain.ETHEREUM, "invalid")))) {
            assertTrue(runCatching { client.portfolio(invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun parse(source: JSONObject) = LiveWalletPortfolioParser.portfolio(source, now)
    private fun network(source: JSONObject, index: Int) = source.getJSONArray("networks").getJSONObject(index)
    private fun holding(value: Any = "0") = JSONObject().put("stockId", "backed:microsoft")
        .put("quantity", "0.000000000000000001").put("valueUsd", value)
    private fun wallets() = listOf(UserWallet(WalletChain.SOLANA, "11111111111111111111111111111111"),
        UserWallet(WalletChain.ETHEREUM, "0x0000000000000000000000000000000000000001"))
    private fun jsonResponse(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun emptyWallet() = JSONObject("""{
        "schemaVersion":1,"scope":"supported-wallet-assets","currency":"USD","status":"ok",
        "receivedAt":"2026-09-18T12:00:00Z","balanceUsd":"0","holdings":[],"tokenHoldings":[],"holdingsComplete":true,"unpricedAssets":0,
        "networks":[{"chain":"SOLANA","status":"ok","observedAt":"2026-09-18T12:00:00Z"},
            {"chain":"ETHEREUM","status":"ok","observedAt":"2026-09-18T12:00:00Z"}],"message":null
    }""")
}
