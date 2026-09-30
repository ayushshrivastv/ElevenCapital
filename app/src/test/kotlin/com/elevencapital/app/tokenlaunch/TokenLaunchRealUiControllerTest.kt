package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.screens.LaunchTokenExecutionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only compiled with -PincludeRealTokenExecution=true. */
class TokenLaunchRealUiControllerTest {
    @Test fun `SPXTR in the production path uploads metadata before preparing with the returned URI`() {
        val controller = TokenLaunchUiController(restored = null, waitForPreview = {},
            enableLocalPreview = false, enableRealLaunch = true)
        val userId = "did:privy:test-user"
        val wallet = UserWallet(
            chain = WalletChain.SOLANA,
            address = "Vote111111111111111111111111111111111111111",
            walletId = "wallet-id",
        )
        var uploaded = false
        var preparedRequest: TokenLaunchRequest? = null

        controller.start(
            scope = CoroutineScope(Dispatchers.Unconfined),
            draft = LaunchTokenDraft(
                imageUri = "content://token/race",
                name = "SPXTR",
                symbol = "SPXTR",
                description = "A community token for racing fans.",
            ),
            userId = userId,
            wallet = wallet,
            currentUserId = { userId },
            uploadMetadata = { requestedUser, _ ->
                assertEquals(userId, requestedUser)
                uploaded = true
                "https://metadata.example/race.json"
            },
            prepare = { request ->
                assertTrue(uploaded)
                preparedRequest = request
                throw TokenLaunchException(
                    TokenLaunchFailureCode.SERVICE_UNAVAILABLE,
                    "The token launch service is unavailable. Nothing was submitted.",
                    retryable = true,
                )
            },
            execute = { _, _ -> error("Failed preparation must not execute") },
        )

        assertEquals("https://metadata.example/race.json", preparedRequest?.metadataUri)
        assertEquals("1000000000", preparedRequest?.totalTokenSupply)
        assertEquals(100, preparedRequest?.feeBps)
        assertEquals("10", preparedRequest?.migrationThresholdSol)
        assertEquals(20, preparedRequest?.percentageSupplyOnMigration)
        assertEquals(LaunchTokenExecutionPhase.FAILED, controller.executionState.phase)
    }
}
