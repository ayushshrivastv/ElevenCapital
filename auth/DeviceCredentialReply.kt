package com.elevencapital.app.auth

internal data class DeviceCredentialReply(
    val attempt: DeviceUnlockAttempt,
    val result: DeviceCredentialResult,
)

/** A successful Android result alone never identifies or authenticates the current attempt. */
internal fun credentialReplyForAttempt(
    expected: DeviceUnlockAttempt,
    reply: DeviceCredentialReply?,
    successfulResult: Boolean,
): DeviceCredentialResult {
    if (reply?.attempt != expected) return DeviceCredentialResult.CANCELLED
    if (reply.result == DeviceCredentialResult.SUCCEEDED && !successfulResult) return DeviceCredentialResult.CANCELLED
    return reply.result
}
