package com.elevencapital.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.elevencapital.app.communitybuy.CommunityBuyCoordinator
import com.elevencapital.app.communitybuy.CommunityBuyException
import com.elevencapital.app.communitybuy.CommunityBuyProgress
import com.elevencapital.app.communitybuy.CommunityBuyRequest
import com.elevencapital.app.communitybuy.CommunityPurchasedToken
import com.elevencapital.app.screens.CommunityBuyPhase
import com.elevencapital.app.screens.CommunityBuyUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the QR buy flow and its recovery work outside the main navigation composable. */
internal class CommunityBuyAppController(
    private val userId: String?,
    private val walletId: String?,
    private val walletAddress: String?,
    private val coordinator: CommunityBuyCoordinator?,
    private val scope: CoroutineScope,
    private val currentUserId: () -> String?,
    private val currentWalletAddress: () -> String?,
) {
    var mint by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf(CommunityBuyUiState())
        private set
    var pendingMessage by mutableStateOf<String?>(null)
        private set
    var purchasedTokens by mutableStateOf(
        if (userId != null && walletAddress != null) {
            coordinator?.purchasedTokens(userId, walletAddress).orEmpty()
        } else emptyList<CommunityPurchasedToken>(),
    )
        private set

    private var job: Job? = null
    private var attempt = 0

    suspend fun restorePending() {
        val owner = userId ?: return
        val wallet = walletAddress ?: return
        val service = coordinator
        if (service != null) {
            val pendingCheck = runCatching { service.hasPending(owner, wallet) }
            if (pendingCheck.getOrDefault(false)) pendingMessage = "Checking a previous buy…"
            val recovered = runCatching { service.recoverPending(owner, wallet) }
            pendingMessage = when {
                pendingCheck.isFailure -> "Previous buy cannot be checked on this device. Do not buy again yet."
                recovered.isFailure && pendingCheck.getOrDefault(false) ->
                    "Previous buy status is unavailable. Do not buy again yet."
                runCatching { service.hasPending(owner, wallet) }.getOrDefault(false) ->
                    "A previous buy is still confirming. Do not buy again yet."
                else -> null
            }
        }
        purchasedTokens = service?.purchasedTokens(owner, wallet).orEmpty()
    }

    suspend fun refreshWhileCommunityVisible() {
        val owner = userId ?: return
        val wallet = walletAddress ?: return
        val service = coordinator
        while (currentCoroutineContext().isActive) {
            val pendingCheck = runCatching { service?.hasPending(owner, wallet) == true }
            pendingMessage = when {
                pendingCheck.isFailure -> "A previous buy cannot be checked on this device. Do not buy again yet."
                pendingCheck.getOrDefault(false) -> {
                    val recovered = runCatching { service?.recoverPending(owner, wallet) }
                    when {
                        recovered.isFailure -> "Previous buy status is unavailable. Do not buy again yet."
                        runCatching { service?.hasPending(owner, wallet) == true }.getOrDefault(true) ->
                            "A previous buy is still confirming. Do not buy again yet."
                        else -> null
                    }
                }
                else -> null
            }
            purchasedTokens = service?.purchasedTokens(owner, wallet).orEmpty()
            delay(if (pendingCheck.getOrDefault(false)) 10_000 else 3_000)
        }
    }

    fun open(tokenMint: String) {
        job?.cancel()
        attempt++
        mint = tokenMint
        state = CommunityBuyUiState()
    }

    fun prepare(amountInLamports: Long) {
        val tokenMint = mint ?: return
        val owner = userId
        val id = walletId
        val wallet = walletAddress
        val service = coordinator
        if (owner == null || id == null || wallet == null || service == null) {
            state = CommunityBuyUiState(phase = CommunityBuyPhase.FAILED,
                message = "Connect your Privy Solana wallet and try again.")
            return
        }
        job?.cancel()
        val currentAttempt = ++attempt
        state = CommunityBuyUiState(phase = CommunityBuyPhase.PREPARING)
        job = scope.launch {
            try {
                val request = CommunityBuyRequest.create(owner, id, wallet, tokenMint, amountInLamports)
                val prepared = service.prepare(request)
                if (attempt == currentAttempt && mint == tokenMint &&
                    currentUserId() == owner && currentWalletAddress() == wallet) {
                    state = CommunityBuyUiState(phase = CommunityBuyPhase.REVIEW, prepared = prepared)
                } else {
                    service.releaseBeforeSigning(prepared)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CommunityBuyException) {
                if (attempt == currentAttempt && mint == tokenMint) state = CommunityBuyUiState(
                    phase = CommunityBuyPhase.FAILED, message = failure.userMessage,
                    submissionMayHaveReachedServer = failure.submissionMayHaveReachedServer)
            } catch (_: Exception) {
                if (attempt == currentAttempt && mint == tokenMint) state = CommunityBuyUiState(
                    phase = CommunityBuyPhase.FAILED, message = "Could not review this Mainnet buy. Nothing was signed.")
            }
        }
    }

    fun confirm() {
        val prepared = state.prepared ?: return
        val tokenMint = mint ?: return
        val service = coordinator ?: return
        val currentAttempt = attempt
        state = state.copy(phase = CommunityBuyPhase.COMMITTING)
        job = scope.launch {
            try {
                val confirmed = service.execute(prepared) { progress ->
                    scope.launch {
                        if (attempt == currentAttempt && mint == tokenMint) state = state.copy(
                            phase = when (progress) {
                                CommunityBuyProgress.COMMITTING -> CommunityBuyPhase.COMMITTING
                                CommunityBuyProgress.SIGNING -> CommunityBuyPhase.SIGNING
                                CommunityBuyProgress.SUBMITTING -> CommunityBuyPhase.SUBMITTING
                                CommunityBuyProgress.CONFIRMING -> CommunityBuyPhase.CONFIRMING
                            },
                        )
                    }
                }
                if (confirmed.isVerifiedPurchase) {
                    purchasedTokens = service.purchasedTokens(prepared.request.userId,
                        prepared.request.walletAddress)
                    pendingMessage = null
                    if (attempt == currentAttempt && mint == tokenMint &&
                        currentUserId() == prepared.request.userId &&
                        currentWalletAddress() == prepared.request.walletAddress) {
                        state = CommunityBuyUiState(phase = CommunityBuyPhase.CONFIRMED,
                            confirmed = confirmed)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CommunityBuyException) {
                if (attempt == currentAttempt && mint == tokenMint) state = CommunityBuyUiState(
                    phase = CommunityBuyPhase.FAILED, message = failure.userMessage,
                    submissionMayHaveReachedServer = failure.submissionMayHaveReachedServer)
            } catch (_: Exception) {
                if (attempt == currentAttempt && mint == tokenMint) state = CommunityBuyUiState(
                    phase = CommunityBuyPhase.FAILED, message = "Buy status is unavailable. Check again before retrying.",
                    submissionMayHaveReachedServer = true)
            }
        }
    }

    fun close() {
        val phase = state.phase
        attempt++
        if (phase == CommunityBuyPhase.REVIEW) {
            state.prepared?.let { prepared ->
                scope.launch { coordinator?.releaseBeforeSigning(prepared) }
            }
        }
        if (phase in setOf(CommunityBuyPhase.PREPARING, CommunityBuyPhase.COMMITTING,
                CommunityBuyPhase.SIGNING)) job?.cancel()
        mint = null
        state = CommunityBuyUiState()
    }

    fun cancelOnWalletChange() {
        attempt++
        job?.cancel()
    }
}

@Composable
internal fun rememberCommunityBuyAppController(
    userId: String?,
    walletId: String?,
    walletAddress: String?,
    coordinator: CommunityBuyCoordinator?,
    rootTab: RootTab,
): CommunityBuyAppController {
    val scope = rememberCoroutineScope()
    val latestUserId = rememberUpdatedState(userId)
    val latestWalletAddress = rememberUpdatedState(walletAddress)
    val controller = remember(userId, walletId, walletAddress, coordinator) {
        CommunityBuyAppController(userId, walletId, walletAddress, coordinator, scope,
            currentUserId = { latestUserId.value },
            currentWalletAddress = { latestWalletAddress.value })
    }
    LaunchedEffect(controller) { controller.restorePending() }
    LaunchedEffect(rootTab, controller, controller.mint) {
        if (rootTab == RootTab.Community && controller.mint == null) {
            controller.refreshWhileCommunityVisible()
        }
    }
    DisposableEffect(controller) { onDispose(controller::cancelOnWalletChange) }
    return controller
}
