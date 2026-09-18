package com.elevencapital.app.auth

/** Pure root-routing decision so transient restore states can never regress to Google login. */
internal enum class EntrySurface { APP, LOGIN, SESSION_SHIELD, DEVICE_UNLOCK }

internal fun entrySurface(
    authState: ElevenAuthState?,
    deviceUnlockState: DeviceUnlockState,
): EntrySurface {
    if (authState == null) return EntrySurface.APP
    return when (authState.phase) {
        AuthPhase.RESTORING, AuthPhase.SESSION_UNVERIFIED -> EntrySurface.SESSION_SHIELD
        AuthPhase.UNCONFIGURED, AuthPhase.SIGNED_OUT, AuthPhase.SIGNING_IN -> EntrySurface.LOGIN
        AuthPhase.AUTHENTICATED -> {
            val userId = authState.userId?.takeIf(String::isNotBlank)
                ?: return EntrySurface.SESSION_SHIELD
            if (deviceUnlockState.phase == DeviceUnlockPhase.UNLOCKED &&
                deviceUnlockState.userId == userId) EntrySurface.APP else EntrySurface.DEVICE_UNLOCK
        }
    }
}
