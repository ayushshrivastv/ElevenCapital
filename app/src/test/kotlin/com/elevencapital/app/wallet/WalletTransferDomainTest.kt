package com.elevencapital.app.wallet

import java.math.BigInteger
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletTransferDomainTest {
    @Test fun `registry pins canonical mainnet identities and precision`() {
        assertEquals(listOf(WalletNetworkId.SOLANA_MAINNET),
            WalletAssetRegistry.networks.map(WalletNetwork::id))
        assertEquals("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", WalletAssetRegistry.solanaMainnet.caip2)

        assertEquals(9, WalletAssetRegistry.solanaSol.decimals)
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            WalletAssetRegistry.solanaUsdc.contractOrMint)
        assertEquals(6, WalletAssetRegistry.solanaUsdc.decimals)
        assertEquals(64, WalletAssetRegistry.solanaUsdc.baseUnitBits)
        assertEquals(WalletAssetRegistry.solanaUsdc.contractOrMint,
            ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET,
                requireNotNull(WalletAssetRegistry.solanaUsdc.contractOrMint)).value)
        WalletAssetId.entries.forEach { assertSame(WalletAssetRegistry.asset(it), WalletAssetRegistry.asset(it)) }
    }

    @Test fun `registry collections cannot be changed by a caller`() {
        @Suppress("UNCHECKED_CAST")
        val assets = WalletAssetRegistry.assets as MutableList<WalletAsset>
        assertThrows(UnsupportedOperationException::class.java) { assets.clear() }
        assertEquals(2, WalletAssetRegistry.assets.size)
    }

    @Test fun `amount conversion is exact for native and token decimals`() {
        val sol = TransferAmount.parse("1.000000001", WalletAssetRegistry.solanaSol)
        assertEquals(BigInteger("1000000001"), sol.baseUnits)
        assertEquals("1.000000001", sol.displayAmount)

        val usdc = TransferAmount.parse("0.000001", WalletAssetRegistry.solanaUsdc)
        assertEquals(BigInteger.ONE, usdc.baseUnits)
        assertEquals("0.000001", usdc.displayAmount)

        val normalized = TransferAmount.parse("12.340000", WalletAssetRegistry.solanaUsdc)
        assertEquals(BigInteger("12340000"), normalized.baseUnits)
        assertEquals("12.34", normalized.displayAmount)
    }

    @Test fun `amount rejects exponent sign whitespace zero leading zeros and excess precision`() {
        for (invalid in listOf("1e3", "-1", "+1", " 1", "1 ", "0", "0.000000", "01", "1.")) {
            val failure = assertThrows(WalletTransferValidationException::class.java) {
                TransferAmount.parse(invalid, WalletAssetRegistry.solanaUsdc)
            }
            assertEquals(WalletTransferErrorCode.INVALID_AMOUNT, failure.code)
            assertFalse(failure.userMessage.contains(invalid))
        }
        val precision = assertThrows(WalletTransferValidationException::class.java) {
            TransferAmount.parse("0.0000001", WalletAssetRegistry.solanaUsdc)
        }
        assertEquals(WalletTransferErrorCode.EXCESS_PRECISION, precision.code)
    }

    @Test fun `Solana u64 boundary is exact and one base unit above overflows`() {
        assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
            TransferAmount.parse("18446744073.709551615", WalletAssetRegistry.solanaSol).baseUnits)
        val overflow = assertThrows(WalletTransferValidationException::class.java) {
            TransferAmount.parse("18446744073.709551616", WalletAssetRegistry.solanaSol)
        }
        assertEquals(WalletTransferErrorCode.AMOUNT_TOO_LARGE, overflow.code)
    }

    @Test fun `draft binds immutable review to user wallet network asset recipient and operation`() {
        val sender = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
        val recipient = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR"
        val session = TransferSessionIdentity.create("privy-user-1", "privy-wallet-1",
            WalletNetworkId.SOLANA_MAINNET, sender)
        val draft = TransferDraft.create(session, WalletAssetId.SOLANA_USDC, recipient, "20.250000", 42L)
        val first = draft.review()
        val second = draft.review()

        assertEquals(first.operationId, second.operationId)
        assertEquals("privy-user-1", first.userId)
        assertEquals("privy-wallet-1", first.walletId)
        assertEquals(sender, first.sender.value)
        assertEquals(recipient, first.recipient.value)
        assertEquals(WalletAssetId.SOLANA_USDC, first.assetId)
        assertEquals("USDC", first.assetSymbol)
        assertEquals(WalletNetworkId.SOLANA_MAINNET, first.networkId)
        assertEquals(WalletAssetRegistry.solanaMainnet.caip2, first.networkCaip2)
        assertEquals(WalletAssetRegistry.solanaUsdc.contractOrMint, first.contractOrMint)
        assertEquals("20.25", first.displayAmount)
        assertEquals("20250000", first.baseUnits)
        assertEquals(6, first.decimals)
        assertEquals(42L, first.createdAtEpochMillis)
        assertTrue(TransferDraft::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .all { Modifier.isFinal(it.modifiers) })
        assertTrue(TransferReviewSnapshot::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            .all { Modifier.isFinal(it.modifiers) })
    }

    @Test fun `each draft receives a new one-shot operation identity`() {
        val session = TransferSessionIdentity.create("user", "wallet", WalletNetworkId.SOLANA_MAINNET,
            "So11111111111111111111111111111111111111112")
        val first = TransferDraft.create(session, WalletAssetId.SOLANA_USDC,
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "1")
        val second = TransferDraft.create(session, WalletAssetId.SOLANA_USDC,
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "1")
        assertNotEquals(first.operationId, second.operationId)
    }

    @Test fun `draft rejects transfers back to the sending wallet after canonicalization`() {
        val solanaAddress = "So11111111111111111111111111111111111111112"
        val solana = TransferSessionIdentity.create("user", "wallet", WalletNetworkId.SOLANA_MAINNET,
            solanaAddress)
        val solanaFailure = assertThrows(WalletTransferValidationException::class.java) {
            TransferDraft.create(solana, WalletAssetId.SOLANA_SOL, solanaAddress, "1")
        }
        assertEquals(WalletTransferErrorCode.SAME_SENDER_AND_RECIPIENT, solanaFailure.code)
        assertFalse(solanaFailure.userMessage.contains(solanaAddress))
    }

    @Test fun `session identity rejects blanks whitespace controls and wrong-chain wallet`() {
        for (id in listOf("", "two words", "line\nbreak")) {
            val failure = assertThrows(WalletTransferValidationException::class.java) {
                TransferSessionIdentity.create(id, "wallet", WalletNetworkId.SOLANA_MAINNET,
                    "So11111111111111111111111111111111111111112")
            }
            assertEquals(WalletTransferErrorCode.INVALID_SESSION, failure.code)
            if (id.isNotEmpty()) assertFalse(failure.userMessage.contains(id))
        }
        val wallet = assertThrows(WalletTransferValidationException::class.java) {
            TransferSessionIdentity.create("user", "wallet", WalletNetworkId.SOLANA_MAINNET,
                "0xde709f2102306220921060314715629080e2fb77")
        }
        assertEquals(WalletTransferErrorCode.INVALID_WALLET, wallet.code)
    }
}
