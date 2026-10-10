package com.elevencapital.app

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.Stock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.app.screens.*
import com.elevencapital.app.ui.*
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.auth.ElevenAuthState
import com.elevencapital.app.auth.AuthPhase
import com.elevencapital.app.auth.DeviceUnlockState
import com.elevencapital.app.auth.DeviceUnlockPhase
import com.elevencapital.app.auth.PendingCommunityTokenScan
import com.elevencapital.app.auth.EntrySurface
import com.elevencapital.app.auth.entrySurface
import com.elevencapital.app.wallet.PreparedTransfer
import com.elevencapital.app.wallet.TransferDraft
import com.elevencapital.app.wallet.TransferStatusObservation
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.app.wallet.filterHomeWalletTransactions
import com.elevencapital.app.wallet.WalletSubmission
import com.elevencapital.app.data.WalletBalancePhase
import com.elevencapital.app.data.LiveWalletTokenHolding
import com.elevencapital.app.data.CompletedPurchaseTransaction
import com.elevencapital.app.data.WalletActivityStatus
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseRouteState
import com.elevencapital.app.tokenlaunch.ConfirmedTokenLaunchReceipt
import com.elevencapital.app.communitybuy.CommunityPurchasedToken
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.data.CommunityChatAccessTokenProvider
import com.elevencapital.app.data.CommunityChatClient
import com.elevencapital.app.data.CommunityChatRepository
import com.elevencapital.app.data.CommunityChatState
import com.elevencapital.app.data.PantaAccessTokenProvider
import com.elevencapital.app.data.PantaPredictionClient
import com.elevencapital.app.data.CommunityTokenMintAccessTokenProvider
import com.elevencapital.app.data.CommunityTokenMintStatus
import com.elevencapital.app.data.CommunityTokenMintValidation
import com.elevencapital.app.data.CommunityTokenMintValidator
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.walletTransactionsFor
import com.elevencapital.core.stock.flow.OrderSide
import com.elevencapital.core.stock.flow.StockDestination
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface VisualRoute {
    data class Root(val tab: RootTab) : VisualRoute
    data class Detail(val stockId: StockId) : VisualRoute
    data class MeteoraDetail(val mintAddress: String) : VisualRoute
    data class CommunityTokenDetail(val mintAddress: String) : VisualRoute
    data class CommunityBuy(val mintAddress: String) : VisualRoute
    data class CommunitySell(val mintAddress: String) : VisualRoute
    data class CommunityChat(val mintAddress: String) : VisualRoute
    data class Order(val stockId: StockId, val side: OrderSide) : VisualRoute
    data object AccountCenter : VisualRoute
    data class Wallet(val page: WalletPage) : VisualRoute
    data object TransferHistory : VisualRoute
    data object PredictionBalance : VisualRoute
    data object LaunchToken : VisualRoute
}

private fun VisualRoute.navigationDepth(): Int = when (this) {
    is VisualRoute.Root -> 0
    is VisualRoute.Order, is VisualRoute.CommunityChat, is VisualRoute.CommunityBuy,
        is VisualRoute.CommunitySell -> 2
    else -> 1
}

private data class CompletedBuyNotice(val quoteId: String, val stock: Stock, val solscanUrl: String)

private data class AccountTabState(
    val liveBalance: BigDecimal?,
    val tokenHoldings: List<LiveWalletTokenHolding>,
    val holdings: List<PrimaryStockRow>,
    val previewHoldings: List<PreviewStockHolding>,
    val previewCommunityTokens: List<CommunityScannedTokenPurchase>,
    val previewSpcxxHolding: PreviewStockHolding?,
    val previewWalletState: PreviewWalletState,
    val previewWalletBalanceUsd: BigDecimal,
    val previewHoldingUsd: BigDecimal,
    val previewSpendableUsdc: BigDecimal,
    val devnetSol: BigDecimal?,
    val accountNotice: String?,
    val balancePlaceholder: String,
    val portfolioPhase: WalletBalancePhase,
    val walletConnected: Boolean,
)

private data class HomeTabState(
    val balance: BigDecimal?,
    val walletAddress: String?,
    val walletConnected: Boolean,
    val displayName: String?,
    val avatarVariant: Int,
    val transactions: List<TransferJournalRecord>,
    val walletTransactions: List<WalletTransaction>,
    val purchaseTransactions: List<CompletedPurchaseTransaction>,
    val previewSpcxxPosition: PreviewSpcxxPosition?,
    val previewWalletState: PreviewWalletState,
    val predictionFundedUsd: BigDecimal,
    val predictionTransfers: List<PredictionMainTransferItem>,
    val communityActivity: List<CommunityUiActivity>,
    val walletActivityStatus: WalletActivityStatus?,
    val stockSymbols: Map<String, String>,
    val unitPricesUsd: Map<String, BigDecimal>,
    val historyStorageHealthy: Boolean,
    val balancePlaceholder: String,
)

// Stock quantities in the portfolio are displayed to six decimal places.
private fun BigDecimal?.hasSellableStockQuantity(): Boolean =
    this?.setScale(6, RoundingMode.DOWN)?.signum() == 1

