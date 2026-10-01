package com.elevencapital.app.tokenlaunch

import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TokenLaunchArtworkStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val packageName = "com.elevencapital.app"
    private val fileName = "${"a".repeat(64)}.jpg"
    private val artworkUri = "content://$packageName.token-artwork/token_artwork/$fileName"

    @Test fun `accepts only the app owned artwork path`() {
        assertEquals(fileName, TokenLaunchArtworkStore.ownedArtworkFileName(artworkUri, packageName))
        assertNull(TokenLaunchArtworkStore.ownedArtworkFileName(
            "content://other.app.token-artwork/token_artwork/$fileName", packageName))
        assertNull(TokenLaunchArtworkStore.ownedArtworkFileName(
            "content://$packageName.token-artwork/other/$fileName", packageName))
        assertNull(TokenLaunchArtworkStore.ownedArtworkFileName("$artworkUri?file=other", packageName))
        assertNull(TokenLaunchArtworkStore.ownedArtworkFileName(
            "content://$packageName.token-artwork/token_artwork/%2E%2E%2Fsecret.jpg", packageName))
    }
}
