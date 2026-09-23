package com.elevencapital.app.auth

import androidx.lifecycle.ViewModel

/** Retains only process-local lock state across Activity configuration changes. */
class AppLockViewModel : ViewModel() {
    val gate = DeviceUnlockGate()
}
