package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.data.CommunityChatEntry
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** CHTMS posts written by this device are a local part of the preview UI flow. */
internal object CommunityLocalChatStore {
    private const val PREFERENCES = "eleven-community-local-chat-v1"
    private const val MAX_MESSAGES = 200
    private const val MAX_MESSAGE_LENGTH = 500
    private const val MAX_STORED_LENGTH = 750_000
    private const val LOCAL_ID_PREFIX = "local-chat-"

    @Synchronized
    fun read(context: Context, userId: String, walletAddress: String): List<CommunityChatMessage> {
        val key = ownerKey(userId, walletAddress) ?: return emptyList()
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyList()
        return decode(raw) ?: emptyList()
    }

    /** A successful commit makes the message visible after an app restart. */
    @Synchronized
    fun append(
        context: Context,
        userId: String,
        walletAddress: String,
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<CommunityChatMessage>? {
        val key = ownerKey(userId, walletAddress) ?: return null
        val body = text.trim()
        if (body.length !in 1..MAX_MESSAGE_LENGTH || nowMillis <= 0L) return null
        val updated = (read(context, userId, walletAddress) + CommunityChatMessage(
            id = "$LOCAL_ID_PREFIX${UUID.randomUUID()}",
            author = "You",
            body = body,
            sentAtMillis = nowMillis,
            isMine = true,
        )).takeLast(MAX_MESSAGES)
        val encoded = encode(updated) ?: return null
        val saved = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(key, encoded).commit()
        return updated.takeIf { saved }
    }

    @Synchronized
    fun clear(context: Context, userId: String, walletAddress: String): Boolean {
        val key = ownerKey(userId, walletAddress) ?: return false
        return context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(key).commit()
    }

    private fun ownerKey(userId: String, walletAddress: String): String? {
        if (userId.isBlank() || userId.length > 256 || userId != userId.trim() ||
            userId.any { it.isISOControl() || it.isWhitespace() } ||
            canonicalSolanaPublicKey(walletAddress) != walletAddress) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$userId\u0000$walletAddress".toByteArray(Charsets.UTF_8))
        return "wallet:" + digest.joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${"0123456789abcdef"[value ushr 4]}${"0123456789abcdef"[value and 0xf]}"
        }
    }

    private fun encode(messages: List<CommunityChatMessage>): String? {
        if (messages.size > MAX_MESSAGES || messages.map { it.id }.toSet().size != messages.size ||
            messages.any { !validLocalMessage(it) }) return null
        val rows = JSONArray()
        messages.forEach { message ->
            rows.put(JSONObject().put("id", message.id).put("text", message.body)
                .put("at", message.sentAtMillis))
        }
        return rows.toString().takeIf { it.length <= MAX_STORED_LENGTH }
    }

    private fun decode(raw: String): List<CommunityChatMessage>? {
        if (raw.length > MAX_STORED_LENGTH) return null
        return try {
            val rows = JSONArray(raw)
            if (rows.length() > MAX_MESSAGES) return null
            val messages = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                CommunityChatMessage(
                    id = row.getString("id"),
                    author = "You",
                    body = row.getString("text"),
                    sentAtMillis = row.getLong("at"),
                    isMine = true,
                )
            }
            messages.takeIf { it.map(CommunityChatMessage::id).toSet().size == it.size &&
                it.all(::validLocalMessage) }
        } catch (_: Exception) {
            null
        }
    }

    private fun validLocalMessage(message: CommunityChatMessage): Boolean =
        message.id.startsWith(LOCAL_ID_PREFIX) &&
            message.id.removePrefix(LOCAL_ID_PREFIX).let { id ->
                try { UUID.fromString(id).toString() == id } catch (_: Exception) { false }
            } &&
            message.author == "You" && message.body.length in 1..MAX_MESSAGE_LENGTH &&
            message.body == message.body.trim() && message.sentAtMillis > 0L && message.isMine
}

/** Old server-side self posts are permanently omitted from the CHTMS preview feed. */
internal fun communityChatFeed(
    remote: List<CommunityChatEntry>,
    local: List<CommunityChatMessage>,
): List<CommunityChatMessage> =
    (remote.filterNot(CommunityChatEntry::isMine).map { message ->
        CommunityChatMessage(message.id, message.authorName, message.text,
            message.createdAtMillis, false)
    } + local).sortedBy(CommunityChatMessage::sentAtMillis)
