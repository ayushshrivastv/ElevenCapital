package com.elevencapital.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.data.CompletedPurchasesClient
import com.elevencapital.app.data.CompletedPurchasesSnapshot
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.data.MeteoraMarketWarmup
import com.elevencapital.app.data.MeteoraTokenChart
import com.elevencapital.app.data.MeteoraTokenFeed
import com.elevencapital.app.data.SolanaDevnetClient
import com.elevencapital.app.data.SolanaDevnetSnapshot
import com.elevencapital.app.data.WalletActivityWallet
import com.elevencapital.app.data.WalletTransactionsClient
import com.elevencapital.app.data.WalletTransactionsSnapshot
import com.elevencapital.app.data.activityWallets
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.app.tokenlaunch.ConfirmedTokenLaunchReceipt
import com.elevencapital.app.tokenlaunch.TokenLaunchCommunityProfileStore
import com.elevencapital.app.tokenlaunch.TokenLaunchUiController
import com.elevencapital.app.tokenlaunch.TokenLaunchUiPreviewStore
import com.elevencapital.app.screens.PredictionBalanceTransferStatus
import com.elevencapital.app.screens.PredictionLedgerStore
import com.elevencapital.app.screens.PredictionWalletLedger
import com.elevencapital.app.screens.PreviewSpcxxHoldingStore
import com.elevencapital.app.screens.PreviewSpcxxPosition
import com.elevencapital.app.screens.PreviewStockPurchase
import com.elevencapital.app.screens.PreviewWalletState
import com.elevencapital.app.screens.PreviewWalletStore
import com.elevencapital.app.screens.resetPreviewRehearsal
import com.elevencapital.app.wallet.TransferJournalRecord
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.flow.StockDestination
import com.elevencapital.core.stock.flow.OrderSide
import com.elevencapital.app.data.WalletPortfolioViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal class PredictionSessionState(
    val store: PredictionLedgerStore,
    private val owner: String?,
    val walletId: String?,
    initialLedger: PredictionWalletLedger,
    initiallyWritable: Boolean,
) {
    var ledger by mutableStateOf(initialLedger)
    var writable by mutableStateOf(initiallyWritable)
        private set
    var accountedTransfer by mutableStateOf<PredictionBalanceTransferStatus?>(null)

    fun save(next: PredictionWalletLedger): Boolean {
        val currentOwner = owner ?: return false
        val currentWalletId = walletId ?: return false
        if (!writable) return false
        if (!store.save(currentOwner, currentWalletId, next)) {
            writable = false
            return false
        }
        ledger = next
        return true
    }
}

internal fun predictionWalletId(wallet: UserWallet?): String? =
    if (BuildConfig.DEBUG) "solana-usdc-btc-practice-v1"
    else wallet?.walletId?.takeIf(String::isNotBlank) ?: wallet?.address

@Composable
internal fun rememberPredictionSession(
    context: Context,
    owner: String?,
    walletId: String?,
): PredictionSessionState {
    val store = remember(context) { PredictionLedgerStore(context, preview = BuildConfig.DEBUG) }
    return remember(owner, walletId) {
        val load = runCatching {
            if (owner != null && walletId != null) store.load(owner, walletId)
            else PredictionWalletLedger.empty()
        }
        PredictionSessionState(store, owner, walletId,
            load.getOrDefault(PredictionWalletLedger.empty()), load.isSuccess)
    }
}