@Composable
internal fun ElevenApp(
    model: ElevenViewModel,
    authState: ElevenAuthState? = null,
    deviceUnlockState: DeviceUnlockState = DeviceUnlockState(),
    onUnlock: () -> Unit = {},
    onOpenSecuritySettings: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onLogout: () -> Unit = {},
    onPrepareTransfer: suspend (TransferDraft) -> PreparedTransfer = {
        error("Transfer preparation is unavailable in this build.")
    },
    onSubmitTransfer: suspend (PreparedTransfer) -> WalletSubmission = {
        error("Transfer submission is unavailable in this build.")
    },
    onCheckTransferStatus: suspend (PreparedTransfer, WalletSubmission) -> TransferStatusObservation = { _, _ ->
        error("Transfer status is unavailable in this build.")
    },
    confirmedTokenLaunches: List<ConfirmedTokenLaunchReceipt> = emptyList(),
    transferHistory: List<TransferJournalRecord> = emptyList(),
    transferHistoryStorageHealthy: Boolean = true,
    onRetrySessionRestore: () -> Unit = {},
    pendingCommunityTokenScan: PendingCommunityTokenScan? = null,
    onCommunityTokenScanned: ((String) -> Unit)? = null,
    onPendingCommunityTokenConsumed: (PendingCommunityTokenScan) -> Unit = {},
) {
    val authenticatedUser = authState?.takeIf { it.authenticated }
    val context = LocalContext.current
    val application = remember(context) { context.applicationContext as ElevenCapitalApplication }
    val meteoraFeed = remember(application) { application.meteoraTokenFeed }
    val meteoraWarmup = remember(application) { application.meteoraMarketWarmup }
    val meteoraWarmState = meteoraWarmup.state.collectAsStateWithLifecycle().value
    val verifiedWallets = authenticatedUser?.takeIf { it.walletsReady }?.wallets.orEmpty()
    val mainSolanaWallet = verifiedWallets.firstOrNull { it.chain == WalletChain.SOLANA }
    val authenticatedUserId: String? = authenticatedUser?.userId
    val mainSolanaAddress: String? = mainSolanaWallet?.address
    val ledgerWalletId: String? = predictionWalletId(mainSolanaWallet)
    val predictionSession = rememberPredictionSession(context, authenticatedUserId, ledgerWalletId)
    val avatarVariant = remember(authenticatedUserId) {
        profileAvatarVariant(context, authenticatedUserId)
    }
    // Start public market data while the entry animation and device gate are still visible.
    // Wallet data is bound only to a verified identity and stays hidden until unlock.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep the public top 20 current behind login and device unlock, as with stocks.
    // The application-scoped warmup owns the initial fetch and persists the last good rows.
    ObservePublicMeteoraWarmup(meteoraWarmup, model.isLive)
    val activity = rememberWalletActivity(application, model.isLive, authenticatedUserId,
        verifiedWallets, mainSolanaAddress, transferHistory)
    val requestedActivityWallets = activity.requestedWallets
    val walletActivity = activity.walletActivity
    val devnetActivity = activity.devnetActivity
    val purchaseActivity = activity.purchaseActivity
    LaunchedEffect(model, lifecycleOwner) {
        if (model.isLive) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refreshWhileVisible()
        }
    }
    var boundUserId by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, lifecycleOwner, authenticatedUserId,
        authenticatedUser?.walletsReady, authenticatedUser?.wallets) {
        model.setAuthenticatedUser(authenticatedUserId)
        model.setWalletSession(authenticatedUserId,
            authenticatedUser?.takeIf { it.walletsReady }?.wallets.orEmpty())
        boundUserId = authenticatedUserId
        if (model.isLive && authenticatedUser?.walletsReady == true) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.refreshWalletPortfolioWhileVisible()
            }
        }
    }
    LaunchedEffect(model, authenticatedUserId, deviceUnlockState.phase, deviceUnlockState.userId) {
        if (deviceUnlockState.phase == DeviceUnlockPhase.UNLOCKED &&
            deviceUnlockState.userId == authenticatedUserId) model.finishWalletWarmup()
    }
    when (entrySurface(authState, deviceUnlockState)) {
        EntrySurface.SESSION_SHIELD -> {
            SecureSessionScreen(retryAvailable = authState?.sessionRestoreRetryAvailable == true,
                onRetry = onRetrySessionRestore, message = authState?.error)
            return
        }
        EntrySurface.LOGIN -> {
            LoginScreen(authState = requireNotNull(authState), onSignIn = onSignIn, onLogout = onLogout)
            return
        }
        EntrySurface.DEVICE_UNLOCK -> {
            DeviceUnlockScreen(deviceUnlockState, onUnlock, onOpenSecuritySettings)
            return
        }
        EntrySurface.APP -> Unit
    }
    // No previous account's watchlist or route may flash while the new identity is bound.
    if (authState != null && boundUserId != authState.userId) {
        SecureSessionScreen()
        return
    }
    val walletNotice = when {
        authState?.phase == AuthPhase.SESSION_UNVERIFIED -> "Reconnecting secure session…"
        authState?.phase == AuthPhase.RESTORING -> "Restoring secure session…"
        authenticatedUser == null -> "Wallet not connected"
        authenticatedUser.provisioning -> "Preparing your wallets…"
        authenticatedUser.walletsReady -> "Wallet connected"
        else -> "Wallet setup incomplete"
    }
    val modelState = collectAppModelState(model)
    val accountCenter = modelState.accountCenter
    val stocks = modelState.stocks
    val destination = modelState.destination
    val rootTab = modelState.rootTab
    val meteoraTokens = meteoraWarmState.tokens
    val meteoraLoading = meteoraWarmState.loading
    val meteoraError = meteoraWarmState.error
    val meteoraRoute = rememberMeteoraRouteState(meteoraFeed, meteoraWarmup)
    val watched = modelState.watched
    val connection = modelState.connection
    val walletPortfolio = modelState.walletPortfolio
    val purchaseState = modelState.purchaseState
    val previewUserId = authenticatedUserId
    val previewSession = rememberPreviewSession(context, previewUserId, model)
    val completedBuy = purchaseState.takeIf {
        it.side == OrderSide.BUY && it.phase == PurchasePhase.COMPLETE &&
            it.status?.state == PurchaseRouteState.COMPLETED
    }
    val completedBuySolscanUrl = completedPurchaseSolscanUrl(completedBuy?.status)
    val completedBuyQuoteId = completedBuy?.status?.quoteId?.takeIf { completedBuySolscanUrl != null }
    var lastBuyNoticeQuoteId by remember(authenticatedUserId) { mutableStateOf<String?>(null) }
    var buyNotice by remember(authenticatedUserId) { mutableStateOf<CompletedBuyNotice?>(null) }
    LaunchedEffect(completedBuyQuoteId, stocks) {
        val result = completedBuy ?: return@LaunchedEffect
        val quoteId = completedBuyQuoteId ?: return@LaunchedEffect
        val solscanUrl = completedBuySolscanUrl ?: return@LaunchedEffect
        if (lastBuyNoticeQuoteId == quoteId) return@LaunchedEffect
        val stock = stocks.firstOrNull { it.id.value == result.stockId } ?: return@LaunchedEffect
        lastBuyNoticeQuoteId = quoteId
        buyNotice = CompletedBuyNotice(quoteId, stock, solscanUrl)
        val activeOrder = destination as? StockDestination.Order
        if (activeOrder?.side == OrderSide.BUY && activeOrder.stockId == stock.id) {
            model.stockFlow.goBack()
        }
    }
    LaunchedEffect(buyNotice?.quoteId) {
        val quoteId = buyNotice?.quoteId ?: return@LaunchedEffect
        delay(4_500)
        if (buyNotice?.quoteId == quoteId) buyNotice = null
    }
    var walletPage by remember(authenticatedUserId) { mutableStateOf<WalletPage?>(null) }
    var transferHistoryVisible by remember(authenticatedUserId) { mutableStateOf(false) }
    var predictionBalanceVisible by remember(authenticatedUserId) { mutableStateOf(false) }
    val launchScope = rememberCoroutineScope()
    // Preserve previously verified receipts for display; scanning a QR never prepares a buy.
    val previouslyPurchasedCommunityTokens: List<CommunityPurchasedToken> = remember(
        authenticatedUserId, mainSolanaAddress, application,
    ) {
        val owner = authenticatedUserId
        val wallet = mainSolanaAddress
        if (owner != null && wallet != null) {
            application.communityPurchasedTokens.load(owner, wallet)
        } else emptyList()
    }
    val tokenLaunchSession = rememberTokenLaunchSession(context, authenticatedUserId,
        mainSolanaAddress, confirmedTokenLaunches)
    val tokenLaunchUi = tokenLaunchSession.ui
    var launchTokenVisible by remember(authenticatedUserId, mainSolanaAddress) { mutableStateOf(false) }
    var selectedCommunityTokenMint by remember(authenticatedUserId) { mutableStateOf<String?>(null) }
    var communityChatVisible by remember(authenticatedUserId) { mutableStateOf(false) }
    var communityBuyVisible by remember(authenticatedUserId) { mutableStateOf(false) }
    var communitySellVisible by remember(authenticatedUserId) { mutableStateOf(false) }
    var communityBuyInProgress by remember(authenticatedUserId) { mutableStateOf(false) }
    var communityBuyError by remember(authenticatedUserId) { mutableStateOf<String?>(null) }
    var communitySellInProgress by remember(authenticatedUserId) { mutableStateOf(false) }
    var communitySellError by remember(authenticatedUserId) { mutableStateOf<String?>(null) }
    var communityBuyNoticeId by remember(authenticatedUserId) { mutableStateOf<String?>(null) }
    LaunchedEffect(communityBuyNoticeId) {
        val noticeId = communityBuyNoticeId ?: return@LaunchedEffect
        delay(4_500)
        if (communityBuyNoticeId == noticeId) communityBuyNoticeId = null
    }
    var communityScannedPurchases by remember(authenticatedUserId) {
        mutableStateOf(authenticatedUserId?.let {
            CommunityScannedTokenPurchaseStore.read(context, it)
        }.orEmpty())
    }
    var communityUiActivity by remember(authenticatedUserId) {
        mutableStateOf(authenticatedUserId?.let {
            CommunityUiActivityStore.read(context, it)
        }.orEmpty())
    }
    var localUiFlowRevision by remember(authenticatedUserId) { mutableIntStateOf(0) }
    var localUiClearInProgress by remember(authenticatedUserId) { mutableStateOf(false) }
    var localCommunityChatMessages by remember(authenticatedUserId, mainSolanaAddress) {
        mutableStateOf(if (authenticatedUserId != null && mainSolanaAddress != null) {
            CommunityLocalChatStore.read(context, authenticatedUserId, mainSolanaAddress)
        } else emptyList())
    }
    val spxtrPreviewMint = remember { tokenLaunchUiPreviewResult().mintAddress }
    val confirmedLocalLaunch = tokenLaunchUi.executionState.phase == LaunchTokenExecutionPhase.CONFIRMED &&
        tokenLaunchUi.communityOrigin == CommunityTokenOrigin.LOCAL_UI &&
        tokenLaunchUi.communityMint == spxtrPreviewMint
    LaunchedEffect(authenticatedUserId, confirmedLocalLaunch) {
        val userId = authenticatedUserId ?: return@LaunchedEffect
        if (confirmedLocalLaunch) {
            CommunityUiActivityStore.recordMint(context, userId, spxtrPreviewMint,
                TOKEN_LAUNCH_UI_PREVIEW_SYMBOL)
                ?.let { communityUiActivity = it }
        }
    }
    val chatRepository = remember(application, model.isLive) {
        if (model.isLive && BuildConfig.MARKET_DATA_URL.isNotBlank()) {
            CommunityChatRepository(CommunityChatClient(
                BuildConfig.MARKET_DATA_URL,
                CommunityChatAccessTokenProvider(application.auth::freshAccessToken),
                allowLoopbackHttp = BuildConfig.DEBUG,
            ))
        } else null
    }
    val pantaPredictionApi = remember(application, model.isLive) {
        if (model.isLive && BuildConfig.MARKET_DATA_URL.isNotBlank()) {
            PantaPredictionClient(
                BuildConfig.MARKET_DATA_URL,
                PantaAccessTokenProvider(application.auth::freshAccessToken),
                allowLoopbackHttp = BuildConfig.DEBUG,
            )
        } else null
    }
    val communityMintValidator = remember(application, model.isLive) {
        if (model.isLive && BuildConfig.MARKET_DATA_URL.isNotBlank()) {
            CommunityTokenMintValidator(
                BuildConfig.MARKET_DATA_URL,
                CommunityTokenMintAccessTokenProvider(application.auth::freshAccessToken),
                allowLoopbackHttp = BuildConfig.DEBUG,
            )
        } else null
    }
    var communityMintValidation by remember(authenticatedUserId) {
        mutableStateOf<CommunityTokenMintValidation?>(null)
    }
    LaunchedEffect(communityMintValidator, authenticatedUserId, selectedCommunityTokenMint) {
        val mint = selectedCommunityTokenMint
        val userId = authenticatedUserId
        communityMintValidation = null
        if (mint != null && mint != spxtrPreviewMint && userId != null) {
            communityMintValidation = communityMintValidator?.validate(userId, mint)
                ?: CommunityTokenMintValidation(CommunityTokenMintStatus.UNAVAILABLE)
        }
    }
    val chatState = chatRepository?.state?.collectAsStateWithLifecycle()?.value ?: CommunityChatState()
    LaunchedEffect(chatRepository, authenticatedUserId, selectedCommunityTokenMint,
        lifecycleOwner) {
        val userId = authenticatedUserId ?: return@LaunchedEffect
        // The debug Christmas room consists of starter posts and this device's posts.
        // Do not poll remote history for it, even while the screen is foregrounded.
        if (!BuildConfig.DEBUG && selectedCommunityTokenMint == spxtrPreviewMint &&
            chatRepository != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatRepository.watch(userId)
            }
        }
    }
    val hasLocalSpxtrPurchase = communityScannedPurchases.any {
        it.mintAddress == spxtrPreviewMint
    }
    val isLocalChristmasCreator = tokenLaunchUi.communityOrigin == CommunityTokenOrigin.LOCAL_UI &&
        tokenLaunchUi.communityMint == spxtrPreviewMint
    val hasChristmasChatAccess = hasLocalSpxtrPurchase || isLocalChristmasCreator

    fun openCommunityToken(mintAddress: String) {
        selectedCommunityTokenMint = mintAddress
        communityChatVisible = false
        communityBuyVisible = false
        communitySellVisible = false
        communityBuyError = null
        communitySellError = null
    }
    LaunchedEffect(pendingCommunityTokenScan?.id, authenticatedUserId, boundUserId) {
        val scan = pendingCommunityTokenScan ?: return@LaunchedEffect
        if (scan.ownerUserId != authenticatedUserId || boundUserId != authenticatedUserId) {
            return@LaunchedEffect
        }
        model.selectTab(RootTab.Community)
        openCommunityToken(scan.mintAddress)
        onPendingCommunityTokenConsumed(scan)
    }

    fun buyCommunityToken(
        mintAddress: String,
        amountUsd: BigDecimal? = null,
        payAsset: CommunityPayAsset? = null,
        paidAmount: BigDecimal? = null,
    ) {
        val userId = authenticatedUserId ?: return
        if (communityBuyInProgress) return
        val localBuy = mintAddress == spxtrPreviewMint
        val localQuote = if (localBuy) amountUsd?.let(::spxtrPreviewReceivedTokensUsd) else null
        if (localBuy) {
            val expectedUsd = when (payAsset) {
                CommunityPayAsset.SOL -> paidAmount?.multiply(PreviewWalletState.SOL_USD_RATE)
                CommunityPayAsset.USDC -> paidAmount
                null -> null
            }
            if (localQuote == null || paidAmount == null || paidAmount.signum() <= 0 ||
                expectedUsd == null || amountUsd == null || expectedUsd.compareTo(amountUsd) != 0) {
                communityBuyError = "Review the amount and try again."
                return
            }
        }
        if (mintAddress != spxtrPreviewMint &&
            communityMintValidation?.takeIf { it.mintAddress == mintAddress }?.isValid != true) {
            communityBuyError = "This token could not be verified."
            return
        }
        communityBuyInProgress = true
        communityBuyError = null
        val flowRevision = localUiFlowRevision
        launchScope.launch {
            var pendingWalletTradeId: String? = null
            try {
                if (flowRevision != localUiFlowRevision) return@launch
                val marketToken = meteoraTokens.firstOrNull { it.mintAddress == mintAddress }
                val tradeId = UUID.randomUUID().toString()
                val walletAfter = if (localBuy) {
                    PreviewWalletStore.recordCommunityTrade(
                        context, userId, tradeId, mintAddress,
                        if (payAsset == CommunityPayAsset.SOL) PreviewCommunityCashAsset.SOL
                            else PreviewCommunityCashAsset.USDC,
                        PreviewCommunityTradeSide.BUY, requireNotNull(paidAmount),
                        requireNotNull(localQuote),
                    )
                } else null
                if (localBuy && walletAfter == null) {
                    communityBuyError = "Not enough balance to buy this token."
                    return@launch
                }
                if (localBuy) pendingWalletTradeId = tradeId
                if (flowRevision != localUiFlowRevision) return@launch
                val saved = if (localBuy) CommunityScannedTokenPurchaseStore.recordBuy(
                    context, userId, mintAddress, tradeId, marketToken,
                    receivedTokens = requireNotNull(localQuote), spentUsd = requireNotNull(amountUsd),
                ) else CommunityScannedTokenPurchaseStore.record(
                    context, userId, mintAddress, marketToken,
                )
                if (saved == null) {
                    communityBuyError = "Couldn't add token to Community. Try again."
                } else {
                    pendingWalletTradeId = null
                    if (walletAfter != null) previewSession.wallet = walletAfter
                    val symbol = if (mintAddress == spxtrPreviewMint) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL
                        else saved.firstOrNull { it.mintAddress == mintAddress }?.symbol
                            ?: marketToken?.symbol ?: "Token"
                    CommunityUiActivityStore.recordBuy(context, userId, mintAddress, symbol,
                        eventId = tradeId, amountUsd = amountUsd)
                        ?.let { communityUiActivity = it }
                    communityScannedPurchases = saved
                    if (mintAddress == spxtrPreviewMint) {
                        communityBuyVisible = false
                        communityBuyNoticeId = UUID.randomUUID().toString()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                communityBuyError = "Couldn't complete Buy. Try again."
            } finally {
                pendingWalletTradeId?.let { tradeId ->
                    previewSession.wallet = PreviewWalletStore.removeCommunityTrade(
                        context, userId, tradeId,
                    ) ?: PreviewWalletStore.read(context, userId)
                }
                if (flowRevision == localUiFlowRevision) communityBuyInProgress = false
            }
        }
    }

    fun sellCommunityToken(mintAddress: String, soldTokens: BigDecimal, proceedsUsd: BigDecimal) {
        val userId = authenticatedUserId ?: return
        if (!BuildConfig.DEBUG || mintAddress != spxtrPreviewMint || communitySellInProgress) return
        val holding = communityScannedPurchases.firstOrNull { it.mintAddress == mintAddress }
        val heldTokens = holding?.receivedTokens
        val tokensPerUsd = spxtrPreviewReceivedTokensUsd(BigDecimal.ONE)
        val expectedUsd = tokensPerUsd?.let { soldTokens.divide(it, 12, RoundingMode.HALF_UP) }
        val proceedsSol = proceedsUsd.divide(PreviewWalletState.SOL_USD_RATE, 9,
            RoundingMode.HALF_UP).stripTrailingZeros()
        if (heldTokens == null || soldTokens.signum() <= 0 || soldTokens > heldTokens ||
            expectedUsd?.compareTo(proceedsUsd) != 0 || proceedsSol.signum() <= 0) {
            communitySellError = "Review the sale amount and try again."
            return
        }
        communitySellInProgress = true
        communitySellError = null
        val flowRevision = localUiFlowRevision
        launchScope.launch {
            var pendingWalletTradeId: String? = null
            try {
                if (flowRevision != localUiFlowRevision) return@launch
                var walletBefore = PreviewWalletStore.read(context, userId)
                if (walletBefore.communityHeldQuantity(mintAddress) < heldTokens) {
                    walletBefore = PreviewWalletStore.reconcileLegacyCommunityHolding(
                        context, userId, mintAddress, holding.spentSol, holding.spentUsd, heldTokens,
                    ) ?: run {
                        communitySellError = "Couldn't prepare this holding for sale."
                        return@launch
                    }
                    previewSession.wallet = walletBefore
                }
                val tradeId = UUID.randomUUID().toString()
                val walletAfter = PreviewWalletStore.recordCommunityTrade(
                    context, userId, tradeId, mintAddress, PreviewCommunityCashAsset.SOL,
                    PreviewCommunityTradeSide.SELL, proceedsSol, soldTokens,
                )
                if (walletAfter == null) {
                    communitySellError = "Couldn't prepare this sale. Try again."
                    return@launch
                }
                pendingWalletTradeId = tradeId
                if (flowRevision != localUiFlowRevision) return@launch
                val saved = CommunityScannedTokenPurchaseStore.recordSell(
                    context, userId, mintAddress, tradeId, soldTokens,
                )
                if (saved == null) {
                    communitySellError = "Couldn't update your token holding. Try again."
                    return@launch
                }
                pendingWalletTradeId = null
                previewSession.wallet = walletAfter
                communityScannedPurchases = saved
                CommunityUiActivityStore.recordSell(context, userId, mintAddress,
                    TOKEN_LAUNCH_UI_PREVIEW_SYMBOL, eventId = tradeId,
                    amountUsd = proceedsUsd)
                    ?.let { communityUiActivity = it }
                communitySellVisible = false
                Toast.makeText(context, "Sale complete", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                communitySellError = "Couldn't complete this sale. Try again."
            } finally {
                pendingWalletTradeId?.let { tradeId ->
                    previewSession.wallet = PreviewWalletStore.removeCommunityTrade(
                        context, userId, tradeId,
                    ) ?: PreviewWalletStore.read(context, userId)
                }
                if (flowRevision == localUiFlowRevision) communitySellInProgress = false
            }
        }
    }

    fun clearAllLocalUiFlow() {
        if (!BuildConfig.DEBUG) return
        val userId = authenticatedUserId ?: return
        val walletAddress = mainSolanaAddress ?: run {
            Toast.makeText(context, "Wallet is still loading. Hold Tokens again shortly.",
                Toast.LENGTH_SHORT).show()
            return
        }
        if (localUiClearInProgress) return
        localUiClearInProgress = true
        try {
                // Own CHTMS posts share this phone's local UI-flow lifetime. A failed
                // preference commit leaves the token available for another hold.
                val chatCleared = CommunityLocalChatStore.clear(context, userId, walletAddress)
                if (!chatCleared) {
                    Toast.makeText(context, "Couldn't clear your chat. Hold Tokens again to retry.",
                        Toast.LENGTH_LONG).show()
                    return
                }
                localCommunityChatMessages = emptyList()
                val localMints = communityScannedPurchases.mapTo(HashSet<String>()) { it.mintAddress }
                localMints += spxtrPreviewMint
                if (tokenLaunchUi.communityOrigin != CommunityTokenOrigin.CONFIRMED_LAUNCH) {
                    tokenLaunchUi.communityMint?.let(localMints::add)
                }
                // First stop in-flight UI actions; a delayed Buy must not recreate a cleared row.
                localUiFlowRevision++
                tokenLaunchUi.reset()
                communityBuyInProgress = false
                communityBuyVisible = false
                communityBuyError = null
                communitySellInProgress = false
                communitySellVisible = false
                communitySellError = null
                communityBuyNoticeId = null
                val walletCleared = previewSession.clear(context, previewUserId, predictionSession)
                val scannedCleared = CommunityScannedTokenPurchaseStore.clear(context, userId)
                val activityCleared = CommunityUiActivityStore.clear(context, userId)
                val launchPreviewCleared = com.elevencapital.app.tokenlaunch.TokenLaunchUiPreviewStore
                    .removeForUser(context, userId)
                val communityCleared = when (tokenLaunchUi.communityOrigin) {
                    CommunityTokenOrigin.LOCAL_UI -> tokenLaunchUi.removeLocalCommunityToken()
                    CommunityTokenOrigin.LINKED_SCAN -> tokenLaunchUi.clearLinkedCommunityToken()
                    else -> true
                }
                // Read back each store: even a failed preference write must not leave a phantom row.
                communityScannedPurchases = CommunityScannedTokenPurchaseStore.read(context, userId)
                communityUiActivity = CommunityUiActivityStore.read(context, userId)
                launchTokenVisible = false
                if (selectedCommunityTokenMint?.let { it in localMints } == true) {
                    communityChatVisible = false
                    selectedCommunityTokenMint = null
                }
                if (!walletCleared || !scannedCleared || !activityCleared ||
                    !launchPreviewCleared || !communityCleared) {
                    Toast.makeText(context, "Couldn't clear all local activity. Try again.",
                        Toast.LENGTH_SHORT).show()
                }
        } catch (_: Exception) {
            Toast.makeText(context, "Couldn't clear all local activity. Hold Tokens again to retry.",
                Toast.LENGTH_LONG).show()
        } finally {
            localUiClearInProgress = false
        }
    }

    fun startTokenLaunch(draft: LaunchTokenDraft) {
        tokenLaunchUi.start(
            scope = launchScope,
            draft = draft,
            userId = authenticatedUserId,
            wallet = mainSolanaWallet,
            currentUserId = tokenLaunchSession.currentUserId,
            // The launch backend implementation remains in source, but this app build
            // must never call it. The controller still owns the SPXTR-only local flow.
            uploadMetadata = { _, _ -> error("Token launch is not connected in this build.") },
            prepare = { error("Token launch is not connected in this build.") },
            execute = { _, _ -> error("Token launch is not connected in this build.") },
        )
    }

    fun openTokenLaunch() {
        tokenLaunchUi.open()
        launchTokenVisible = true
    }
    DisposableEffect(tokenLaunchUi) {
        onDispose(tokenLaunchUi::cancel)
    }
    LaunchedEffect(model, authenticatedUserId, purchaseState.phase, purchaseState.quote?.id) {
        val userId = authenticatedUserId
        val quoteId = purchaseState.quote?.id
        if (purchaseState.phase == com.elevencapital.app.purchase.PurchasePhase.COMPLETE &&
            userId != null && quoteId != null) {
            model.refreshPortfolioAfterPurchase(userId, quoteId)
            activity.requestRefresh()
        }
    }
    val route = when {
        launchTokenVisible -> VisualRoute.LaunchToken
        selectedCommunityTokenMint != null && communityBuyVisible ->
            VisualRoute.CommunityBuy(requireNotNull(selectedCommunityTokenMint))
        selectedCommunityTokenMint != null && communitySellVisible ->
            VisualRoute.CommunitySell(requireNotNull(selectedCommunityTokenMint))
        selectedCommunityTokenMint != null && communityChatVisible ->
            VisualRoute.CommunityChat(requireNotNull(selectedCommunityTokenMint))
        selectedCommunityTokenMint != null ->
            VisualRoute.CommunityTokenDetail(requireNotNull(selectedCommunityTokenMint))
        predictionBalanceVisible -> VisualRoute.PredictionBalance
        walletPage != null -> VisualRoute.Wallet(requireNotNull(walletPage))
        transferHistoryVisible -> VisualRoute.TransferHistory
        accountCenter -> VisualRoute.AccountCenter
        meteoraRoute.selectedToken != null -> VisualRoute.MeteoraDetail(requireNotNull(meteoraRoute.selectedToken).mintAddress)
        destination is StockDestination.Detail -> VisualRoute.Detail((destination as StockDestination.Detail).stockId)
        destination is StockDestination.Order -> (destination as StockDestination.Order).let {
            VisualRoute.Order(it.stockId, it.side)
        }
        else -> VisualRoute.Root(rootTab)
    }
    val fixtures = model.fixtures
    val portfolio = fixtures.portfolio
    var marketQuery by remember(model) { mutableStateOf(MarketQuery()) }
    var watchlistOnly by remember(model) { mutableStateOf(false) }
    var marketOverlay by remember(model) { mutableStateOf<String?>(null) }
    val holdings = remember(model, stocks, watched, walletPortfolio.holdings) {
        if (model.isLive) model.liveHoldingRows(stocks, watched) else fixtures.holdings(stocks, watched)
    }
    val previewHoldings = remember(stocks, previewSession.wallet.purchases) {
        if (BuildConfig.DEBUG) buildList {
            previewSession.wallet.purchases.groupBy { it.stockId }.forEach { (stockId, purchases) ->
                stocks.firstOrNull { it.id.value == stockId }?.let { stock ->
                    val quantities = purchases.mapNotNull { purchase ->
                        purchase.receivedTokens ?: previewStockPurchase(stock, purchase.spentUsd)?.receiveTokens
                    }
                    if (quantities.isNotEmpty()) {
                        val quantity = quantities.fold(BigDecimal.ZERO, BigDecimal::add)
                        val currentValue = stock.quote.price?.takeIf {
                            !isRobinhoodTeslaPreviewStock(stock) &&
                            it.signum() > 0 && stock.quote.currencyCode in setOf("USD", "USDC")
                        }?.multiply(quantity)
                        val purchaseValue = purchases.fold(BigDecimal.ZERO) { total, purchase ->
                            total + (purchase.receivedValueUsd ?: purchase.spentUsd)
                        }
                        add(PreviewStockHolding(stock, quantity, currentValue ?: purchaseValue))
                    }
                }
            }
        } else emptyList()
    }
    val legacySpcxxHolding = remember(stocks, previewSession.spcxxPosition) {
        if (BuildConfig.DEBUG) previewSession.spcxxPosition?.let { position ->
            stocks.firstOrNull { it.id.value == SPCXX_PREVIEW_STOCK_ID }?.let { stock ->
                PreviewStockHolding(stock, position.quantity, position.valueUsd)
            }
        } else null
    }
    val previewHoldingUsd = previewHoldings.fold(BigDecimal.ZERO) { total, holding -> total + holding.valueUsd } +
        (legacySpcxxHolding?.valueUsd ?: BigDecimal.ZERO)
    val previewUsdcAvailable = previewSession.wallet.availableUsdc
    val previewSpendableUsdc = if (previewSession.wallet.funded) {
        (previewUsdcAvailable - predictionSession.ledger.localUsdcNetFundingUsd).max(BigDecimal.ZERO)
    } else BigDecimal.ZERO
    val previewSpendableSol = previewSession.wallet.availableSol
    val previewSpendableSolUsd = previewSession.wallet.availableSolUsd
    val previewSolPriceUsd = PreviewWalletState.INCOMING_SOL_USD
        .divide(PreviewWalletState.INCOMING_SOL, 6, RoundingMode.HALF_UP)
    val previewWalletBalanceUsd = previewSession.wallet.availableSolUsd + previewSpendableUsdc
    val practiceUsdcAvailable = if (BuildConfig.DEBUG) {
        previewSpendableUsdc
    } else {
        (BigDecimal("100.00") - predictionSession.ledger.localUsdcNetFundingUsd).max(BigDecimal.ZERO)
    }
    val accountNotice = when {
        authenticatedUser?.walletsReady != true -> walletNotice
        walletPortfolio.phase == WalletBalancePhase.PARTIAL ||
            walletPortfolio.phase == WalletBalancePhase.UNAVAILABLE -> walletPortfolio.notice
        else -> ""
    }
    val visibleWalletTransactions: List<WalletTransaction> = remember(
        walletActivity, requestedActivityWallets, devnetActivity, mainSolanaAddress,
    ) {
        walletActivity?.takeIf { it.wallets == requestedActivityWallets }?.transactions.orEmpty() +
            devnetActivity?.walletTransactionsFor(mainSolanaAddress).orEmpty()
    }
    val visibleDevnetSol = devnetActivity?.takeIf { it.walletAddress == mainSolanaAddress }
        ?.balanceSol

    BackHandler(walletPage != null) { walletPage = null }
    BackHandler(walletPage == null && transferHistoryVisible) { transferHistoryVisible = false }
    BackHandler(walletPage == null && !transferHistoryVisible && meteoraRoute.selectedToken == null && destination != StockDestination.Markets) { model.stockFlow.goBack() }
    BackHandler(walletPage == null && !transferHistoryVisible && meteoraRoute.selectedToken == null && destination == StockDestination.Markets && rootTab != RootTab.Home) { model.selectTab(RootTab.Home) }
    BackHandler(walletPage == null && !transferHistoryVisible && accountCenter) { model.hideAccountCenter() }
    BackHandler(predictionBalanceVisible) { predictionBalanceVisible = false }
    BackHandler(meteoraRoute.selectedToken != null) { meteoraRoute.selectedToken = null }
    BackHandler(selectedCommunityTokenMint != null) {
        if (communityBuyVisible) communityBuyVisible = false
        else if (communitySellVisible) communitySellVisible = false
        else if (communityChatVisible) communityChatVisible = false
        else selectedCommunityTokenMint = null
    }

    val routeBackground = when {
        route is VisualRoute.Root && rootTab == RootTab.Predictions -> androidx.compose.ui.graphics.Color.Black
        route is VisualRoute.Root && rootTab == RootTab.Home -> androidx.compose.ui.graphics.Color.Black
        route == VisualRoute.PredictionBalance -> androidx.compose.ui.graphics.Color.Black
        route == VisualRoute.LaunchToken -> androidx.compose.ui.graphics.Color.Black
        route is VisualRoute.Root -> WalletStyle.Background
        route is VisualRoute.Detail || route is VisualRoute.MeteoraDetail ||
            route is VisualRoute.CommunityTokenDetail || route is VisualRoute.CommunityChat ||
            route is VisualRoute.CommunityBuy || route is VisualRoute.CommunitySell ->
            androidx.compose.ui.graphics.Color.Black
        route == VisualRoute.Wallet(WalletPage.RECEIVE) -> androidx.compose.ui.graphics.Color.Black
        else -> P.Background
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(routeBackground)) {
        CompositionLocalProvider(LocalReferenceScale provides (maxWidth.value / 392f)) {
            AnimatedContent(
                targetState = route,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                contentKey = { if (it is VisualRoute.Root) "root" else it },
                transitionSpec = {
                    val direction = if (targetState.navigationDepth() < initialState.navigationDepth()) -1 else 1
                    val motion = tween<IntOffset>(ReferenceMotion.RouteMillis, easing = ReferenceMotion.Easing)
                    val opacity = tween<Float>(ReferenceMotion.RouteMillis, easing = ReferenceMotion.Easing)
                    (slideInHorizontally(motion) { direction * it / 4 } + fadeIn(opacity)) togetherWith
                        (slideOutHorizontally(motion) { -direction * it / 4 } + fadeOut(opacity))
                },
                label = "reference-screen-route",
            ) { visible ->
                Box(Modifier.fillMaxSize().background(routeBackground)) {
                    when (visible) {
                        is VisualRoute.CommunityBuy -> {
                            CommunityTokenBuyScreen(
                                tokenName = TOKEN_LAUNCH_UI_PREVIEW_NAME,
                                symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL,
                                artworkUri = TOKEN_LAUNCH_UI_PREVIEW_ARTWORK_URI,
                                availableSol = previewSpendableSol,
                                availableUsdc = previewSpendableUsdc,
                                solPriceUsd = previewSolPriceUsd,
                                onClose = { communityBuyVisible = false; communityBuyError = null },
                                onConfirm = { spentUsd, receivedTokens, payAsset, paidAmount ->
                                    if (visible.mintAddress == spxtrPreviewMint &&
                                        spxtrPreviewReceivedTokensUsd(spentUsd) == receivedTokens) {
                                        buyCommunityToken(visible.mintAddress, spentUsd,
                                            payAsset, paidAmount)
                                    } else communityBuyError = "Couldn't prepare this buy. Try again."
                                },
                                processing = communityBuyInProgress,
                                error = communityBuyError,
                            )
                        }
                        is VisualRoute.CommunitySell -> {
                            val purchased = communityScannedPurchases.firstOrNull {
                                it.mintAddress == visible.mintAddress
                            }
                            CommunityTokenSellScreen(
                                tokenName = TOKEN_LAUNCH_UI_PREVIEW_NAME,
                                symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL,
                                artworkUri = TOKEN_LAUNCH_UI_PREVIEW_ARTWORK_URI,
                                heldTokens = purchased?.receivedTokens ?: BigDecimal.ZERO,
                                solPriceUsd = previewSolPriceUsd,
                                onClose = { communitySellVisible = false; communitySellError = null },
                                onConfirm = { soldTokens, proceedsUsd ->
                                    sellCommunityToken(visible.mintAddress, soldTokens, proceedsUsd)
                                },
                                processing = communitySellInProgress,
                                error = communitySellError,
                            )
                        }
                        is VisualRoute.CommunityTokenDetail -> {
                            val isSpxtr = visible.mintAddress == spxtrPreviewMint
                            val draft = tokenLaunchUi.communityDraft.takeIf {
                                tokenLaunchUi.communityMint == visible.mintAddress
                            }
                            val purchased = communityScannedPurchases.firstOrNull {
                                it.mintAddress == visible.mintAddress
                            }
                            val marketToken = meteoraTokens.firstOrNull {
                                it.mintAddress == visible.mintAddress
                            }
                            val name = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_NAME else
                                draft?.name?.takeIf(String::isNotBlank)
                                    ?: purchased?.name ?: marketToken?.name ?: "Creator token"
                            val symbol = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL else
                                draft?.symbol?.takeIf(String::isNotBlank)
                                    ?: purchased?.symbol ?: marketToken?.symbol ?: ""
                            CommunityTokenDetailScreen(
                                tokenName = name,
                                symbol = symbol,
                                artworkUri = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_ARTWORK_URI else
                                    draft?.imageUri ?: purchased?.imageUrl ?: marketToken?.imageUrl,
                                description = draft?.description,
                                owned = purchased != null,
                                allowRepeatBuy = isSpxtr,
                                showSell = isSpxtr && purchased?.receivedTokens?.signum() == 1,
                                sellInProgress = communitySellInProgress,
                                canPost = isSpxtr && hasChristmasChatAccess,
                                chatIsLive = isSpxtr,
                                showLocalTrend = isSpxtr,
                                showBuy = BuildConfig.DEBUG && (isSpxtr ||
                                    communityMintValidation?.takeIf {
                                        it.mintAddress == visible.mintAddress
                                    }?.isValid == true),
                                buyInProgress = communityBuyInProgress,
                                actionError = communityBuyError,
                                statusMessage = if (isSpxtr) null else when (communityMintValidation?.status) {
                                    null -> "Checking token…"
                                    CommunityTokenMintStatus.VALID -> null
                                    CommunityTokenMintStatus.INVALID -> "This QR code is not a token mint."
                                    CommunityTokenMintStatus.AUTH_REQUIRED -> "Sign in again to continue."
                                    CommunityTokenMintStatus.UNAVAILABLE -> "Couldn't verify this token right now."
                                },
                                launchDraft = draft,
                                marketToken = marketToken.takeUnless { isSpxtr },
                                localPurchase = purchased,
                                avatarVariant = avatarVariant,
                                onClose = { selectedCommunityTokenMint = null },
                                onOpenChat = { communityChatVisible = true },
                                onBuy = {
                                    if (isSpxtr) {
                                        communityBuyError = null
                                        communityBuyVisible = true
                                    } else buyCommunityToken(visible.mintAddress)
                                },
                                onSell = {
                                    if (isSpxtr && purchased?.receivedTokens?.signum() == 1) {
                                        communitySellError = null
                                        communitySellVisible = true
                                    }
                                },
                            )
                        }
                        is VisualRoute.CommunityChat -> {
                            if (visible.mintAddress == spxtrPreviewMint) {
                                val localDemoChat = BuildConfig.DEBUG
                                val draft = tokenLaunchUi.communityDraft.takeIf {
                                    tokenLaunchUi.communityMint == visible.mintAddress
                                }
                                CommunityChatScreen(
                                    tokenName = TOKEN_LAUNCH_UI_PREVIEW_NAME,
                                    symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL,
                                    artworkUri = TOKEN_LAUNCH_UI_PREVIEW_ARTWORK_URI,
                                    isSpxtrRoom = true,
                                    avatarVariant = avatarVariant,
                                    messages = communityChatFeed(
                                        if (localDemoChat) emptyList() else chatState.messages,
                                        localCommunityChatMessages),
                                    canPost = hasChristmasChatAccess && mainSolanaAddress != null,
                                    chatReady = true,
                                    loading = !localDemoChat && chatState.loading,
                                    error = if (localDemoChat) null else
                                        (chatState.error?.message ?: if (chatRepository == null)
                                            "Live Chat is unavailable. Try again shortly." else null),
                                    sending = false,
                                    onClose = { communityChatVisible = false },
                                    onSend = { text ->
                                        val userId = authenticatedUserId
                                        val walletAddress = mainSolanaAddress
                                        if (userId == null || walletAddress == null ||
                                            !hasChristmasChatAccess || localUiClearInProgress) false
                                        else {
                                            val saved = CommunityLocalChatStore.append(context, userId,
                                                walletAddress, text)
                                            if (saved != null) localCommunityChatMessages = saved
                                            saved != null
                                        }
                                    },
                                    onRetry = {
                                        authenticatedUserId?.takeUnless { localDemoChat }?.let { userId ->
                                            launchScope.launch {
                                                chatRepository?.refresh(userId)
                                            }
                                        }
                                    },
                                )
                            } else {
                                val purchased = communityScannedPurchases.firstOrNull {
                                    it.mintAddress == visible.mintAddress
                                }
                                CommunityChatPlaceholderScreen(
                                    tokenName = purchased?.name?.takeIf(String::isNotBlank)
                                        ?: "Creator token",
                                    onClose = { communityChatVisible = false },
                                )
                            }
                        }
                        VisualRoute.LaunchToken -> key(tokenLaunchUi.attemptNonce) {
                            LaunchTokenFlow(
                                onClose = { launchTokenVisible = false },
                                initialDraft = tokenLaunchUi.activeDraft?.let { saved ->
                                    if (BuildConfig.DEBUG && saved.symbol.equals("SPXTR", ignoreCase = true))
                                        saved.copy(symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL)
                                    else saved
                                } ?: LaunchTokenDraft(),
                                executionState = tokenLaunchUi.executionState,
                                onAction = { action ->
                                    when (action) {
                                        is LaunchTokenAction.Create -> startTokenLaunch(action.draft)
                                        LaunchTokenAction.Retry -> tokenLaunchUi.activeDraft?.let(::startTokenLaunch)
                                        LaunchTokenAction.Edit -> tokenLaunchUi.edit()
                                        LaunchTokenAction.Cancel -> tokenLaunchUi.reset()
                                    }
                                },
                            )
                        }
                        VisualRoute.PredictionBalance -> PredictionBalanceScreen(
                            predictionBalanceUsd = predictionSession.ledger.availableQuoteUsd,
                            onBack = { predictionBalanceVisible = false },
                            onRefresh = {
                                previewUserId?.let { userId ->
                                    previewSession.wallet = PreviewWalletStore.read(context, userId)
                                }
                            },
                            onSubmitTransfer = { direction, amount, requestId ->
                                val next = runCatching {
                                    when (direction) {
                                        PredictionBalanceTransferDirection.ADD ->
                                            predictionSession.ledger.creditLocalUsdc(requestId, amount,
                                                System.currentTimeMillis())
                                        PredictionBalanceTransferDirection.WITHDRAW ->
                                            predictionSession.ledger.withdrawLocalUsdc(requestId, amount,
                                                System.currentTimeMillis())
                                    }
                                }.getOrNull()
                                val saved = next != null && predictionSession.save(next)
                                predictionSession.accountedTransfer = PredictionBalanceTransferStatus(
                                    requestId = requestId,
                                    phase = if (saved) PredictionBalanceTransferPhase.CONFIRMED
                                        else PredictionBalanceTransferPhase.FAILED,
                                    message = if (saved) null else "Practice balance could not be updated.",
                                )
                            },
                            performanceUsd = predictionSession.ledger.lifetimeRealizedPnlUsd,
                            performancePercent = predictionSession.ledger.lifetimePnlPercent(),
                            history = predictionSession.ledger.balanceHistoryItems(),
                            transferStatus = predictionSession.accountedTransfer,
                            sourceUsdcAvailable = practiceUsdcAvailable,
                        )
                        VisualRoute.AccountCenter -> AccountCenterScreen(model::hideAccountCenter)
                        VisualRoute.TransferHistory -> TransferHistoryScreen(
                            verifiedUserId = authenticatedUserId.orEmpty(),
                            records = transferHistory,
                            walletTransactions = visibleWalletTransactions,
                            purchaseTransactions = purchaseActivity?.transactions.orEmpty(),
                            predictionTransfers = predictionSession.ledger.mainTransferItems(),
                            communityActivity = communityUiActivity,
                            onPredictionBalance = {
                                transferHistoryVisible = false
                                model.selectTab(RootTab.Predictions)
                                predictionBalanceVisible = true
                            },
                            walletActivityStatus = walletActivity?.status,
                            stockSymbols = stocks.associate { it.id.value to it.symbol },
                            storageHealthy = transferHistoryStorageHealthy,
                            onBack = { transferHistoryVisible = false },
                            previewSpcxxPosition = previewSession.spcxxPosition,
                            // This is the user's older SPCXx swap, not a signature created by the local UI rehearsal.
                            onPreviewSpcxxTransaction = {
                                openPurchaseSolscan(context, SPCXX_PREVIEW_TRANSACTION)
                            },
                            previewWalletState = previewSession.wallet,
                            onClearLocalUiFlow = ::clearAllLocalUiFlow,
                        )
                        is VisualRoute.Wallet -> when (visible.page) {
                            WalletPage.RECEIVE -> ReceiveWalletScreen(
                                wallets = authenticatedUser?.wallets.orEmpty(),
                                onBack = { walletPage = null },
                            )
                            WalletPage.SEND -> SendWalletScreen(
                                userId = authenticatedUserId.orEmpty(),
                                wallets = authenticatedUser?.wallets.orEmpty(),
                                onBack = { walletPage = null },
                                onPrepare = onPrepareTransfer,
                                onSubmit = onSubmitTransfer,
                                onCheckStatus = onCheckTransferStatus,
                            )
                        }
                        is VisualRoute.Detail -> stocks.firstOrNull { it.id == visible.stockId }?.let { selected ->
                            var selectedRange by remember(selected.id) { mutableStateOf(ChartRange.ONE_DAY) }
                            StockDetailScreen(
                                stock = selected,
                                onBack = { model.stockFlow.goBack() },
                                onBuy = { model.openOrder(OrderSide.BUY) },
                                onSell = { model.openOrder(OrderSide.SELL) },
                                hasPosition = holdings.any { it.stock.id == selected.id &&
                                    it.quantity.hasSellableStockQuantity() } ||
                                    previewHoldings.any { it.stock.id == selected.id &&
                                        it.quantity.hasSellableStockQuantity() } ||
                                    (legacySpcxxHolding?.let { it.stock.id == selected.id &&
                                        it.quantity.hasSellableStockQuantity() } == true),
                                isWatched = selected.id in watched,
                                onWatch = { model.toggleWatch(selected.id) },
                                detail = if (model.isLive) model.liveDetail(selected) else fixtures.detail(selected.id),
                                onRangeSelected = { range -> selectedRange = range; model.loadChart(selected.id, range) },
                                priceNotice = if (model.isLive) model.priceNotice(selected) else null,
                                chartMessage = if (model.isLive) connection.chartMessages[selected.id to selectedRange]
                                    ?: if (selected.charts[selectedRange].isNullOrEmpty()) "Loading price history…" else null
                                    else null,
                                chartContext = if (model.isLive) connection.chartContexts[selected.id to selectedRange] else null,
                                useQuoteChangeForAllRanges = !model.isLive,
                                onExit = { model.clearActiveChart(selected.id) },
                            )
                        }
                        is VisualRoute.MeteoraDetail -> meteoraRoute.selectedToken?.let { selected ->
                            MeteoraTokenDetailScreen(
                                token = selected,
                                chart = meteoraRoute.chart,
                                chartLoading = meteoraRoute.chartLoading,
                                chartError = meteoraRoute.chartError,
                                description = meteoraRoute.description,
                                descriptionLoading = meteoraRoute.descriptionLoading,
                                selectedRange = meteoraRoute.chartRange,
                                onRangeSelected = { range ->
                                    meteoraRoute.chart = if (range == ChartRange.ONE_DAY)
                                        meteoraWarmup.cachedChartFor(selected) else null
                                    meteoraRoute.chartLoading = meteoraRoute.chart == null
                                    meteoraRoute.chartRange = range
                                },
                                onBack = { meteoraRoute.selectedToken = null },
                                onRetry = { meteoraRoute.chartRefreshNonce++ },
                            )
                        }
                        is VisualRoute.Order -> stocks.firstOrNull { it.id == visible.stockId }?.let { selected ->
                            val previewBuy = visible.side == OrderSide.BUY && previewStockAsset(selected) != null
                            VisibleMarketInterest(model, "order", setOf(selected.id))
                            if (!previewBuy) {
                                LaunchedEffect(model, selected.id, visible.side, authenticatedUserId,
                                    authenticatedUser?.walletsReady) {
                                    model.bindPurchase(selected.takeIf { model.isLive }, visible.side)
                                }
                                DisposableEffect(model, selected.id, visible.side) {
                                    onDispose { model.bindPurchase(null, visible.side) }
                                }
                            }
                            TradeScreen(
                                stock = selected,
                                side = visible.side,
                                initialAmountUsd = "0",
                                onBack = { model.stockFlow.goBack() },
                                paymentBalance = if (model.isLive) null else portfolio.paymentBalance,
                                onChooseStock = {},
                                stockLogoReference = if (model.isLive) selected.logo else fixtures.detail(selected.id).headerLogo,
                                stockBalance = holdings.firstOrNull { it.stock.id == selected.id }?.quantity,
                                unavailableReason = if (!previewBuy && model.isLive && authenticatedUser?.walletsReady != true)
                                    walletNotice else null,
                                purchaseState = if (previewBuy) previewStockTradeState(
                                    selected, previewSpendableSol, previewSpendableSolUsd)
                                    else purchaseState.takeIf { model.isLive && it.side == visible.side },
                                onSelectPaymentAsset = if (previewBuy) ({}) else model::selectPurchasePaymentAsset,
                                onSelectDestination = if (previewBuy) ({}) else model::selectPurchaseDestination,
                                onRequestQuote = if (previewBuy) ({}) else model::requestPurchaseQuote,
                                onPurchaseNow = if (previewBuy) ({}) else model::purchaseNow,
                                onExecutePurchase = if (previewBuy) ({}) else model::executePurchase,
                                onEditQuote = if (previewBuy) ({}) else model::editPurchaseQuote,
                                onRefreshPurchase = if (previewBuy) ({}) else model::refreshPurchaseOptions,
                                preview = if (previewBuy) previewStockTrade(selected,
                                    previewSpendableSol, previewSpendableSolUsd,
                                    previewSession.processingStockId == selected.id.value) else null,
                                onPreviewPurchase = { enteredAmount ->
                                    val terms = previewStockPurchase(selected, enteredAmount)
                                    if (previewBuy && previewSession.processingStockId == null &&
                                        previewSession.wallet.funded &&
                                        terms != null && previewSpendableSolUsd >= enteredAmount) {
                                        previewSession.pendingPurchase = terms.copy(purchaseId = UUID.randomUUID().toString())
                                        previewSession.processingStockId = selected.id.value
                                    }
                                },
                            )
                        }
                        is VisualRoute.Root -> RootTabsContent(
                            selectedTab = visible.tab,
                            model = model,
                            avatarVariant = avatarVariant,
                            onSearch = { tab ->
                                if (tab == RootTab.Markets && meteoraRoute.visible) {
                                    meteoraRoute.filterRequestNonce++
                                } else {
                                    model.selectTab(RootTab.Markets)
                                    marketOverlay = "search"
                                }
                            },
                            home = {
                                                    HomeTabContent(
                                                        state = HomeTabState(
                                                            balance = if (model.isLive) walletPortfolio.balanceUsd
                                                                else portfolio.balance,
                                                            walletAddress = mainSolanaAddress,
                                                            walletConnected = authenticatedUser?.walletsReady == true,
                                                            displayName = authenticatedUser?.displayName,
                                                            avatarVariant = avatarVariant,
                                                            transactions = filterHomeWalletTransactions(
                                                                transferHistory, authenticatedUserId,
                                                                verifiedWallets),
                                                            walletTransactions = visibleWalletTransactions,
                                                            purchaseTransactions = purchaseActivity?.transactions.orEmpty(),
                                                            previewSpcxxPosition = previewSession.spcxxPosition,
                                                            previewWalletState = previewSession.wallet,
                                                            predictionFundedUsd = predictionSession.ledger.availableQuoteUsd,
                                                            predictionTransfers = predictionSession.ledger.mainTransferItems(),
                                                            communityActivity = communityUiActivity,
                                                            walletActivityStatus = walletActivity?.status,
                                                            stockSymbols = stocks.associate { it.id.value to it.symbol },
                                                            unitPricesUsd = walletPortfolio.tokenHoldings.mapNotNull { token ->
                                                                token.unitPriceUsd?.let { token.assetId to it }
                                                            }.toMap(),
                                                            historyStorageHealthy = transferHistoryStorageHealthy,
                                                            balancePlaceholder = if (model.isLive)
                                                                walletPortfolio.balancePlaceholder else "—",
                                                        ),
                                                        onPreviewSpcxxTransaction = {
                                                            openPurchaseSolscan(context, SPCXX_PREVIEW_TRANSACTION)
                                                        },
                                                        onPredictionBalance = {
                                                            model.selectTab(RootTab.Predictions)
                                                            predictionBalanceVisible = true
                                                        },
                                                        onRefreshTransactions = {
                                                            previewUserId?.let { userId ->
                                                                previewSession.wallet = PreviewWalletStore.refresh(context, userId)
                                                            }
                                                            activity.requestRefresh()
                                                        },
                                                        onStocks = { model.selectTab(RootTab.Markets) },
                                                        onReceive = { walletPage = WalletPage.RECEIVE },
                                                        onSend = { walletPage = WalletPage.SEND },
                                                        onManage = { model.selectTab(RootTab.Account) },
                                                        onHistory = { transferHistoryVisible = true },
                                                        onClearLocalUiFlow = ::clearAllLocalUiFlow,
                                                    )
                            },
                            markets = {
                                                    MarketsTabContent(
                                                        model = model,
                                                        stocks = stocks,
                                                        watched = watched,
                                                        connection = connection,
                                                        query = marketQuery,
                                                        watchlistOnly = watchlistOnly,
                                                        meteoraTokens = meteoraTokens,
                                                        meteoraLoading = meteoraLoading,
                                                        meteoraError = meteoraError,
                                                        meteoraFilterRequestNonce = meteoraRoute.filterRequestNonce,
                                                        onFilter = { marketOverlay = "filters" },
                                                        onQueryChange = { marketQuery = it },
                                                        onMeteoraSelect = meteoraWarmup::requestRefreshIfStale,
                                                        onMeteoraRetry = meteoraWarmup::requestRefresh,
                                                        onMeteoraVisibilityChange = { meteoraRoute.visible = it },
                                                        onMeteoraToken = { token ->
                                                            meteoraRoute.chart = meteoraWarmup.cachedChartFor(token)
                                                            meteoraRoute.chartLoading = meteoraRoute.chart == null
                                                            meteoraRoute.chartError = null
                                                            meteoraRoute.description = null
                                                            meteoraRoute.descriptionLoading = true
                                                            meteoraRoute.chartRange = ChartRange.ONE_DAY
                                                            meteoraRoute.selectedToken = token
                                                        })
                            },
                            predictions = {
                                PantaPredictionScreen(
                                                    onBack = { model.selectTab(RootTab.Markets) },
                                                    api = pantaPredictionApi,
                                                    userId = authenticatedUserId,
                                                    walletAddress = mainSolanaAddress,
                                                    // Panta's cluster is not confirmed; do not label a mainnet
                                                    // wallet balance as spendable Panta-market USDC.
                                                    walletUsdc = null,
                                )
                            },
                            community = {
                                CommunityScreen(
                                                    onLaunchToken = ::openTokenLaunch,
                                                    onOpenTokenDetail = {
                                                        tokenLaunchUi.communityMint?.let(::openCommunityToken)
                                                    },
                                                    localDraft = tokenLaunchUi.communityDraft,
                                                    linkedMint = tokenLaunchUi.communityMint,
                                                    tokenOrigin = tokenLaunchUi.communityOrigin,
                                                    purchasedTokens = previouslyPurchasedCommunityTokens,
                                                    scannedTokens = communityScannedPurchases,
                                                    onTokenLinked = { mint ->
                                                        onCommunityTokenScanned?.invoke(mint)
                                                            ?: openCommunityToken(mint)
                                                    },
                                                    onOpenScannedToken = ::openCommunityToken,
                                                    onScannedTokenRemove = { clearAllLocalUiFlow() },
                                                    onLocalTokenRemove = ::clearAllLocalUiFlow,
                                                    onLocalTokenImageChosen = tokenLaunchUi::updateLocalCommunityImage,
                                )
                            },
                            account = {
                                                    AccountTabContent(
                                                        model = model,
                                                        state = AccountTabState(
                                                            liveBalance = if (model.isLive) walletPortfolio.balanceUsd
                                                                else portfolio.balance,
                                                            tokenHoldings = if (model.isLive) walletPortfolio.tokenHoldings
                                                                else emptyList(),
                                                            holdings = holdings,
                                                            previewHoldings = previewHoldings,
                                                            previewCommunityTokens = communityScannedPurchases,
                                                            previewSpcxxHolding = legacySpcxxHolding,
                                                            previewWalletState = previewSession.wallet,
                                                            previewWalletBalanceUsd = previewWalletBalanceUsd,
                                                            previewHoldingUsd = previewHoldingUsd,
                                                            previewSpendableUsdc = previewSpendableUsdc,
                                                            devnetSol = if (model.isLive) visibleDevnetSol else null,
                                                            accountNotice = if (model.isLive) accountNotice else null,
                                                            balancePlaceholder = if (model.isLive)
                                                                walletPortfolio.balancePlaceholder else "—",
                                                            portfolioPhase = if (model.isLive) walletPortfolio.phase
                                                                else WalletBalancePhase.READY,
                                                            walletConnected = !model.isLive ||
                                                                authenticatedUser?.walletsReady == true,
                                                        ),
                                                        onPreviewOpenTransaction = { holding ->
                                                            if (isRobinhoodTeslaPreviewStock(holding.stock)) {
                                                                openRobinhoodTeslaTransaction(context)
                                                            }
                                                        },
                                                        onPreviewSpcxxOpenTransaction = {
                                                            openPurchaseSolscan(context, SPCXX_PREVIEW_TRANSACTION)
                                                        },
                                                        onPreviewSpcxxRemove = ::clearAllLocalUiFlow,
                                                        onPreviewRemoveAll = ::clearAllLocalUiFlow,
                                                        onPreviewCommunityToken = { mint ->
                                                            model.selectTab(RootTab.Community)
                                                            openCommunityToken(mint)
                                                        },
                                                        onToken = { token ->
                                                            portfolioTokenSolscanUrl(token, mainSolanaAddress)
                                                                ?.let { openPortfolioSolscan(context, it) }
                                                        },
                                                        onDevnetSol = {
                                                            devnetSolSolscanUrl(mainSolanaAddress)
                                                                ?.let { openPortfolioSolscan(context, it) }
                                                        },
                                                    )
                            },
                        )
                    }
                }
            }
            marketOverlay?.let {
                MarketControlsDialog(query = marketQuery,
                    onChange = { marketQuery = it },
                    watchlistOnly = watchlistOnly,
                    onWatchlistOnlyChange = { watchlistOnly = it },
                    focusSearch = marketOverlay == "search",
                    onDismiss = { marketOverlay = null })
            }
            buyNotice?.let { notice ->
                StockBuySuccessNotice(
                    stock = notice.stock,
                    solscanUrl = notice.solscanUrl,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = rd(9f)),
                )
            }
            previewSession.buyNotice?.let { stock ->
                StockBuySuccessNotice(
                    stock = stock,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = rd(9f)),
                )
            }
            if (communityBuyNoticeId != null) {
                CommunityBuySuccessNotice(
                    tokenName = TOKEN_LAUNCH_UI_PREVIEW_NAME,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = rd(9f)),
                )
            }
        }
    }
}

