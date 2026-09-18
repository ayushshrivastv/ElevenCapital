package com.elevencapital.app.auth

/** Keeps observed SDK events separate from completion of the user's current sign-in operation. */
internal class AuthSessionGate(allowRestore: Boolean) {
    private enum class Mode { RESTORING, SIGNING_IN, VERIFIED, BLOCKED }
    private var mode = if (allowRestore) Mode.RESTORING else Mode.BLOCKED
    private var operation = 0L
    private var verifiedUserId: String? = null

    val isSigningIn: Boolean get() = mode == Mode.SIGNING_IN
    val isBlocked: Boolean get() = mode == Mode.BLOCKED

    fun beginSignIn(): Long {
        operation++
        verifiedUserId = null
        mode = Mode.SIGNING_IN
        return operation
    }

    fun isCurrentSignIn(attempt: Long): Boolean = mode == Mode.SIGNING_IN && operation == attempt

    fun isCurrentOperation(attempt: Long): Boolean = mode != Mode.BLOCKED && operation == attempt

    fun completeSignIn(attempt: Long, userId: String): Boolean {
        if (!isCurrentSignIn(attempt) || userId.isBlank()) return false
        verifiedUserId = userId
        mode = Mode.VERIFIED
        return true
    }

    fun block() {
        operation++
        verifiedUserId = null
        mode = Mode.BLOCKED
    }

    /** Returns a transient/unfinished browser operation to normal SDK session restoration. */
    fun resumeRestore() {
        operation++
        verifiedUserId = null
        mode = Mode.RESTORING
    }

    fun acceptsObservedUser(userId: String): Boolean {
        if (userId.isBlank()) return false
        return when (mode) {
            Mode.RESTORING -> {
                verifiedUserId = userId
                mode = Mode.VERIFIED
                true
            }
            Mode.VERIFIED -> verifiedUserId == userId
            Mode.SIGNING_IN, Mode.BLOCKED -> false
        }
    }

    fun matchesVerifiedUser(userId: String): Boolean =
        mode == Mode.VERIFIED && userId.isNotBlank() && verifiedUserId == userId
}
