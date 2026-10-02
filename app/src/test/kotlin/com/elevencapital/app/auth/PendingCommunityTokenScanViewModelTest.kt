package com.elevencapital.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingCommunityTokenScanViewModelTest {
    private val spxtrMint = "Xs3oZwbHvqis4NYcf4YKWmEia2eC84wSiVrcYcTqpH8"
    private val anotherMint = "So11111111111111111111111111111111111111112"

    @Test fun `valid SPXTR scan survives route replacement and is consumed once`() {
        val handoff = PendingCommunityTokenScanViewModel()
        assertTrue(handoff.offer("first-user", spxtrMint))
        val scan = handoff.pending.value!!
        assertEquals("first-user", scan.ownerUserId)
        assertEquals(spxtrMint, scan.mintAddress)

        assertTrue(handoff.consume(scan))
        assertNull(handoff.pending.value)
        assertFalse(handoff.consume(scan))
    }

    @Test fun `invalid scan cannot replace a valid pending result`() {
        val handoff = PendingCommunityTokenScanViewModel()
        assertTrue(handoff.offer("first-user", spxtrMint))
        val pending = handoff.pending.value

        assertFalse(handoff.offer("first-user", "not a token"))
        assertFalse(handoff.offer("", anotherMint))
        assertEquals(pending, handoff.pending.value)
    }

    @Test fun `another account cannot receive previous account scan`() {
        val handoff = PendingCommunityTokenScanViewModel()
        assertTrue(handoff.offer("first-user", spxtrMint))
        val oldScan = handoff.pending.value!!

        handoff.discardOtherOwner("first-user")
        assertEquals(oldScan, handoff.pending.value)
        handoff.discardOtherOwner("second-user")
        assertNull(handoff.pending.value)

        assertTrue(handoff.offer("second-user", anotherMint))
        assertFalse(handoff.consume(oldScan))
        assertEquals("second-user", handoff.pending.value?.ownerUserId)
    }

    @Test fun `stale callback cannot consume a newer scan of the same token`() {
        val handoff = PendingCommunityTokenScanViewModel()
        assertTrue(handoff.offer("first-user", spxtrMint))
        val first = handoff.pending.value!!
        assertTrue(handoff.offer("first-user", spxtrMint))
        val second = handoff.pending.value!!

        assertTrue(second.id > first.id)
        assertFalse(handoff.consume(first))
        assertEquals(second, handoff.pending.value)
    }
}
