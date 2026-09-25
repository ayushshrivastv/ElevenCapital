package com.elevencapital.app

import com.elevencapital.app.data.SolanaDevnetParser
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.walletTransactionsFor
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SolanaDevnetWalletTest {
    private val wallet = "11111111111111111111111111111111"
    private val signature = "4".repeat(87)
    private val now = Instant.parse("2026-09-24T12:00:00Z")

    @Test fun confirmedDevnetReceiveStaysSeparateAndHasNoDollarValue() {
        val snapshot = SolanaDevnetParser.parse(response(), wallet, now)
        assertEquals("1.25", snapshot.balanceSol?.toPlainString())
        val transaction = snapshot.walletTransactionsFor(wallet).single()
        assertEquals(WalletActivityChain.SOLANA_DEVNET, transaction.chain)
        assertEquals("1.25", transaction.amount.toPlainString())
        assertNull(transaction.valueUsd)
        assertNull(transaction.usdBasis)
        assertEquals(0, snapshot.walletTransactionsFor("Vote111111111111111111111111111111111111111").size)
    }

    @Test fun mainnetOrMismatchedAddressCannotMasqueradeAsDevnet() {
        assertThrows(IllegalArgumentException::class.java) {
            SolanaDevnetParser.parse(response().put("network", "SOLANA"), wallet, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SolanaDevnetParser.parse(response().put("walletAddress", "Vote111111111111111111111111111111111111111"), wallet, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SolanaDevnetParser.parse(response().put("valueUsd", "100"), wallet, now)
        }
    }

    private fun response(): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("scope", "solana-devnet-wallet")
        .put("network", "SOLANA_DEVNET").put("walletAddress", wallet)
        .put("status", "ok").put("observedAt", now.toString()).put("balanceSol", "1.25")
        .put("transactions", JSONArray().put(JSONObject()
            .put("signature", signature).put("timestamp", now.toString())
            .put("direction", "RECEIVE").put("amount", "1.25")
            .put("counterparty", JSONObject.NULL)))
}
