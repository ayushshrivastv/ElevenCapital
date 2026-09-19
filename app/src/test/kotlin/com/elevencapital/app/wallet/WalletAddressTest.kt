package com.elevencapital.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAddressTest {
    @Test fun `known EIP-55 and uniform-case addresses validate and canonicalize`() {
        val checksummed = "0x52908400098527886E0F7030069857D2E4169EE7"
        assertEquals(checksummed,
            ValidatedWalletAddress.recipient(WalletNetworkId.ETHEREUM_MAINNET, checksummed).value)
        assertEquals("0xde709f2102306220921060314715629080e2fb77",
            ValidatedWalletAddress.recipient(WalletNetworkId.ETHEREUM_MAINNET,
                "0xde709f2102306220921060314715629080e2fb77").value)
        assertEquals(checksummed,
            ValidatedWalletAddress.recipient(WalletNetworkId.ETHEREUM_MAINNET,
                checksummed.lowercase()).value)
    }

    @Test fun `mixed-case Ethereum address requires its exact EIP-55 checksum`() {
        val invalid = "0x52908400098527886e0F7030069857D2E4169EE7"
        val failure = assertThrows(WalletTransferValidationException::class.java) {
            ValidatedWalletAddress.recipient(WalletNetworkId.ETHEREUM_MAINNET, invalid)
        }
        assertEquals(WalletTransferErrorCode.INVALID_RECIPIENT, failure.code)
        assertFalse(failure.userMessage.contains(invalid))
    }

    @Test fun `Ethereum blocked recipients malformed prefix length and wrong-chain formats are rejected`() {
        val invalid = listOf(
            ETHEREUM_ZERO_ADDRESS,
            ETHEREUM_DEAD_ADDRESS,
            ETHEREUM_DEAD_ADDRESS.lowercase(),
            ETHEREUM_DEAD_ADDRESS.uppercase().replace("0X", "0x"),
            "0X52908400098527886E0F7030069857D2E4169EE7",
            "0x52908400098527886E0F7030069857D2E4169EE",
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        )
        invalid.forEach { address ->
            assertThrows(WalletTransferValidationException::class.java) {
                ValidatedWalletAddress.recipient(WalletNetworkId.ETHEREUM_MAINNET, address)
            }
        }
    }

    @Test fun `connected Ethereum wallet validation remains format-only`() {
        for (address in listOf(ETHEREUM_ZERO_ADDRESS, ETHEREUM_DEAD_ADDRESS)) {
            assertEquals(canonicalEthereumAddress(address),
                ValidatedWalletAddress.wallet(WalletNetworkId.ETHEREUM_MAINNET, address).value)
        }
    }

    @Test fun `Solana validation decodes exactly 32 bytes and canonically re-encodes`() {
        val addresses = listOf(
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "So11111111111111111111111111111111111111112",
        )
        addresses.forEach { address ->
            assertTrue(isSolanaPublicKey(address))
            assertEquals(address, canonicalSolanaPublicKey(address))
            assertEquals(address,
                ValidatedWalletAddress.recipient(WalletNetworkId.SOLANA_MAINNET, address).value)
        }
        assertEquals("11111111111111111111111111111111", encodeBase58(ByteArray(32)))
    }

    @Test fun `Solana program and burn addresses are blocked only as recipients`() {
        assertEquals(5, SOLANA_BLOCKED_RECIPIENTS.size)
        SOLANA_BLOCKED_RECIPIENTS.forEach { address ->
            assertTrue(isSolanaPublicKey(address))
            assertEquals(address, canonicalSolanaPublicKey(address))
            assertEquals(address,
                ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, address).value)
            val failure = assertThrows(WalletTransferValidationException::class.java) {
                ValidatedWalletAddress.recipient(WalletNetworkId.SOLANA_MAINNET, address)
            }
            assertEquals(WalletTransferErrorCode.INVALID_RECIPIENT, failure.code)
            assertFalse(failure.userMessage.contains(address))
        }
    }

    @Test fun `Solana rejects invalid alphabet wrong byte length and Ethereum format`() {
        val invalid = listOf(
            "1111111111111111111111111111111",
            "111111111111111111111111111111111",
            "0PjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            "z".repeat(44),
            "0x52908400098527886E0F7030069857D2E4169EE7",
        )
        invalid.forEach { address ->
            assertFalse(isSolanaPublicKey(address))
            assertThrows(WalletTransferValidationException::class.java) {
                ValidatedWalletAddress.recipient(WalletNetworkId.SOLANA_MAINNET, address)
            }
        }
    }

    @Test fun `connected-wallet failures use a distinct safe reason`() {
        val privateInput = "not-a-wallet-private-value"
        val failure = assertThrows(WalletTransferValidationException::class.java) {
            ValidatedWalletAddress.wallet(WalletNetworkId.SOLANA_MAINNET, privateInput)
        }
        assertEquals(WalletTransferErrorCode.INVALID_WALLET, failure.code)
        assertFalse(failure.message.orEmpty().contains(privateInput))
    }

    @Test fun `Solana transaction signatures require canonical nonzero 64 byte base58`() {
        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        assertTrue(isCanonicalSolanaSignature(signature))
        assertFalse(isCanonicalSolanaSignature("1".repeat(64)))
        assertFalse(isCanonicalSolanaSignature(signature.dropLast(1)))
        assertFalse(isCanonicalSolanaSignature("0${signature.drop(1)}"))
    }
}
