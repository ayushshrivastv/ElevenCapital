package com.elevencapital.app.data

import com.elevencapital.app.wallet.parseSingleJsonObject
import com.elevencapital.app.wallet.validatedBearerToken
import com.elevencapital.app.wallet.validatedTransferBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** The SPXTR chat room is UI-only; it does not represent a real mint or on-chain holdings. */
internal const val SPXTR_CHAT_ROOM = "spxtr"
private const val MAX_CHAT_MESSAGES = 200
private const val MAX_CHAT_PAGE = 50
private const val MAX_RESPONSE_BYTES = 64 * 1024
private val NUMERIC_CURSOR = Regex("(?:0|[1-9][0-9]{0,14})")

internal data class CommunityChatEntry(
    val id: String,
    val authorName: String,
    val text: String,
    val createdAtMillis: Long,
    val isMine: Boolean,
)

internal data class CommunityChatPage(
    val messages: List<CommunityChatEntry>,
    val nextCursor: String,
    val canPost: Boolean,
    val deletionVersion: Long = 0,
)

internal enum class CommunityChatFailure {
    AUTHENTICATION_REQUIRED,
    INVALID_MESSAGE,
    JOIN_REQUIRED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    INVALID_RESPONSE,
}

internal class CommunityChatException(
    val failure: CommunityChatFailure,
    override val message: String,
) : Exception(message)

internal fun interface CommunityChatAccessTokenProvider {
    suspend fun freshToken(expectedUserId: String): String
}

internal interface CommunityChatBackend {
    suspend fun history(userId: String, after: String? = null): CommunityChatPage
    suspend fun post(userId: String, text: String): CommunityChatEntry
    suspend fun join(userId: String): Boolean
    suspend fun deleteMine(userId: String): Int
}

