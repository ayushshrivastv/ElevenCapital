package com.elevencapital.app.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchUiPreviewTest {
    @Test fun `local Community token hold lasts exactly four seconds`() {
        assertEquals(4_000L, PREVIEW_ROW_HOLD_MILLIS)
    }

    @Test fun `only the requested ticker enters the debug launch rehearsal`() {
        assertTrue(isTokenLaunchUiPreview("CHTMS"))
        assertTrue(isTokenLaunchUiPreview(" chtms "))
        assertFalse(isTokenLaunchUiPreview("SPXTR"))
        assertFalse(isTokenLaunchUiPreview("SPXRT"))
        assertFalse(isTokenLaunchUiPreview("CHTMS2"))
        assertFalse(isTokenLaunchUiPreview("RACE"))
    }

    @Test fun `rehearsal result renders through the production QR contract`() {
        val result = tokenLaunchUiPreviewResult()
        assertEquals(result.mintAddress, parseSolanaTokenQrPayload(result.mintAddress))
        assertEquals(result.mintAddress, parseSolanaTokenQrPayload(TOKEN_LAUNCH_UI_PREVIEW_LINK))
        assertNotNull(mainnetSolscanTokenUrl(result.mintAddress))
        assertTrue(result.poolAddress.isNotBlank())
        assertTrue(result.signature.isNotBlank())
    }

    @Test fun `confirmation presentation contains only the ticker and token link`() {
        val draft = LaunchTokenDraft(name = "Christmas", symbol = "CHTMS")
        val result = tokenLaunchUiPreviewResult()

        assertEquals(
            TokenLaunchConfirmationPresentation(
                ticker = "\$CHTMS",
                tokenLink = TOKEN_LAUNCH_UI_PREVIEW_LINK,
            ),
            tokenLaunchConfirmationPresentation(draft, result),
        )
    }
}
