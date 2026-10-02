package com.elevencapital.app.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.data.MeteoraMarketToken
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

class CommunityScannedTokenPurchaseStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val mint = key(1)
    private val otherMint = key(2)
    private val pool = key(3)
    private val now = 1_791_500_000_000L
    private val image = "https://assets.geckoterminal.com/token.png"

    @Test fun `a scanned token purchase round trips without claiming a verified receipt`() {
        val purchase = CommunityScannedTokenPurchase(mint, now)
        val encoded = encodeCommunityScannedTokenPurchases(listOf(purchase))

        assertNotNull(encoded)
        assertEquals(listOf(purchase), decodeCommunityScannedTokenPurchases(encoded!!, now))
        assertTrue(encoded.startsWith("1|$mint|"))
    }

    @Test fun `matching market identity is retained and a repeated buy moves token to top`() {
        val metadata = MeteoraMarketToken(mint, pool, "A Creator Token", "CREATOR", null, null, image)
        val first = addCommunityScannedTokenPurchase(emptyList(), mint, metadata, now)!!
        assertEquals("A Creator Token", first.single().name)
        assertEquals("CREATOR", first.single().symbol)
        assertEquals(image, first.single().imageUrl)

        val withOther = addCommunityScannedTokenPurchase(first, otherMint, null, now + 1)!!
        val repeated = addCommunityScannedTokenPurchase(withOther, mint, null, now + 2)!!
        assertEquals(listOf(mint, otherMint), repeated.map { it.mintAddress })
        assertEquals("A Creator Token", repeated.first().name)
        assertEquals(now + 2, repeated.first().purchasedAtMillis)
        assertEquals(repeated, decodeCommunityScannedTokenPurchases(
            encodeCommunityScannedTokenPurchases(repeated)!!, now + 2))
    }

    @Test fun `SPXTR quote records a quantity and repeated buys accumulate the holding`() {
        val firstAmount = BigDecimal("0.05")
        val firstQuantity = spxtrPreviewReceivedTokens(firstAmount)
        assertEquals(BigDecimal("50.000000"), firstQuantity)
        val first = addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            firstAmount, firstQuantity)!!
        assertEquals(BigDecimal("0.05"), first.single().spentSol)
        assertEquals(BigDecimal("50.000000"), first.single().receivedTokens)

        val secondAmount = BigDecimal("0.01")
        val second = addCommunityScannedTokenPurchase(first, mint, null, now + 1,
            secondAmount, spxtrPreviewReceivedTokens(secondAmount))!!
        assertEquals(BigDecimal("0.06"), second.single().spentSol)
        assertEquals(BigDecimal("60.000000"), second.single().receivedTokens)
        val encoded = encodeCommunityScannedTokenPurchases(second)!!
        assertTrue(encoded.startsWith("2|$mint|"))
        assertEquals(second, decodeCommunityScannedTokenPurchases(encoded, now + 1))
    }

    @Test fun `SPXTR holding value uses SOL spend rather than token quantity`() {
        val spend = BigDecimal("0.4")
        val quantity = spxtrPreviewReceivedTokens(spend)
        assertEquals(BigDecimal("400.000000"), quantity)
        assertEquals(BigDecimal("82.00"), spxtrPreviewHoldingValueUsd(spend))
        assertEquals(BigDecimal("0.21"), spxtrPreviewHoldingValueUsd(BigDecimal("0.001")))
        assertNull(spxtrPreviewHoldingValueUsd(null))
        assertNull(spxtrPreviewHoldingValueUsd(BigDecimal.ZERO))
    }

    @Test fun `USD buy stores exact dollar cost and quantity without treating dollars as SOL`() {
        val amountUsd = BigDecimal("0.40")
        val received = spxtrPreviewReceivedTokensUsd(amountUsd)
        assertEquals(BigDecimal("400.000000"), received)
        val bought = addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            receivedTokens = received, spentUsd = amountUsd)!!
        assertNull(bought.single().spentSol)
        assertEquals(amountUsd, bought.single().spentUsd)
        assertEquals(BigDecimal("0.40"), spxtrPreviewHoldingCostUsd(bought.single()))
        assertEquals(BigDecimal("0.4000"),
            spxtrPreviewHoldingMarketValueUsd(bought.single().receivedTokens))

        val encoded = encodeCommunityScannedTokenPurchases(bought)!!
        assertTrue(encoded.startsWith("3|$mint|"))
        assertEquals(bought, decodeCommunityScannedTokenPurchases(encoded, now))
    }

    @Test fun `new USD buy retains earlier SOL spend and combines cost basis`() {
        val earlier = addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            spentSol = BigDecimal("0.05"), receivedTokens = BigDecimal("50.000000"))!!
        val combined = addCommunityScannedTokenPurchase(earlier, mint, null, now + 1,
            receivedTokens = spxtrPreviewReceivedTokensUsd(BigDecimal("0.40")),
            spentUsd = BigDecimal("0.40"))!!
        val purchase = combined.single()
        assertEquals(BigDecimal("0.05"), purchase.spentSol)
        assertEquals(BigDecimal("0.40"), purchase.spentUsd)
        assertEquals(BigDecimal("450.000000"), purchase.receivedTokens)
        assertEquals(BigDecimal("10.65"), spxtrPreviewHoldingCostUsd(purchase))
        assertEquals(BigDecimal("0.4500"),
            spxtrPreviewHoldingMarketValueUsd(purchase.receivedTokens))

        val encoded = encodeCommunityScannedTokenPurchases(combined)!!
        assertTrue(encoded.startsWith("3|$mint|"))
        assertEquals(combined, decodeCommunityScannedTokenPurchases(encoded, now + 1))
    }

    @Test fun `partial sell reduces quantity and both cost bases proportionally`() {
        val mixed = addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            spentSol = BigDecimal("0.05"), receivedTokens = BigDecimal("50.000000"))!!
        val bought = addCommunityScannedTokenPurchase(mixed, mint, null, now + 1,
            spentUsd = BigDecimal("0.40"), receivedTokens = BigDecimal("400.000000"))!!
        val afterSell = sellCommunityScannedTokenPurchase(bought, mint, BigDecimal("225.000000"))!!
        assertEquals(BigDecimal("225.000000"), afterSell.single().receivedTokens)
        assertEquals(BigDecimal("0.025000000"), afterSell.single().spentSol)
        assertEquals(BigDecimal("0.200000000"), afterSell.single().spentUsd)
        assertEquals(BigDecimal("5.33"), spxtrPreviewHoldingCostUsd(afterSell.single()))
        assertEquals(afterSell, decodeCommunityScannedTokenPurchases(
            encodeCommunityScannedTokenPurchases(afterSell)!!, now + 1))
        assertEquals(emptyList<CommunityScannedTokenPurchase>(),
            sellCommunityScannedTokenPurchase(afterSell, mint, BigDecimal("225.000000")))
    }

    @Test fun `selling absent or more than held quantity fails without mutating purchases`() {
        val bought = addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            spentUsd = BigDecimal("0.40"), receivedTokens = BigDecimal("400"))!!
        assertNull(sellCommunityScannedTokenPurchase(bought, otherMint, BigDecimal.ONE))
        assertNull(sellCommunityScannedTokenPurchase(bought, mint, BigDecimal("401")))
        assertNull(sellCommunityScannedTokenPurchase(bought, mint, BigDecimal.ZERO))
        assertNull(sellCommunityScannedTokenPurchase(listOf(bought.single(), bought.single()),
            mint, BigDecimal.ONE))
        assertEquals(BigDecimal("400"), bought.single().receivedTokens)
    }

    @Test fun `trade IDs prevent duplicate buy and full sale even after later rebuy`() {
        val context = memoryContext()
        val first = CommunityScannedTokenPurchaseStore.recordBuy(context, "holder", mint,
            "buy-1", spentUsd = BigDecimal("0.40"), receivedTokens = BigDecimal("400"))!!
        assertEquals(1, first.size)
        val retry = CommunityScannedTokenPurchaseStore.recordBuy(context, "holder", mint,
            "buy-1", spentUsd = BigDecimal("0.4"), receivedTokens = BigDecimal("400"))!!
        assertEquals(BigDecimal("400"), retry.single().receivedTokens)
        assertNull(CommunityScannedTokenPurchaseStore.recordBuy(context, "holder", mint,
            "buy-1", spentUsd = BigDecimal("0.50"), receivedTokens = BigDecimal("500")))
        assertEquals(emptyList<CommunityScannedTokenPurchase>(),
            CommunityScannedTokenPurchaseStore.recordSell(context, "holder", mint,
                "sell-1", BigDecimal("400")))
        assertEquals(emptyList<CommunityScannedTokenPurchase>(),
            CommunityScannedTokenPurchaseStore.recordSell(context, "holder", mint,
                "sell-1", BigDecimal("400")))
        assertNotNull(CommunityScannedTokenPurchaseStore.recordBuy(context, "holder", mint,
            "buy-2", spentUsd = BigDecimal("0.10"), receivedTokens = BigDecimal("100")))
        assertEquals(BigDecimal("100"), CommunityScannedTokenPurchaseStore.recordSell(
            context, "holder", mint, "sell-1", BigDecimal("400"))!!.single().receivedTokens)
        assertNull(CommunityScannedTokenPurchaseStore.recordSell(context, "holder", mint,
            "sell-1", BigDecimal("100")))
        assertTrue(CommunityScannedTokenPurchaseStore.clear(context, "holder"))
        assertNotNull(CommunityScannedTokenPurchaseStore.recordBuy(context, "holder", mint,
            "buy-1", spentUsd = BigDecimal("0.05"), receivedTokens = BigDecimal("50")))
    }

    @Test fun `older purchase rows remain readable beside quantified buys`() {
        val legacy = CommunityScannedTokenPurchase(mint, now)
        val quantified = CommunityScannedTokenPurchase(otherMint, now + 1,
            spentSol = BigDecimal("0.1"), receivedTokens = BigDecimal("100.000000"))
        val encoded = encodeCommunityScannedTokenPurchases(listOf(legacy, quantified))!!
        assertEquals(listOf(legacy, quantified), decodeCommunityScannedTokenPurchases(encoded, now + 1))
        assertTrue(encoded.lineSequence().first().startsWith("1|"))
        assertTrue(encoded.lineSequence().last().startsWith("2|"))
    }

    @Test fun `invalid or incomplete purchase quantities fail closed`() {
        assertNull(spxtrPreviewReceivedTokens(BigDecimal.ZERO))
        assertNull(spxtrPreviewReceivedTokens(BigDecimal("0.0000000001")))
        assertNull(addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            BigDecimal("0.05"), null))
        assertNull(addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            BigDecimal("-0.05"), BigDecimal("50")))
        assertNull(encodeCommunityScannedTokenPurchases(listOf(
            CommunityScannedTokenPurchase(mint, now, spentSol = BigDecimal("0.05")))))
        assertNull(spxtrPreviewReceivedTokensUsd(BigDecimal.ZERO))
        assertNull(addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            spentUsd = BigDecimal("0.40")))
        assertNull(addCommunityScannedTokenPurchase(emptyList(), mint, null, now,
            spentSol = BigDecimal("0.05"), spentUsd = BigDecimal("0.40"),
            receivedTokens = BigDecimal("450")))
        assertNull(encodeCommunityScannedTokenPurchases(listOf(
            CommunityScannedTokenPurchase(mint, now, spentUsd = BigDecimal("0.40")))))
    }

    @Test fun `unmatched or unsafe market metadata cannot alter scanned mint identity`() {
        val wrong = MeteoraMarketToken(otherMint, pool, "Wrong", "WRONG", null, null, image)
        val unmatched = addCommunityScannedTokenPurchase(emptyList(), mint, wrong, now)!!.single()
        assertEquals(null, unmatched.name)
        assertEquals(null, unmatched.imageUrl)

        val unsafe = MeteoraMarketToken(mint, pool, "Valid", "VALID", null, null,
            "https://evil.example/token.png")
        val sanitized = addCommunityScannedTokenPurchase(emptyList(), mint, unsafe, now)!!.single()
        assertEquals("Valid", sanitized.name)
        assertEquals(null, sanitized.imageUrl)
    }

    @Test fun `invalid mint and tampered persistent records fail closed`() {
        assertNull(addCommunityScannedTokenPurchase(emptyList(), "SPXTR", null, now))
        assertNull(encodeCommunityScannedTokenPurchases(listOf(
            CommunityScannedTokenPurchase(mint, now, name = "No ticker"))))
        val valid = encodeCommunityScannedTokenPurchases(listOf(
            CommunityScannedTokenPurchase(mint, now)))!!
        assertNull(decodeCommunityScannedTokenPurchases(valid.replace(mint, "SPXTR"), now))
        assertNull(decodeCommunityScannedTokenPurchases(valid, now - 5 * 60 * 1_000L - 1))
        assertNull(decodeCommunityScannedTokenPurchases("$valid\n$valid", now))
    }

    @Test fun `purchase history is bounded to newest one hundred unique mints`() {
        var purchases = emptyList<CommunityScannedTokenPurchase>()
        repeat(102) { index ->
            purchases = addCommunityScannedTokenPurchase(
                purchases, key(index + 10), null, now + index)!!
        }
        assertEquals(100, purchases.size)
        assertEquals(key(111), purchases.first().mintAddress)
        assertEquals(key(12), purchases.last().mintAddress)
    }

    @Test fun `four second hold removes only the matching simulated purchase`() {
        val launched = CommunityScannedTokenPurchase(mint, now)
        val another = CommunityScannedTokenPurchase(otherMint, now + 1)
        val remaining = removeCommunityScannedTokenPurchase(listOf(launched, another), mint)

        assertEquals(listOf(another), remaining)
        assertEquals(listOf(another), decodeCommunityScannedTokenPurchases(
            encodeCommunityScannedTokenPurchases(remaining!!)!!, now + 1))
        assertEquals(emptyList<CommunityScannedTokenPurchase>(),
            removeCommunityScannedTokenPurchase(listOf(launched), mint))
    }

    @Test fun `simulated purchase removal rejects an invalid mint or corrupt existing state`() {
        val valid = CommunityScannedTokenPurchase(mint, now)
        assertNull(removeCommunityScannedTokenPurchase(listOf(valid), "SPXTR"))
        assertNull(removeCommunityScannedTokenPurchase(listOf(valid, valid), mint))
        assertEquals(listOf(valid), removeCommunityScannedTokenPurchase(listOf(valid), otherMint))
    }

    @Test fun `clearing rehearsal buys removes only the selected owners purchases`() {
        val context = memoryContext()
        assertNotNull(CommunityScannedTokenPurchaseStore.record(context, "user-one", mint))
        assertNotNull(CommunityScannedTokenPurchaseStore.record(context, "user-one", otherMint))
        assertNotNull(CommunityScannedTokenPurchaseStore.record(context, "user-two", mint))

        assertFalse(CommunityScannedTokenPurchaseStore.clear(context, "user one"))
        assertEquals(2, CommunityScannedTokenPurchaseStore.read(context, "user-one").size)
        assertTrue(CommunityScannedTokenPurchaseStore.clear(context, "user-one"))
        assertTrue(CommunityScannedTokenPurchaseStore.read(context, "user-one").isEmpty())
        assertEquals(1, CommunityScannedTokenPurchaseStore.read(context, "user-two").size)
        assertTrue(CommunityScannedTokenPurchaseStore.clear(context, "user-one"))
    }

    @Test fun `persisted quantified Buy is a token holding for only the purchasing user`() {
        val context = memoryContext()
        val amount = BigDecimal("0.08")
        val received = spxtrPreviewReceivedTokens(amount)!!
        val saved = CommunityScannedTokenPurchaseStore.record(context, "holder", mint,
            spentSol = amount, receivedTokens = received)

        assertNotNull(saved)
        assertEquals(received, CommunityScannedTokenPurchaseStore.read(context, "holder")
            .single().receivedTokens)
        assertTrue(CommunityScannedTokenPurchaseStore.read(context, "other-user").isEmpty())
        assertTrue(CommunityScannedTokenPurchaseStore.clear(context, "holder"))
        assertTrue(CommunityScannedTokenPurchaseStore.read(context, "holder").isEmpty())
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
