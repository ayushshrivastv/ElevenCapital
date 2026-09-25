package com.elevencapital.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.disabled
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
import com.elevencapital.app.auth.EntrySurface
import com.elevencapital.app.auth.entrySurface
import com.elevencapital.app.wallet.PreparedTransfer
import com.elevencapital.app.wallet.TransferDraft
import com.elevencapital.app.wallet.TransferStatusObservation
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.app.wallet.filterHomeWalletTransactions
import com.elevencapital.app.wallet.WalletSubmission
import com.elevencapital.app.data.WalletBalancePhase
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseRouteState
import com.elevencapital.app.data.CompletedPurchasesClient
import com.elevencapital.app.data.CompletedPurchasesSnapshot
import com.elevencapital.app.data.WalletTransactionsClient
import com.elevencapital.app.data.WalletTransactionsSnapshot
import com.elevencapital.app.data.SolanaDevnetClient
import com.elevencapital.app.data.SolanaDevnetSnapshot
import com.elevencapital.app.data.WalletTransaction
import com.elevencapital.app.data.activityWallets
import com.elevencapital.app.data.walletTransactionsFor
import com.elevencapital.core.stock.flow.OrderSide
import com.elevencapital.core.stock.flow.StockDestination
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private sealed interface VisualRoute {
    data class Root(val tab: RootTab) : VisualRoute
    data class Detail(val stockId: StockId) : VisualRoute
    data class Order(val stockId: StockId, val side: OrderSide) : VisualRoute
    data object AccountCenter : VisualRoute
    data class Wallet(val page: WalletPage) : VisualRoute
    data object TransferHistory : VisualRoute
}

private fun VisualRoute.navigationDepth(): Int = when (this) {
    is VisualRoute.Root -> 0
    is VisualRoute.Order -> 2
    else -> 1
}

private data class CompletedBuyNotice(val quoteId: String, val stock: Stock, val solscanUrl: String)

