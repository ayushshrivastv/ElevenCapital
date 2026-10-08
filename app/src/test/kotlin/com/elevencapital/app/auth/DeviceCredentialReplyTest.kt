package com.elevencapital.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCredentialReplyTest {
    private val expected = DeviceUnlockAttempt("verified-user", 2L, "current-process")

    @Test fun `success requires the exact immutable attempt and Android success code`() {
        assertEquals(DeviceCredentialResult.SUCCEEDED,
            credentialReplyForAttempt(expected, DeviceCredentialReply(expected, DeviceCredentialResult.SUCCEEDED), true))
        assertEquals(DeviceCredentialResult.CANCELLED,
            credentialReplyForAttempt(expected, DeviceCredentialReply(expected, DeviceCredentialResult.SUCCEEDED), false))
    }

    @Test fun `Android success without an attempt payload fails closed`() {
        assertEquals(DeviceCredentialResult.CANCELLED, credentialReplyForAttempt(expected, null, true))
    }

    @Test fun `old retry success cannot be assigned to the expected attempt`() {
        val oldReply = DeviceCredentialReply(expected.copy(id = 1L), DeviceCredentialResult.SUCCEEDED)
        assertEquals(DeviceCredentialResult.CANCELLED, credentialReplyForAttempt(expected, oldReply, true))
    }

    @Test fun `another user or old process cannot supply the expected attempt success`() {
        for (old in listOf(expected.copy(userId = "other-user"), expected.copy(epoch = "old-process"))) {
            assertEquals(DeviceCredentialResult.CANCELLED,
                credentialReplyForAttempt(expected, DeviceCredentialReply(old, DeviceCredentialResult.SUCCEEDED), true))
        }
    }

    @Test fun `exact attempt preserves cancellation and missing credential outcomes`() {
        for (result in listOf(DeviceCredentialResult.CANCELLED, DeviceCredentialResult.UNAVAILABLE)) {
            assertEquals(result, credentialReplyForAttempt(expected, DeviceCredentialReply(expected, result), false))
        }
    }
}
