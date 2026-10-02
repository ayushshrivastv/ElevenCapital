package com.elevencapital.app.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigDecimal
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CommunityUiActivityStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val mint = key(1)
    private val otherMint = key(2)

    @Test fun mintAndRepeatedBuysPersistWithoutForgingWalletTransactions() {
        val context = memoryContext()
        val now = System.currentTimeMillis() - 1_000L
        val first = CommunityUiActivityStore.recordMint(context, "user-one", mint, "SPXTR", now)
        assertNotNull(first)
        assertEquals(first, CommunityUiActivityStore.recordMint(context, "user-one", mint, "SPXTR", now + 5))
        assertNotNull(CommunityUiActivityStore.recordBuy(context, "user-one", mint, "SPXTR",
            eventId = "first", occurredAtMillis = now + 1))
        assertNotNull(CommunityUiActivityStore.recordBuy(context, "user-one", mint, "SPXTR",
            eventId = "second", occurredAtMillis = now + 2))

        val restored = CommunityUiActivityStore.read(context, "user-one")
        assertEquals(listOf(CommunityUiActivityKind.BUY, CommunityUiActivityKind.BUY,
            CommunityUiActivityKind.MINT), restored.map(CommunityUiActivity::kind))
        assertEquals(listOf("buy:second", "buy:first", "mint:$mint"), restored.map(CommunityUiActivity::id))
        assertTrue(CommunityUiActivityStore.read(context, "another-user").isEmpty())
    }

    @Test fun holdingRemovesAllLocalActivityForOnlyItsMintAndOwner() {
        val context = memoryContext()
        val now = System.currentTimeMillis() - 1_000L
        CommunityUiActivityStore.recordMint(context, "user-one", mint, "SPXTR", now)
        CommunityUiActivityStore.recordBuy(context, "user-one", mint, "SPXTR", "buy-one", now + 1)
        CommunityUiActivityStore.recordBuy(context, "user-one", otherMint, "OTHER", "buy-two", now + 2)
        CommunityUiActivityStore.recordBuy(context, "user-two", mint, "SPXTR", "buy-three", now + 3)

        val remaining = CommunityUiActivityStore.removeForMint(context, "user-one", mint)

        assertEquals(listOf(otherMint), remaining?.map(CommunityUiActivity::mintAddress))
        assertEquals(remaining, CommunityUiActivityStore.read(context, "user-one"))
        assertEquals(listOf(mint), CommunityUiActivityStore.read(context, "user-two")
            .map(CommunityUiActivity::mintAddress))
        assertNull(CommunityUiActivityStore.removeForMint(context, "user-one", "SPXTR"))
    }

    @Test fun clearingRehearsalActivityRemovesOnlyTheSelectedOwnersEvents() {
        val context = memoryContext()
        val now = System.currentTimeMillis() - 1_000L
        CommunityUiActivityStore.recordMint(context, "user-one", mint, "SPXTR", now)
        CommunityUiActivityStore.recordBuy(context, "user-one", otherMint, "OTHER", "one-buy", now + 1)
        CommunityUiActivityStore.recordBuy(context, "user-two", mint, "SPXTR", "two-buy", now + 2)

        assertFalse(CommunityUiActivityStore.clear(context, "user one"))
        assertEquals(2, CommunityUiActivityStore.read(context, "user-one").size)
        assertTrue(CommunityUiActivityStore.clear(context, "user-one"))
        assertTrue(CommunityUiActivityStore.read(context, "user-one").isEmpty())
        assertEquals(1, CommunityUiActivityStore.read(context, "user-two").size)
        assertTrue(CommunityUiActivityStore.clear(context, "user-one"))
    }

    @Test fun earlierChristmasActivityDisplaysTheRenamedTickerWithoutChangingTheMint() {
        val context = memoryContext()
        val now = System.currentTimeMillis() - 1_000L
        val previewMint = tokenLaunchUiPreviewResult().mintAddress
        val old = CommunityUiActivity("mint:$previewMint", CommunityUiActivityKind.MINT,
            previewMint, "SPXTR", now)
        val raw = requireNotNull(encodeCommunityUiActivity(listOf(old)))
        assertTrue(context.getSharedPreferences("eleven-community-ui-activity-v1", Context.MODE_PRIVATE)
            .edit().putString("user:dXNlci1vbmU", raw).commit())

        val restored = CommunityUiActivityStore.read(context, "user-one").single()
        assertEquals(previewMint, restored.mintAddress)
        assertEquals("CHTMS", restored.symbol)
        assertEquals(old.id, restored.id)
        assertEquals(listOf(restored), CommunityUiActivityStore.recordMint(context, "user-one",
            previewMint, "CHTMS", now + 1))
    }

    @Test fun buyAndSellKeepExactUsdAmountWhileMintRemainsAmountFree() {
        val context = memoryContext()
        val now = System.currentTimeMillis() - 1_000L
        CommunityUiActivityStore.recordMint(context, "user-one", mint, "CHTMS", now)
        CommunityUiActivityStore.recordBuy(context, "user-one", mint, "CHTMS",
            eventId = "buy-1", occurredAtMillis = now + 1,
            amountUsd = BigDecimal("20.50"))
        CommunityUiActivityStore.recordSell(context, "user-one", mint, "CHTMS",
            eventId = "sell-1", occurredAtMillis = now + 2,
            amountUsd = BigDecimal("2.05"))

        val restored = CommunityUiActivityStore.read(context, "user-one")
        assertEquals(BigDecimal("2.05"), restored[0].amountUsd)
        assertEquals(BigDecimal("20.5"), restored[1].amountUsd)
        assertNull(restored[2].amountUsd)
        assertEquals("2", encodeCommunityUiActivity(restored)!!.substringBefore('|'))
    }

    @Test fun earlierSingleBuyCanRecoverUsdFromItsOnlyLocalHolding() {
        val context = memoryContext()
        val previewMint = tokenLaunchUiPreviewResult().mintAddress
        val now = System.currentTimeMillis() - 1_000L
        assertNotNull(CommunityScannedTokenPurchaseStore.record(context, "user-one", previewMint,
            spentSol = BigDecimal("0.1"), receivedTokens = BigDecimal("100")))
        CommunityUiActivityStore.recordBuy(context, "user-one", previewMint, "SPXTR",
            eventId = "legacy-one", occurredAtMillis = now)

        val restored = CommunityUiActivityStore.read(context, "user-one").single()
        assertEquals("CHTMS", restored.symbol)
        assertEquals(BigDecimal("20.50"), restored.amountUsd)
    }

    @Test fun repeatedLegacyBuysDoNotDivideAnAggregateCostAmongEvents() {
        val context = memoryContext()
        val previewMint = tokenLaunchUiPreviewResult().mintAddress
        val now = System.currentTimeMillis() - 1_000L
        assertNotNull(CommunityScannedTokenPurchaseStore.record(context, "user-one", previewMint,
            spentSol = BigDecimal("0.1"), receivedTokens = BigDecimal("100")))
        val activities = listOf("first", "second").mapIndexed { index, id ->
            CommunityUiActivity("buy:$id", CommunityUiActivityKind.BUY, previewMint,
                "SPXTR", now + index)
        }
        val raw = requireNotNull(encodeCommunityUiActivity(activities))
        assertTrue(context.getSharedPreferences("eleven-community-ui-activity-v1", Context.MODE_PRIVATE)
            .edit().putString("user:dXNlci1vbmU", raw).commit())
        assertTrue(CommunityUiActivityStore.read(context, "user-one").all { it.amountUsd == null })
    }

    @Test fun malformedOrConflictingEventsFailClosed() {
        val now = System.currentTimeMillis()
        val mintEvent = CommunityUiActivity("mint:$mint", CommunityUiActivityKind.MINT, mint,
            "SPXTR", now)
        assertEquals(listOf(mintEvent), decodeCommunityUiActivity(
            encodeCommunityUiActivity(listOf(mintEvent))!!, now))
        assertNull(addCommunityUiActivity(listOf(mintEvent), mintEvent.copy(symbol = "OTHER")))
        assertNull(encodeCommunityUiActivity(listOf(mintEvent, mintEvent)))
        assertNull(encodeCommunityUiActivity(listOf(mintEvent.copy(amountUsd = BigDecimal.ONE))))
        assertNull(decodeCommunityUiActivity("2|buy:x|BUY|$mint|U1BYVFI|$now|NaN", now))
        assertNull(decodeCommunityUiActivity("1|mint:$mint|MINT|$mint|@@@|$now", now))
        assertNull(decodeCommunityUiActivity(encodeCommunityUiActivity(listOf(mintEvent))!!,
            now - 5 * 60 * 1_000L - 1))
        assertNull(removeCommunityUiActivityForMint(listOf(mintEvent), "SPXTR"))
    }

    private fun key(seed: Int): String = encodeBase58(ByteArray(32) { index ->
        (seed * 37 + index).toByte()
    })

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
