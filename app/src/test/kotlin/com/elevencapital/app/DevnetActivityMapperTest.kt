package com.elevencapital.app

import com.elevencapital.app.data.SolanaActivityDirection
import com.elevencapital.app.data.SolanaActivityStatus
import com.elevencapital.app.data.SolanaDevnetSnapshot
import com.elevencapital.app.data.SolanaDevnetTransaction
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.data.walletTransactionsFor
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevnetActivityMapperTest {
    @Test fun `faucet receipt stays on devnet with no invented dollar value`() {
        val address = "11111111111111111111111111111111"
        val receipt = SolanaDevnetTransaction(
            signature = "2".repeat(88),
            timestamp = Instant.parse("2026-09-24T00:00:00Z"),
            direction = SolanaActivityDirection.RECEIVE,
            amount = BigDecimal("0.5"),
            counterparty = null,
        )
        val snapshot = SolanaDevnetSnapshot(address, SolanaActivityStatus.OK,
            Instant.parse("2026-09-24T00:00:01Z"), BigDecimal("0.5"), listOf(receipt))

        val row = snapshot.walletTransactionsFor(address).single()
        assertEquals(WalletActivityChain.SOLANA_DEVNET, row.chain)
        assertEquals(WalletTransactionDirection.RECEIVE, row.direction)
        assertEquals(BigDecimal("0.5"), row.amount)
        assertNull(row.valueUsd)
        assertNull(row.usdBasis)
        assertTrue(snapshot.walletTransactionsFor("DifferentWallet").isEmpty())
    }
}