@Composable
private fun RootTabsContent(
    selectedTab: RootTab,
    model: ElevenViewModel,
    avatarVariant: Int,
    onSearch: (RootTab) -> Unit,
    home: @Composable () -> Unit,
    markets: @Composable () -> Unit,
    predictions: @Composable () -> Unit,
    community: @Composable () -> Unit,
    account: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = selectedTab,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        transitionSpec = {
            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
            val duration = if (targetState == RootTab.Predictions || initialState == RootTab.Predictions)
                ReferenceMotion.RouteMillis else ReferenceMotion.SectionMillis
            val motion = tween<IntOffset>(duration, easing = ReferenceMotion.Easing)
            (slideInHorizontally(motion) { it * direction } togetherWith
                slideOutHorizontally(motion) { -it * direction }).using(null)
        }, label = "reference-root-tabs",
    ) { tab ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (tab != RootTab.Predictions && tab != RootTab.Community) {
                    SharedMainHeader(avatarVariant,
                        onAccount = model::showAccountCenter,
                        onSearch = { onSearch(tab) })
                    if (tab == RootTab.Account) {
                        PortfolioAnalyticsSection()
                    }
                    if (tab != RootTab.Account) {
                        Spacer(Modifier.height(rd(16f)))
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        RootTab.Home -> home()
                        RootTab.Markets -> markets()
                        RootTab.Predictions -> predictions()
                        RootTab.Community -> community()
                        RootTab.Account -> account()
                    }
                }
            }
            if (tab != RootTab.Predictions) {
                StockDock(tab, model::selectTab,
                    Modifier.align(Alignment.BottomCenter), avatarVariant)
            }
        }
    }
}

