package com.elevencapital.app

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchasesParser
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.data.WalletActivityWallet
import com.elevencapital.app.data.WalletTransactionsParser
import com.elevencapital.app.data.activityWallets
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WalletTransactionsTest {
    private val now = Instant.parse("2026-09-24T12:00:00Z")
    private val solana = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    private val other = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR"
    private val signature = encodeBase58(ByteArray(64) { 5 })
    private val wallet = WalletActivityWallet(WalletActivityChain.SOLANA, solana)

    @Test fun `wallet activity binds to verified address and preserves exact USD decimals`() {
        val result = WalletTransactionsParser.parse(walletResponse(), listOf(wallet), now)
        assertEquals(WalletActivityStatus.OK, result.status)
        assertEquals(WalletActivityChain.SOLANA, result.transactions.single().chain)
        assertEquals(BigDecimal("1.23456789"), result.transactions.single().valueUsd)
        assertEquals(solana, result.wallets.single().address)
    }

    @Test fun `wallet identity and malformed values cannot be displayed as activity`() {
        assertThrows(RuntimeException::class.java) {
            WalletTransactionsParser.parse(walletResponse(), listOf(wallet.copy(address = other)), now)
        }
        for (bad in listOf<Any>(1.5, "-1", "1e9", "NaN")) {
            val response = walletResponse()
            response.getJSONArray("transactions").getJSONObject(0).put("valueUsd", bad)
            assertThrows(RuntimeException::class.java) { WalletTransactionsParser.parse(response, listOf(wallet), now) }
        }
        val wrongChain = walletResponse()
        wrongChain.getJSONArray("transactions").getJSONObject(0).put("chain", "BITCOIN")
        assertThrows(RuntimeException::class.java) { WalletTransactionsParser.parse(wrongChain, listOf(wallet), now) }
    }

    @Test fun `purchase activity keeps side optional but rejects unverified valuation basis`() {
        val buy = CompletedPurchasesParser.parse(purchaseResponse(), now).transactions.single()
        assertEquals(CompletedPurchaseSide.BUY, buy.side)
        assertEquals(BigDecimal("1.00"), buy.valueUsd)
        val legacy = purchaseResponse().also { it.getJSONArray("transactions").getJSONObject(0).put("side", JSONObject.NULL) }
        assertNull(CompletedPurchasesParser.parse(legacy, now).transactions.single().side)
        val invalid = purchaseResponse()
        invalid.getJSONArray("transactions").getJSONObject(0).put("usdBasis", JSONObject.NULL)
        assertThrows(RuntimeException::class.java) { CompletedPurchasesParser.parse(invalid, now) }
    }

    @Test fun `one Privy Solana wallet maps to the supported activity network`() {
        val result = activityWallets(listOf(UserWallet(WalletChain.SOLANA, solana)))
        assertEquals(listOf(WalletActivityChain.SOLANA), result.map { it.chain })
        assertEquals(listOf(solana), result.map { it.address })
    }

    private fun walletResponse() = JSONObject()
        .put("schemaVersion", 1).put("scope", "wallet-transactions")
        .put("wallets", JSONArray().put(JSONObject().put("chain", "SOLANA").put("address", solana)))
        .put("status", "ok").put("observedAt", now.toString())
        .put("transactions", JSONArray().put(JSONObject()
            .put("id", "SOLANA:$signature:SOL:outer").put("chain", "SOLANA")
            .put("transactionId", signature).put("timestamp", "2026-09-24T11:59:00Z")
            .put("direction", "RECEIVE").put("assetSymbol", "SOL")
            .put("amount", "0.0001").put("valueUsd", "1.23456789")
            .put("usdBasis", "current_spot").put("counterparty", other)))

    private fun purchaseResponse() = JSONObject()
        .put("schemaVersion", 1).put("scope", "completed-purchases")
        .put("observedAt", now.toString()).put("hasMore", false)
        .put("transactions", JSONArray().put(JSONObject()
            .put("id", "00000000-0000-4000-8000-000000000001")
            .put("transactionId", signature).put("relatedTransactionIds", JSONArray().put(signature))
            .put("timestamp", "2026-09-24T11:59:00Z").put("timestampBasis", "completion_observed")
            .put("side", "BUY").put("stockId", "backpack:SPCX.US")
            .put("fromAssetId", "SOLANA:native").put("inputAmount", "0.0002")
            .put("receivedAmount", "0.002").put("valueUsd", "1.00")
            .put("usdBasis", "quote_estimate")))
}
