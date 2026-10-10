package com.elevencapital.app.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import java.lang.reflect.Proxy
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class PreviewRehearsalResetTest {
    @get:Rule val paparazzi = Paparazzi()

    @Test fun fourthRefreshFundsEightyTwoDollarsOfSolOnlyOnce() {
        val context = memoryContext()
        val user = "sol-only-preview-user"
        repeat(3) { tap ->
            val state = PreviewWalletStore.refresh(context, user)
            assertEquals(tap + 1, state.refreshTapCount)
            assertFalse(state.funded)
            assertEquals(BigDecimal.ZERO, state.availableUsd)
        }
        val funded = PreviewWalletStore.refresh(context, user)
        assertTrue(funded.funded)
        assertEquals(BigDecimal("0.4"), funded.availableSol)
        assertEquals(BigDecimal("82.00"), funded.availableSolUsd)
        assertEquals(BigDecimal.ZERO, funded.availableUsdc)
        assertEquals(BigDecimal("82.00"), funded.availableUsd)
        assertEquals(funded, PreviewWalletStore.refresh(context, user))
    }

    @Test fun previousPreviewFundingSurvivesUpgradeButNotAnExplicitReset() {
        val context = memoryContext()
        val user = "preview-upgrade-user"
        val legacy = context.getSharedPreferences("eleven-wallet-preview-v3", Context.MODE_PRIVATE)
        val current = context.getSharedPreferences("eleven-wallet-preview-v2", Context.MODE_PRIVATE)
        val funded = JSONObject().put("refreshTapCount", 4)
            .put("fundedAtEpochMillis", 1_000L)
            .put("purchases", JSONArray()).toString()
        assertTrue(legacy.edit().putString(user, funded).commit())

        assertEquals(BigDecimal("82.00"), PreviewWalletStore.read(context, user).availableSolUsd)
        assertEquals(BigDecimal.ZERO, PreviewWalletStore.read(context, user).availableUsdc)
        assertTrue(current.contains(user))
        assertEquals(PreviewWalletState(), PreviewWalletStore.clear(context, user))
        assertFalse(legacy.contains(user))

        // Even if old app data is restored afterward, the newer account's reset wins.
        assertTrue(legacy.edit().putString(user, funded).commit())
        assertEquals(PreviewWalletState(), PreviewWalletStore.read(context, user))
        repeat(4) { PreviewWalletStore.refresh(context, user) }
        assertEquals(BigDecimal("82.00"), PreviewWalletStore.read(context, user).availableSolUsd)
        assertEquals(BigDecimal.ZERO, PreviewWalletStore.read(context, user).availableUsdc)
    }

    @Test fun tokenHoldClearsAllLocalPreviewBalancesAndActivityOnlyForThatUser() {
        val context = memoryContext()
        val user = "preview-reset-user"
        val anotherUser = "preview-other-user"
        val walletId = "solana-usdc-btc-ui-v1"
        val previewLedgerStore = PredictionLedgerStore(context, preview = true)
        val liveLedgerStore = PredictionLedgerStore(context, preview = false)

        repeat(4) { PreviewWalletStore.refresh(context, user) }
        val funded = PreviewWalletStore.recordPurchase(context, user, "stock-one", "ONE",
            BigDecimal("5.00"), BigDecimal("0.1"), BigDecimal("5.00"))
        assertNotNull(funded)
        assertEquals(BigDecimal("0.1"), PreviewWalletStore.read(context, user).purchases.single().receivedTokens)
        assertNotNull(PreviewSpcxxHoldingStore.set(context, user,
            BigDecimal("1"), BigDecimal("10")))
        val prediction = PredictionWalletLedger.empty()
            .creditLocalUsdc("preview-transfer", BigDecimal("20"), 2_000L)
        assertTrue(previewLedgerStore.save(user, walletId, prediction))

        repeat(4) { PreviewWalletStore.refresh(context, anotherUser) }
        val anotherPrediction = PredictionWalletLedger.empty()
            .creditLocalUsdc("other-transfer", BigDecimal("8"), 2_100L)
        assertTrue(previewLedgerStore.save(anotherUser, walletId, anotherPrediction))
        val livePrediction = PredictionWalletLedger.empty()
            .creditLocalUsdc("live-transfer", BigDecimal("7"), 2_200L)
        assertTrue(liveLedgerStore.save(user, walletId, livePrediction))

        val cleared = resetPreviewRehearsal(context, user, walletId,
            previewLedgerStore, prediction)

        assertEquals(PreviewWalletState(), cleared)
        assertEquals(PreviewWalletState(), PreviewWalletStore.read(context, user))
        assertNull(PreviewSpcxxHoldingStore.read(context, user))
        val emptyPrediction = previewLedgerStore.load(user, walletId)
        assertEquals(0, emptyPrediction.availableQuoteUsd.signum())
        assertTrue(emptyPrediction.history.isEmpty())
        assertTrue(emptyPrediction.mainTransferItems().isEmpty())
        assertTrue(homeActivityItems(emptyList(), emptyList(), emptyList(),
            previewWalletState = requireNotNull(cleared),
            predictionTransfers = emptyPrediction.mainTransferItems()).isEmpty())

        assertTrue(PreviewWalletStore.read(context, anotherUser).funded)
        assertEquals(BigDecimal("8.000000"),
            previewLedgerStore.load(anotherUser, walletId).availableQuoteUsd)
        assertEquals(BigDecimal("7.000000"),
            liveLedgerStore.load(user, walletId).availableQuoteUsd)
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