@Composable
private fun HomeTabContent(
    state: HomeTabState,
    onPreviewSpcxxTransaction: () -> Unit,
    onPredictionBalance: () -> Unit,
    onRefreshTransactions: () -> Unit,
    onStocks: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onManage: () -> Unit,
    onHistory: () -> Unit,
    onClearLocalUiFlow: () -> Unit,
) {
    HomeScreen(
        balance = state.balance,
        walletAddress = state.walletAddress,
        walletConnected = state.walletConnected,
        displayName = state.displayName,
        profileAvatarVariant = state.avatarVariant,
        transactions = state.transactions,
        walletTransactions = state.walletTransactions,
        purchaseTransactions = state.purchaseTransactions,
        previewSpcxxPosition = state.previewSpcxxPosition,
        onPreviewSpcxxTransaction = onPreviewSpcxxTransaction,
        previewWalletState = state.previewWalletState,
        previewPredictionFundedUsd = state.predictionFundedUsd,
        predictionTransfers = state.predictionTransfers,
        communityActivity = state.communityActivity,
        onPredictionBalance = onPredictionBalance,
        onRefreshTransactions = onRefreshTransactions,
        onClearLocalUiFlow = onClearLocalUiFlow,
        walletActivityStatus = state.walletActivityStatus,
        stockSymbols = state.stockSymbols,
        unitPricesUsd = state.unitPricesUsd,
        historyStorageHealthy = state.historyStorageHealthy,
        onStocks = onStocks,
        onReceive = onReceive,
        onSend = onSend,
        onManage = onManage,
        onHistory = onHistory,
        balancePlaceholder = state.balancePlaceholder,
        showTopPanel = false,
    )
}