internal class PreviewSessionState(
    initialWallet: PreviewWalletState,
    initialSpcxxPosition: PreviewSpcxxPosition?,
) {
    var wallet by mutableStateOf(initialWallet)
    var spcxxPosition by mutableStateOf(initialSpcxxPosition)
    var processingStockId by mutableStateOf<String?>(null)
    var pendingPurchase by mutableStateOf<PreviewStockPurchase?>(null)
    var buyNotice by mutableStateOf<Stock?>(null)

    fun clear(context: Context, userId: String?, prediction: PredictionSessionState): Boolean {
        val owner = userId ?: return false
        val walletId = prediction.walletId ?: return false
        // Cancel an in-flight local stock Buy before it can recreate a cleared purchase.
        processingStockId = null
        pendingPurchase = null
        buyNotice = null
        // A hold on any preview row removes its rehearsal activity everywhere.
        val clearedWallet = resetPreviewRehearsal(context, owner, walletId,
            prediction.store, prediction.ledger)
        // Keep Compose aligned with durable state even if one preference write failed.
        wallet = PreviewWalletStore.read(context, owner)
        spcxxPosition = PreviewSpcxxHoldingStore.read(context, owner)
        prediction.ledger = runCatching { prediction.store.load(owner, walletId) }
            .getOrDefault(PredictionWalletLedger.empty())
        prediction.accountedTransfer = null
        return clearedWallet != null
    }
}

@Composable
internal fun rememberPreviewSession(
    context: Context,
    userId: String?,
    model: ElevenViewModel,
): PreviewSessionState {
    val state = remember(userId) {
        PreviewSessionState(
            userId?.let { PreviewWalletStore.read(context, it) } ?: PreviewWalletState(),
            userId?.let { PreviewSpcxxHoldingStore.read(context, it) },
        )
    }
    LaunchedEffect(state.processingStockId, userId) {
        val stockId = state.processingStockId ?: return@LaunchedEffect
        val preview = state.pendingPurchase?.takeIf { it.stockId == stockId } ?: return@LaunchedEffect
        if (!BuildConfig.DEBUG || userId == null) return@LaunchedEffect
        delay(2_000)
        val stock = model.repository.catalog.value.firstOrNull { it.id.value == stockId }
        val currentFunding = PreviewWalletStore.read(context, userId)
        val canSpend = currentFunding.funded &&
            currentFunding.availableSolUsd >= preview.inputUsd && preview.purchaseId.isNotBlank()
        val nextWallet = if (stock != null && canSpend) {
            PreviewWalletStore.recordPurchase(context, userId, stockId, stock.symbol,
                preview.inputUsd, preview.receiveTokens, preview.receiveValueUsd, preview.purchaseId)
        } else null
        state.processingStockId = null
        state.pendingPurchase = null
        if (stock != null && nextWallet != null) {
            state.wallet = nextWallet
            state.buyNotice = stock
            val activeOrder = model.stockFlow.destination.value as? StockDestination.Order
            if (activeOrder?.side == OrderSide.BUY && activeOrder.stockId.value == stockId) {
                model.stockFlow.goBack()
            }
        }
    }
    LaunchedEffect(state.buyNotice) {
        val notice = state.buyNotice ?: return@LaunchedEffect
        delay(4_500)
        if (state.buyNotice == notice) state.buyNotice = null
    }
    return state
}

internal data class TokenLaunchSessionState(
    val ui: TokenLaunchUiController,
    val currentUserId: () -> String?,
)

