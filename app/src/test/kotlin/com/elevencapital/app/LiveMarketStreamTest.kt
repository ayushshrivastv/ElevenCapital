package com.elevencapital.app

import com.elevencapital.app.data.*
import com.elevencapital.core.market.recoveringMarketStream
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real loopback WebSocket tests: no live providers, external keys, or user data. */
class LiveMarketStreamTest {
    @Test fun `client decodes socket frames and sends viewport plus detail on one connection`() = runBlocking {
        MockWebServer().use { server ->
            val subscriptions = ConcurrentLinkedQueue<String>()
            server.enqueue(socketResponse { socket, text ->
                subscriptions.add(text)
                socket.send("""{"type":"status","state":"loading"}""")
                socket.send(heartbeat(0))
            })
            server.start()
            val client = client(server)
            client.subscribe(MarketSubscription(setOf(StockId("backed:test")), StockId("backed:test") to ChartRange.ONE_DAY))
            val events = withTimeout(5_000) { client.stream().take(2).toList() }
            assertEquals(MarketStreamEvent.Status("loading"), events.first())
            assertEquals(MarketStreamEvent.Heartbeat(Instant.parse("2026-09-19T00:00:00Z")), events.last())
            assertEquals("/v1/market/ws", server.takeRequest().path)
            val request = JSONObject(subscriptions.single())
            assertEquals("subscribe", request.getString("type"))
            assertEquals("ONE_DAY", request.getJSONObject("detail").getString("range"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `closed socket reconnects with current subscriptions and a fresh authoritative snapshot`() = runBlocking {
        MockWebServer().use { server ->
            val subscriptions = ConcurrentLinkedQueue<String>()
            server.enqueue(socketResponse { socket, text ->
                subscriptions.add(text)
                socket.send(snapshot("first", 9))
                socket.close(1000, "test disconnect")
            })
            server.enqueue(socketResponse { socket, text ->
                subscriptions.add(text)
                socket.send(snapshot("second", 0))
            })
            server.start()
            val client = client(server)
            val store = OrderedMarketCatalog()
            var failures = 0
            val events = withTimeout(5_000) {
                recoveringMarketStream(
                    connect = { store.beginConnection(); client.stream() },
                    onInterrupted = { failures++; client.subscribe(MarketSubscription(setOf(StockId("backpack:new")))) },
                    pause = {},
                ).take(2).toList()
            }
            assertEquals(2, server.requestCount)
            assertEquals(1, failures)
            assertEquals("second", (events.last() as MarketStreamEvent.Snapshot).sessionId)
            assertEquals("backpack:new", JSONObject(subscriptions.last()).getJSONArray("ids").getString(0))
        }
    }

    @Test fun `lifecycle cancellation closes socket and stops reconnecting`() = runBlocking {
        MockWebServer().use { server ->
            val closed = AtomicBoolean(false)
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(heartbeat(0)) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.set(true) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.set(true) }
            }))
            server.start()
            var received = 0
            val foreground = launch { recoveringMarketStream(client(server)::stream, {}, pause = {}).collect { received++ } }
            withTimeout(5_000) { while (received == 0) delay(10) }
            foreground.cancelAndJoin()
            withTimeout(5_000) { while (!closed.get()) delay(10) }
            delay(100)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `silent upgrade times out and next connection recovers automatically`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(socketResponse { socket, _ -> socket.send(heartbeat(1)) })
            server.start()
            val http = OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build()
            val dataClient = LiveMarketDataClient(server.url("/").toString(), http)
            var failures = 0
            val events = withTimeout(5_000) {
                recoveringMarketStream(dataClient::stream, { failures++ }, pause = { delay(25) }).take(1).toList()
            }
            assertEquals(1, failures)
            assertEquals(2, server.requestCount)
            assertTrue(events.single() is MarketStreamEvent.Heartbeat)
        }
    }

    @Test fun `viewport changes reuse socket and repeated equal subscriptions are coalesced`() = runBlocking {
        MockWebServer().use { server ->
            val subscriptions = ConcurrentLinkedQueue<String>()
            server.enqueue(socketResponse { socket, text -> subscriptions.add(text); socket.send(heartbeat(0)) })
            server.start()
            val client = client(server)
            val job = launch { client.stream().collect {} }
            withTimeout(5_000) { while (subscriptions.isEmpty()) delay(10) }
            val changed = MarketSubscription(setOf(StockId("backed:next")))
            client.subscribe(changed)
            withTimeout(5_000) { while (subscriptions.size < 2) delay(10) }
            repeat(4) { client.subscribe(changed) }
            delay(100)
            assertEquals(2, subscriptions.size)
            assertEquals(1, server.requestCount)
            job.cancelAndJoin()
        }
    }

    @Test fun `invalid frames reconnect instead of killing automatic updates`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(socketResponse { socket, _ -> socket.send("""{"type":"snapshot","revision":1.5}""") })
            server.enqueue(socketResponse { socket, _ -> socket.send(heartbeat(2)) })
            server.start()
            var failures = 0
            val events = withTimeout(5_000) {
                recoveringMarketStream(client(server)::stream, { failures++ }, pause = {}).take(1).toList()
            }
            assertEquals(1, failures)
            assertTrue(events.single() is MarketStreamEvent.Heartbeat)
        }
    }

    @Test fun `socket burst keeps the latest frame without overflowing a slow consumer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(socketResponse { socket, _ ->
                repeat(2_000) { socket.send("""{"type":"status","state":"loading"}""") }
                socket.send(heartbeat(3))
            })
            server.start()
            val heartbeat = withTimeout(5_000) {
                client(server).stream()
                    .onEach { delay(5) }
                    .filterIsInstance<MarketStreamEvent.Heartbeat>()
                    .first()
            }
            assertEquals(Instant.parse("2026-09-19T00:00:03Z"), heartbeat.serverTime)
            assertEquals(1, server.requestCount)
        }
    }

    private fun client(server: MockWebServer) = LiveMarketDataClient(server.url("/").toString())
    private fun socketResponse(onSubscription: (WebSocket, String) -> Unit) =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onSubscription(webSocket, text)
        })
    private fun heartbeat(second: Int) = """{"type":"heartbeat","serverTime":"2026-09-19T00:00:0${second}Z"}"""
    private fun snapshot(session: String, revision: Int) = """{
        "type":"snapshot","sessionId":"$session","revision":$revision,
        "catalog":{"schemaVersion":1,"mode":"live-read-only","receivedAt":"2026-09-19T00:00:00Z",
          "stocks":[],"providers":[{"id":"backed","status":"ok"},{"id":"backpack","status":"ok"},
            {"id":"prestocks","status":"ok"}]}
    }"""
}