@Composable
private fun MarketsTabContent(
    model: ElevenViewModel,
    stocks: List<Stock>,
    watched: Set<StockId>,
    connection: MarketConnectionState,
    query: MarketQuery,
    watchlistOnly: Boolean,
    meteoraTokens: List<MeteoraMarketToken>,
    meteoraLoading: Boolean,
    meteoraError: String?,
    meteoraFilterRequestNonce: Int,
    onFilter: () -> Unit,
    onQueryChange: (MarketQuery) -> Unit,
    onMeteoraSelect: () -> Unit,
    onMeteoraRetry: () -> Unit,
    onMeteoraVisibilityChange: (Boolean) -> Unit,
    onMeteoraToken: (MeteoraMarketToken) -> Unit,
) {
    // Only derive the full market table while its tab is on screen.
    val rows = remember(model, stocks, watched, connection.catalog) {
        if (model.isLive) model.liveRows(stocks, watched)
        else model.fixtures.rows(stocks, watched)
    }
    MarketsScreen(
        rows, model::openStock, model::toggleWatch,
        onFilter = onFilter,
        statusMessage = when {
            !model.isLive -> null
            connection.catalog == null && !connection.offline && !connection.refreshFailed -> ""
            else -> connection.message
        },
        onRetry = null,
        query = query,
        watchlistOnly = watchlistOnly,
        onQueryChange = onQueryChange,
        onVisibleStockIds = model::setVisibleStockIds,
        meteoraTokens = meteoraTokens,
        meteoraLoading = meteoraLoading,
        meteoraError = meteoraError,
        onMeteoraSelect = onMeteoraSelect,
        onMeteoraRetry = onMeteoraRetry,
        onMeteoraVisibilityChange = onMeteoraVisibilityChange,
        meteoraFilterRequestNonce = meteoraFilterRequestNonce,
        onMeteoraToken = onMeteoraToken,
    )
}