/** Authenticated, no-cache HTTP transport for the fixed SPXTR chat room. */
internal class CommunityChatClient(
    baseUrl: String,
    private val accessToken: CommunityChatAccessTokenProvider,
    http: OkHttpClient = OkHttpClient(),
    allowLoopbackHttp: Boolean = false,
) : CommunityChatBackend {
    private val base = validatedTransferBackend(baseUrl, allowLoopbackHttp)
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    override suspend fun history(userId: String, after: String?): CommunityChatPage {
        if (after != null && !NUMERIC_CURSOR.matches(after)) throw invalidResponse()
        val url = "$base/v1/community-chat/$SPXTR_CHAT_ROOM/messages".toHttpUrl()
            .newBuilder().apply { if (after != null) addQueryParameter("after", after) }.build()
        val request = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${tokenFor(userId)}")
            .build()
        val response = execute(request)
        return try {
            requireEnvelope(response)
            val array = response.optJSONArray("messages") ?: throw invalidResponse()
            val canPost = response.opt("canPost") as? Boolean ?: throw invalidResponse()
            val deletionVersion = response.opt("deletionVersion")?.let { raw ->
                if (raw !is Int && raw !is Long) throw invalidResponse()
                (raw as Number).toLong().takeIf { it >= 0 } ?: throw invalidResponse()
            } ?: 0L
            if (array.length() > MAX_CHAT_PAGE) throw invalidResponse()
            val messages = (0 until array.length()).map { index ->
                parseMessage(array.optJSONObject(index) ?: throw invalidResponse())
            }
            if (messages.zipWithNext().any { (a, b) -> a.id.toLong() >= b.id.toLong() }) throw invalidResponse()
            val cursor = response.optString("nextCursor")
            if (!NUMERIC_CURSOR.matches(cursor) ||
                (after != null && cursor.toLong() < after.toLong()) ||
                (messages.isNotEmpty() && cursor != messages.last().id) ||
                (messages.isEmpty() && after != null && cursor != after)) throw invalidResponse()
            CommunityChatPage(messages, cursor, canPost, deletionVersion)
        } catch (failure: CommunityChatException) {
            throw failure
        } catch (_: Exception) {
            throw invalidResponse()
        }
    }

    override suspend fun post(userId: String, text: String): CommunityChatEntry {
        val normalized = text.trim()
        if (normalized.length !in 1..500) throw CommunityChatException(
            CommunityChatFailure.INVALID_MESSAGE, "Write a message up to 500 characters.")
        val request = Request.Builder().url("$base/v1/community-chat/$SPXTR_CHAT_ROOM/messages")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${tokenFor(userId)}")
            .post(JSONObject().put("text", normalized).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        return try {
            requireEnvelope(response)
            val message = parseMessage(response.optJSONObject("message") ?: throw invalidResponse())
            if (!message.isMine || message.text != normalized) throw invalidResponse()
            message
        } catch (failure: CommunityChatException) {
            throw failure
        } catch (_: Exception) {
            throw invalidResponse()
        }
    }

    override suspend fun join(userId: String): Boolean {
        val request = Request.Builder().url("$base/v1/community-chat/$SPXTR_CHAT_ROOM/join")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${tokenFor(userId)}")
            .post(JSONObject().toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        requireEnvelope(response)
        if (response.opt("canPost") != true) throw invalidResponse()
        return true
    }

    override suspend fun deleteMine(userId: String): Int {
        val request = Request.Builder().url("$base/v1/community-chat/$SPXTR_CHAT_ROOM/messages/mine")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${tokenFor(userId)}")
            .delete()
            .build()
        val response = execute(request, 200..200)
        requireEnvelope(response)
        val deletedCount = response.opt("deletedCount")
        if (deletedCount !is Int || deletedCount < 0) throw invalidResponse()
        return deletedCount
    }

    private suspend fun tokenFor(userId: String): String = try {
        validatedBearerToken(accessToken.freshToken(userId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw CommunityChatException(CommunityChatFailure.AUTHENTICATION_REQUIRED,
            "Sign in again to use Live Chat.")
    }

    private suspend fun execute(request: Request, acceptedStatus: IntRange = 200..201): JSONObject =
        suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(
                    CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE,
                        "Live Chat is unavailable. Reconnecting…"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { received ->
                        if (received.header("Content-Type")?.substringBefore(';')?.trim() != "application/json") {
                            throw invalidResponse()
                        }
                        val json = parseSingleJsonObject(readBounded(received))
                        if (received.code !in acceptedStatus) throw serverError(received.code, json)
                        if ("no-store" !in received.header("Cache-Control").orEmpty().lowercase()
                                .split(',').map(String::trim)) throw invalidResponse()
                        json
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (failure is CommunityChatException) failure else invalidResponse())
                }
            }
        })
        }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: throw invalidResponse()
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw invalidResponse()
        return body.byteStream().use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_RESPONSE_BYTES) throw invalidResponse()
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }

    private fun requireEnvelope(json: JSONObject) {
        if (json.optInt("schemaVersion", -1) != 1 || json.optString("roomId") != SPXTR_CHAT_ROOM) {
            throw invalidResponse()
        }
    }

    private fun parseMessage(json: JSONObject): CommunityChatEntry {
        val id = json.optString("id")
        val author = json.optString("authorName")
        val text = json.optString("text")
        val timestamp = json.optString("createdAt")
        if (!NUMERIC_CURSOR.matches(id) || author.length !in 1..64 ||
            author.any { it.code < 32 || it.code == 127 } || text.length !in 1..500 ||
            json.opt("isMine") !is Boolean) throw invalidResponse()
        val at = try { Instant.parse(timestamp).toEpochMilli() }
            catch (_: DateTimeParseException) { throw invalidResponse() }
        return CommunityChatEntry(id, author, text, at, json.getBoolean("isMine"))
    }

    private fun serverError(status: Int, body: JSONObject): CommunityChatException = when (status) {
        400, 413 -> CommunityChatException(CommunityChatFailure.INVALID_MESSAGE,
            "Write a message up to 500 characters.")
        401 -> CommunityChatException(CommunityChatFailure.AUTHENTICATION_REQUIRED,
            "Sign in again to use Live Chat.")
        403 -> if (body.optString("error") == "chat_join_required")
            CommunityChatException(CommunityChatFailure.JOIN_REQUIRED,
                "Reconnect to Live Chat and try again.")
            else CommunityChatException(CommunityChatFailure.AUTHENTICATION_REQUIRED,
                "Sign in again to use Live Chat.")
        429 -> CommunityChatException(CommunityChatFailure.RATE_LIMITED,
            "Slow down a moment before sending another message.")
        else -> CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE,
            "Live Chat is unavailable. Reconnecting…")
    }

    private fun invalidResponse() = CommunityChatException(CommunityChatFailure.INVALID_RESPONSE,
        "Live Chat could not be updated. Try again shortly.")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal data class CommunityChatState(
    val messages: List<CommunityChatEntry> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val canPost: Boolean = false,
    val error: CommunityChatException? = null,
)

