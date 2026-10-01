package com.elevencapital.app.tokenlaunch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.screens.LaunchTokenDraft
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TokenLaunchCommunityProfileStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val contentArtwork =
        "content://com.android.providers.media.documents/document/image%3A42"
    private val publicArtwork = "https://cdn.example.com/solana/token.jpg"

    @Test fun `confirmed display fields round trip with public artwork`() {
        val context = memoryContext()
        val draft = LaunchTokenDraft(imageUri = publicArtwork, name = "Thankyou", symbol = "SPXTR")

        assertTrue(TokenLaunchCommunityProfileStore.save(context, "did:privy:alice", WRAPPED_SOL_MINT, draft))
        assertEquals(draft.imageUri,
            TokenLaunchCommunityProfileStore.read(context, "did:privy:alice", WRAPPED_SOL_MINT)
                ?.toDraft()?.imageUri)
        assertEquals(draft.name,
            TokenLaunchCommunityProfileStore.read(context, "did:privy:alice", WRAPPED_SOL_MINT)
                ?.toDraft()?.name)
        assertEquals(draft.symbol,
            TokenLaunchCommunityProfileStore.read(context, "did:privy:alice", WRAPPED_SOL_MINT)
                ?.toDraft()?.symbol)
    }

    @Test fun `a profile cannot leak to a different user or mint`() {
        val context = memoryContext()
        assertTrue(TokenLaunchCommunityProfileStore.save(context, "did:privy:alice", WRAPPED_SOL_MINT,
            LaunchTokenDraft(imageUri = publicArtwork, name = "Race", symbol = "RACE")))

        assertNull(TokenLaunchCommunityProfileStore.read(context, "did:privy:bob", WRAPPED_SOL_MINT))
        assertNull(TokenLaunchCommunityProfileStore.read(context, "did:privy:alice", METEORA_DBC_PROGRAM_ID))
    }

    @Test fun `profile codec accepts public artwork and rejects unsafe fields`() {
        val valid = StoredTokenLaunchCommunityProfile(
            userId = "did:privy:alice",
            mintAddress = WRAPPED_SOL_MINT,
            name = "Race",
            symbol = "RACE",
            imageUri = publicArtwork,
        )
        assertEquals(valid, decodeTokenLaunchCommunityProfile(
            requireNotNull(encodeTokenLaunchCommunityProfile(valid))))
        val content = valid.copy(imageUri = contentArtwork)
        assertEquals(content, decodeTokenLaunchCommunityProfile(
            requireNotNull(encodeTokenLaunchCommunityProfile(content))))
        assertNull(encodeTokenLaunchCommunityProfile(valid.copy(symbol = "race")))
        assertNull(encodeTokenLaunchCommunityProfile(valid.copy(imageUri = "file:///private/token.jpg")))
        assertNull(encodeTokenLaunchCommunityProfile(valid.copy(imageUri = "http://example.com/token.jpg")))
        assertNull(encodeTokenLaunchCommunityProfile(valid.copy(mintAddress = "not-a-mint")))
        assertNull(decodeTokenLaunchCommunityProfile("{\"version\":99}"))
    }

    @Test fun `invalid save does not replace a valid profile`() {
        val context = memoryContext()
        val valid = LaunchTokenDraft(imageUri = publicArtwork, name = "Race", symbol = "RACE")
        assertTrue(TokenLaunchCommunityProfileStore.save(context, "did:privy:alice", WRAPPED_SOL_MINT, valid))
        assertFalse(TokenLaunchCommunityProfileStore.save(context, "did:privy:alice", WRAPPED_SOL_MINT,
            valid.copy(imageUri = "file:///private/token.jpg")))
        assertFalse(TokenLaunchCommunityProfileStore.save(context, "did:privy:alice", WRAPPED_SOL_MINT,
            valid.copy(imageUri = contentArtwork)))
        assertEquals(valid.name,
            TokenLaunchCommunityProfileStore.read(context, "did:privy:alice", WRAPPED_SOL_MINT)
                ?.name)
    }

    private fun memoryContext(): Context {
        val stores = HashMap<String, SharedPreferences>()
        return object : ContextWrapper(paparazzi.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                stores.getOrPut(name, ::memoryPreferences)
        }
    }

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
