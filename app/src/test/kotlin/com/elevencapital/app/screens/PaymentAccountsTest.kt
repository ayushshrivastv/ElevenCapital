package com.elevencapital.app.screens

import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchasePaymentAsset
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentAccountsTest {
    private val sol = PurchaseNetwork.SOLANA

    @Test fun sortsByDollarValueInsteadOfRawUnits() {
        val rows = listOf(asset("USDC", "500", "500"), asset("SOL", "10", "1500"))
        assertEquals(listOf("SOLANA:SOL", "SOLANA:USDC"), fundedPaymentAssets(rows).map { it.id })
    }

    @Test fun excludesDisabledAndEmptyAssets() {
        val rows = listOf(asset("SOL", "0", "0"), asset("USDC", "500", "500", enabled = false),
            asset("BONK", "1", "3500"))
        assertEquals(listOf("SOLANA:BONK"), fundedPaymentAssets(rows).map { it.id })
    }

    @Test fun keepsDifferentSolanaTokenIdentitiesDistinct() {
        val rows = listOf(asset("USDC", "40", "40"), asset("USDT", "80", "80"))
        assertEquals(listOf("SOLANA:USDT", "SOLANA:USDC"), fundedPaymentAssets(rows).map { it.id })
    }

    @Test fun knownValuesSortBeforeUnknownWithDeterministicTies() {
        val rows = listOf(asset("USDC", "40", "40"), asset("SOL", "1", "40"), asset("BONK", "1", null))
        assertEquals(listOf("SOLANA:SOL", "SOLANA:USDC", "SOLANA:BONK"), fundedPaymentAssets(rows).map { it.id })
    }

    private fun asset(symbol: String, balance: String, usd: String?,
        network: PurchaseNetwork = sol, enabled: Boolean = true): PurchasePaymentAsset {
        val amount = BigDecimal(balance)
        return PurchasePaymentAsset("${network.name}:$symbol", symbol, symbol, network, "native", 6,
            amount.movePointRight(6).toBigIntegerExact(), amount, usd?.let(::BigDecimal), enabled)
    }
}
