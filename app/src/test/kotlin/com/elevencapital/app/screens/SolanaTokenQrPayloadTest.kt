package com.elevencapital.app.screens

import com.elevencapital.app.wallet.encodeBase58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolanaTokenQrPayloadTest {
    private val mint = encodeBase58(ByteArray(32) { (it + 1).toByte() })

    @Test fun `accepts canonical Solana token address payloads`() {
        val accepted = listOf(
            mint,
            "  $mint\n",
            "solana:$mint",
            "SOLANA:$mint",
            "https://explorer.solana.com/address/$mint",
            "https://solscan.io/token/$mint",
        )

        accepted.forEach { payload ->
            assertEquals(payload, mint, parseSolanaTokenQrPayload(payload))
        }
    }

    @Test fun `current and earlier Christmas QR links resolve to the same local token`() {
        assertEquals(tokenLaunchUiPreviewResult().mintAddress,
            parseSolanaTokenQrPayload(TOKEN_LAUNCH_UI_PREVIEW_LINK))
        assertEquals("eleven://token/chtms", TOKEN_LAUNCH_UI_PREVIEW_LINK)
        assertEquals(tokenLaunchUiPreviewResult().mintAddress,
            parseSolanaTokenQrPayload("eleven://token/spxtr"))
        // Existing installed builds and previously shared QR images encode the synthetic mint
        // directly. Both representations must enter the same local Christmas flow.
        assertEquals(tokenLaunchUiPreviewResult().mintAddress,
            parseSolanaTokenQrPayload(tokenLaunchUiPreviewResult().mintAddress))
        assertNull(parseSolanaTokenQrPayload("eleven://token/other"))
        assertNull(parseSolanaTokenQrPayload("eleven://token/spxtr?amount=1"))
        assertNull(parseSolanaTokenQrPayload("eleven://token/chtms?amount=1"))
    }

    @Test fun `rejects invalid base58 and addresses with the wrong decoded length`() {
        val wrongLength = encodeBase58(ByteArray(31) { (it + 1).toByte() })
        val invalid = listOf(
            "",
            "not-a-solana-address",
            wrongLength,
            "0${mint.drop(1)}",
            "solana:$wrongLength",
            "solana:$mint?amount=1",
            "solana://$mint",
        )

        invalid.forEach { payload ->
            assertNull(payload, parseSolanaTokenQrPayload(payload))
        }
        assertNull(parseSolanaTokenQrPayload(null))
    }

    @Test fun `rejects insecure lookalike and executable URLs`() {
        val rejected = listOf(
            "http://solscan.io/token/$mint",
            "http://explorer.solana.com/address/$mint",
            "https://solscan.io.evil.test/token/$mint",
            "https://explorer.solana.com.evil.test/address/$mint",
            "https://solscan.io@evil.test/token/$mint",
            "https://explorer.solana.com@evil.test/address/$mint",
            "javascript:alert(1)",
            "data:text/plain,$mint",
        )

        rejected.forEach { payload ->
            assertNull(payload, parseSolanaTokenQrPayload(payload))
        }
    }

    @Test fun `rejects URLs outside the exact supported host and path`() {
        val rejected = listOf(
            "https://www.solscan.io/token/$mint",
            "https://solscan.io/account/$mint",
            "https://solscan.io/token/$mint/extra",
            "https://solscan.io/token/$mint?cluster=mainnet",
            "https://solscan.io/token/$mint?cluster=devnet",
            "https://solscan.io/token/$mint?cluster=devnet&amount=1",
            "https://explorer.solana.com/address/$mint?cluster=devnet",
            "https://solscan.io/token/$mint#details",
            "https://solscan.io:443/token/$mint",
            "https://explorer.solana.com/token/$mint",
            "https://explorer.solana.com/address/$mint/extra",
            "https://example.com/address/$mint",
        )

        rejected.forEach { payload ->
            assertNull(payload, parseSolanaTokenQrPayload(payload))
        }
    }
}
