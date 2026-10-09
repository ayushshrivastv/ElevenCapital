package com.elevencapital.app.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.wallet.encodeBase58
import java.lang.reflect.Proxy
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PreviewWalletStoreRepeatPurchaseTest {
    @get:Rule val paparazzi = Paparazzi()
    private val creatorMint = encodeBase58(ByteArray(32) { index -> (37 + index).toByte() })

    @Test fun separateBuysOfTheSameStockPersistAndEachDebitSolValue() {
        val context = memoryContext()
        val userId = "repeat-buy-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }

        val first = PreviewWalletStore.recordPurchase(context, userId, SPCXX_PREVIEW_STOCK_ID,
            "SPCXx", BigDecimal("12.34"), BigDecimal("0.12"), BigDecimal("12.30"), "tap-1")
        val second = PreviewWalletStore.recordPurchase(context, userId, SPCXX_PREVIEW_STOCK_ID,
            "SPCXx", BigDecimal("3.21"), BigDecimal("0.03"), BigDecimal("3.20"), "tap-2")

        assertNotNull(first)
        assertNotNull(second)
        val restored = PreviewWalletStore.read(context, userId)
        assertEquals(listOf("tap-1", "tap-2"), restored.purchases.map { it.purchaseId })
        assertEquals(listOf(BigDecimal("12.34"), BigDecimal("3.21")),
            restored.purchases.map { it.spentUsd })
        assertEquals(BigDecimal("66.45"), restored.availableSolUsd)
        assertEquals(BigDecimal.ZERO, restored.availableUsdc)
        assertEquals(2, homeActivityItems(emptyList(), emptyList(), emptyList(),
            previewWalletState = restored).count { it.previewPurchase != null })
    }

    @Test fun retryingOneBuyIdIsIdempotentAndConflictingTermsAreRejected() {
        val context = memoryContext()
        val userId = "retry-buy-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        val amount = BigDecimal("5.00")
        val quantity = BigDecimal("0.1")
        assertNotNull(PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", amount, quantity, amount, "one-buy"))

        val retry = PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", amount, quantity, amount, "one-buy")
        assertEquals(1, retry?.purchases?.size)
        assertEquals(BigDecimal("77.00"), retry?.availableSolUsd)
        assertNull(PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", BigDecimal("6.00"), quantity, BigDecimal("6.00"), "one-buy"))
        assertEquals(1, PreviewWalletStore.read(context, userId).purchases.size)
    }

    @Test fun repeatedBuysCannotSpendMoreThanSeededSolValue() {
        val context = memoryContext()
        val userId = "limited-buy-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        assertNotNull(PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", BigDecimal("81.99"), BigDecimal("0.7"), BigDecimal("81.99"), "large-buy"))
        assertNull(PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", BigDecimal("0.02"), BigDecimal("0.001"), BigDecimal("0.02"), "too-large"))
        val final = PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", BigDecimal("0.01"), BigDecimal("0.0001"), BigDecimal("0.01"), "last-cent")
        assertEquals(2, final?.purchases?.size)
        assertEquals(BigDecimal("0.00"), final?.availableSolUsd)
        assertEquals(BigDecimal.ZERO, final?.availableUsdc)
    }

    @Test fun oldRowsGainStableIdsAndLegacySpcxxActivityRemainsSeparate() {
        val context = memoryContext()
        val userId = "legacy-buy-user"
        val oldRow = JSONObject()
            .put("stockId", SPCXX_PREVIEW_STOCK_ID)
            .put("symbol", "SPCXx")
            .put("spentUsd", "0.01")
            .put("purchasedAtEpochMillis", 2_000L)
        val raw = JSONObject()
            .put("refreshTapCount", 4)
            .put("fundedAtEpochMillis", 1_000L)
            .put("purchases", JSONArray().put(oldRow)).toString()
        context.getSharedPreferences("eleven-wallet-preview-v2", Context.MODE_PRIVATE)
            .edit().putString(userId, raw).commit()

        val original = PreviewWalletStore.read(context, userId).purchases.single()
        assertTrue(original.purchaseId.startsWith("legacy-"))
        assertEquals(original.purchaseId,
            PreviewWalletStore.read(context, userId).purchases.single().purchaseId)
        val updated = PreviewWalletStore.recordPurchase(context, userId, SPCXX_PREVIEW_STOCK_ID,
            "SPCXx", BigDecimal("0.02"), BigDecimal("0.0002"), BigDecimal("0.02"), "new-buy")
        assertNotNull(updated)
        assertEquals(listOf(original.purchaseId, "new-buy"),
            PreviewWalletStore.read(context, userId).purchases.map { it.purchaseId })

        val separateLegacyPosition = PreviewSpcxxPosition(
            BigDecimal.ONE, BigDecimal("10.00"), 3_000L)
        val activity = homeActivityItems(emptyList(), emptyList(), emptyList(),
            previewSpcxxPosition = separateLegacyPosition,
            previewWalletState = requireNotNull(updated))
        assertEquals(2, activity.count { it.previewPurchase != null })
        assertEquals(1, activity.count { it.previewSpcxx != null })
    }

    @Test fun localCommunityBuysAndPartialSalesChangeOnlyPreviewCashAndAreIdempotent() {
        val context = memoryContext()
        val userId = "community-trade-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        val buy = PreviewWalletStore.recordCommunityTrade(context, userId, "buy-1", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.002"), BigDecimal("400"))!!
        assertEquals(BigDecimal("0.398"), buy.availableSol)
        assertEquals(BigDecimal("81.59"), buy.availableSolUsd)
        assertEquals(BigDecimal("400"), buy.communityHeldQuantity(creatorMint))
        assertEquals(1, PreviewWalletStore.recordCommunityTrade(context, userId, "buy-1", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.0020"), BigDecimal("400.0"))!!.communityTrades.size)
        assertNull(PreviewWalletStore.recordCommunityTrade(context, userId, "buy-1", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.003"), BigDecimal("400")))

        val sold = PreviewWalletStore.recordCommunityTrade(context, userId, "sell-1", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.SELL,
            BigDecimal("0.001"), BigDecimal("100"))!!
        assertEquals(BigDecimal("0.399"), sold.availableSol)
        assertEquals(BigDecimal("81.80"), sold.availableSolUsd)
        assertEquals(BigDecimal("300"), sold.communityHeldQuantity(creatorMint))
        assertEquals(2, PreviewWalletStore.recordCommunityTrade(context, userId, "sell-1", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.SELL,
            BigDecimal("0.001"), BigDecimal("100"))!!.communityTrades.size)
        assertNull(PreviewWalletStore.removeCommunityTrade(context, userId, "buy-1"))
        assertNull(PreviewWalletStore.recordCommunityTrade(context, userId, "sell-2", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.SELL,
            BigDecimal("1"), BigDecimal("301")))
        assertEquals(BigDecimal("0.398"), PreviewWalletStore.removeCommunityTrade(
            context, userId, "sell-1")?.availableSol)
        assertEquals(BigDecimal("400"), PreviewWalletStore.read(context, userId)
            .communityHeldQuantity(creatorMint))
    }

    @Test fun aSolBoughtTokenCanSellForUsdcAndUseOnlyThatLocalUsdc() {
        val context = memoryContext()
        val userId = "community-usdc-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        assertNull(PreviewWalletStore.recordCommunityTrade(context, userId, "no-usdc", creatorMint,
            PreviewCommunityCashAsset.USDC, PreviewCommunityTradeSide.BUY,
            BigDecimal("1.00"), BigDecimal("100")))
        assertNotNull(PreviewWalletStore.recordCommunityTrade(context, userId, "sol-buy", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.01"), BigDecimal("100")))
        val sold = PreviewWalletStore.recordCommunityTrade(context, userId, "usdc-sell", creatorMint,
            PreviewCommunityCashAsset.USDC, PreviewCommunityTradeSide.SELL,
            BigDecimal("0.50"), BigDecimal("50"))!!
        assertEquals(BigDecimal("0.5"), sold.availableUsdc)
        val boughtAgain = PreviewWalletStore.recordCommunityTrade(context, userId, "usdc-buy", creatorMint,
            PreviewCommunityCashAsset.USDC, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.30"), BigDecimal("30"))!!
        assertEquals(BigDecimal("0.2"), boughtAgain.availableUsdc)
        assertEquals(BigDecimal("80"), boughtAgain.communityHeldQuantity(creatorMint))
        assertNull(PreviewWalletStore.recordCommunityTrade(context, userId, "overspend", creatorMint,
            PreviewCommunityCashAsset.USDC, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.21"), BigDecimal("21")))
    }

    @Test fun legacyHoldingBackfillCoversOnlyMissingQuantityAfterARecentBuy() {
        val context = memoryContext()
        val userId = "mixed-community-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        assertNotNull(PreviewWalletStore.recordCommunityTrade(context, userId, "new-buy", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.0005"), BigDecimal("100")))
        val restored = PreviewWalletStore.reconcileLegacyCommunityHolding(context, userId,
            creatorMint, null, BigDecimal("0.50"), BigDecimal("500"))!!
        assertEquals(BigDecimal("500"), restored.communityHeldQuantity(creatorMint))
        assertEquals(BigDecimal("400"), restored.communityTrades.last().tokenQuantity)
        assertEquals(restored, PreviewWalletStore.reconcileLegacyCommunityHolding(context,
            userId, creatorMint, null, BigDecimal("0.50"), BigDecimal("500")))
        assertNotNull(PreviewWalletStore.recordCommunityTrade(context, userId, "sale", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.SELL,
            BigDecimal("0.001"), BigDecimal("500")))
        assertEquals(BigDecimal.ZERO, PreviewWalletStore.read(context, userId)
            .communityHeldQuantity(creatorMint))
    }

    @Test fun communityLedgerPersistsBesideLaterStockBuysAndClearsWithPreviewWallet() {
        val context = memoryContext()
        val userId = "community-stock-user"
        repeat(4) { PreviewWalletStore.refresh(context, userId) }
        assertNotNull(PreviewWalletStore.recordCommunityTrade(context, userId, "buy", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.3"), BigDecimal("300")))
        assertNotNull(PreviewWalletStore.recordCommunityTrade(context, userId, "sell", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.SELL,
            BigDecimal("0.3"), BigDecimal("300")))
        assertNotNull(PreviewWalletStore.recordPurchase(context, userId, NIKE_PREVIEW_STOCK_ID,
            "NKE.US", BigDecimal("40.00"), BigDecimal("1"), BigDecimal("40.00"), "stock-after-sale"))
        assertEquals(BigDecimal("42.00"), PreviewWalletStore.read(context, userId).availableSolUsd)
        assertEquals(2, PreviewWalletStore.read(context, userId).communityTrades.size)
        assertEquals(PreviewWalletState(), PreviewWalletStore.clear(context, userId))
        assertTrue(PreviewWalletStore.read(context, userId).communityTrades.isEmpty())
        assertNull(PreviewWalletStore.recordCommunityTrade(context, userId, "unfunded", creatorMint,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            BigDecimal("0.01"), BigDecimal("10")))
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