@Composable
internal fun rememberTokenLaunchSession(
    context: Context,
    userId: String?,
    walletAddress: String?,
    confirmedLaunches: List<ConfirmedTokenLaunchReceipt>,
): TokenLaunchSessionState {
    val latestUserId = rememberUpdatedState(userId)
    val restored = remember(userId, walletAddress, confirmedLaunches) {
        confirmedLaunches.firstOrNull { receipt ->
            receipt.userId == userId && receipt.walletAddress == walletAddress
        }
    }
    val restoredPreview = remember(userId, walletAddress) {
        if (userId != null && walletAddress != null) {
            TokenLaunchUiPreviewStore.read(context, userId, walletAddress)
        } else null
    }
    val restoredProfile = remember(userId, restored?.mintAddress) {
        val mint = restored?.mintAddress
        if (userId != null && mint != null) {
            TokenLaunchCommunityProfileStore.read(context, userId, mint)?.toDraft()
        } else null
    }
    val ui = remember(userId, walletAddress, restored?.operationId, restoredPreview, restoredProfile) {
        TokenLaunchUiController(
            restored = restored,
            restoredPreview = restoredPreview,
            restoredConfirmedDraft = restoredProfile,
            savePreview = { draft, mint ->
                userId != null && walletAddress != null &&
                    TokenLaunchUiPreviewStore.save(context, userId, walletAddress, draft, mint)
            },
            saveConfirmedDisplay = { draft, mint ->
                userId?.let { TokenLaunchCommunityProfileStore.save(context, it, mint, draft) } == true
            },
            removePreview = {
                userId != null && walletAddress != null &&
                    TokenLaunchUiPreviewStore.remove(context, userId, walletAddress)
            },
        )
    }
    return TokenLaunchSessionState(ui, currentUserId = { latestUserId.value })
}

internal data class CollectedAppModelState(
    val accountCenter: Boolean,
    val stocks: List<Stock>,
    val destination: StockDestination,
    val rootTab: RootTab,
    val watched: Set<StockId>,
    val connection: MarketConnectionState,
    val walletPortfolio: WalletPortfolioViewState,
    val purchaseState: PurchaseUiState,
)

@Composable
internal fun collectAppModelState(model: ElevenViewModel): CollectedAppModelState {
    val accountCenter by model.accountCenter.collectAsStateWithLifecycle()
    val stocks by model.repository.catalog.collectAsStateWithLifecycle()
    val destination by model.stockFlow.destination.collectAsStateWithLifecycle()
    val rootTab by model.tab.collectAsStateWithLifecycle()
    val watched by model.watched.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val walletPortfolio by model.walletPortfolio.collectAsStateWithLifecycle()
    val purchaseState by model.purchaseState.collectAsStateWithLifecycle()
    return CollectedAppModelState(accountCenter, stocks, destination, rootTab, watched,
        connection, walletPortfolio, purchaseState)
}

internal data class WalletActivityState(
    val requestedWallets: List<WalletActivityWallet>,
    val walletActivity: WalletTransactionsSnapshot?,
    val devnetActivity: SolanaDevnetSnapshot?,
    val purchaseActivity: CompletedPurchasesSnapshot?,
    val requestRefresh: () -> Unit,
)

