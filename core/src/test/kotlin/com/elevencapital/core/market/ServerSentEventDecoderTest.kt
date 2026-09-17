package com.elevencapital.core.market

import org.junit.Assert.*
import org.junit.Test

class ServerSentEventDecoderTest {
    @Test fun `multiline catalog frame is emitted only after complete event`() {
        val decoder = ServerSentEventDecoder()
        assertNull(decoder.line("\uFEFFretry: 2000"))
        assertNull(decoder.line(""))
        assertNull(decoder.line(": comment"))
        assertNull(decoder.line("event: snapshot"))
        assertNull(decoder.line("data: {"))
        assertNull(decoder.line("data: \"price\":\"123.4567890123456789\"}"))
        assertEquals(ServerSentEventDecoder.Event("snapshot", "{\n\"price\":\"123.4567890123456789\"}"), decoder.line(""))
    }

    @Test fun `heartbeat stays separate and missing event name defaults independently`() {
        val decoder = ServerSentEventDecoder()
        decoder.line("event: heartbeat")
        decoder.line("data: {\"serverTime\":\"now\"}")
        assertEquals("heartbeat", decoder.line("")?.name)
        decoder.line("data: second")
        assertEquals(ServerSentEventDecoder.Event("message", "second"), decoder.line(""))
    }

    @Test fun `partial frame cannot replace a valid catalog`() {
        val decoder = ServerSentEventDecoder()
        decoder.line("event: snapshot")
        assertNull(decoder.line("data: {\"incomplete\":"))
    }

    @Test fun `oversized lines and accumulated events are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ServerSentEventDecoder(12).line("a".repeat(13)) }
        val decoder = ServerSentEventDecoder(20)
        decoder.line("data: 1234567890")
        assertThrows(IllegalArgumentException::class.java) { decoder.line("data: 1234567890") }
    }
}
