package com.elevencapital.app.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SessionRestoreRecoveryTest {
    @Test fun `repeated undetermined SDK events neither duplicate automatic work nor erase delayed retry`() = runBlocking {
        var calls = 0
        var available = false
        val ready = CompletableDeferred<Unit>()
        val recovery = SessionRestoreRecovery(
            scope = this,
            isCurrent = { true },
            verify = { calls++; "unverified" },
            onObservation = {},
            onRetryAvailable = { available = it; if (it) ready.complete(Unit) },
            retryAvailableAfterMs = 15,
            automaticDelaysMs = listOf(0, 0, 0),
        )
        recovery.start()
        repeat(20) { recovery.start() }
        withTimeout(500) { ready.await() }
        assertEquals(3, calls)
        assertTrue(available)
        repeat(20) { recovery.start() }
        assertEquals(3, calls)
        assertTrue(available)
        recovery.stop()
        assertFalse(available)
    }

    @Test fun `manual taps are single flight and transient failure never publishes an authenticated or signed out observation`() = runBlocking {
        var calls = 0
        var active = 0
        var mostActive = 0
        val observations = mutableListOf<String>()
        val retryEvents = Channel<Boolean>(Channel.UNLIMITED)
        val recovery = SessionRestoreRecovery<String>(
            scope = this,
            isCurrent = { true },
            verify = {
                calls++; active++; mostActive = maxOf(mostActive, active)
                try { delay(10); throw IllegalStateException("Transient network failure") }
                finally { active-- }
            },
            onObservation = { observations += it },
            onRetryAvailable = { retryEvents.trySend(it) },
            retryAvailableAfterMs = 5,
            automaticDelaysMs = listOf(0),
        )
        recovery.start()
        recovery.retry() // No retry is available during automatic verification.
        assertTrue(withTimeout(500) { retryEvents.receive() })
        recovery.retry()
        recovery.retry()
        assertFalse(withTimeout(500) { retryEvents.receive() })
        assertTrue(withTimeout(500) { retryEvents.receive() })
        assertEquals(2, calls)
        assertEquals(1, mostActive)
        assertTrue(observations.isEmpty())
        recovery.stop()
    }

    @Test fun `an SDK call that never returns is bounded and offers recovery without clearing the session`() = runBlocking {
        var calls = 0
        var credentialsRemain = true
        val ready = CompletableDeferred<Unit>()
        val recovery = SessionRestoreRecovery<String>(
            scope = this,
            isCurrent = { credentialsRemain },
            verify = { calls++; awaitCancellation() },
            onObservation = { credentialsRemain = false },
            onRetryAvailable = { if (it) ready.complete(Unit) },
            attemptTimeoutMs = 10,
            retryAvailableAfterMs = 5,
            automaticDelaysMs = listOf(0, 0),
        )
        recovery.start()
        withTimeout(500) { ready.await() }
        assertEquals(2, calls)
        assertTrue(credentialsRemain)
        recovery.stop()
    }

    @Test fun `a retired SDK reply cannot replace a later login operation`() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newSeen = CompletableDeferred<Unit>()
        var calls = 0
        val accepted = mutableListOf<String>()
        val recovery = SessionRestoreRecovery(
            scope = this,
            isCurrent = { true },
            verify = {
                if (++calls == 1) {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { oldRelease.await() }
                    "old-user"
                } else "new-user"
            },
            onObservation = { accepted += it; if (it == "new-user") newSeen.complete(Unit) },
            onRetryAvailable = {},
            automaticDelaysMs = listOf(0),
        )
        recovery.start()
        withTimeout(500) { oldStarted.await() }
        recovery.stop()
        recovery.start()
        withTimeout(500) { newSeen.await() }
        oldRelease.complete(Unit)
        delay(10)
        assertEquals(listOf("new-user"), accepted)
        recovery.stop()
    }

    @Test fun `SDK verified restoration still requires an identity matched device credential`() = runBlocking {
        val gate = AuthSessionGate(allowRestore = true)
        var state = ElevenAuthState(true, AuthPhase.RESTORING)
        val observed = CompletableDeferred<Unit>()
        lateinit var recovery: SessionRestoreRecovery<String>
        recovery = SessionRestoreRecovery(
            scope = this,
            isCurrent = { state.phase == AuthPhase.RESTORING || state.phase == AuthPhase.SESSION_UNVERIFIED },
            verify = { "verified-user" },
            onObservation = { user ->
                assertTrue(gate.acceptsObservedUser(user))
                recovery.stop()
                state = ElevenAuthState(true, AuthPhase.AUTHENTICATED, userId = user)
                observed.complete(Unit)
            },
            onRetryAvailable = { state = state.copy(sessionRestoreRetryAvailable = it) },
            automaticDelaysMs = listOf(0),
        )
        recovery.start()
        withTimeout(500) { observed.await() }
        assertEquals(EntrySurface.DEVICE_UNLOCK, entrySurface(state, DeviceUnlockState()))
        assertEquals(EntrySurface.DEVICE_UNLOCK,
            entrySurface(state, DeviceUnlockState(DeviceUnlockPhase.UNLOCKED, userId = "another-user")))
        assertEquals(EntrySurface.APP,
            entrySurface(state, DeviceUnlockState(DeviceUnlockPhase.UNLOCKED, userId = "verified-user")))
        assertFalse(state.sessionRestoreRetryAvailable)
    }

    @Test fun `timed out explicit logout stays blocked until deliberately retried cleanup succeeds`() = runBlocking {
        val gate = AuthSessionGate(allowRestore = true)
        assertTrue(gate.acceptsObservedUser("old-user"))
        gate.block()
        var calls = 0
        var state = ElevenAuthState(true, AuthPhase.RESTORING)
        val retry = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        lateinit var cleanup: SessionRestoreRecovery<Unit>
        cleanup = SessionRestoreRecovery(
            scope = this,
            isCurrent = { gate.isBlocked && state.phase != AuthPhase.SIGNED_OUT },
            verify = { if (++calls == 1) awaitCancellation() },
            onObservation = {
                cleanup.stop()
                state = ElevenAuthState(true, AuthPhase.SIGNED_OUT)
                finished.complete(Unit)
            },
            onRetryAvailable = { state = state.copy(sessionRestoreRetryAvailable = it); if (it) retry.complete(Unit) },
            attemptTimeoutMs = 10,
            retryAvailableAfterMs = 5,
            automaticDelaysMs = listOf(0),
        )
        cleanup.start()
        withTimeout(500) { retry.await() }
        assertTrue(gate.isBlocked)
        assertFalse(gate.acceptsObservedUser("old-user"))
        assertEquals(EntrySurface.SESSION_SHIELD, entrySurface(state, DeviceUnlockState()))
        cleanup.retry()
        withTimeout(500) { finished.await() }
        assertEquals(AuthPhase.SIGNED_OUT, state.phase)
        assertFalse(state.sessionRestoreRetryAvailable)
        assertTrue(gate.isBlocked)
    }
}
