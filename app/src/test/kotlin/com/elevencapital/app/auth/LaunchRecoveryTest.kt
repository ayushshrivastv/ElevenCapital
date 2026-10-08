package com.elevencapital.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchRecoveryTest {
    @Test fun `intro completion has a bounded wall clock fallback`() {
        assertFalse(introDeadlineReached(1_000, 4_499))
        assertTrue(introDeadlineReached(1_000, 4_500))
        assertFalse(introDeadlineReached(1_000, 999))
    }

    @Test fun `a missing credential popup only recovers after the launch deadline`() {
        val watchdog = DevicePromptWatchdog(startedAtMillis = 1_000)
        assertFalse(watchdog.missingPrompt(1_000, hostVisible = true))
        assertFalse(watchdog.missingPrompt(5_499, hostVisible = true))
        assertTrue(watchdog.missingPrompt(5_500, hostVisible = true))
    }

    @Test fun `system credential entry can keep the host hidden indefinitely`() {
        val watchdog = DevicePromptWatchdog(startedAtMillis = 1_000)
        assertFalse(watchdog.missingPrompt(1_000, hostVisible = true))
        assertFalse(watchdog.missingPrompt(1_050, hostVisible = false))
        assertFalse(watchdog.missingPrompt(120_000, hostVisible = false))
        assertFalse(watchdog.missingPrompt(3_600_000, hostVisible = false))
    }

    @Test fun `return from credential screen gives its pending callback time before offering recovery`() {
        val watchdog = DevicePromptWatchdog(startedAtMillis = 1_000)
        assertFalse(watchdog.missingPrompt(5_000, hostVisible = false))
        assertFalse(watchdog.missingPrompt(20_000, hostVisible = true))
        assertFalse(watchdog.missingPrompt(20_749, hostVisible = true))
        assertTrue(watchdog.missingPrompt(20_750, hostVisible = true))
    }

    @Test fun `losing host focus resets the visible recovery grace period`() {
        val watchdog = DevicePromptWatchdog(startedAtMillis = 1_000)
        assertFalse(watchdog.missingPrompt(10_000, hostVisible = true))
        assertFalse(watchdog.missingPrompt(10_700, hostVisible = false))
        assertFalse(watchdog.missingPrompt(11_000, hostVisible = true))
        assertFalse(watchdog.missingPrompt(11_749, hostVisible = true))
        assertTrue(watchdog.missingPrompt(11_750, hostVisible = true))
    }
}
