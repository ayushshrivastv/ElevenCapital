package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.screens.CommunityTokenOrigin
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.screens.LaunchTokenExecutionPhase
import com.elevencapital.app.screens.tokenLaunchUiPreviewResult
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchUiControllerTest {
    @Test fun `CHTMS completes locally persists the preview and records its origin`() {
        val saved = mutableListOf<Pair<LaunchTokenDraft, String>>()
        val controller = TokenLaunchUiController(
            restored = null,
            savePreview = { draft, mint -> saved += draft to mint; true },
            waitForPreview = {},
            enableLocalPreview = true,
        )
        val draft = LaunchTokenDraft(name = "Christmas", symbol = "CHTMS")

        startPreview(controller, draft)

        assertEquals(LaunchTokenExecutionPhase.CONFIRMED, controller.executionState.phase)
        assertNotNull(controller.executionState.confirmed)
        assertEquals(listOf(draft to tokenLaunchUiPreviewResult().mintAddress), saved)
        assertEquals(draft, controller.communityDraft)
        assertTrue(controller.communityMintConfirmed)
        assertEquals(controller.executionState.confirmed?.mintAddress, controller.communityMint)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)
    }

    @Test fun `opening and resetting the launch flow retain the local Community token`() {
        val controller = TokenLaunchUiController(restored = null, waitForPreview = {}, enableLocalPreview = true)
        val draft = LaunchTokenDraft(name = "Christmas", symbol = "CHTMS")
        startPreview(controller, draft)

        controller.open()
        assertEquals(draft, controller.communityDraft)
        assertEquals(tokenLaunchUiPreviewResult().mintAddress, controller.communityMint)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)

        controller.reset()
        assertEquals(draft, controller.communityDraft)
        assertEquals(tokenLaunchUiPreviewResult().mintAddress, controller.communityMint)
        assertTrue(controller.communityMintConfirmed)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)
    }

    @Test fun `removing the local Community token clears it and removes its saved preview`() {
        var removeCalls = 0
        val controller = TokenLaunchUiController(
            restored = null,
            removePreview = { removeCalls++; true },
            waitForPreview = {},
            enableLocalPreview = true,
        )
        startPreview(controller, LaunchTokenDraft(name = "Christmas", symbol = "CHTMS"))

        assertTrue(controller.removeLocalCommunityToken())

        assertEquals(1, removeCalls)
        assertNull(controller.communityDraft)
        assertNull(controller.communityMint)
        assertFalse(controller.communityMintConfirmed)
        assertNull(controller.communityOrigin)
    }

    @Test fun `restored real and scanned Community tokens cannot be removed as local previews`() {
        var realRemoveCalls = 0
        val restored = confirmedReceipt()
        val realController = TokenLaunchUiController(
            restored = restored,
            removePreview = { realRemoveCalls++; true },
            waitForPreview = {},
        )

        assertFalse(realController.removeLocalCommunityToken())
        assertEquals(0, realRemoveCalls)
        assertEquals(restored.mintAddress, realController.communityMint)
        assertEquals(CommunityTokenOrigin.CONFIRMED_LAUNCH, realController.communityOrigin)

        var scannedRemoveCalls = 0
        val scannedController = TokenLaunchUiController(
            restored = null,
            removePreview = { scannedRemoveCalls++; true },
            waitForPreview = {},
        )
        val scannedMint = "Vote111111111111111111111111111111111111111"
        scannedController.linkScannedMint(scannedMint)
        val callsAfterLink = scannedRemoveCalls

        assertFalse(scannedController.removeLocalCommunityToken())
        assertEquals(callsAfterLink, scannedRemoveCalls)
        assertEquals(scannedMint, scannedController.communityMint)
        assertEquals(CommunityTokenOrigin.LINKED_SCAN, scannedController.communityOrigin)

        assertTrue(scannedController.clearLinkedCommunityToken())
        assertNull(scannedController.communityMint)
        assertNull(scannedController.communityOrigin)
        assertFalse(scannedController.clearLinkedCommunityToken())
        assertFalse(realController.clearLinkedCommunityToken())
        assertEquals(restored.mintAddress, realController.communityMint)
    }

    @Test fun `restored local preview populates Community without becoming a real receipt`() {
        val preview = StoredTokenLaunchUiPreview(
            name = "Christmas",
            symbol = "CHTMS",
            mintAddress = tokenLaunchUiPreviewResult().mintAddress,
        )

        val controller = TokenLaunchUiController(
            restored = null,
            restoredPreview = preview,
            waitForPreview = {},
            enableLocalPreview = true,
        )

        assertEquals(LaunchTokenDraft(name = preview.name, symbol = preview.symbol), controller.communityDraft)
        assertEquals(preview.mintAddress, controller.communityMint)
        assertTrue(controller.communityMintConfirmed)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)
    }

    @Test fun `latest local CHTMS remains visible above an older real receipt and removal restores it`() {
        val receipt = confirmedReceipt()
        val realDraft = LaunchTokenDraft(name = "Race", symbol = "RACE")
        val preview = StoredTokenLaunchUiPreview(
            name = "Christmas", symbol = "CHTMS",
            mintAddress = tokenLaunchUiPreviewResult().mintAddress,
        )
        val controller = TokenLaunchUiController(
            restored = receipt,
            restoredPreview = preview,
            restoredConfirmedDraft = realDraft,
            removePreview = { true },
            enableLocalPreview = true,
        )

        assertEquals(preview.mintAddress, controller.communityMint)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)
        assertTrue(controller.removeLocalCommunityToken())
        assertEquals(receipt.mintAddress, controller.communityMint)
        assertEquals(realDraft, controller.communityDraft)
        assertEquals(CommunityTokenOrigin.CONFIRMED_LAUNCH, controller.communityOrigin)
    }

    @Test fun `failed preview deletion leaves local token visible for retry`() {
        val preview = StoredTokenLaunchUiPreview(
            name = "Christmas", symbol = "CHTMS",
            mintAddress = tokenLaunchUiPreviewResult().mintAddress,
        )
        val controller = TokenLaunchUiController(
            restored = null,
            restoredPreview = preview,
            removePreview = { false },
            enableLocalPreview = true,
        )

        assertFalse(controller.removeLocalCommunityToken())
        assertEquals(preview.mintAddress, controller.communityMint)
        assertEquals(CommunityTokenOrigin.LOCAL_UI, controller.communityOrigin)
    }

    @Test fun `mainnet controller quarantines the fake CHTMS Community card`() {
        val preview = StoredTokenLaunchUiPreview(
            name = "Christmas",
            symbol = "CHTMS",
            mintAddress = tokenLaunchUiPreviewResult().mintAddress,
        )
        val controller = TokenLaunchUiController(restored = null, restoredPreview = preview,
            enableLocalPreview = false)
        assertNull(controller.communityMint)
        assertNull(controller.communityOrigin)
        assertFalse(controller.communityMintConfirmed)
    }

    @Test fun `local Community artwork is updated only after its preview is saved`() {
        val imageUri = "content://media/external/images/media/123"
        val original = LaunchTokenDraft(name = "Christmas", symbol = "CHTMS")
        val saved = mutableListOf<Pair<LaunchTokenDraft, String>>()
        val controller = TokenLaunchUiController(
            restored = null,
            restoredPreview = StoredTokenLaunchUiPreview(
                name = original.name,
                symbol = original.symbol,
                mintAddress = tokenLaunchUiPreviewResult().mintAddress,
            ),
            savePreview = { draft, mint -> saved += draft to mint; true },
            enableLocalPreview = true,
        )

        assertFalse(controller.updateLocalCommunityImage("https://example.com/image.jpg"))
        assertTrue(controller.updateLocalCommunityImage(imageUri))

        val updated = original.copy(imageUri = imageUri)
        assertEquals(updated, controller.communityDraft)
        assertEquals(listOf(updated to tokenLaunchUiPreviewResult().mintAddress), saved)
    }

    @Test fun `failed artwork save and nonlocal tokens do not change Community`() {
        val imageUri = "content://media/external/images/media/123"
        val preview = StoredTokenLaunchUiPreview(
            name = "Christmas",
            symbol = "CHTMS",
            mintAddress = tokenLaunchUiPreviewResult().mintAddress,
        )
        val local = TokenLaunchUiController(
            restored = null,
            restoredPreview = preview,
            savePreview = { _, _ -> false },
            enableLocalPreview = true,
        )
        val before = local.communityDraft

        assertFalse(local.updateLocalCommunityImage(imageUri))
        assertEquals(before, local.communityDraft)

        val real = TokenLaunchUiController(restored = confirmedReceipt())
        assertFalse(real.updateLocalCommunityImage(imageUri))
        assertNull(real.communityDraft)
    }

    @Test fun `confirmed receipt can restore its saved display draft`() {
        val receipt = confirmedReceipt()
        val draft = LaunchTokenDraft(
            imageUri = "https://example.com/token.jpg",
            name = "Race",
            symbol = "RACE",
        )
        val controller = TokenLaunchUiController(restored = receipt, restoredConfirmedDraft = draft)

        assertEquals(draft, controller.communityDraft)
        assertEquals(receipt.mintAddress, controller.communityMint)
        assertEquals(CommunityTokenOrigin.CONFIRMED_LAUNCH, controller.communityOrigin)
    }

    @Test fun `ordinary ticker cannot reach launch backend when real launches are disconnected`() {
        val controller = TokenLaunchUiController(restored = null, enableLocalPreview = false)

        controller.start(
            scope = CoroutineScope(Dispatchers.Unconfined),
            draft = LaunchTokenDraft(name = "Race", symbol = "RACE"),
            userId = null,
            wallet = null,
            currentUserId = { null },
            uploadMetadata = { _, _ -> error("Must not upload metadata") },
            prepare = { error("Must not prepare a transaction") },
            execute = { _, _ -> error("Must not sign or submit") },
        )

        assertEquals(LaunchTokenExecutionPhase.FAILED, controller.executionState.phase)
        assertEquals("Token launching isn't available yet.", controller.executionState.message)
    }

    private fun startPreview(controller: TokenLaunchUiController, draft: LaunchTokenDraft) {
        controller.start(
            scope = CoroutineScope(Dispatchers.Unconfined),
            draft = draft,
            userId = null,
            wallet = null,
            currentUserId = { null },
            uploadMetadata = { _, _ -> error("Preview must not upload metadata") },
            prepare = { error("Preview must not prepare a transaction") },
            execute = { _, _ -> error("Preview must not execute a transaction") },
        )
    }

    private fun confirmedReceipt(): ConfirmedTokenLaunchReceipt {
        val preview = tokenLaunchUiPreviewResult()
        return ConfirmedTokenLaunchReceipt(
            userId = "did:privy:test-user",
            operationId = "123e4567-e89b-42d3-a456-426614174000",
            walletAddress = preview.mintAddress,
            mintAddress = preview.mintAddress,
            configAddress = preview.mintAddress,
            poolAddress = preview.poolAddress,
            transactionId = preview.signature,
            explorerUrl = mainnetTokenExplorerUrl(preview.mintAddress),
            confirmedAt = Instant.parse("2026-10-07T10:00:00Z"),
            finalized = true,
        )
    }
}