@Composable
fun ElevenApp(
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
    transferHistory: List<TransferJournalRecord> = emptyList(),
    transferHistoryStorageHealthy: Boolean = true,
) {
    val authenticatedUser = authState?.takeIf { it.authenticated }
    val context = LocalContext.current
    val avatarVariant = remember(authenticatedUser?.userId) {
        profileAvatarVariant(context, authenticatedUser?.userId)
    }
    val verifiedWallets = authenticatedUser?.takeIf { it.walletsReady }?.wallets.orEmpty()
    val mainSolanaWallet = verifiedWallets.firstOrNull { it.chain == WalletChain.SOLANA }
    // Start public market data while the entry animation and device gate are still visible.
    // Wallet data is bound only to a verified identity and stays hidden until unlock.
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityWalletAddresses = verifiedWallets.map { it.chain to it.address }
    val requestedActivityWallets = remember(authenticatedUser?.userId, activityWalletAddresses) {
        activityWallets(verifiedWallets)
    }
    val activityClient = remember(model.isLive) {
        if (model.isLive) WalletTransactionsClient(BuildConfig.MARKET_DATA_URL) else null
    }
    val devnetClient = remember(model.isLive) {
        if (model.isLive) SolanaDevnetClient(BuildConfig.MARKET_DATA_URL) else null
    }
    val purchaseActivityClient = remember(model.isLive) {
        if (model.isLive) CompletedPurchasesClient(BuildConfig.MARKET_DATA_URL,
            (context.applicationContext as ElevenCapitalApplication).auth::freshPurchaseAccessToken) else null
    }
    var walletActivity by remember(authenticatedUser?.userId, requestedActivityWallets) {
        mutableStateOf<WalletTransactionsSnapshot?>(null)
    }
    var devnetActivity by remember(authenticatedUser?.userId, mainSolanaWallet?.address) {
        mutableStateOf<SolanaDevnetSnapshot?>(null)
    }
    var purchaseActivity by remember(authenticatedUser?.userId) {
        mutableStateOf<CompletedPurchasesSnapshot?>(null)
    }
    var activityRefreshNonce by remember(authenticatedUser?.userId) { mutableStateOf(0) }
    LaunchedEffect(transferHistory) {
        if (transferHistory.isNotEmpty()) activityRefreshNonce++
    }
    // One initial read can finish while the phone's credential prompt pauses the Activity.
    // Subsequent reads run only while the app is foregrounded.
    LaunchedEffect(lifecycleOwner, activityClient, authenticatedUser?.userId,
        requestedActivityWallets, activityRefreshNonce) {
        if (authenticatedUser?.userId == null || requestedActivityWallets.isEmpty()) return@LaunchedEffect
        val client = activityClient ?: return@LaunchedEffect
        suspend fun refresh() {
            val observation = try {
                client.activity(requestedActivityWallets)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (observation?.wallets == requestedActivityWallets) walletActivity = observation
        }
        refresh()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                delay(15_000)
                refresh()
            }
        }
    }
    LaunchedEffect(lifecycleOwner, devnetClient, authenticatedUser?.userId,
        mainSolanaWallet?.address, activityRefreshNonce) {
        if (authenticatedUser?.userId == null) return@LaunchedEffect
        val client = devnetClient ?: return@LaunchedEffect
        val address = mainSolanaWallet?.address ?: return@LaunchedEffect
        suspend fun refresh() {
            val observation = try {
                client.wallet(address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (observation?.walletAddress == address) devnetActivity = observation
        }
        refresh()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                delay(15_000)
                refresh()
            }
        }
    }
    LaunchedEffect(lifecycleOwner, purchaseActivityClient, authenticatedUser?.userId, activityRefreshNonce) {
        val userId = authenticatedUser?.userId ?: return@LaunchedEffect
        val client = purchaseActivityClient ?: return@LaunchedEffect
        suspend fun refresh() {
            val observation = try {
                client.activity(userId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (observation != null) purchaseActivity = observation
        }
        refresh()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                delay(15_000)
                refresh()
            }
        }
    }
    LaunchedEffect(model, lifecycleOwner) {
        if (model.isLive) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refreshWhileVisible()
        }
    }
    var boundUserId by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, lifecycleOwner, authenticatedUser?.userId,
        authenticatedUser?.walletsReady, authenticatedUser?.wallets) {
        model.setAuthenticatedUser(authenticatedUser?.userId)
        model.setWalletSession(authenticatedUser?.userId,
            authenticatedUser?.takeIf { it.walletsReady }?.wallets.orEmpty())
        boundUserId = authenticatedUser?.userId
        if (model.isLive && authenticatedUser?.walletsReady == true) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.refreshWalletPortfolioWhileVisible()
            }
        }
    }
    LaunchedEffect(model, authenticatedUser?.userId, deviceUnlockState.phase, deviceUnlockState.userId) {
        if (deviceUnlockState.phase == DeviceUnlockPhase.UNLOCKED &&
            deviceUnlockState.userId == authenticatedUser?.userId) model.finishWalletWarmup()
    }
    when (entrySurface(authState, deviceUnlockState)) {
        EntrySurface.SESSION_SHIELD -> {
            SecureSessionScreen()
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
    val accountCenter by model.accountCenter.collectAsStateWithLifecycle()
    val stocks by model.repository.catalog.collectAsStateWithLifecycle()
    val destination by model.stockFlow.destination.collectAsStateWithLifecycle()
    val rootTab by model.tab.collectAsStateWithLifecycle()
    val watched by model.watched.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val signal by model.signal.collectAsStateWithLifecycle()
    val walletPortfolio by model.walletPortfolio.collectAsStateWithLifecycle()
    val purchaseState by model.purchaseState.collectAsStateWithLifecycle()
    val previewUserId = authenticatedUser?.userId
    var previewWalletState by remember(previewUserId) {
        mutableStateOf(previewUserId?.let { PreviewWalletStore.read(context, it) } ?: PreviewWalletState())
    }
    var spcxxPreviewPosition by remember(previewUserId) {
        mutableStateOf(previewUserId?.takeIf { BuildConfig.DEBUG }
            ?.let { PreviewSpcxxHoldingStore.read(context, it) })
    }
    var previewProcessingStockId by remember(previewUserId) { mutableStateOf<String?>(null) }
    var previewBuyNotice by remember(previewUserId) { mutableStateOf<Stock?>(null) }
    LaunchedEffect(previewProcessingStockId, previewUserId) {
        val stockId = previewProcessingStockId ?: return@LaunchedEffect
        if (!BuildConfig.DEBUG || previewUserId == null) return@LaunchedEffect
        delay(2_000)
        val stock = model.repository.catalog.value.firstOrNull { it.id.value == stockId }
        val preview = stock?.let(::previewStockPurchase)
        val nextWallet = if (stock != null && preview != null) {
            PreviewWalletStore.recordPurchase(context, previewUserId, stockId, stock.symbol, preview.inputUsd)
        } else null
        previewProcessingStockId = null
        if (stock != null && preview != null && nextWallet != null) {
            previewWalletState = nextWallet
            if (stockId == SPCXX_PREVIEW_STOCK_ID) {
                spcxxPreviewPosition = PreviewSpcxxHoldingStore.set(context, previewUserId,
                    preview.receiveTokens, preview.receiveValueUsd)
            }
            previewBuyNotice = stock
            val activeOrder = model.stockFlow.destination.value as? StockDestination.Order
            if (activeOrder?.side == OrderSide.BUY && activeOrder.stockId.value == stockId) {
                model.stockFlow.goBack()
            }
        }
    }
    LaunchedEffect(previewBuyNotice) {
        val notice = previewBuyNotice ?: return@LaunchedEffect
        delay(4_500)
        if (previewBuyNotice == notice) previewBuyNotice = null
    }
    val completedBuy = purchaseState.takeIf {
        it.side == OrderSide.BUY && it.phase == PurchasePhase.COMPLETE &&
            it.status?.state == PurchaseRouteState.COMPLETED
    }
    val completedBuySolscanUrl = completedPurchaseSolscanUrl(completedBuy?.status)
    val completedBuyQuoteId = completedBuy?.status?.quoteId?.takeIf { completedBuySolscanUrl != null }
    var lastBuyNoticeQuoteId by remember(authenticatedUser?.userId) { mutableStateOf<String?>(null) }
    var buyNotice by remember(authenticatedUser?.userId) { mutableStateOf<CompletedBuyNotice?>(null) }
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
    var walletPage by remember(authenticatedUser?.userId) { mutableStateOf<WalletPage?>(null) }
    var transferHistoryVisible by remember(authenticatedUser?.userId) { mutableStateOf(false) }
    LaunchedEffect(model, authenticatedUser?.userId, purchaseState.phase, purchaseState.quote?.id) {
        val userId = authenticatedUser?.userId
        val quoteId = purchaseState.quote?.id
        if (purchaseState.phase == com.elevencapital.app.purchase.PurchasePhase.COMPLETE &&
            userId != null && quoteId != null) {
            model.refreshPortfolioAfterPurchase(userId, quoteId)
            activityRefreshNonce++
        }
    }
    val route = when {
        walletPage != null -> VisualRoute.Wallet(requireNotNull(walletPage))
        transferHistoryVisible -> VisualRoute.TransferHistory
        accountCenter -> VisualRoute.AccountCenter
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
    val previewHoldings = remember(stocks, previewWalletState.purchases, spcxxPreviewPosition) {
        if (BuildConfig.DEBUG) buildList {
            previewWalletState.purchases.forEach { purchase ->
                stocks.firstOrNull { it.id.value == purchase.stockId }?.let { stock ->
                    previewStockPurchase(stock)?.let { details ->
                        add(PreviewStockHolding(stock, details.receiveTokens, details.receiveValueUsd))
                    }
                }
            }
            if (none { it.stock.id.value == SPCXX_PREVIEW_STOCK_ID }) {
                spcxxPreviewPosition?.let { position ->
                    stocks.firstOrNull { it.id.value == SPCXX_PREVIEW_STOCK_ID }?.let { stock ->
                        add(PreviewStockHolding(stock, position.quantity, position.valueUsd))
                    }
                }
            }
        } else emptyList()
    }
    val previewHoldingUsd = previewHoldings.fold(BigDecimal.ZERO) { total, holding -> total + holding.valueUsd }
    val accountNotice = when {
        authenticatedUser?.walletsReady != true -> walletNotice
        walletPortfolio.phase == WalletBalancePhase.PARTIAL ||
            walletPortfolio.phase == WalletBalancePhase.UNAVAILABLE -> walletPortfolio.notice
        else -> ""
    }
    val visibleWalletTransactions: List<WalletTransaction> = remember(
        walletActivity, requestedActivityWallets, devnetActivity, mainSolanaWallet?.address,
    ) {
        walletActivity?.takeIf { it.wallets == requestedActivityWallets }?.transactions.orEmpty() +
            devnetActivity?.walletTransactionsFor(mainSolanaWallet?.address).orEmpty()
    }
    val visibleDevnetSol = devnetActivity?.takeIf { it.walletAddress == mainSolanaWallet?.address }
        ?.balanceSol

    BackHandler(walletPage != null) { walletPage = null }
    BackHandler(walletPage == null && transferHistoryVisible) { transferHistoryVisible = false }
    BackHandler(walletPage == null && !transferHistoryVisible && destination != StockDestination.Markets) { model.stockFlow.goBack() }
    BackHandler(walletPage == null && !transferHistoryVisible && destination == StockDestination.Markets && rootTab != RootTab.Home) { model.selectTab(RootTab.Home) }
    BackHandler(walletPage == null && !transferHistoryVisible && destination == StockDestination.Markets &&
        rootTab == RootTab.Trade && signal.selectedProfileId != null) { model.closeSignalProfile() }
    BackHandler(walletPage == null && !transferHistoryVisible && accountCenter) { model.hideAccountCenter() }

    val routeBackground = when {
        route is VisualRoute.Root -> WalletStyle.Background
        route == VisualRoute.Wallet(WalletPage.RECEIVE) -> androidx.compose.ui.graphics.Color(0xFF101010)
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
                        VisualRoute.AccountCenter -> AccountCenterScreen(model::hideAccountCenter)
                        VisualRoute.TransferHistory -> TransferHistoryScreen(
                            verifiedUserId = authenticatedUser?.userId.orEmpty(),
                            records = transferHistory,
                            walletTransactions = visibleWalletTransactions,
                            purchaseTransactions = purchaseActivity?.transactions.orEmpty(),
                            walletActivityStatus = walletActivity?.status,
                            stockSymbols = stocks.associate { it.id.value to it.symbol },
                            storageHealthy = transferHistoryStorageHealthy,
                            onBack = { transferHistoryVisible = false },
                            previewSpcxxPosition = spcxxPreviewPosition,
                            // This is the user's older SPCXx swap, not a signature created by the local UI rehearsal.
                            onPreviewSpcxxTransaction = {
                                openPurchaseSolscan(context, SPCXX_PREVIEW_TRANSACTION)
                            },
                            previewWalletState = previewWalletState,
                            onPreviewPurchaseTransaction = { purchase ->
                                val historicalUrl = when (purchase.stockId) {
                                    SPCXX_PREVIEW_STOCK_ID -> SPCXX_PREVIEW_TRANSACTION
                                    NIKE_PREVIEW_STOCK_ID -> NIKE_PREVIEW_TRANSACTION
                                    else -> null
                                }
                                historicalUrl?.let { openPurchaseSolscan(context, it) }
                            },
                        )
                        is VisualRoute.Wallet -> when (visible.page) {
                            WalletPage.RECEIVE -> ReceiveWalletScreen(
                                wallets = authenticatedUser?.wallets.orEmpty(),
                                onBack = { walletPage = null },
                            )
                            WalletPage.SEND -> SendWalletScreen(
                                userId = authenticatedUser?.userId.orEmpty(),
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
                        is VisualRoute.Order -> stocks.firstOrNull { it.id == visible.stockId }?.let { selected ->
                            val previewPurchase = selected.takeIf { visible.side == OrderSide.BUY }
                                ?.let(::previewStockPurchase)
                            val previewBuy = previewPurchase != null
                            VisibleMarketInterest(model, "order", setOf(selected.id))
                            if (!previewBuy) {
                                LaunchedEffect(model, selected.id, visible.side, authenticatedUser?.userId,
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
                                onBack = { model.stockFlow.goBack() },
                                paymentBalance = if (model.isLive) null else portfolio.paymentBalance,
                                onChooseStock = {},
                                stockLogoReference = if (model.isLive) selected.logo else fixtures.detail(selected.id).headerLogo,
                                stockBalance = holdings.firstOrNull { it.stock.id == selected.id }?.quantity,
                                unavailableReason = if (!previewBuy && model.isLive && authenticatedUser?.walletsReady != true)
                                    walletNotice else null,
                                purchaseState = if (previewBuy) previewStockTradeState(
                                    selected, previewWalletState.availableSol, previewWalletState.availableUsd)
                                    else purchaseState.takeIf { model.isLive && it.side == visible.side },
                                onSelectPaymentAsset = if (previewBuy) ({}) else model::selectPurchasePaymentAsset,
                                onSelectDestination = if (previewBuy) ({}) else model::selectPurchaseDestination,
                                onRequestQuote = if (previewBuy) ({}) else model::requestPurchaseQuote,
                                onPurchaseNow = if (previewBuy) ({}) else model::purchaseNow,
                                onExecutePurchase = if (previewBuy) ({}) else model::executePurchase,
                                onEditQuote = if (previewBuy) ({}) else model::editPurchaseQuote,
                                onRefreshPurchase = if (previewBuy) ({}) else model::refreshPurchaseOptions,
                                preview = if (previewBuy) previewStockTrade(selected,
                                    previewWalletState.availableSol,
                                    previewProcessingStockId == selected.id.value) else null,
                                onPreviewPurchase = {
                                    if (previewBuy && previewProcessingStockId == null &&
                                        previewWalletState.funded &&
                                        previewWalletState.availableUsd >= requireNotNull(previewPurchase).inputUsd) {
                                        previewProcessingStockId = selected.id.value
                                    }
                                },
                            )
                        }
                        is VisualRoute.Root -> {
                            Column(Modifier.fillMaxSize()) {
                                WalletTopPanel(
                                    title = when (visible.tab) {
                                        RootTab.Home -> "Wallet"
                                        RootTab.Account -> "Portfolio"
                                        RootTab.Markets -> "Stocks"
                                        RootTab.Trade -> "Signal"
                                    },
                                    alternateTitle = if (visible.tab == RootTab.Home) "Stocks" else "Wallet",
                                    onAlternate = {
                                        model.selectTab(if (visible.tab == RootTab.Home) RootTab.Markets else RootTab.Home)
                                    },
                                    onMore = {
                                        if (visible.tab == RootTab.Account) model.showAccountCenter()
                                        else model.selectTab(RootTab.Account)
                                    },
                                )
                                Spacer(Modifier.height(rd(26f)))
                                AnimatedContent(
                                    targetState = visible.tab,
                                    modifier = Modifier.weight(1f),
                                    transitionSpec = {
                                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                                        slideInHorizontally(tween(ReferenceMotion.SectionMillis, easing = ReferenceMotion.Easing)) { it * direction } togetherWith
                                            slideOutHorizontally(tween(ReferenceMotion.SectionMillis, easing = ReferenceMotion.Easing)) { -it * direction }
                                    }, label = "reference-root-tabs",
                                ) { tab ->
                                    when (tab) {
                                        RootTab.Home -> {
                                            HomeScreen(
                                                balance = if (model.isLive) walletPortfolio.balanceUsd else portfolio.balance,
                                                walletAddress = mainSolanaWallet?.address
                                                    ?: authenticatedUser?.wallets?.firstOrNull()?.address,
                                                walletConnected = authenticatedUser?.walletsReady == true,
                                                displayName = authenticatedUser?.displayName,
                                                profileAvatarVariant = avatarVariant,
                                                transactions = filterHomeWalletTransactions(transferHistory,
                                                    authenticatedUser?.userId,
                                                    verifiedWallets),
                                                walletTransactions = visibleWalletTransactions,
                                                purchaseTransactions = purchaseActivity?.transactions.orEmpty(),
                                                previewSpcxxPosition = spcxxPreviewPosition,
                                                onPreviewSpcxxTransaction = {
                                                    openPurchaseSolscan(context, SPCXX_PREVIEW_TRANSACTION)
                                                },
                                                previewWalletState = previewWalletState,
                                                onRefreshTransactions = {
                                                    previewUserId?.let { userId ->
                                                        previewWalletState = PreviewWalletStore.refresh(context, userId)
                                                    }
                                                    activityRefreshNonce++
                                                },
                                                onPreviewPurchaseTransaction = { purchase ->
                                                    val historicalUrl = when (purchase.stockId) {
                                                        SPCXX_PREVIEW_STOCK_ID -> SPCXX_PREVIEW_TRANSACTION
                                                        NIKE_PREVIEW_STOCK_ID -> NIKE_PREVIEW_TRANSACTION
                                                        else -> null
                                                    }
                                                    historicalUrl?.let { openPurchaseSolscan(context, it) }
                                                },
                                                walletActivityStatus = walletActivity?.status,
                                                stockSymbols = stocks.associate { it.id.value to it.symbol },
                                                unitPricesUsd = walletPortfolio.tokenHoldings.mapNotNull { token ->
                                                    token.unitPriceUsd?.let { token.assetId to it }
                                                }.toMap(),
                                                historyStorageHealthy = transferHistoryStorageHealthy,
                                                onStocks = { model.selectTab(RootTab.Markets) },
                                                onReceive = { walletPage = WalletPage.RECEIVE },
                                                onSend = { walletPage = WalletPage.SEND },
                                                onManage = { model.selectTab(RootTab.Account) },
                                                onHistory = { transferHistoryVisible = true },
                                                balancePlaceholder = if (model.isLive) walletPortfolio.balancePlaceholder else "—",
                                                showTopPanel = false,
                                            )
                                        }
                                        RootTab.Markets -> {
                                            // Only derive the full market table while its tab is on screen.
                                            val rows = remember(model, stocks, watched, connection.catalog) {
                                                if (model.isLive) model.liveRows(stocks, watched)
                                                else fixtures.rows(stocks, watched)
                                            }
                                            MarketsScreen(rows, model::openStock, model::toggleWatch,
                                                onFilter = { marketOverlay = "filters" },
                                                statusMessage = when {
                                                    !model.isLive -> null
                                                    connection.catalog == null && !connection.offline && !connection.refreshFailed -> ""
                                                    else -> connection.message
                                                },
                                                onRetry = null,
                                                query = marketQuery, watchlistOnly = watchlistOnly,
                                                onQueryChange = { marketQuery = it },
                                                onVisibleStockIds = model::setVisibleStockIds)
                                        }
                                        RootTab.Trade -> {
                                            val signalStockIds = remember(stocks) {
                                                stocks.groupBy { it.symbol.removeSuffix(".US").removeSuffix("x").uppercase() }
                                                    .mapValues { (_, matches) ->
                                                        (matches.firstOrNull { it.symbol.endsWith("x") } ?: matches.first()).id
                                                    }
                                            }
                                            SignalScreen(
                                                profiles = signal.profiles,
                                                selectedProfileId = signal.selectedProfileId,
                                                trades = signal.trades,
                                                news = signal.news,
                                                bargoTrades = signal.tradeSource ==
                                                    com.elevencapital.app.data.SignalTradeSource.BARGO,
                                                onProfile = model::openSignalProfile,
                                                onBack = model::closeSignalProfile,
                                                onOpenLink = { url ->
                                                    if (url.startsWith("https://")) runCatching {
                                                        context.startActivity(android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                                                    }
                                                },
                                                notice = signal.notice,
                                                onRefresh = { model.refreshSignalDirectory(force = true)
                                                    signal.selectedProfileId?.let(model::openSignalProfile) },
                                                canExploreStock = { symbol -> symbol.uppercase() in signalStockIds },
                                                onExploreStock = { symbol -> signalStockIds[symbol.uppercase()]?.let(model::openStock) },
                                            )
                                        }
                                        RootTab.Account -> {
                                            val liveBalance = if (model.isLive) walletPortfolio.balanceUsd else portfolio.balance
                                            VisibleMarketInterest(model, "account", holdings.map { it.stock.id }.toSet())
                                            val hasPreviewBalance = BuildConfig.DEBUG &&
                                                (previewWalletState.funded || previewHoldings.isNotEmpty())
                                            AccountScreen(
                                                balance = if (hasPreviewBalance) (liveBalance ?: BigDecimal.ZERO)
                                                    .add(previewWalletState.availableUsd).add(previewHoldingUsd)
                                                else liveBalance,
                                                tokenHoldings = if (model.isLive) walletPortfolio.tokenHoldings else emptyList(),
                                                previewHoldings = previewHoldings,
                                                onPreviewOpenTransaction = { holding ->
                                                    previewStockPurchase(holding.stock)?.transactionUrl
                                                        ?.let { openPurchaseSolscan(context, it) }
                                                },
                                                onPreviewRemoveAll = {
                                                    previewUserId?.let { userId ->
                                                        previewWalletState = PreviewWalletStore.clear(context, userId)
                                                        PreviewSpcxxHoldingStore.remove(context, userId)
                                                        spcxxPreviewPosition = null
                                                        previewBuyNotice = null
                                                    }
                                                },
                                                previewSolBalance = previewWalletState.availableSol
                                                    .takeIf { previewWalletState.funded },
                                                previewSolValueUsd = previewWalletState.availableUsd
                                                    .takeIf { previewWalletState.funded },
                                                onPreviewSolRemove = {
                                                    previewUserId?.let { userId ->
                                                        previewWalletState = PreviewWalletStore.removeFunding(context, userId)
                                                    }
                                                },
                                                devnetSol = if (model.isLive) visibleDevnetSol else null,
                                                holdings = holdings,
                                                onStock = model::openStock,
                                                onToken = { token ->
                                                    portfolioTokenSolscanUrl(token, mainSolanaWallet?.address)
                                                        ?.let { openPortfolioSolscan(context, it) }
                                                },
                                                onDevnetSol = {
                                                    devnetSolSolscanUrl(mainSolanaWallet?.address)
                                                        ?.let { openPortfolioSolscan(context, it) }
                                                },
                                                accountNotice = if (model.isLive) accountNotice else null,
                                                balancePlaceholder = if (model.isLive) walletPortfolio.balancePlaceholder else "—",
                                                portfolioPhase = if (model.isLive) walletPortfolio.phase else WalletBalancePhase.READY,
                                                walletConnected = !model.isLive || authenticatedUser?.walletsReady == true,
                                                compactTopSpacing = true,
                                            )
                                        }
                                    }
                                }
                            }
                            StockDock(visible.tab, model::selectTab,
                                Modifier.align(Alignment.BottomCenter), avatarVariant)
                        }
                    }
                }
            }
            marketOverlay?.let {
                MarketControlsDialog(query = marketQuery,
                    onChange = { marketQuery = it },
                    watchlistOnly = watchlistOnly,
                    onWatchlistOnlyChange = { watchlistOnly = it },
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
            previewBuyNotice?.let { stock ->
                StockBuySuccessNotice(
                    stock = stock,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = rd(9f)),
                )
            }
        }
    }
}


/** Animated tabs own independent interests, so outgoing cleanup cannot erase the new tab. */
@Composable
private fun VisibleMarketInterest(model: ElevenViewModel, owner: String, ids: Set<StockId>) {
    LaunchedEffect(model, owner, ids) { model.setVisibleStockIds(owner, ids) }
    DisposableEffect(model, owner) { onDispose { model.setVisibleStockIds(owner, emptySet()) } }
}

/** The reference dock uses four separate circular buttons and no visible labels. */
@Composable
internal fun StockDock(selected: RootTab, onSelect: (RootTab) -> Unit,
    modifier: Modifier = Modifier, avatarVariant: Int = 0) {
    Row(
        modifier.padding(bottom = rd(18f)).clip(CircleShape).background(WalletStyle.Panel)
            .padding(rd(5f)),
        horizontalArrangement = Arrangement.spacedBy(rd(6f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RootTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                Modifier.size(rd(58f)).clip(CircleShape)
                    .background(if (active) WalletStyle.Cream else WalletStyle.Circle)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .semantics {
                        contentDescription = when (tab) {
                            RootTab.Home -> "Home"
                            RootTab.Markets -> "Stocks"
                            RootTab.Trade -> "Signal"
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
                        RootTab.Trade -> "walletActivity"
                        RootTab.Account -> "walletProfile"
                    }, 25f, if (active) WalletStyle.Ink else WalletStyle.Cream.copy(alpha = .7f))
                }
            }
        }
    }
}
