package com.elevencapital.core.market

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MarketReconnectTest {
    @Test fun `reconnect grows with jitter stays bounded and resets after recovery`() {
        val backoff = MarketReconnectBackoff(Random(8))
        val ceilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        ceilings.forEach { ceiling -> assertTrue(backoff.nextDelayMillis() in ceiling / 2..ceiling) }
        repeat(100) { assertTrue(backoff.nextDelayMillis() in 15_000L..30_000L) }
        backoff.recovered()
        assertTrue(backoff.nextDelayMillis() in 500L..1_000L)
    }

    @Test fun `disconnect retains last value then replacement arrives without caller retry`() = runBlocking {
        var connections = 0
        val interruptions = mutableListOf<Exception>()
        val pauses = mutableListOf<Long>()
        val values = recoveringMarketStream(
            connect = {
                connections++
                if (connections == 1) flow { emit("cached price"); throw IOException("Connection lost") }
                else flowOf("new price")
            },
            onInterrupted = { interruptions += it },
            pause = { pauses += it },
        ).take(2).toList()
        assertEquals(listOf("cached price", "new price"), values)
        assertEquals(2, connections)
        assertEquals(1, interruptions.size)
        assertEquals(1, pauses.size)
    }

    @Test fun `repeated connection failure recovers automatically with increasing delay`() = runBlocking {
        var connections = 0
        val pauses = mutableListOf<Long>()
        val values = recoveringMarketStream(
            connect = { flow {
                connections++
                if (connections <= 3) throw IOException("Server starting")
                emit("ready")
            } },
            onInterrupted = {}, backoff = MarketReconnectBackoff(Random(9)), pause = { pauses += it },
        ).take(1).toList()
        assertEquals(listOf("ready"), values)
        assertEquals(4, connections)
        assertTrue(pauses[0] in 500L..1_000L)
        assertTrue(pauses[1] in 1_000L..2_000L)
        assertTrue(pauses[2] in 2_000L..4_000L)
    }

    @Test fun `clean remote close also reconnects`() = runBlocking {
        var connects = 0
        val result = recoveringMarketStream(
            connect = { flowOf(++connects) }, onInterrupted = {}, pause = {},
        ).take(2).toList()
        assertEquals(listOf(1, 2), result)
    }

    @Test fun `background cancellation closes upstream and never retries`() = runBlocking {
        var opened = false
        var closed = false
        var interruptions = 0
        val reader = async {
            recoveringMarketStream<Int>(
                connect = { flow {
                    opened = true
                    try { awaitCancellation() } finally { closed = true }
                } },
                onInterrupted = { interruptions++ }, pause = { error("Cancellation must not retry") },
            ).collect { }
        }
        yield()
        assertTrue(opened)
        reader.cancel()
        try { reader.await() } catch (_: CancellationException) { }
        assertTrue(closed)
        assertEquals(0, interruptions)
    }

    @Test fun `consumer failure is not mistaken for a server disconnection`() = runBlocking {
        var interruptions = 0
        try {
            recoveringMarketStream(connect = { flowOf(1) }, onInterrupted = { interruptions++ }, pause = {})
                .collect { throw IllegalStateException("UI failed") }
            fail("Consumer exception expected")
        } catch (expected: IllegalStateException) { assertEquals("UI failed", expected.message) }
        assertEquals(0, interruptions)
    }
}
