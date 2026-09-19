package com.elevencapital.app.wallet

import java.math.BigInteger
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletTransferDomainTest {
    @Test fun `registry pins canonical mainnet identities and precision`() {
        assertEquals(listOf(WalletNetworkId.ETHEREUM_MAINNET, WalletNetworkId.SOLANA_MAINNET),
            WalletAssetRegistry.networks.map(WalletNetwork::id))
        assertEquals("eip155:1", WalletAssetRegistry.ethereumMainnet.caip2)
        assertEquals(1L, WalletAssetRegistry.ethereumMainnet.eip155ChainId)
        assertEquals("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp", WalletAssetRegistry.solanaMainnet.caip2)
        assertNull(WalletAssetRegistry.solanaMainnet.eip155ChainId)

        assertEquals(18, WalletAssetRegistry.ethereumEth.decimals)
        assertEquals(9, WalletAssetRegistry.solanaSol.decimals)
        assertEquals("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            WalletAssetRegistry.ethereumUsdc.contractOrMint)
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            WalletAssetRegistry.solanaUsdc.contractOrMint)
        assertEquals(6, WalletAssetRegistry.ethereumUsdc.decimals)
        assertEquals(6, WalletAssetRegistry.solanaUsdc.decimals)
        assertEquals(256, WalletAssetRegistry.ethereumUsdc.baseUnitBits)
        assertEquals(64, WalletAssetRegistry.solanaUsdc.baseUnitBits)
        assertEquals(WalletAssetRegistry.ethereumUsdc.contractOrMint,
            ValidatedWalletAddress.wallet(WalletNetworkId.ETHEREUM_MAINNET,
                requireNotNull(WalletAssetRegistry.ethereumUsdc.contractOrMint)).value)
        assertEquals(WalletAssetRegistry.solanaUsdc.contractOrMint,
            ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET,
                requireNotNull(WalletAssetRegistry.solanaUsdc.contractOrMint)).value)
        WalletAssetId.entries.forEach { assertSame(WalletAssetRegistry.asset(it), WalletAssetRegistry.asset(it)) }
    }

    @Test fun `registry collections cannot be changed by a caller`() {
        @Suppress("UNCHECKED_CAST")
        val assets = WalletAssetRegistry.assets as MutableList<WalletAsset>
        assertThrows(UnsupportedOperationException::class.java) { assets.clear() }
        assertEquals(4, WalletAssetRegistry.assets.size)
    }

    @Test fun `amount conversion is exact for native and token decimals`() {
        val eth = TransferAmount.parse("1.000000000000000001", WalletAssetRegistry.ethereumEth)
        assertEquals(BigInteger("1000000000000000001"), eth.baseUnits)
        assertEquals("1.000000000000000001", eth.displayAmount)

        val usdc = TransferAmount.parse("0.000001", WalletAssetRegistry.ethereumUsdc)
        assertEquals(BigInteger.ONE, usdc.baseUnits)
        assertEquals("0.000001", usdc.displayAmount)

        val normalized = TransferAmount.parse("12.340000", WalletAssetRegistry.solanaUsdc)
        assertEquals(BigInteger("12340000"), normalized.baseUnits)
        assertEquals("12.34", normalized.displayAmount)
    }

    @Test fun `amount rejects exponent sign whitespace zero leading zeros and excess precision`() {
        for (invalid in listOf("1e3", "-1", "+1", " 1", "1 ", "0", "0.000000", "01", "1.")) {
            val failure = assertThrows(WalletTransferValidationException::class.java) {
                TransferAmount.parse(invalid, WalletAssetRegistry.ethereumUsdc)
            }
            assertEquals(WalletTransferErrorCode.INVALID_AMOUNT, failure.code)
            assertFalse(failure.userMessage.contains(invalid))
        }
        val precision = assertThrows(WalletTransferValidationException::class.java) {
            TransferAmount.parse("0.0000001", WalletAssetRegistry.ethereumUsdc)
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

    @Test fun `Ethereum uint256 boundary is exact and one base unit above overflows`() {
        val maximum = "115792089237316195423570985008687907853269984665640564039457.584007913129639935"
        assertEquals(BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE),
            TransferAmount.parse(maximum, WalletAssetRegistry.ethereumEth).baseUnits)
        val overflow = assertThrows(WalletTransferValidationException::class.java) {
            TransferAmount.parse(
                "115792089237316195423570985008687907853269984665640564039457.584007913129639936",
                WalletAssetRegistry.ethereumEth,
            )
        }
        assertEquals(WalletTransferErrorCode.AMOUNT_TOO_LARGE, overflow.code)
    }

    @Test fun `draft binds immutable review to user wallet network asset recipient and operation`() {
        val sender = "0x52908400098527886E0F7030069857D2E4169EE7"
        val recipient = "0xde709f2102306220921060314715629080e2fb77"
        val session = TransferSessionIdentity.create("privy-user-1", "privy-wallet-1",
            WalletNetworkId.ETHEREUM_MAINNET, sender)
        val draft = TransferDraft.create(session, WalletAssetId.ETHEREUM_USDC, recipient, "20.250000", 42L)
        val first = draft.review()
        val second = draft.review()

        assertEquals(first.operationId, second.operationId)
        assertEquals("privy-user-1", first.userId)
        assertEquals("privy-wallet-1", first.walletId)
        assertEquals(sender, first.sender.value)
        assertEquals(recipient, first.recipient.value)
        assertEquals(WalletAssetId.ETHEREUM_USDC, first.assetId)
        assertEquals("USDC", first.assetSymbol)
        assertEquals(WalletNetworkId.ETHEREUM_MAINNET, first.networkId)
        assertEquals("eip155:1", first.networkCaip2)
        assertEquals(WalletAssetRegistry.ethereumUsdc.contractOrMint, first.contractOrMint)
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

    @Test fun `draft rejects wallet and asset network mismatch before review`() {
        val ethereum = TransferSessionIdentity.create("user", "wallet", WalletNetworkId.ETHEREUM_MAINNET,
            "0xde709f2102306220921060314715629080e2fb77")
        val failure = assertThrows(WalletTransferValidationException::class.java) {
            TransferDraft.create(ethereum, WalletAssetId.SOLANA_SOL,
                "So11111111111111111111111111111111111111112", "1")
        }
        assertEquals(WalletTransferErrorCode.WRONG_NETWORK, failure.code)
    }

    @Test fun `draft rejects transfers back to the sending wallet after canonicalization`() {
        val ethereum = TransferSessionIdentity.create("user", "wallet", WalletNetworkId.ETHEREUM_MAINNET,
            "0x52908400098527886E0F7030069857D2E4169EE7")
        val ethereumFailure = assertThrows(WalletTransferValidationException::class.java) {
            TransferDraft.create(ethereum, WalletAssetId.ETHEREUM_ETH,
                "0x52908400098527886e0f7030069857d2e4169ee7", "1")
        }
        assertEquals(WalletTransferErrorCode.SAME_SENDER_AND_RECIPIENT, ethereumFailure.code)
        assertFalse(ethereumFailure.userMessage.contains(ethereum.walletAddress.value))

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
                TransferSessionIdentity.create(id, "wallet", WalletNetworkId.ETHEREUM_MAINNET,
                    "0xde709f2102306220921060314715629080e2fb77")
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
