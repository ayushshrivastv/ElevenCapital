package com.elevencapital.app.auth

/** The normal 1.45-second animation remains intact; a stalled frame clock cannot hold the gate. */
internal fun introDeadlineReached(startedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis >= startedAtMillis && nowMillis - startedAtMillis >= 3_500L

/** A credential screen may stop or unfocus its host for any duration without being timed out. */
internal class DevicePromptWatchdog(private val startedAtMillis: Long) {
    private var hostVisibleSince: Long? = null

    fun missingPrompt(nowMillis: Long, hostVisible: Boolean): Boolean {
        if (!hostVisible) {
            hostVisibleSince = null
            return false
        }
        val visibleSince = hostVisibleSince ?: nowMillis.also { hostVisibleSince = it }
        return nowMillis >= startedAtMillis && nowMillis - startedAtMillis >= 4_500L &&
            nowMillis >= visibleSince && nowMillis - visibleSince >= 750L
    }
}
