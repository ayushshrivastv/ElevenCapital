package com.elevencapital.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local gate for personal app content.
 *
 * Privy remains the owner of the login session and wallet credentials. This gate never stores a
 * password, PIN, biometric, or authentication token; it only records whether Android's system
 * credential prompt has unlocked the current foreground session.
 */
class DeviceUnlockGate {
    private val mutableState = MutableStateFlow(DeviceUnlockState())
    val state: StateFlow<DeviceUnlockState> = mutableState.asStateFlow()

    private var authenticatedUserId: String? = null
    private var automaticPromptAllowed = true
    private var nextAttemptId = 0L

    fun onSessionChanged(userId: String?) {
        val verifiedUserId = userId?.takeIf(String::isNotBlank)
        if (authenticatedUserId == verifiedUserId) return
        authenticatedUserId = verifiedUserId
        automaticPromptAllowed = true
        mutableState.value = if (verifiedUserId != null) {
            DeviceUnlockState(DeviceUnlockPhase.LOCKED, userId = verifiedUserId)
        } else {
            DeviceUnlockState(DeviceUnlockPhase.NOT_REQUIRED)
        }
    }

    /** Returns a unique identity-bound attempt exactly once for each prompt that may be launched. */
    fun beginPrompt(userRequested: Boolean = false): DeviceUnlockAttempt? {
        val userId = authenticatedUserId ?: return null
        if (userRequested) automaticPromptAllowed = true
        if (!automaticPromptAllowed || mutableState.value.phase != DeviceUnlockPhase.LOCKED) return null
        val attempt = DeviceUnlockAttempt(userId, ++nextAttemptId)
        mutableState.value = DeviceUnlockState(
            DeviceUnlockPhase.PROMPTING,
            userId = userId,
            attemptId = attempt.id,
        )
        return attempt
    }

    fun activeAttempt(): DeviceUnlockAttempt? = mutableState.value.let { state ->
        if (state.phase == DeviceUnlockPhase.PROMPTING && state.userId != null && state.attemptId != null) {
            DeviceUnlockAttempt(state.userId, state.attemptId)
        } else null
    }

    fun onAuthenticationSucceeded(attempt: DeviceUnlockAttempt) {
        if (!matchesActiveAttempt(attempt)) return
        automaticPromptAllowed = false
        mutableState.value = DeviceUnlockState(DeviceUnlockPhase.UNLOCKED, userId = attempt.userId)
    }

    fun onAuthenticationCancelled(
        attempt: DeviceUnlockAttempt,
        message: String = "Eleven Capital remains locked.",
    ) {
        if (!matchesActiveAttempt(attempt)) return
        // Cancellation returns to a safe branded screen. Re-opening the system prompt requires
        // a deliberate tap on the logo, which avoids an inescapable prompt loop.
        automaticPromptAllowed = false
        mutableState.value = DeviceUnlockState(DeviceUnlockPhase.LOCKED, attempt.userId, message)
    }

    fun onDeviceCredentialUnavailable() {
        val userId = authenticatedUserId ?: return
        automaticPromptAllowed = false
        mutableState.value = DeviceUnlockState(
            DeviceUnlockPhase.DEVICE_LOCK_REQUIRED,
            userId,
            "Set a phone PIN, pattern, or password in Android Settings to open Eleven Capital.",
        )
    }

    fun onDeviceCredentialAvailable() {
        val userId = authenticatedUserId ?: return
        if (mutableState.value.phase != DeviceUnlockPhase.DEVICE_LOCK_REQUIRED) return
        automaticPromptAllowed = true
        mutableState.value = DeviceUnlockState(DeviceUnlockPhase.LOCKED, userId = userId)
    }

    /** A real background transition locks content and permits one prompt on the next entry. */
    fun onAppBackgrounded() {
        val userId = authenticatedUserId ?: return
        when (mutableState.value.phase) {
            DeviceUnlockPhase.UNLOCKED -> {
                automaticPromptAllowed = true
                mutableState.value = DeviceUnlockState(DeviceUnlockPhase.LOCKED, userId = userId)
            }
            // A cancelled system prompt stays dismissed while this surface is visible. Once the
            // user leaves the app, the next launcher entry may offer the phone credential again.
            DeviceUnlockPhase.LOCKED -> automaticPromptAllowed = true
            else -> Unit
        }
    }

    private fun matchesActiveAttempt(attempt: DeviceUnlockAttempt): Boolean =
        authenticatedUserId == attempt.userId && mutableState.value.let { state ->
            state.phase == DeviceUnlockPhase.PROMPTING && state.userId == attempt.userId &&
                state.attemptId == attempt.id
        }
}

enum class DeviceUnlockPhase { NOT_REQUIRED, LOCKED, PROMPTING, UNLOCKED, DEVICE_LOCK_REQUIRED }

data class DeviceUnlockState(
    val phase: DeviceUnlockPhase = DeviceUnlockPhase.NOT_REQUIRED,
    val userId: String? = null,
    val message: String? = null,
    internal val attemptId: Long? = null,
) {
    val contentAllowed: Boolean get() = phase == DeviceUnlockPhase.NOT_REQUIRED || phase == DeviceUnlockPhase.UNLOCKED
}

data class DeviceUnlockAttempt(val userId: String, val id: Long)
