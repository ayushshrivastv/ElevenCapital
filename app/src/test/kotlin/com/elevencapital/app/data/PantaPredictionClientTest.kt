package com.elevencapital.app.data

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PantaPredictionClientTest {
    private val marketId = "11111111111111111111111111111111"
    private val wallet = "22222222222222222222222222222222"
    private val token = "aaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbb.cccccccccccccccc"

    private fun client(server: MockWebServer) = PantaPredictionClient(
        server.url("/").toString(), PantaAccessTokenProvider { token }, allowLoopbackHttp = true)

    private fun response(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun market(yesPrice: String? = null): String = JSONObject()
        .put("marketId", marketId).put("title", "Will the event happen?")
        .put("description", "Public market description")
        .put("category", "crypto").put("phase", "primary").put("status", "open")
        .put("volumeUsdc", "1200.00")
        .put("yesPrice", yesPrice ?: JSONObject.NULL)
        .put("noPrice", if (yesPrice == null) JSONObject.NULL else "0.48")
        .toString()

    @Test fun `list and detail preserve Panta price distinction and private backend auth`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"items":[${market()}],"nextCursor":null}"""))
            server.enqueue(response(market("0.52")))
            server.start()
            val api = client(server)
            val list = api.markets("user-1")
            assertEquals(1, list.size)
            assertNull(list.single().yesPrice)
            assertEquals(BigDecimal("0.52"), api.market("user-1", marketId).yesPrice)
            val first = server.takeRequest()
            assertEquals("/v1/panta/markets?limit=20&status=primary", first.path)
            assertEquals("Bearer $token", first.getHeader("Authorization"))
            assertEquals("no-store", first.getHeader("Cache-Control"))
            assertEquals("/v1/panta/markets/$marketId", server.takeRequest().path)
        }
    }

    @Test fun `positions are wallet bound and never locally fabricated`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response("""{"wallet":"$wallet","positions":[{"marketId":"$marketId","side":"yes","shares":"38.40","phase":"primary","claimable":false,"outcome":null}]}"""))
            server.start()
            val positions = client(server).positions("user-1", wallet)
            assertEquals(BigDecimal("38.40"), positions.single().shares)
            assertEquals("yes", positions.single().side)
            assertEquals("/v1/panta/positions?wallet=$wallet", server.takeRequest().path)
        }
    }

    @Test fun `unavailable server does not become practice market data`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json")
                .setBody("""{"error":"PANTA_UNAVAILABLE"}"""))
            server.start()
            assertThrows(java.io.IOException::class.java) {
                runBlocking { client(server).markets("user-1") }
            }
        }
        Unit
    }

    @Test fun `invalid detail price cannot be displayed as live probability`() {
        assertThrows(java.io.IOException::class.java) {
            PantaPredictionClient.parseMarket(JSONObject(market("1.50")))
        }
    }
}
