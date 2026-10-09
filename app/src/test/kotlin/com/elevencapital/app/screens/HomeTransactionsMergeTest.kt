package com.elevencapital.app.screens

import com.elevencapital.app.data.CompletedPurchaseSide
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.PurchaseUsdBasis
import com.elevencapital.app.data.WalletActivityChain
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.WalletTransactionDirection
import com.elevencapital.app.data.WalletUsdBasis
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTransactionsMergeTest {
    @Test
    fun seededSolAndBothPreviewBuysAppearInTimeOrder() {
        val preview = PreviewWalletState(4, 1_000L, listOf(
            PreviewWalletPurchase(SPCXX_PREVIEW_STOCK_ID, "SPCXx", BigDecimal("0.01"), 2_000L),
            PreviewWalletPurchase(NIKE_PREVIEW_STOCK_ID, "NKE.US", BigDecimal("0.02"), 3_000L),
        ))
        val items = homeActivityItems(emptyList(), emptyList(), emptyList(),
            previewWalletState = preview)
        assertEquals(listOf(NIKE_PREVIEW_STOCK_ID, SPCXX_PREVIEW_STOCK_ID),
            items.mapNotNull { it.previewPurchase?.stockId })
        assertEquals(listOf("SOL"), items.mapNotNull { it.previewFundingSymbol })
        assertEquals(listOf(1_000L), items.mapNotNull { it.previewFundingAt })
    }

    @Test
    fun completedSolanaBuyAppearsOnceAndUnrelatedTransfersRemainOrdered() {
        val routeSignature = encodeBase58(ByteArray(64) { 3 })
        val settlementSignature = encodeBase58(ByteArray(64) { 4 })
        val buy = CompletedPurchaseTransaction(
            id = "order-1", transactionId = settlementSignature,
            relatedTransactionIds = listOf(routeSignature, settlementSignature),
            timestamp = Instant.parse("2026-09-24T02:00:00Z"),
            timestampBasis = "completion_observed", side = CompletedPurchaseSide.BUY,
            stockId = "prestocks:ANTHROPIC", fromAssetId = "SOLANA:USDC",
            inputAmount = BigDecimal("0.001"), receivedAmount = BigDecimal("1"),
            valueUsd = BigDecimal("1.00"), usdBasis = PurchaseUsdBasis.QUOTE_ESTIMATE,
        )
        val routeSend = transfer("route-sol", routeSignature, WalletActivityChain.SOLANA,
            WalletTransactionDirection.SEND, "2026-09-24T01:58:00Z")
        val settlementReceive = transfer("settlement-sol", settlementSignature, WalletActivityChain.SOLANA,
            WalletTransactionDirection.RECEIVE, "2026-09-24T01:59:00Z")
        val unrelated = transfer("deposit", encodeBase58(ByteArray(64) { 5 }), WalletActivityChain.SOLANA,
            WalletTransactionDirection.RECEIVE, "2026-09-24T02:01:00Z")

        val result = homeActivityItems(emptyList(), listOf(routeSend, settlementReceive, unrelated), listOf(buy))

        assertEquals(listOf("deposit", "order-1"), result.map { it.chain?.id ?: it.purchase?.id })
    }

    @Test
    fun creatorTokenActivityMergesWithoutAChainHashOrFabricatedAmount() {
        val mint = encodeBase58(ByteArray(32) { 9 })
        val activity = listOf(
            CommunityUiActivity("mint:$mint", CommunityUiActivityKind.MINT, mint, "SPXTR", 1_000L),
            CommunityUiActivity("buy:one", CommunityUiActivityKind.BUY, mint, "SPXTR", 3_000L),
        )
        val preview = PreviewWalletState(4, 2_000L)

        val merged = homeActivityItems(emptyList(), emptyList(), emptyList(),
            previewWalletState = preview, communityActivity = activity)

        assertEquals(listOf("buy:one", null, "mint:$mint"),
            merged.map { it.communityActivity?.id })
        assertEquals(2, merged.count { it.communityActivity != null })
        assertEquals(0, merged.count { it.chain != null || it.journal != null || it.purchase != null })
    }

    private fun transfer(id: String, hash: String, chain: WalletActivityChain,
        direction: WalletTransactionDirection, timestamp: String) = WalletTransaction(
        id = id, chain = chain, transactionId = hash, timestamp = Instant.parse(timestamp),
        direction = direction, assetSymbol = "SOL", amount = BigDecimal.ONE,
        valueUsd = BigDecimal("1.00"), usdBasis = WalletUsdBasis.CURRENT_SPOT,
        counterparty = null,
    )
}