@Composable
internal fun rememberWalletActivity(
    application: ElevenCapitalApplication,
    isLive: Boolean,
    userId: String?,
    verifiedWallets: List<UserWallet>,
    mainSolanaAddress: String?,
    transferHistory: List<TransferJournalRecord>,
): WalletActivityState {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activityWalletAddresses = verifiedWallets.map { it.chain to it.address }
    val requestedWallets = remember(userId, activityWalletAddresses) { activityWallets(verifiedWallets) }
    val activityClient = remember(isLive) {
        if (isLive) WalletTransactionsClient(BuildConfig.MARKET_DATA_URL) else null
    }
    val devnetClient = remember(isLive) {
        if (isLive) SolanaDevnetClient(BuildConfig.MARKET_DATA_URL) else null
    }
    val purchaseActivityClient = remember(isLive) {
        if (isLive) CompletedPurchasesClient(BuildConfig.MARKET_DATA_URL,
            application.auth::freshPurchaseAccessToken) else null
    }
    var walletActivity by remember(userId, requestedWallets) {
        mutableStateOf<WalletTransactionsSnapshot?>(null)
    }
    var devnetActivity by remember(userId, mainSolanaAddress) {
        mutableStateOf<SolanaDevnetSnapshot?>(null)
    }
    var purchaseActivity by remember(userId) {
        mutableStateOf<CompletedPurchasesSnapshot?>(null)
    }
    var refreshNonce by remember(userId) { mutableStateOf(0) }
    LaunchedEffect(transferHistory) {
        if (transferHistory.isNotEmpty()) refreshNonce++
    }
    // The first read may finish while the credential prompt pauses the Activity.
    // Later reads run only while foregrounded.
    LaunchedEffect(lifecycleOwner, activityClient, userId, requestedWallets, refreshNonce) {
        if (userId == null || requestedWallets.isEmpty()) return@LaunchedEffect
        val client = activityClient ?: return@LaunchedEffect
        suspend fun refresh() {
            val observation = try {
                client.activity(requestedWallets)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (observation?.wallets == requestedWallets) walletActivity = observation
        }
        refresh()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                delay(15_000)
                refresh()
            }
        }
    }
    LaunchedEffect(lifecycleOwner, devnetClient, userId, mainSolanaAddress, refreshNonce) {
        if (userId == null) return@LaunchedEffect
        val client = devnetClient ?: return@LaunchedEffect
        val address = mainSolanaAddress ?: return@LaunchedEffect
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
    LaunchedEffect(lifecycleOwner, purchaseActivityClient, userId, refreshNonce) {
        val currentUserId = userId ?: return@LaunchedEffect
        val client = purchaseActivityClient ?: return@LaunchedEffect
        suspend fun refresh() {
            val observation = try {
                client.activity(currentUserId)
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
    return WalletActivityState(requestedWallets, walletActivity, devnetActivity,
        purchaseActivity, requestRefresh = { refreshNonce++ })
}

@Composable
internal fun ObservePublicMeteoraWarmup(warmup: MeteoraMarketWarmup, isLive: Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, warmup, isLive) {
        if (!isLive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            warmup.requestRefreshIfStale()
            while (currentCoroutineContext().isActive) {
                delay(60_000L)
                warmup.requestRefreshIfStale()
            }
        }
    }
}

internal class MeteoraRouteState {
    var visible by mutableStateOf(false)
    var filterRequestNonce by mutableStateOf(0)
    var selectedToken by mutableStateOf<MeteoraMarketToken?>(null)
    var chartRange by mutableStateOf(ChartRange.ONE_DAY)
    var chart by mutableStateOf<MeteoraTokenChart?>(null)
    var chartLoading by mutableStateOf(false)
    var chartError by mutableStateOf<String?>(null)
    var chartRefreshNonce by mutableStateOf(0)
    var description by mutableStateOf<String?>(null)
    var descriptionLoading by mutableStateOf(false)
}

@Composable
internal fun rememberMeteoraRouteState(
    feed: MeteoraTokenFeed,
    warmup: MeteoraMarketWarmup,
): MeteoraRouteState {
    val state = remember { MeteoraRouteState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(feed, state.selectedToken?.poolAddress, state.selectedToken?.mintAddress,
        state.chartRange, state.chartRefreshNonce) {
        val token = state.selectedToken ?: return@LaunchedEffect
        state.chartLoading = state.chart == null
        state.chartError = null
        try {
            state.chart = feed.chartFor(token, state.chartRange)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.chartError = "Price history is unavailable right now"
        } finally {
            state.chartLoading = false
        }
    }
    LaunchedEffect(feed, state.selectedToken?.mintAddress) {
        val token = state.selectedToken ?: return@LaunchedEffect
        state.descriptionLoading = true
        try {
            state.description = feed.descriptionFor(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.description = null
        } finally {
            state.descriptionLoading = false
        }
    }
    LaunchedEffect(lifecycleOwner, state.selectedToken?.mintAddress) {
        if (state.selectedToken == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (currentCoroutineContext().isActive) {
                delay(60_000L)
                val currentToken = state.selectedToken ?: break
                try {
                    val refreshed = feed.currentToken(currentToken)
                    state.selectedToken = refreshed
                    warmup.updateToken(refreshed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last real quote when the market feed is temporarily unavailable.
                }
                state.chartRefreshNonce++
            }
        }
    }
    return state
}
