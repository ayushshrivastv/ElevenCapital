package com.elevencapital.app.communitybuy

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityMainnetVerifierTest {
    @Test fun `fixed genesis and valid blockhash are required before signing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""))
            server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"context":{"slot":1},"value":true}}"""))
            val verifier = CommunityMainnetVerifier(server.url("/").toString(), OkHttpClient(), Unit)
            verifier.verifyRecentBlockhash("a-reviewed-blockhash")
            assertEquals(2, server.requestCount)
            assertEquals("getGenesisHash", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("method"))
            assertEquals("isBlockhashValid", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("method"))
        }
    }

    @Test fun `wrong cluster or stale blockhash fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":"EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"}"""))
            val verifier = CommunityMainnetVerifier(server.url("/").toString(), OkHttpClient(), Unit)
            assertThrows(CommunityBuyException::class.java) { runBlocking {
                verifier.verifyRecentBlockhash("a-reviewed-blockhash")
            } }
            assertEquals(1, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}"""))
            server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"context":{"slot":1},"value":false}}"""))
            val verifier = CommunityMainnetVerifier(server.url("/").toString(), OkHttpClient(), Unit)
            assertThrows(CommunityBuyException::class.java) { runBlocking {
                verifier.verifyRecentBlockhash("a-reviewed-blockhash")
            } }
        }
    }

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .addHeader("Content-Type", "application/json").setBody(body)
}
