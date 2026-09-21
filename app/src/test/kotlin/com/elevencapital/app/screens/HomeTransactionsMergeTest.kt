package com.elevencapital.app.screens

import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.PurchaseUsdBasis
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.data.WalletUsdBasis
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTransactionsMergeTest {
    @Test
    fun completedCrossChainBuyAppearsOnceAndUnrelatedTransfersRemainOrdered() {
        val evmHash = "0x" + "a".repeat(64)
        val solanaSignature = "3".repeat(88)
        val buy = CompletedPurchaseTransaction(
            id = "order-1", transactionId = solanaSignature,
            relatedTransactionIds = listOf(evmHash, solanaSignature),
            timestamp = Instant.parse("2026-09-24T02:00:00Z"),
            timestampBasis = "completion_observed", side = CompletedPurchaseSide.BUY,
            stockId = "prestocks:ANTHROPIC", fromAssetId = "ARBITRUM:ETH",
            inputAmount = BigDecimal("0.001"), receivedAmount = BigDecimal("1"),
            valueUsd = BigDecimal("1.00"), usdBasis = PurchaseUsdBasis.QUOTE_ESTIMATE,
        )
        val evmSend = transfer("route-evm", evmHash, WalletActivityChain.ARBITRUM,
            WalletTransactionDirection.SEND, "2026-09-24T01:58:00Z")
        val solanaReceive = transfer("route-sol", solanaSignature, WalletActivityChain.SOLANA,
            WalletTransactionDirection.RECEIVE, "2026-09-24T01:59:00Z")
        val unrelated = transfer("deposit", "0x" + "b".repeat(64), WalletActivityChain.ARBITRUM,
            WalletTransactionDirection.RECEIVE, "2026-09-24T02:01:00Z")

        val result = homeActivityItems(emptyList(), listOf(evmSend, solanaReceive, unrelated), listOf(buy))

        assertEquals(listOf("deposit", "order-1"), result.map { it.chain?.id ?: it.purchase?.id })
    }

    private fun transfer(id: String, hash: String, chain: WalletActivityChain,
        direction: WalletTransactionDirection, timestamp: String) = WalletTransaction(
        id = id, chain = chain, transactionId = hash, timestamp = Instant.parse(timestamp),
        direction = direction, assetSymbol = "ETH", amount = BigDecimal.ONE,
        valueUsd = BigDecimal("1.00"), usdBasis = WalletUsdBasis.CURRENT_SPOT,
        counterparty = null,
    )
}
