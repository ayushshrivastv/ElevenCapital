package com.elevencapital.app.tokenlaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchMetadataTest {
    @Test fun `artwork fit preserves the full image at any aspect ratio`() {
        assertEquals(TokenArtworkFit(1_024, 683, 1_024), fitTokenArtwork(3_000, 2_000))
        assertEquals(TokenArtworkFit(683, 1_024, 1_024), fitTokenArtwork(2_000, 3_000))
        assertEquals(TokenArtworkFit(1_024, 1_024, 1_024), fitTokenArtwork(1_024, 1_024))
        assertEquals(TokenArtworkFit(512, 384, 512), fitTokenArtwork(512, 384))
        assertEquals(TokenArtworkFit(1, 1_024, 1_024), fitTokenArtwork(1, 50_000))
    }

    @Test fun `metadata input accepts only safe public optional links`() {
        val valid = TokenLaunchMetadataInput(
            imageUri = "content://token/race",
            name = "Race",
            symbol = "RACE",
            description = "A community token for racing fans.",
            websiteUrl = "https://race.example/token",
            xUrl = "https://x.com/race_token",
            telegramUrl = "https://t.me/race_token",
        )
        assertEquals("content://token/race", valid.imageUri)

        listOf(
            "http://race.example",
            "https://127.0.0.1/token",
            "https://8.8.8.8/token",
            "https://[::ffff:7f00:1]/token",
            "https://user@race.example/token",
            " https://race.example/token",
        )
            .forEach { unsafe ->
                assertTrue(runCatching { valid.copy(websiteUrl = unsafe) }.isFailure)
            }
    }
}
