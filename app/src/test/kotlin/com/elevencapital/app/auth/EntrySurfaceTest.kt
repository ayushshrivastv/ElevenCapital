package com.elevencapital.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySurfaceTest {
    @Test fun `restoring persisted session never renders Google login`() {
        assertEquals(
            EntrySurface.SESSION_SHIELD,
            entrySurface(ElevenAuthState(true, AuthPhase.RESTORING), DeviceUnlockState()),
        )
        assertEquals(
            EntrySurface.SESSION_SHIELD,
            entrySurface(ElevenAuthState(true, AuthPhase.SESSION_UNVERIFIED), DeviceUnlockState()),
        )
    }

    @Test fun `only a genuinely signed out session renders login`() {
        assertEquals(
            EntrySurface.LOGIN,
            entrySurface(ElevenAuthState(true, AuthPhase.SIGNED_OUT), DeviceUnlockState()),
        )
        assertEquals(
            EntrySurface.LOGIN,
            entrySurface(ElevenAuthState(true, AuthPhase.SIGNING_IN), DeviceUnlockState()),
        )
    }

    @Test fun `authenticated content requires unlock for the exact Privy user`() {
        val auth = ElevenAuthState(true, AuthPhase.AUTHENTICATED, userId = "user-a")
        assertEquals(EntrySurface.DEVICE_UNLOCK, entrySurface(auth, DeviceUnlockState()))
        assertEquals(
            EntrySurface.DEVICE_UNLOCK,
            entrySurface(auth, DeviceUnlockState(DeviceUnlockPhase.UNLOCKED, userId = "user-b")),
        )
        assertEquals(
            EntrySurface.APP,
            entrySurface(auth, DeviceUnlockState(DeviceUnlockPhase.UNLOCKED, userId = "user-a")),
        )
    }

    @Test fun `offline design fixture remains available without auth integration`() {
        assertEquals(EntrySurface.APP, entrySurface(null, DeviceUnlockState()))
    }
}