/** Keeps the chat current only while its screen is visible; never invents local messages. */
internal class CommunityChatRepository(
    private val backend: CommunityChatBackend,
    private val pollIntervalMillis: Long = 2_500L,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(CommunityChatState())
    val state: StateFlow<CommunityChatState> = mutableState.asStateFlow()
    private var activeUserId: String? = null
    private var cursor: String? = null
    private var deletionVersion: Long? = null

    init { require(pollIntervalMillis in 500L..60_000L) }

    suspend fun watch(userId: String) {
        require(userId.isNotBlank())
        lock.withLock {
            activeUserId = userId
            cursor = null
            deletionVersion = null
            mutableState.value = CommunityChatState(loading = true)
        }
        try {
            while (currentCoroutineContext().isActive) {
                refresh(userId)
                delay(pollIntervalMillis)
            }
        } finally {
            lock.withLock {
                if (activeUserId == userId) {
                    activeUserId = null
                    cursor = null
                    deletionVersion = null
                    mutableState.value = CommunityChatState()
                }
            }
        }
    }

    suspend fun send(userId: String, text: String): Boolean = lock.withLock {
        if (activeUserId != userId || mutableState.value.sending) return@withLock false
        if (!mutableState.value.canPost) {
            mutableState.value = mutableState.value.copy(error = CommunityChatException(
                CommunityChatFailure.JOIN_REQUIRED, "Reconnect to Live Chat and try again."))
            return@withLock false
        }
        mutableState.value = mutableState.value.copy(sending = true, error = null)
        try {
            val sent = backend.post(userId, text)
            mutableState.value = mutableState.value.copy(
                messages = merge(mutableState.value.messages, listOf(sent)),
                sending = false,
            )
            true
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(sending = false)
            throw cancelled
        } catch (failure: CommunityChatException) {
            mutableState.value = mutableState.value.copy(sending = false, error = failure,
                canPost = if (failure.failure == CommunityChatFailure.JOIN_REQUIRED) false
                    else mutableState.value.canPost)
            false
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(sending = false,
                error = CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE,
                    "Live Chat is unavailable. Try sending again."))
            false
        }
    }

    /** The caller checks local access first. The server still decides whether a post succeeds. */
    suspend fun sendAfterJoining(userId: String, text: String): Boolean {
        if (!state.value.canPost && !join(userId)) return false
        return send(userId, text)
    }

    /** Records an explicit SPXTR UI-only Buy action; it does not sign or submit a transaction. */
    suspend fun join(userId: String): Boolean = lock.withLock {
        try {
            val joined = backend.join(userId)
            if (activeUserId == userId) {
                mutableState.value = mutableState.value.copy(canPost = joined, error = null)
            }
            joined
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CommunityChatException) {
            mutableState.value = mutableState.value.copy(error = failure)
            false
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = CommunityChatException(
                CommunityChatFailure.SERVICE_UNAVAILABLE, "Could not join Live Chat. Try again."))
            false
        }
    }

    /** A confirmed server deletion removes only this account's posts from the visible feed. */
    suspend fun deleteMine(userId: String): Int = lock.withLock {
        require(userId.isNotBlank())
        val deletedCount = backend.deleteMine(userId)
        if (activeUserId == userId) {
            cursor = null
            deletionVersion = null
            mutableState.value = mutableState.value.copy(
                messages = mutableState.value.messages.filterNot(CommunityChatEntry::isMine),
                canPost = false,
                error = null,
            )
        }
        deletedCount
    }

    /** Fetches up to four pages at once, then continues from the cursor on the next poll. */
    internal suspend fun refresh(userId: String) = lock.withLock {
        if (activeUserId != userId) return@withLock
        try {
            repeat(4) {
                var previousCursor = cursor
                var page = backend.history(userId, previousCursor)
                if (deletionVersion != null && page.deletionVersion != deletionVersion) {
                    // A different device removed posts. Incremental pages cannot tell us which
                    // cached rows disappeared, so replace the feed from a fresh first page.
                    cursor = null
                    deletionVersion = null
                    mutableState.value = mutableState.value.copy(messages = emptyList(), loading = true,
                        canPost = false)
                    previousCursor = null
                    page = backend.history(userId)
                }
                if (previousCursor != null && page.messages.isNotEmpty() &&
                    page.nextCursor.toLong() <= previousCursor.toLong()) {
                    throw CommunityChatException(CommunityChatFailure.INVALID_RESPONSE,
                        "Live Chat could not be updated. Try again shortly.")
                }
                cursor = page.nextCursor
                deletionVersion = page.deletionVersion
                mutableState.value = mutableState.value.copy(
                    messages = merge(mutableState.value.messages, page.messages),
                    loading = false,
                    canPost = page.canPost,
                    error = null,
                )
                if (page.messages.size < MAX_CHAT_PAGE) return@withLock
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CommunityChatException) {
            mutableState.value = mutableState.value.copy(loading = false, error = failure)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false,
                error = CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE,
                    "Live Chat is unavailable. Reconnecting…"))
        }
    }

    private fun merge(existing: List<CommunityChatEntry>, incoming: List<CommunityChatEntry>): List<CommunityChatEntry> =
        (existing + incoming).associateBy(CommunityChatEntry::id).values
            .sortedBy { it.id.toLong() }.takeLast(MAX_CHAT_MESSAGES)
}