@Composable
private fun AccountTabContent(
    model: ElevenViewModel,
    state: AccountTabState,
    onPreviewOpenTransaction: (PreviewStockHolding) -> Unit,
    onPreviewSpcxxOpenTransaction: () -> Unit,
    onPreviewSpcxxRemove: () -> Unit,
    onPreviewRemoveAll: () -> Unit,
    onPreviewCommunityToken: (String) -> Unit,
    onToken: (LiveWalletTokenHolding) -> Unit,
    onDevnetSol: () -> Unit,
) {
    VisibleMarketInterest(model, "account", state.holdings.map { it.stock.id }.toSet())
    val hasPreviewBalance = BuildConfig.DEBUG &&
        (state.previewWalletState.funded || state.previewHoldings.isNotEmpty() ||
            state.previewSpcxxHolding != null)
    AccountScreen(
        balance = if (hasPreviewBalance) (state.liveBalance ?: BigDecimal.ZERO)
            .add(state.previewWalletBalanceUsd).add(state.previewHoldingUsd)
        else state.liveBalance,
        tokenHoldings = state.tokenHoldings,
        previewHoldings = state.previewHoldings,
        previewCommunityTokens = state.previewCommunityTokens,
        onPreviewCommunityToken = onPreviewCommunityToken,
        onPreviewOpenTransaction = onPreviewOpenTransaction,
        previewSpcxxHolding = state.previewSpcxxHolding,
        onPreviewSpcxxOpenTransaction = onPreviewSpcxxOpenTransaction,
        onPreviewSpcxxRemove = onPreviewSpcxxRemove,
        onPreviewRemoveAll = onPreviewRemoveAll,
        previewSolBalance = state.previewWalletState.availableSol.takeIf { state.previewWalletState.funded },
        previewSolValueUsd = state.previewWalletState.availableSolUsd.takeIf { state.previewWalletState.funded },
        previewUsdcBalance = state.previewSpendableUsdc.takeIf { state.previewWalletState.funded },
        onPreviewTokenRemove = onPreviewRemoveAll,
        devnetSol = state.devnetSol,
        holdings = state.holdings,
        onStock = model::openStock,
        onToken = onToken,
        onDevnetSol = onDevnetSol,
        accountNotice = state.accountNotice,
        balancePlaceholder = state.balancePlaceholder,
        portfolioPhase = state.portfolioPhase,
        walletConnected = state.walletConnected,
        compactTopSpacing = true,
    )
}


