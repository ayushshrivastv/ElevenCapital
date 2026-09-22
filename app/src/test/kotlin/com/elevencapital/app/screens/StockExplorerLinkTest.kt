package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchaseRouteState
import com.elevencapital.app.purchase.PurchaseStatus
import com.elevencapital.app.wallet.encodeBase58
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockExplorerLinkTest {
    @Test
    fun `validated exact Solana mint gets the Solscan address link`() {
        val mint = "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB"

        val link = exactStockExplorerLink("solana", mint)

        assertEquals("Solana", link?.networkLabel)
        assertEquals("Solscan", link?.explorerLabel)
        assertEquals(mint, link?.address)
        assertEquals("https://solscan.io/address/$mint", link?.url)
    }

    @Test
    fun `unknown networks and malformed addresses never become explorer links`() {
        assertNull(exactStockExplorerLink("ethereum", "0x8ad3c73f833d3f9a523ab01476625f269aeb7cf0"))
        assertNull(exactStockExplorerLink("solana", "not-a-solana-address"))
        assertNull(exactStockExplorerLink("solana", null))
        assertNull(exactStockExplorerLink(null, "XsDoVfqeBukxuZHWhdvWHBhgEHjGNst4MLodqsJHzoB"))
    }

    @Test
    fun `only HTTPS product information URLs can be opened`() {
        assertEquals("https://prestocks.com/products/openai",
            safeStockInformationUrl("https://prestocks.com/products/openai"))
        assertNull(safeStockInformationUrl("http://prestocks.com/products/openai"))
        assertNull(safeStockInformationUrl("javascript:alert(1)"))
        assertNull(safeStockInformationUrl("https://user:password@prestocks.com/products/openai"))
    }

    @Test
    fun `only completed purchase opens its exact Solscan transaction`() {
        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        val completed = PurchaseStatus("quote-1", PurchaseRouteState.COMPLETED, 1, 1,
            listOf(signature), null, null, Instant.parse("2026-09-22T10:00:01Z"), signature)
        assertEquals("https://solscan.io/tx/$signature", completedPurchaseSolscanUrl(completed))
        assertNull(completedPurchaseSolscanUrl(completed.copy(state = PurchaseRouteState.EXECUTING,
            solanaTransactionSignature = null)))
        assertNull(completedPurchaseSolscanUrl(completed.copy(solanaTransactionSignature = null)))
    }
}
