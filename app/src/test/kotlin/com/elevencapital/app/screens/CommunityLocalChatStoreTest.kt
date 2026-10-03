package com.elevencapital.app.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.data.CommunityChatEntry
import com.elevencapital.app.wallet.encodeBase58
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommunityLocalChatStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val user = "did:privy:chat-user"
    private val wallet = wallet(1)
    private val now = 1_791_500_000_000L

    @Test fun `sent messages survive screen and context recreation until this token resets`() {
        val preferences = HashMap<String, SharedPreferences>()
        val firstContext = memoryContext(preferences)

        val first = CommunityLocalChatStore.append(firstContext, user, wallet, " Hi ", now)!!
        val second = CommunityLocalChatStore.append(firstContext, user, wallet, "Again", now + 1)!!
        assertEquals(listOf("Hi", "Again"), second.map { it.body })
        assertEquals(1, first.size)
        assertTrue(second.all { it.isMine })
        assertEquals(second.size, second.map { it.id }.distinct().size)

        val recreatedContext = memoryContext(preferences)
        assertEquals(second, CommunityLocalChatStore.read(recreatedContext, user, wallet))

        assertTrue(CommunityLocalChatStore.clear(recreatedContext, user, wallet))
        assertTrue(CommunityLocalChatStore.read(firstContext, user, wallet).isEmpty())
        assertEquals("Fresh", CommunityLocalChatStore.append(
            firstContext, user, wallet, "Fresh", now + 2)!!.single().body)
    }

    @Test fun `holding one token clears only that wallet's local messages`() {
        val context = memoryContext()
        CommunityLocalChatStore.append(context, user, wallet, "Clear me", now)
        CommunityLocalChatStore.append(context, user, wallet(2), "Keep other wallet", now + 1)
        CommunityLocalChatStore.append(context, "did:privy:other-user", wallet,
            "Keep other account", now + 2)

        assertTrue(CommunityLocalChatStore.clear(context, user, wallet))
        assertTrue(CommunityLocalChatStore.read(context, user, wallet).isEmpty())
        assertEquals(listOf("Keep other wallet"),
            CommunityLocalChatStore.read(context, user, wallet(2)).map { it.body })
        assertEquals(listOf("Keep other account"),
            CommunityLocalChatStore.read(context, "did:privy:other-user", wallet).map { it.body })
    }

    @Test fun `invalid draft does not enter local history`() {
        val context = memoryContext()
        assertEquals(null, CommunityLocalChatStore.append(context, user, wallet, "  ", now))
        assertEquals(null, CommunityLocalChatStore.append(context, user, wallet,
            "x".repeat(501), now))
        assertTrue(CommunityLocalChatStore.read(context, user, wallet).isEmpty())
        assertEquals(500, CommunityLocalChatStore.append(context, user, wallet,
            "x".repeat(500), now)!!.single().body.length)
    }

    @Test fun `old server posts by me stay hidden after local chat is reset`() {
        val context = memoryContext()
        val remote = listOf(
            CommunityChatEntry("1", "You", "Old server Hi", now - 2, true),
            CommunityChatEntry("2", "@snowcap", "Keep another person", now - 1, false),
        )
        val local = CommunityLocalChatStore.append(context, user, wallet, "My new message", now)!!
        assertEquals(listOf("Keep another person", "My new message"),
            communityChatFeed(remote, local).map { it.body })

        assertTrue(CommunityLocalChatStore.clear(context, user, wallet))
        val afterReset = communityChatFeed(remote, CommunityLocalChatStore.read(context, user, wallet))
        assertEquals(listOf("Keep another person"), afterReset.map { it.body })
        assertFalse(afterReset.single().isMine)
    }

    @Test fun `phone keeps only the latest two hundred local posts`() {
        val preferences = HashMap<String, SharedPreferences>()
        val context = memoryContext(preferences)
        repeat(201) { index ->
            assertFalse(CommunityLocalChatStore.append(context, user, wallet,
                "Post $index", now + index).isNullOrEmpty())
        }
        val messages = CommunityLocalChatStore.read(memoryContext(preferences), user, wallet)
        assertEquals(200, messages.size)
        assertEquals("Post 1", messages.first().body)
        assertEquals("Post 200", messages.last().body)
    }

    private fun memoryContext(
        preferences: HashMap<String, SharedPreferences> = HashMap(),
    ): Context = object : ContextWrapper(paparazzi.context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            preferences.getOrPut(name, ::memoryPreferences)
    }

    private fun wallet(seed: Int): String = encodeBase58(ByteArray(32) { index ->
        (seed * 37 + index).toByte()
    })

    private fun memoryPreferences(): SharedPreferences {
        val values = HashMap<String, String>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val key = args!![0] as String
                    val value = args[1] as String?
                    if (value == null) values.remove(key) else values[key] = value
                    proxy
                }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "clear" -> { values.clear(); proxy }
                "commit" -> true
                "apply" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }
}