/** Animated tabs own independent interests, so outgoing cleanup cannot erase the new tab. */
@Composable
private fun VisibleMarketInterest(model: ElevenViewModel, owner: String, ids: Set<StockId>) {
    LaunchedEffect(model, owner, ids) { model.setVisibleStockIds(owner, ids) }
    DisposableEffect(model, owner) { onDispose { model.setVisibleStockIds(owner, emptySet()) } }
}

/** The reference dock uses separate circular buttons and accessible tab labels. */
@Composable
internal fun StockDock(selected: RootTab, onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier, avatarVariant: Int = 0) {
    Row(
        modifier.padding(bottom = rd(18f)).clip(CircleShape).background(WalletStyle.Panel)
            .padding(rd(5f)),
        horizontalArrangement = Arrangement.spacedBy(rd(6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(RootTab.Home, RootTab.Markets, RootTab.Predictions, RootTab.Community, RootTab.Account).forEach { tab ->
            val active = tab == selected
            Box(
                Modifier.size(rd(58f)).clip(CircleShape)
                    .background(if (active) WalletStyle.Cream else WalletStyle.Circle)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics {
                        contentDescription = when (tab) {
                            RootTab.Home -> "Home"
                            RootTab.Markets -> "Stocks"
                            RootTab.Predictions -> "Predictions"
                            RootTab.Community -> "Community"
                            RootTab.Account -> "Account"
                        }
                        this.selected = active
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (tab == RootTab.Account) {
                    ProfileAvatar(avatarVariant, Modifier.fillMaxSize())
                } else {
                    RefIcon(when (tab) {
                        RootTab.Home -> "walletHome"
                        RootTab.Markets -> "walletMarkets"
                        RootTab.Predictions -> "walletActivity"
                        RootTab.Community -> "community"
                        RootTab.Account -> "walletProfile"
                    }, 25f, when {
                        active -> WalletStyle.Ink
                        else -> WalletStyle.Cream.copy(alpha = .7f)
                    })
                }
            }
        }
    }
}
