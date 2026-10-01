package com.elevencapital.app.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchExplorerLinkTest {
    @Test fun `local Christmas launch opens backend token page without a Solscan mint claim`() {
        val destination = tokenLaunchExplorerDestination(
            LaunchTokenDraft(name = "Christmas", symbol = "CHTMS"),
            tokenLaunchUiPreviewResult(),
            "http://127.0.0.1:8787",
        )!!

        assertEquals("http://127.0.0.1:8787/explorer", destination.url)
        assertEquals("View token page", destination.contentDescription)
        assertFalse(destination.isSolscan)
    }

    @Test fun `confirmed mainnet launch opens its Solscan transaction page`() {
        val signature = tokenLaunchUiPreviewResult().signature
        val destination = tokenLaunchExplorerDestination(
            LaunchTokenDraft(name = "Example", symbol = "EXAMPLE"),
            ConfirmedTokenLaunch(
                mintAddress = "So11111111111111111111111111111111111111112",
                signature = signature,
            ),
            "http://127.0.0.1:8787",
        )!!

        assertEquals("https://solscan.io/tx/$signature", destination.url)
        assertEquals("Check Transaction on Solscan", destination.contentDescription)
        assertTrue(destination.isSolscan)
    }

    @Test fun `a launch without a canonical transaction has no Solscan action`() {
        assertNull(tokenLaunchExplorerDestination(
            LaunchTokenDraft(name = "Example", symbol = "EXAMPLE"),
            ConfirmedTokenLaunch(
                mintAddress = "So11111111111111111111111111111111111111112",
                signature = "not-a-signature",
            ),
            "http://127.0.0.1:8787",
        ))
    }

    @Test fun `preview explorer URL accepts only a configured backend origin`() {
        assertEquals("https://eleven.example/explorer", backendExplorerPageUrl("https://eleven.example/"))
        assertNull(backendExplorerPageUrl("http://other.example:8787"))
        assertNull(backendExplorerPageUrl("http://127.0.0.1:8787@other.example"))
        assertNull(backendExplorerPageUrl("http://127.0.0.1:8787/?target=other"))
        assertNull(backendExplorerPageUrl("http://127.0.0.1:8787/other"))
        assertNull(backendExplorerPageUrl(""))
    }

    @Test fun `unconfirmed launch has no explorer action`() {
        assertNull(tokenLaunchExplorerDestination(
            LaunchTokenDraft(symbol = "CHTMS"),
            null,
            "http://127.0.0.1:8787",
        ))
    }
}
