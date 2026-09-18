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
        val attempt = requireNotNull(gate.beginPrompt())
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
        assertEquals(null, gate.beginPrompt())
        assertTrue(gate.beginPrompt(userRequested = true) != null)
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
}
