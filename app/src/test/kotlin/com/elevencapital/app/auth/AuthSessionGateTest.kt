package com.elevencapital.app.auth

import org.junit.Assert.*
import org.junit.Test

class AuthSessionGateTest {
    @Test fun `verified prior session can restore when permitted`() {
        val gate = AuthSessionGate(allowRestore = true)
        assertTrue(gate.acceptsObservedUser("existing-user"))
        assertTrue(gate.matchesVerifiedUser("existing-user"))
    }

    @Test fun `persisted signed out intent blocks restoration after process restart`() {
        val gate = AuthSessionGate(allowRestore = false)
        assertTrue(gate.isBlocked)
        assertFalse(gate.acceptsObservedUser("former-user"))
        assertFalse(gate.matchesVerifiedUser("former-user"))
    }

    @Test fun `late authenticated event cannot complete an explicit browser login`() {
        val gate = AuthSessionGate(allowRestore = false)
        val attempt = gate.beginSignIn()
        assertFalse(gate.acceptsObservedUser("old-user"))
        assertFalse(gate.acceptsObservedUser("new-user"))
        assertTrue(gate.isCurrentSignIn(attempt))
        assertTrue(gate.completeSignIn(attempt, "new-user"))
        assertTrue(gate.acceptsObservedUser("new-user"))
    }

    @Test fun `logout during sign in invalidates the pending result`() {
        val gate = AuthSessionGate(allowRestore = true)
        val attempt = gate.beginSignIn()
        gate.block()
        assertFalse(gate.completeSignIn(attempt, "old-user"))
        assertFalse(gate.acceptsObservedUser("old-user"))
    }

    @Test fun `old completion cannot overwrite a newer sign in`() {
        val gate = AuthSessionGate(allowRestore = false)
        val old = gate.beginSignIn()
        gate.block()
        val current = gate.beginSignIn()
        assertFalse(gate.completeSignIn(old, "old-user"))
        assertTrue(gate.completeSignIn(current, "new-user"))
        assertFalse(gate.acceptsObservedUser("old-user"))
        assertTrue(gate.matchesVerifiedUser("new-user"))
    }

    @Test fun `a new sign in succeeds after logout`() {
        val gate = AuthSessionGate(allowRestore = true)
        assertTrue(gate.acceptsObservedUser("first-user"))
        gate.block()
        assertFalse(gate.matchesVerifiedUser("first-user"))
        val attempt = gate.beginSignIn()
        assertTrue(gate.completeSignIn(attempt, "second-user"))
        assertTrue(gate.acceptsObservedUser("second-user"))
    }

    @Test fun `an unexpected identity cannot replace a verified account`() {
        val gate = AuthSessionGate(allowRestore = true)
        assertTrue(gate.acceptsObservedUser("expected-user"))
        assertFalse(gate.acceptsObservedUser("unexpected-user"))
        assertTrue(gate.matchesVerifiedUser("expected-user"))
    }

    @Test fun `blank SDK user id cannot open the session gate`() {
        val gate = AuthSessionGate(allowRestore = true)
        assertFalse(gate.acceptsObservedUser(""))
        val attempt = gate.beginSignIn()
        assertFalse(gate.completeSignIn(attempt, " "))
        assertFalse(gate.matchesVerifiedUser(" "))
    }

    @Test fun `duplicate completion is rejected without changing the valid account`() {
        val gate = AuthSessionGate(allowRestore = false)
        val attempt = gate.beginSignIn()
        assertTrue(gate.completeSignIn(attempt, "expected-user"))
        assertFalse(gate.completeSignIn(attempt, "another-user"))
        assertTrue(gate.matchesVerifiedUser("expected-user"))
    }

    @Test fun `failed attempt can be followed by a fresh successful attempt`() {
        val gate = AuthSessionGate(allowRestore = false)
        val failed = gate.beginSignIn()
        gate.block()
        val retry = gate.beginSignIn()
        assertFalse(gate.isCurrentSignIn(failed))
        assertTrue(gate.isCurrentSignIn(retry))
        assertTrue(gate.completeSignIn(retry, "user"))
    }

    @Test fun `transient browser failure resumes persisted session restoration`() {
        val gate = AuthSessionGate(allowRestore = false)
        val browserAttempt = gate.beginSignIn()
        assertTrue(gate.isCurrentSignIn(browserAttempt))
        gate.resumeRestore()
        assertFalse(gate.isCurrentSignIn(browserAttempt))
        assertTrue(gate.acceptsObservedUser("persisted-user"))
        assertTrue(gate.matchesVerifiedUser("persisted-user"))
    }

    @Test fun `empty identity never reports authenticated in UI state`() {
        val state = ElevenAuthState(true, AuthPhase.AUTHENTICATED, userId = " ")
        assertFalse(state.authenticated)
        assertFalse(state.walletsReady)
    }
}
