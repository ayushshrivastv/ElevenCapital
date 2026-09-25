package com.elevencapital.app.purchase

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseOneTapGateTest {
    @Test
    fun `concurrent purchase taps reserve only one quote request`() {
        val state = MutableStateFlow(PurchaseUiState(stockId = "prestocks:ANTHROPIC", phase = PurchasePhase.ENTRY))
        val start = CountDownLatch(1)
        val attempts = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..24).map {
                pool.submit {
                    start.await()
                    if (reserveQuoteRequest(state, state.value) != null) attempts.incrementAndGet()
                }
            }
            start.countDown()
            jobs.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, attempts.get())
            assertEquals(PurchasePhase.QUOTING, state.value.phase)
            assertNull(reserveQuoteRequest(state, state.value))
        } finally {
            pool.shutdownNow()
        }
    }
}
