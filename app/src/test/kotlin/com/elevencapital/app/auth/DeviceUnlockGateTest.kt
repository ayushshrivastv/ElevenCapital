package com.elevencapital.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUnlockGateTest {
    @Test fun `signed out users do not receive an app lock`() {
        val gate = DeviceUnlockGate()
        assertEquals(DeviceUnlockPhase.NOT_REQUIRED, gate.state.value.phase)
        assertTrue(gate.state.value.contentAllowed)
        assertEquals(null, gate.beginPrompt())
    }

    @Test fun `restored authenticated session requires one system prompt`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("returning-user")
        assertEquals(DeviceUnlockPhase.LOCKED, gate.state.value.phase)
        assertFalse(gate.state.value.contentAllowed)
        assertFalse(gate.state.value.retryAvailable)
        val attempt = requireNotNull(gate.beginPrompt())
        assertFalse(gate.state.value.retryAvailable)
        assertEquals(attempt, gate.activeAttempt())
        assertEquals(null, gate.beginPrompt())
        gate.onAuthenticationSucceeded(attempt)
        assertEquals(DeviceUnlockPhase.UNLOCKED, gate.state.value.phase)
        assertTrue(gate.state.value.contentAllowed)
    }

    @Test fun `cancelled prompt never loops and visible retry can request it again`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val attempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationCancelled(attempt)
        assertTrue(gate.state.value.retryAvailable)
        assertFalse(gate.state.value.contentAllowed)
        assertEquals(null, gate.beginPrompt())
        assertTrue(gate.beginPrompt(userRequested = true) != null)
        assertFalse(gate.state.value.retryAvailable)
    }

    @Test fun `cancelled prompt is offered again after leaving and reopening the app`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val firstAttempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationCancelled(firstAttempt)
        assertEquals(null, gate.beginPrompt())

        gate.onAppBackgrounded()
        val nextAttempt = requireNotNull(gate.beginPrompt())
        assertTrue(nextAttempt.id > firstAttempt.id)
        assertFalse(gate.state.value.contentAllowed)
    }

    @Test fun `backgrounding locks an unlocked authenticated session`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val attempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationSucceeded(attempt)
        gate.onAppBackgrounded()
        assertEquals(DeviceUnlockPhase.LOCKED, gate.state.value.phase)
        assertTrue(gate.beginPrompt() != null)
    }

    @Test fun `prompt transition itself is not mistaken for app backgrounding`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        gate.beginPrompt()
        gate.onAppBackgrounded()
        assertEquals(DeviceUnlockPhase.PROMPTING, gate.state.value.phase)
    }

    @Test fun `logout removes the lock and old success cannot reopen content`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("old-user")
        val oldAttempt = requireNotNull(gate.beginPrompt())
        gate.onSessionChanged(null)
        gate.onAuthenticationSucceeded(oldAttempt)
        assertEquals(DeviceUnlockPhase.NOT_REQUIRED, gate.state.value.phase)
        assertTrue(gate.state.value.contentAllowed)
    }

    @Test fun `missing phone lock fails closed`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        gate.onDeviceCredentialUnavailable()
        assertEquals(DeviceUnlockPhase.DEVICE_LOCK_REQUIRED, gate.state.value.phase)
        assertFalse(gate.state.value.contentAllowed)
    }

    @Test fun `old prompt cannot unlock a newly authenticated account`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("old-user")
        val oldAttempt = requireNotNull(gate.beginPrompt())
        gate.onSessionChanged("new-user")
        gate.onAuthenticationSucceeded(oldAttempt)
        assertEquals(DeviceUnlockPhase.LOCKED, gate.state.value.phase)
        assertEquals("new-user", gate.state.value.userId)
    }

    @Test fun `a missing prompt can be retried without an old callback affecting the retry`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val missingAttempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationCancelled(missingAttempt, "Phone security did not open. Try unlocking again.")
        assertTrue(gate.state.value.retryAvailable)
        assertEquals(null, gate.beginPrompt())

        val retry = requireNotNull(gate.beginPrompt(userRequested = true))
        gate.onAuthenticationSucceeded(missingAttempt)
        gate.onAuthenticationCancelled(missingAttempt)
        assertEquals(retry, gate.activeAttempt())
        assertFalse(gate.state.value.contentAllowed)
        gate.onAuthenticationSucceeded(retry)
        assertTrue(gate.state.value.contentAllowed)
        assertFalse(gate.state.value.retryAvailable)
    }

    @Test fun `restored old process callback cannot unlock a new attempt for the same user`() {
        val oldGate = DeviceUnlockGate().apply { onSessionChanged("user") }
        val oldAttempt = requireNotNull(oldGate.beginPrompt())
        val newGate = DeviceUnlockGate().apply { onSessionChanged("user") }
        val newAttempt = requireNotNull(newGate.beginPrompt())
        assertEquals(oldAttempt.id, newAttempt.id)
        assertTrue(oldAttempt.epoch != newAttempt.epoch)

        newGate.onAuthenticationSucceeded(oldAttempt)
        newGate.onAuthenticationCancelled(oldAttempt)
        assertEquals(newAttempt, newGate.activeAttempt())
        assertFalse(newGate.state.value.contentAllowed)
    }

    @Test fun `unverified session retires its prompt and requires fresh verification before retry`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val oldAttempt = requireNotNull(gate.beginPrompt())
        gate.onSessionChanged(null)
        assertEquals(null, gate.beginPrompt(userRequested = true))

        gate.onSessionChanged("user")
        val verifiedAttempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationSucceeded(oldAttempt)
        assertEquals(verifiedAttempt, gate.activeAttempt())
        assertFalse(gate.state.value.contentAllowed)
    }

    @Test fun `setting up phone credentials does not itself unlock or preserve old prompt results`() {
        val gate = DeviceUnlockGate()
        gate.onSessionChanged("user")
        val oldAttempt = requireNotNull(gate.beginPrompt())
        gate.onDeviceCredentialUnavailable()
        assertEquals(null, gate.beginPrompt(userRequested = true))
        gate.onAuthenticationSucceeded(oldAttempt)
        assertFalse(gate.state.value.contentAllowed)

        gate.onDeviceCredentialAvailable()
        val newAttempt = requireNotNull(gate.beginPrompt())
        gate.onAuthenticationSucceeded(oldAttempt)
        assertEquals(newAttempt, gate.activeAttempt())
        assertFalse(gate.state.value.contentAllowed)
    }
}
