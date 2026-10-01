package com.elevencapital.app.tokenlaunch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.screens.ConfirmedTokenLaunch
import com.elevencapital.app.screens.CommunityTokenOrigin
import com.elevencapital.app.screens.LaunchTokenDraft
import com.elevencapital.app.screens.LaunchTokenExecutionPhase
import com.elevencapital.app.screens.LaunchTokenExecutionState
import com.elevencapital.app.screens.isTokenLaunchUiPreview
import com.elevencapital.app.screens.tokenLaunchUiPreviewResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Local launch presentation; real execution lives in the opt-in controller source. */
internal class TokenLaunchUiController(
    restored: ConfirmedTokenLaunchReceipt?,
    restoredPreview: StoredTokenLaunchUiPreview? = null,
    restoredConfirmedDraft: LaunchTokenDraft? = null,
    private val savePreview: (LaunchTokenDraft, String) -> Boolean = { _, _ -> false },
    @Suppress("unused") private val saveConfirmedDisplay: (LaunchTokenDraft, String) -> Boolean = { _, _ -> false },
    private val removePreview: () -> Boolean = { false },
    private val waitForPreview: suspend (Long) -> Unit = { delay(it) },
    private val enableLocalPreview: Boolean = BuildConfig.DEBUG,
    @Suppress("unused") private val enableRealLaunch: Boolean = false,
) {
    private val confirmedMint = restored?.mintAddress
    private val confirmedDraft = restoredConfirmedDraft
    private val initialPreview = restoredPreview?.takeIf {
        enableLocalPreview && isCanonicalTokenLaunchUiPreview(it)
    }
    var attemptNonce by mutableStateOf(0)
        private set
    var executionState by mutableStateOf(LaunchTokenExecutionState())
        private set
    var activeDraft by mutableStateOf<LaunchTokenDraft?>(null)
        private set
    var communityDraft by mutableStateOf(initialPreview?.toDraft() ?: restoredConfirmedDraft)
        private set
    var communityMint by mutableStateOf(initialPreview?.mintAddress ?: restored?.mintAddress)
        private set
    var communityMintConfirmed by mutableStateOf(restored != null || initialPreview != null)
        private set
    var communityOrigin by mutableStateOf(
        when {
            initialPreview != null -> CommunityTokenOrigin.LOCAL_UI
            restored != null -> CommunityTokenOrigin.CONFIRMED_LAUNCH
            else -> null
        },
    )
        private set

    private var activeOperationId: String? = null
    private var job: Job? = null

    fun open() {
        val resumable = activeDraft != null && executionState.phase in setOf(
            LaunchTokenExecutionPhase.CONFIRMING,
            LaunchTokenExecutionPhase.FAILED,
        )
        if (!resumable) reset()
    }

    fun reset() {
        job?.cancel()
        job = null
        activeDraft = null
        activeOperationId = null
        executionState = LaunchTokenExecutionState()
        attemptNonce++
    }

    fun edit() {
        executionState = LaunchTokenExecutionState()
    }

    fun linkScannedMint(mint: String) {
        removePreview()
        communityDraft = null
        communityMint = mint
        communityMintConfirmed = false
        communityOrigin = CommunityTokenOrigin.LINKED_SCAN
    }

    fun updateLocalCommunityImage(imageUri: String): Boolean {
        if (communityOrigin != CommunityTokenOrigin.LOCAL_UI ||
            !isCanonicalTokenLaunchUiPreviewImageUri(imageUri)) return false
        val currentDraft = communityDraft ?: return false
        val mint = communityMint ?: return false
        if (mint != tokenLaunchUiPreviewResult().mintAddress) return false
        val updatedDraft = currentDraft.copy(imageUri = imageUri)
        if (!savePreview(updatedDraft, mint)) return false
        communityDraft = updatedDraft
        return true
    }

    fun removeLocalCommunityToken(): Boolean {
        if (communityOrigin != CommunityTokenOrigin.LOCAL_UI) return false
        if (!removePreview()) return false
        communityDraft = confirmedDraft
        communityMint = confirmedMint
        communityMintConfirmed = confirmedMint != null
        communityOrigin = confirmedMint?.let { CommunityTokenOrigin.CONFIRMED_LAUNCH }
        return true
    }

    /** Clears an unsaved QR link without changing a confirmed launch receipt. */
    fun clearLinkedCommunityToken(): Boolean {
        if (communityOrigin != CommunityTokenOrigin.LINKED_SCAN) return false
        communityDraft = confirmedDraft
        communityMint = confirmedMint
        communityMintConfirmed = confirmedMint != null
        communityOrigin = confirmedMint?.let { CommunityTokenOrigin.CONFIRMED_LAUNCH }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    // Preserve ElevenApp's call shape until its route extraction is complete. These callbacks
    // are never invoked in this build; the real controller has the executable implementation.
    @Suppress("UNUSED_PARAMETER")
    fun start(
        scope: CoroutineScope,
        draft: LaunchTokenDraft,
        userId: String?,
        wallet: UserWallet?,
        currentUserId: () -> String?,
        uploadMetadata: suspend (String, LaunchTokenDraft) -> String,
        prepare: suspend (TokenLaunchRequest) -> PreparedTokenLaunch,
        execute: suspend (PreparedTokenLaunch, (TokenLaunchProgress) -> Unit) -> ConfirmedTokenLaunchReceipt,
    ) {
        job?.cancel()
        val operationId = UUID.randomUUID().toString()
        activeDraft = draft
        activeOperationId = operationId
        executionState = LaunchTokenExecutionState(
            phase = LaunchTokenExecutionPhase.PREPARING,
            message = "Saving token artwork.",
        )
        job = scope.launch {
            try {
                if (enableLocalPreview && isTokenLaunchUiPreview(draft.symbol)) {
                    runPreview(operationId, draft)
                    return@launch
                }
                throw TokenLaunchUiException("Token launching isn't available yet.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (activeOperationId == operationId) {
                    executionState = LaunchTokenExecutionState(
                        phase = LaunchTokenExecutionPhase.FAILED,
                        message = if (failure is TokenLaunchUiException) failure.safeMessage
                            else "The token launch service is unavailable. Nothing was submitted.",
                    )
                }
            }
        }
    }

    private suspend fun runPreview(operationId: String, draft: LaunchTokenDraft) {
        waitForPreview(700)
        currentCoroutineContext().ensureActive()
        advance(operationId, LaunchTokenExecutionPhase.SIGNING)
        waitForPreview(900)
        currentCoroutineContext().ensureActive()
        advance(operationId, LaunchTokenExecutionPhase.CONFIRMING)
        waitForPreview(1_600)
        currentCoroutineContext().ensureActive()
        if (activeOperationId == operationId) {
            finish(draft, tokenLaunchUiPreviewResult())
        }
    }

    private fun advance(operationId: String, next: LaunchTokenExecutionPhase) {
        if (activeOperationId == operationId && executionState.phase.ordinal < next.ordinal) {
            executionState = LaunchTokenExecutionState(next)
        }
    }

    private fun finish(draft: LaunchTokenDraft, confirmed: ConfirmedTokenLaunch) {
        savePreview(draft, confirmed.mintAddress)
        communityDraft = draft
        communityMint = confirmed.mintAddress
        communityMintConfirmed = true
        communityOrigin = CommunityTokenOrigin.LOCAL_UI
        executionState = LaunchTokenExecutionState(
            phase = LaunchTokenExecutionPhase.CONFIRMED,
            confirmed = confirmed,
        )
    }

    private class TokenLaunchUiException(val safeMessage: String) : Exception(safeMessage)
}
