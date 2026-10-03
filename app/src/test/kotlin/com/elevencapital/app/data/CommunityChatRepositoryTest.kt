package com.elevencapital.app.data

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityChatRepositoryTest {
    private val userId = "did:privy:chat-user"
    private val jwt = "a".repeat(40) + "." + "b".repeat(40) + "." + "c".repeat(40)

    @Test fun `authenticated history join and post share one SPXTR room`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"schemaVersion":1,"roomId":"spxtr","messages":[${message(1, "Hi", false)}],"nextCursor":"1","canPost":false,"deletionVersion":3}"""))
            server.enqueue(ok("""{"schemaVersion":1,"roomId":"spxtr","canPost":true}"""))
            server.enqueue(ok("""{"schemaVersion":1,"roomId":"spxtr","message":${message(2, "Hello", true)}}"""))
            val client = CommunityChatClient(server.url("/").toString(),
                CommunityChatAccessTokenProvider { expected ->
                    assertEquals(userId, expected); jwt
                }, allowLoopbackHttp = true)

            val history = client.history(userId)
            assertEquals("1", history.nextCursor)
            assertEquals(3L, history.deletionVersion)
            assertFalse(history.canPost)
            assertEquals("Hi", history.messages.single().text)
            assertTrue(client.join(userId))
            assertEquals("Hello", client.post(userId, " Hello ").text)

            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/v1/community-chat/spxtr/messages", get.path)
            assertEquals("Bearer $jwt", get.getHeader("Authorization"))
            assertEquals("no-store", get.getHeader("Cache-Control"))
            val join = server.takeRequest()
            assertEquals("POST", join.method)
            assertEquals("/v1/community-chat/spxtr/join", join.path)
            assertEquals("{}", join.body.readUtf8())
            val post = server.takeRequest()
            assertEquals("/v1/community-chat/spxtr/messages", post.path)
            assertEquals("Hello", JSONObject(post.body.readUtf8()).getString("text"))
        }
    }

    @Test fun `posting before join is distinguishable from expired login`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .addHeader("Cache-Control", "no-store")
                .setBody("""{"error":"chat_join_required","message":"Join first"}"""))
            val client = CommunityChatClient(server.url("/").toString(),
                CommunityChatAccessTokenProvider { jwt }, allowLoopbackHttp = true)
            val failure = try { client.post(userId, "Hello"); null }
                catch (caught: CommunityChatException) { caught }
            assertEquals(CommunityChatFailure.JOIN_REQUIRED, failure?.failure)
        }
    }

    @Test fun `authenticated delete targets only the current user's SPXTR messages`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"schemaVersion":1,"roomId":"spxtr","deletedCount":2}"""))
            val client = CommunityChatClient(server.url("/").toString(),
                CommunityChatAccessTokenProvider { expected ->
                    assertEquals(userId, expected); jwt
                }, allowLoopbackHttp = true)

            assertEquals(2, client.deleteMine(userId))
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/community-chat/spxtr/messages/mine", request.path)
            assertEquals("Bearer $jwt", request.getHeader("Authorization"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
        }
    }

    @Test fun `delete rejects an unconfirmed response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(ok("""{"schemaVersion":1,"roomId":"spxtr"}"""))
            val client = CommunityChatClient(server.url("/").toString(),
                CommunityChatAccessTokenProvider { jwt }, allowLoopbackHttp = true)
            val failure = try { client.deleteMine(userId); null }
                catch (caught: CommunityChatException) { caught }
            assertEquals(CommunityChatFailure.INVALID_RESPONSE, failure?.failure)
        }
    }

    @Test fun `visible feed joins after Buy and merges remote messages without duplication`() = runBlocking {
        val backend = FakeChatBackend()
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 500)
        val watcher = launch { repository.watch(userId) }
        try {
            // An empty first page restores the StateFlow to its initial value. StateFlow may
            // conflate the transient loading=true update, so observe the backend call directly.
            withTimeout(2_000) { backend.firstHistory.await() }
            assertFalse(repository.state.value.loading)
            assertFalse(repository.state.value.canPost)
            assertFalse(repository.send(userId, "No join yet"))
            assertTrue(repository.join(userId))
            assertTrue(repository.send(userId, "Mine"))
            backend.remote("From another device")
            withTimeout(2_000) { repository.state.first { it.messages.size == 2 } }
            val messages = repository.state.value.messages
            assertEquals(listOf("Mine", "From another device"), messages.map { it.text })
            assertTrue(repository.state.value.canPost)
            delay(600)
            assertEquals(2, repository.state.value.messages.size)
        } finally {
            watcher.cancel()
            watcher.join()
        }
        assertTrue(repository.state.value.messages.isEmpty())
    }

    @Test fun `eligible send joins before posting when history still says not joined`() = runBlocking {
        val backend = FakeChatBackend()
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 500)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { backend.firstHistory.await() }
            assertFalse(repository.state.value.canPost)
            assertTrue(repository.sendAfterJoining(userId, "Hello"))
            assertTrue(repository.state.value.canPost)
            assertEquals("Hello", repository.state.value.messages.single().text)
            assertEquals(1, backend.postCalls)
        } finally {
            watcher.cancel()
            watcher.join()
        }
    }

    @Test fun `failed join does not post or invent a chat message`() = runBlocking {
        val backend = FakeChatBackend().apply { failNextJoin = true }
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 500)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { backend.firstHistory.await() }
            assertFalse(repository.sendAfterJoining(userId, "Hello"))
            assertEquals(0, backend.postCalls)
            assertTrue(repository.state.value.messages.isEmpty())
            assertEquals(CommunityChatFailure.SERVICE_UNAVAILABLE,
                repository.state.value.error?.failure)
            assertTrue(repository.join(userId))
            repository.refresh(userId)
            assertTrue(repository.sendAfterJoining(userId, "Hello"))
        } finally {
            watcher.cancel()
            watcher.join()
        }
    }

    @Test fun `poll errors retain already received history and recover`() = runBlocking {
        val backend = FakeChatBackend()
        backend.remote("Existing")
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 500)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { repository.state.first { it.messages.size == 1 } }
            backend.failNext = true
            withTimeout(2_000) { repository.state.first { it.error != null } }
            assertEquals("Existing", repository.state.value.messages.single().text)
            withTimeout(2_000) { repository.state.first { it.error == null && !it.loading } }
        } finally {
            watcher.cancel()
            watcher.join()
        }
        Unit
    }

    @Test fun `confirmed delete removes only my cached posts and refreshes from a fresh cursor`() = runBlocking {
        val backend = FakeChatBackend()
        backend.remote("Another member")
        backend.mine("Old message")
        backend.join(userId)
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 60_000)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { repository.state.first { it.messages.size == 2 } }
            assertTrue(repository.state.value.canPost)
            assertEquals(1, repository.deleteMine(userId))
            assertEquals(listOf("Another member"), repository.state.value.messages.map { it.text })
            assertFalse(repository.state.value.canPost)
            repository.refresh(userId)
            assertEquals(listOf("Another member"), repository.state.value.messages.map { it.text })
            assertEquals(listOf(null, null), backend.historyAfter)
        } finally {
            watcher.cancel()
            watcher.join()
        }
    }

    @Test fun `failed delete preserves chat and can be retried`() = runBlocking {
        val backend = FakeChatBackend().apply {
            mine("Old message")
            failNextDelete = true
        }
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 60_000)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { repository.state.first { it.messages.size == 1 } }
            val failure = try { repository.deleteMine(userId); null }
                catch (caught: CommunityChatException) { caught }
            assertEquals(CommunityChatFailure.SERVICE_UNAVAILABLE, failure?.failure)
            assertEquals("Old message", repository.state.value.messages.single().text)
            assertEquals(1, repository.deleteMine(userId))
            assertTrue(repository.state.value.messages.isEmpty())
        } finally {
            watcher.cancel()
            watcher.join()
        }
    }

    @Test fun `remote deletion version replaces cached history on incremental poll`() = runBlocking {
        val backend = FakeChatBackend()
        backend.remote("Keep this")
        backend.mine("Remove this")
        val repository = CommunityChatRepository(backend, pollIntervalMillis = 60_000)
        val watcher = launch { repository.watch(userId) }
        try {
            withTimeout(2_000) { repository.state.first { it.messages.size == 2 } }
            backend.removeMineRemotely()
            repository.refresh(userId)
            assertEquals(listOf("Keep this"), repository.state.value.messages.map { it.text })
            assertEquals(listOf(null, "2", null), backend.historyAfter)
        } finally {
            watcher.cancel()
            watcher.join()
        }
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .addHeader("Cache-Control", "no-store")
        .setBody(body)

    private fun message(id: Int, text: String, mine: Boolean) =
        """{"id":"$id","authorName":"Member ABC123","text":"$text","createdAt":"2026-10-09T00:00:00.000Z","isMine":$mine}"""

    private class FakeChatBackend : CommunityChatBackend {
        private val entries = mutableListOf<CommunityChatEntry>()
        private var joined = false
        private var nextId = 1
        private var deletionVersion = 0L
        val firstHistory = CompletableDeferred<Unit>()
        var failNext = false
        var failNextJoin = false
        var failNextDelete = false
        var historyCalls = 0
        val historyAfter = mutableListOf<String?>()
        var postCalls = 0

        override suspend fun history(userId: String, after: String?): CommunityChatPage {
            historyCalls++
            historyAfter += after
            firstHistory.complete(Unit)
            if (failNext) {
                failNext = false
                throw CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE, "Unavailable")
            }
            val selected = if (after == null) entries.takeLast(50)
                else entries.filter { it.id.toLong() > after.toLong() }.take(50)
            return CommunityChatPage(selected, selected.lastOrNull()?.id ?: after ?: "0",
                joined, deletionVersion)
        }

        override suspend fun post(userId: String, text: String): CommunityChatEntry {
            postCalls++
            if (!joined) throw CommunityChatException(CommunityChatFailure.JOIN_REQUIRED, "Join")
            return append(text, true)
        }

        override suspend fun join(userId: String): Boolean {
            if (failNextJoin) {
                failNextJoin = false
                throw CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE, "Unavailable")
            }
            joined = true
            return true
        }

        override suspend fun deleteMine(userId: String): Int {
            if (failNextDelete) {
                failNextDelete = false
                throw CommunityChatException(CommunityChatFailure.SERVICE_UNAVAILABLE, "Unavailable")
            }
            return removeMineRemotely()
        }

        fun remote(text: String) = append(text, false)
        fun mine(text: String) = append(text, true)

        fun removeMineRemotely(): Int {
            val count = entries.count { it.isMine }
            entries.removeAll { it.isMine }
            joined = false
            deletionVersion++
            return count
        }

        private fun append(text: String, mine: Boolean) = CommunityChatEntry(
            (nextId++).toString(), "Member ABC123", text,
            Instant.parse("2026-10-09T00:00:00Z").toEpochMilli(), mine,
        ).also(entries::add)
    }
}
