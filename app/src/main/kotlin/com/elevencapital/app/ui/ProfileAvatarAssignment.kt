package com.elevencapital.app.ui

import android.content.Context
import java.security.MessageDigest

/** Assigns the supplied orb first, then stable variants for later accounts on this device. */
internal fun profileAvatarVariant(context: Context, verifiedUserId: String?): Int {
    if (verifiedUserId.isNullOrBlank()) return 0
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifiedUserId.toByteArray(Charsets.UTF_8))
    val accountKey = buildString(24) {
        digest.take(12).forEach { byte ->
            append("0123456789abcdef"[(byte.toInt() ushr 4) and 0xf])
            append("0123456789abcdef"[byte.toInt() and 0xf])
        }
    }
    val preferences = context.applicationContext.getSharedPreferences("eleven-profile-avatars", Context.MODE_PRIVATE)
    val saved = preferences.getInt(accountKey, -1)
    if (saved in 0 until PROFILE_AVATAR_COUNT) return saved
    val next = preferences.getInt("next-avatar", 0).coerceIn(0, PROFILE_AVATAR_COUNT - 1)
    preferences.edit().putInt(accountKey, next)
        .putInt("next-avatar", (next + 1) % PROFILE_AVATAR_COUNT).apply()
    return next
}
