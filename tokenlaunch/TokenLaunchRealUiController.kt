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

/** Opt-in real launch orchestration; excluded from the installable UI-only APK. */
internal class TokenLaunchUiController(
    restored: ConfirmedTokenLaunchReceipt?,
    restoredPreview: StoredTokenLaunchUiPreview? = null,
    restoredConfirmedDraft: LaunchTokenDraft? = null,
    private val savePreview: (LaunchTokenDraft, String) -> Boolean = { _, _ -> false },
    private val saveConfirmedDisplay: (LaunchTokenDraft, String) -> Boolean = { _, _ -> false },
    private val removePreview: () -> Boolean = { false },
    private val waitForPreview: suspend (Long) -> Unit = { delay(it) },
    // SPXTR is a debug-only local UI rehearsal; release launches always use Mainnet.
    private val enableLocalPreview: Boolean = BuildConfig.DEBUG,
    // Keep the real launch implementation in source, but do not connect this APK to it.
    private val enableRealLaunch: Boolean = false,
) {
    private var confirmedMint = restored?.mintAddress
    private var confirmedDraft = restoredConfirmedDraft
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

    /** Updates the local rehearsal artwork only after it has been saved for process recreation. */
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

    /** Only the hidden local UI launch can be removed; real receipts and scanned tokens are safe. */
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
                if (!enableRealLaunch) {
                    throw TokenLaunchUiException("Token launching isn't available yet.")
                }
                // The retained on-chain request has no fields for these settings. Never
                // silently launch with a different curve, protection, or vesting plan.
                if (draft.curvePreset != "Classic" ||
                    draft.initialMarketCapSol.isNotBlank() || draft.graduationMarketCapSol.isNotBlank() ||
                    draft.launchProtectionEnabled || draft.vestCreatorTokens ||
                    draft.vestedSupplyPercent != "10" || draft.vestingDurationMonths != "12" ||
                    draft.vestingFrequency != "Monthly") {
                    throw TokenLaunchUiException("These curve settings aren't available for token launching yet.")
                }
                val verifiedUserId = userId
                    ?: throw TokenLaunchUiException("Sign in with Privy before launching a token.")
                val verifiedWallet = wallet
                    ?: throw TokenLaunchUiException("A verified Solana wallet is required to launch.")
                if (verifiedWallet.walletId.isBlank()) {
                    throw TokenLaunchUiException("The verified Privy wallet is unavailable. Sign in again.")
                }
                val metadataUri = uploadMetadata(verifiedUserId, draft)
                currentCoroutineContext().ensureActive()
                if (activeOperationId != operationId) return@launch
                executionState = LaunchTokenExecutionState(
                    phase = LaunchTokenExecutionPhase.PREPARING,
                    message = "Building your Solana transaction.",
                )
                val request = TokenLaunchRequest.create(
                    userId = verifiedUserId,
                    walletId = verifiedWallet.walletId,
                    walletAddress = verifiedWallet.address,
                    name = draft.name,
                    symbol = draft.symbol,
                    metadataUri = metadataUri,
                    totalTokenSupply = draft.totalSupply,
                    feeBps = draft.feeBps.toIntOrNull() ?: throw IllegalArgumentException(),
                    migrationThresholdSol = draft.migrationThresholdSol,
                    percentageSupplyOnMigration = draft.supplyOnMigrationPercent.toIntOrNull()
                        ?: throw IllegalArgumentException(),
                    operationId = operationId,
                )
                val prepared = prepare(request)
                currentCoroutineContext().ensureActive()
                if (activeOperationId != operationId) return@launch
                val receipt = execute(prepared) { progress ->
                    scope.launch { advance(operationId, progress.toExecutionPhase()) }
                }
                if (receipt.operationId != operationId || receipt.userId != verifiedUserId ||
                    receipt.walletAddress != verifiedWallet.address) {
                    throw TokenLaunchUiException("The confirmed launch did not match this wallet.")
                }
                if (activeOperationId == operationId && currentUserId() == receipt.userId) {
                    finish(
                        draft,
                        ConfirmedTokenLaunch(receipt.mintAddress, receipt.transactionId, receipt.poolAddress),
                        CommunityTokenOrigin.CONFIRMED_LAUNCH,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (activeOperationId == operationId) {
                    executionState = LaunchTokenExecutionState(
                        phase = LaunchTokenExecutionPhase.FAILED,
                        message = failure.userMessage(),
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
            finish(draft, tokenLaunchUiPreviewResult(), CommunityTokenOrigin.LOCAL_UI)
        }
    }

    private fun advance(operationId: String, next: LaunchTokenExecutionPhase) {
        if (activeOperationId == operationId && executionState.phase.ordinal < next.ordinal) {
            executionState = LaunchTokenExecutionState(next)
        }
    }

    private fun finish(
        draft: LaunchTokenDraft,
        confirmed: ConfirmedTokenLaunch,
        origin: CommunityTokenOrigin,
    ) {
        if (origin == CommunityTokenOrigin.LOCAL_UI) {
            savePreview(draft, confirmed.mintAddress)
        } else {
            removePreview()
            // Display persistence must never turn a confirmed on-chain launch into a UI failure.
            runCatching { saveConfirmedDisplay(draft, confirmed.mintAddress) }
            confirmedMint = confirmed.mintAddress
            confirmedDraft = draft
        }
        communityDraft = draft
        communityMint = confirmed.mintAddress
        communityMintConfirmed = true
        communityOrigin = origin
        executionState = LaunchTokenExecutionState(
            phase = LaunchTokenExecutionPhase.CONFIRMED,
            confirmed = confirmed,
        )
    }

    private fun TokenLaunchProgress.toExecutionPhase(): LaunchTokenExecutionPhase = when (this) {
        TokenLaunchProgress.COMMITTING -> LaunchTokenExecutionPhase.PREPARING
        TokenLaunchProgress.SIGNING -> LaunchTokenExecutionPhase.SIGNING
        TokenLaunchProgress.SUBMITTING,
        TokenLaunchProgress.CONFIRMING -> LaunchTokenExecutionPhase.CONFIRMING
    }

    private fun Throwable.userMessage(): String = when (this) {
        is TokenLaunchException -> userMessage
        is TokenLaunchUiException -> safeMessage
        is IllegalArgumentException -> "Review the token details and try again."
        else -> "The token launch service is unavailable. Nothing was submitted."
    }

    private class TokenLaunchUiException(val safeMessage: String) : Exception(safeMessage)
}
