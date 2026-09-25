package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.constrainHeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.purchase.PurchaseDestination
import com.elevencapital.app.purchase.PurchaseNetwork
import com.elevencapital.app.purchase.PurchasePaymentAsset
import com.elevencapital.app.purchase.PurchasePhase
import com.elevencapital.app.purchase.PurchaseUiState
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.StockIcon
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.drawSolanaLogo
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.flow.OrderSide
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.abs

private const val ANTHROPIC_PRESTOCK_ID = "prestocks:Pren1FvFX6J3E4kXhJuCiAD5aDmGEb7qJRncwA8Lkhw"

/** Stock-specific order entry. Buy quotes and execution run from the Purchase action. */
@Composable
fun TradeScreen(
    stock: Stock,
    paymentBalance: BigDecimal?,
    onChooseStock: () -> Unit,
    modifier: Modifier = Modifier,
    side: OrderSide = OrderSide.BUY,
    onBack: () -> Unit = {},
    onPurchaseReady: ((BigDecimal) -> Unit)? = null,
    stockBalance: BigDecimal? = null,
    initialAmountUsd: String = "0",
    initialPaymentWheelExpanded: Boolean = false,
    stockLogoReference: StockLogoReference? = null,
    unavailableReason: String? = null,
    purchaseState: PurchaseUiState? = null,
    onSelectPaymentAsset: (String) -> Unit = {},
    onSelectDestination: (String?) -> Unit = {},
    onRequestQuote: (String) -> Unit = {},
    onPurchaseNow: (String) -> Unit = {},
    onExecutePurchase: () -> Unit = {},
    onEditQuote: () -> Unit = {},
    onRefreshPurchase: () -> Unit = {},
    preview: StockTradePreview? = null,
    onPreviewPurchase: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val refreshPurchase = rememberUpdatedState(onRefreshPurchase)
    var input by remember(stock.id, side) { mutableStateOf(initialAmountUsd) }
    var paymentSelectorVisible by remember(stock.id, side) { mutableStateOf(initialPaymentWheelExpanded) }
    var destinationMenuVisible by remember(stock.id, side) { mutableStateOf(false) }
    val livePurchase = purchaseState?.takeIf { it.side == side && it.stockId == stock.id.value }
    val selectedAsset = livePurchase?.selectedPaymentAsset
    val livePhase = livePurchase?.phase
    // A deposit can arrive after Buy opens. Refresh only this live order while it is idle and
    // visible; quote, signing, and tracking phases must keep their exact reviewed state.
    LaunchedEffect(stock.id.value, side, livePhase, lifecycleOwner, preview) {
        if (stock.id.value == ANTHROPIC_PRESTOCK_ID && side == OrderSide.BUY &&
            livePhase == PurchasePhase.ENTRY && preview == null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(15_000L)
                    refreshPurchase.value()
                }
            }
        }
    }
    val isReview = livePhase == PurchasePhase.REVIEW
    val displayedQuote = livePurchase?.quote?.takeIf {
        livePhase in setOf(PurchasePhase.REVIEW, PurchasePhase.COMMITTING, PurchasePhase.SIGNING,
            PurchasePhase.TRACKING, PurchasePhase.COMPLETE) || livePurchase.ambiguousSubmission
    }
    val solscanUrl = completedPurchaseSolscanUrl(livePurchase?.status)
    val effectiveBalance = selectedAsset?.balance
        ?: if (side == OrderSide.BUY) paymentBalance else stockBalance
    val amount = input.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val orderInputAmount = orderInputAmount(side, amount, selectedAsset)
    val exceedsBalance = orderExceedsBalance(side, amount, selectedAsset, effectiveBalance)
    // Entry can show a clearly-marked market-price estimate; review always switches to the bound quote.
    val previewAmountEntered = preview != null && amount.compareTo(preview.inputUsd) == 0
    val receiveEstimate = if (preview != null) {
        preview.receiveTokens.takeIf { previewAmountEntered }
    } else displayedQuote?.estimatedOutputAmount ?: if (
        side == OrderSide.BUY && amount.signum() > 0 &&
        (livePurchase == null || livePhase in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED))
    ) indicativeStockQuantity(amount, stock) else null
    val receiveIsIndicative = displayedQuote == null && receiveEstimate != null
    val paymentAssets = livePurchase?.options?.paymentAssets.orEmpty()
    val destinations = livePurchase?.options?.destinations.orEmpty()
    val selectedDestination = displayedQuote?.destination ?: livePurchase?.selectedDestination
    val paymentSelectorEnabled = unavailableReason == null && side == OrderSide.BUY &&
        livePurchase != null && !livePurchase.busy && !isReview && preview?.processing != true
    val destinationEnabled = unavailableReason == null && livePurchase != null && !livePurchase.busy && !isReview &&
        preview?.processing != true &&
        destinations.any { it.enabled }
    val liveCanQuote = livePurchase != null && livePhase in setOf(PurchasePhase.ENTRY, PurchasePhase.FAILED) &&
        !livePurchase.ambiguousSubmission && selectedAsset?.enabled == true &&
        orderInputAmount?.signum() == 1 && !exceedsBalance
    val canContinueNormally = if (unavailableReason != null) false else if (livePurchase != null) {
        if (side == OrderSide.BUY) liveCanQuote else
            (isReview && BuildConfig.PURCHASE_EXECUTION_ENABLED && livePurchase.quote?.executionEnabled == true &&
                livePurchase.quote?.binding?.side == side) || liveCanQuote
    } else {
        side == OrderSide.BUY && paymentBalance != null &&
            amount.signum() > 0 && !exceedsBalance &&
            onPurchaseReady != null
    }
    val canContinue = canContinueNormally && (preview == null || (previewAmountEntered && !preview.processing))
    val actionLabel = when {
        preview?.processing == true -> "Purchasing…"
        unavailableReason != null && livePhase != PurchasePhase.COMPLETE -> "Order unavailable"
        livePhase == PurchasePhase.REVIEW && (!BuildConfig.PURCHASE_EXECUTION_ENABLED ||
            livePurchase.quote?.executionEnabled != true) -> "Execution unavailable"
        livePhase == PurchasePhase.REVIEW -> if (side == OrderSide.BUY) "Processing purchase…" else "Sell ${stock.symbol}"
        livePhase == PurchasePhase.LOADING_OPTIONS -> "Select asset"
        livePhase == PurchasePhase.QUOTING -> "Finding best route…"
        livePhase == PurchasePhase.COMMITTING -> "Securing route…"
        livePhase == PurchasePhase.SIGNING -> "Confirm in wallet"
        livePhase == PurchasePhase.TRACKING -> "Order in progress"
        livePhase == PurchasePhase.COMPLETE -> if (side == OrderSide.BUY) "Purchase complete" else "Sale complete"
        livePhase == PurchasePhase.UNAVAILABLE -> "Refresh assets"
        livePurchase?.ambiguousSubmission == true -> "Checking order status"
        livePurchase != null && livePhase == PurchasePhase.FAILED && amount.signum() > 0 ->
            if (side == OrderSide.BUY) "Purchase" else "Request new quote"
        amount.signum() == 0 -> "Enter amount"
        preview != null && !previewAmountEntered -> "Enter \$${tradePlain(preview.inputUsd)}"
        exceedsBalance -> "Insufficient ${selectedAsset?.symbol ?: if (side == OrderSide.SELL) stock.symbol else "USDC"} balance"
        livePurchase != null && orderInputAmount == null ->
            if (side == OrderSide.BUY) "Payment value unavailable" else "Stock balance unavailable"
        side == OrderSide.SELL && livePurchase == null -> "Selling unavailable"
        side == OrderSide.BUY -> "Purchase"
        else -> "Enter amount"
    }
    val keypadVisible = !isReview && livePurchase?.busy != true && livePurchase?.ambiguousSubmission != true &&
        livePhase != PurchasePhase.COMPLETE && preview?.processing != true
    val maximumDecimals = if (side == OrderSide.SELL) selectedAsset?.decimals?.coerceIn(0, 36) ?: 9 else 2
    LaunchedEffect(paymentSelectorEnabled) {
        if (!paymentSelectorEnabled) paymentSelectorVisible = false
    }
    if (side == OrderSide.SELL && livePurchase != null && displayedQuote != null && livePhase in setOf(
            PurchasePhase.REVIEW, PurchasePhase.COMMITTING, PurchasePhase.SIGNING,
            PurchasePhase.TRACKING, PurchasePhase.COMPLETE,
        )
    ) {
        StockOrderReview(
            stock = stock,
            stockLogoReference = stockLogoReference ?: stock.logo,
            state = livePurchase,
            canConfirm = canContinue,
            solscanUrl = solscanUrl,
            onEdit = onEditQuote,
            onConfirm = onExecutePurchase,
            onBack = onBack,
        )
        return
    }
    val buyExecutionLocked = side == OrderSide.BUY && livePhase in setOf(
        PurchasePhase.COMMITTING, PurchasePhase.SIGNING, PurchasePhase.TRACKING,
    ) || preview?.processing == true
    BackHandler(paymentSelectorVisible || destinationMenuVisible || buyExecutionLocked) {
        when {
            destinationMenuVisible -> destinationMenuVisible = false
            paymentSelectorVisible -> paymentSelectorVisible = false
        }
    }

    val modalOpen = paymentSelectorVisible || destinationMenuVisible
    BoxWithConstraints(modifier.fillMaxSize().background(P.Background)) {
        val openTokenSelector: (Rect) -> Unit = {
            destinationMenuVisible = false
            paymentSelectorVisible = true
        }
        Column(
            Modifier.fillMaxSize().then(
                if (modalOpen) Modifier.clearAndSetSemantics { } else Modifier,
            ),
        ) {
            OrderToolbar(side, stock.symbol, if (buyExecutionLocked) ({}) else onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(rd(20f)))
                OrderPayReceiveCards(
                    side = side,
                    input = if (side == OrderSide.SELL) {
                        displayedQuote?.inputAmount?.let(::tradePlain) ?: input
                    } else input,
                    stock = stock,
                    stockLogoReference = stockLogoReference ?: stock.logo,
                    selectedAsset = selectedAsset,
                    selectedDestination = selectedDestination,
                    receiveAmount = receiveEstimate,
                    receiveValueUsd = preview?.receiveValueUsd?.takeIf { previewAmountEntered },
                    receiveIsIndicative = receiveIsIndicative,
                    receiveSymbol = displayedQuote?.destination?.symbol
                        ?: stock.symbol.takeIf { receiveEstimate != null && side == OrderSide.BUY },
                    receivePending = when {
                        amount.signum() == 0 -> if (side == OrderSide.BUY) "Enter amount" else "Enter quantity"
                        livePhase == PurchasePhase.QUOTING -> "Quote pending"
                        else -> "Quote required"
                    },
                    previewBalanceLabel = preview?.paymentBalanceLabel,
                    selectorEnabled = paymentSelectorEnabled,
                    destinationEnabled = destinationEnabled,
                    onPaymentClick = openTokenSelector,
                    onDestinationClick = { if (destinationEnabled) destinationMenuVisible = true },
                )
                OrderRouteRow(
                    side = side,
                    stock = stock,
                    stockLogoReference = stockLogoReference ?: stock.logo,
                    selectedAsset = selectedAsset,
                    paymentBalanceLabel = if (preview != null) selectedAsset?.usdValue
                        ?.let { "\$${tradeMoney(it)}" } else null,
                    paymentBalance = effectiveBalance,
                    selectedDestination = selectedDestination,
                    destinations = destinations,
                    paymentSelectorEnabled = paymentSelectorEnabled,
                    destinationEnabled = destinationEnabled,
                    onPaymentClick = {
                        destinationMenuVisible = false
                        paymentSelectorVisible = true
                    },
                    onStockClick = {
                        paymentSelectorVisible = false
                        if (destinationEnabled) destinationMenuVisible = true else onChooseStock()
                    },
                )
                val inlineMessage = livePurchase?.message?.takeIf {
                    it.isNotBlank() && (livePhase in setOf(PurchasePhase.FAILED, PurchasePhase.UNAVAILABLE) ||
                        livePurchase?.ambiguousSubmission == true ||
                        (side == OrderSide.BUY && livePhase == PurchasePhase.REVIEW))
                } ?: if (side == OrderSide.BUY && livePhase == PurchasePhase.REVIEW &&
                    livePurchase?.quote?.executionEnabled != true) {
                    livePurchase?.quote?.executionReason ?: "This route cannot be purchased yet."
                } else unavailableReason?.takeIf { it.isNotBlank() }
                val visibleQuote = displayedQuote?.takeIf { side == OrderSide.SELL }
                val visibleSolscanUrl = solscanUrl?.takeIf { side == OrderSide.SELL }
                if (visibleQuote != null || inlineMessage != null || visibleSolscanUrl != null) {
                    Spacer(Modifier.height(rd(8f)))
                    visibleQuote?.let {
                        PurchaseQuoteCard(livePurchase, onEdit = onEditQuote.takeIf { isReview })
                    }
                    inlineMessage?.let { message ->
                        Spacer(Modifier.height(rd(8f)))
                        RefText(
                            message,
                            13f,
                            if (livePurchase?.ambiguousSubmission == true) Color(0xFFFF7188) else P.Muted,
                            modifier = Modifier.padding(horizontal = rd(22f)),
                            maxLines = 3,
                        )
                    }
                    visibleSolscanUrl?.let { url ->
                        Spacer(Modifier.height(rd(14f)))
                        Box(
                            Modifier.padding(horizontal = rd(22f)).fillMaxWidth()
                                .height(rd(48f)).clip(CircleShape).background(P.White)
                                .clickable(role = Role.Button) { uriHandler.openUri(url) },
                            contentAlignment = Alignment.Center,
                        ) { RefText("View transaction on Solscan", 14f, P.Background, FontWeight.SemiBold) }
                    }
                }
                if (visibleQuote != null || inlineMessage != null || visibleSolscanUrl != null) Spacer(Modifier.height(rd(12f)))
            }
            OrderBottomControls(
                    actionLabel = actionLabel,
                    canContinue = canContinue,
                    keypadVisible = keypadVisible,
                    maximumDecimals = maximumDecimals,
                    input = input,
                    onInputChange = { input = it },
                    onContinue = {
                        when {
                            preview != null -> onPreviewPurchase()
                            livePurchase == null -> onPurchaseReady?.invoke(amount)
                            side == OrderSide.BUY -> orderInputAmount?.let { onPurchaseNow(tradePlain(it)) }
                            isReview -> onExecutePurchase()
                            else -> orderInputAmount?.let { onRequestQuote(tradePlain(it)) }
                        }
                    },
                    onRefresh = onRefreshPurchase.takeIf {
                        unavailableReason == null && (livePhase == PurchasePhase.UNAVAILABLE ||
                            (livePhase == PurchasePhase.FAILED && amount.signum() == 0)
                        )
                    },
            )
        }

        PaymentSelectorPopup(
            visible = paymentSelectorVisible,
            assets = paymentAssets,
            selectedId = livePurchase?.selectedPaymentAssetId,
            onDismiss = { paymentSelectorVisible = false },
            onSelect = { asset ->
                if (asset.id != selectedAsset?.id) {
                    input = "0"
                    onSelectPaymentAsset(asset.id)
                }
                paymentSelectorVisible = false
            },
        )
        DestinationPicker(
            visible = destinationMenuVisible,
            state = livePurchase,
            onDismiss = { destinationMenuVisible = false },
            onSelect = { id ->
                onSelectDestination(id)
                destinationMenuVisible = false
            },
        )
    }
}

/** The final quote is a separate confirmation surface; the amount keypad cannot obscure signing details. */
@Composable
private fun StockOrderReview(
    stock: Stock,
    stockLogoReference: StockLogoReference?,
    state: PurchaseUiState,
    canConfirm: Boolean,
    solscanUrl: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val quote = state.quote ?: return
    val isReview = state.phase == PurchasePhase.REVIEW
    val isComplete = state.phase == PurchasePhase.COMPLETE
    val isBuy = state.side == OrderSide.BUY
    // Completed/recovered orders can retain their verified quote after the options snapshot is cleared.
    val paymentAsset = state.options?.paymentAssets?.firstOrNull { it.id == quote.binding.fromAssetId }
    val paymentSymbol = paymentAsset?.symbol ?: if (isBuy) {
        quote.binding.fromAssetId.substringAfterLast(':')
    } else stock.symbol
    val paymentNetwork = paymentAsset?.network ?: quote.binding.fromNetwork
    var currentTime by remember(quote.id) { mutableStateOf(java.time.Instant.now()) }
    LaunchedEffect(quote.id, isReview) {
        while (isReview) {
            currentTime = java.time.Instant.now()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val secondsLeft = java.time.Duration.between(currentTime, quote.expiresAt).seconds.coerceAtLeast(0)
    val quoteExpired = isReview && secondsLeft == 0L
    val confirmEnabled = isReview && canConfirm && !quoteExpired
    val uriHandler = LocalUriHandler.current
    val handleBack = {
        when {
            isReview -> onEdit()
            isComplete -> onBack()
            state.phase == PurchasePhase.TRACKING -> onBack()
            else -> Unit // A Privy action may still finish after Android back is pressed.
        }
    }
    BackHandler { handleBack() }
    val title = when (state.phase) {
        PurchasePhase.REVIEW -> if (isBuy) "Review purchase" else "Review sale"
        PurchasePhase.SIGNING -> "Confirm in Privy"
        PurchasePhase.COMPLETE -> if (isBuy) "Purchase complete" else "Sale complete"
        else -> "Order in progress"
    }
    val actionLabel = when {
        isComplete && solscanUrl != null -> "View on Solscan"
        isComplete -> "Back to stock"
        state.phase == PurchasePhase.SIGNING -> "Confirm in Privy"
        state.phase == PurchasePhase.COMMITTING -> "Securing route…"
        state.phase == PurchasePhase.TRACKING -> "Tracking order…"
        quoteExpired -> "Quote expired"
        !BuildConfig.PURCHASE_EXECUTION_ENABLED || !quote.executionEnabled -> "Execution unavailable"
        else -> if (isBuy) "Confirm purchase" else "Confirm sale"
    }
    val actionEnabled = confirmEnabled || isComplete
    Column(Modifier.fillMaxSize().background(WalletStyle.Background).padding(horizontal = rd(22f))) {
        TransactionReviewHeader(title, isReview || isComplete || state.phase == PurchasePhase.TRACKING, handleBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(rd(16f)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StockIcon(stock, 48f, logoReference = stockLogoReference)
                Spacer(Modifier.width(rd(12f)))
                Column {
                    RefText(if (isBuy) "Buy ${stock.symbol}" else "Sell ${stock.symbol}",
                        20f, WalletStyle.White, FontWeight.SemiBold)
                    RefText("${paymentNetwork.displayName} to ${quote.destination.network.displayName}",
                        13f, WalletStyle.Muted)
                }
            }
            Spacer(Modifier.height(rd(16f)))
            RefText("Estimated receive", 13f, WalletStyle.Muted)
            Spacer(Modifier.height(rd(4f)))
            RefText("${tradePlain(quote.estimatedOutputAmount)} ${quote.destination.symbol}",
                31f, WalletStyle.White, FontWeight.Bold)
            Spacer(Modifier.height(rd(16f)))
            TransactionReviewPanel {
                TransactionReviewRow(if (isBuy) "You pay" else "You sell",
                    "${tradePlain(quote.inputAmount)} $paymentSymbol")
                TransactionReviewDivider()
                TransactionReviewRow("Payment network", paymentNetwork.displayName)
                TransactionReviewRow("Receive network", quote.destination.network.displayName)
                TransactionReviewRow("Receive wallet", quote.destination.address, fullAddress = true)
                TransactionReviewDivider()
                TransactionReviewRow("Estimated fees", quote.feesUsd?.let { "\$${tradeMoney(it)}" } ?: "Not provided")
                quote.executableUnitPriceUsd?.let {
                    TransactionReviewRow("Unit price", "\$${tradeMoney(it)}")
                }
                TransactionReviewRow("Minimum receive",
                    "${tradePlain(quote.minimumReceived)} ${quote.destination.symbol}")
                TransactionReviewRow("Maximum slippage",
                    "${BigDecimal(quote.slippageBps).movePointLeft(2).stripTrailingZeros().toPlainString()}%")
                quote.priceImpactPercent?.let {
                    TransactionReviewRow("Price impact", "${tradePlain(it)}%")
                }
                if (isReview) {
                    TransactionReviewDivider()
                    TransactionReviewRow("Quote expires", if (quoteExpired) "Expired" else "in ${secondsLeft}s")
                }
            }
            Spacer(Modifier.height(rd(16f)))
            TransactionReviewPanel {
                RefText("Privy wallet approval", 15f, WalletStyle.White, FontWeight.SemiBold)
                TransactionReviewNote(
                    "Privy will request ${quote.walletConfirmations} wallet " +
                        (if (quote.walletConfirmations == 1) "confirmation" else "confirmations") +
                        ". Review each amount and network before approving. Eleven then tracks the route to your wallet.",
                )
            }
            if (!BuildConfig.PURCHASE_EXECUTION_ENABLED || !quote.executionEnabled) {
                Spacer(Modifier.height(rd(12f)))
                TransactionReviewNote(quote.executionReason ?: "Live execution is not enabled yet.")
            }
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(rd(12f)))
                TransactionReviewNote(message)
            }
            Spacer(Modifier.height(rd(22f)))
        }
        Spacer(Modifier.height(rd(10f)))
        TransactionReviewPrimaryButton(actionLabel, actionEnabled) {
            when {
                isComplete && solscanUrl != null -> uriHandler.openUri(solscanUrl)
                isComplete -> onBack()
                confirmEnabled -> onConfirm()
            }
        }
        if (isReview || isComplete) {
            TransactionReviewSecondaryButton(if (isReview) "Edit order" else "Back to stock", true, handleBack)
        } else {
            Spacer(Modifier.height(rd(44f)))
        }
        Spacer(Modifier.height(rd(12f)))
    }
}

@Composable
private fun OrderToolbar(side: OrderSide, symbol: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(rd(64f)).padding(horizontal = rd(18f))) {
        Box(
            Modifier.align(Alignment.CenterStart).size(rd(44f)).clip(CircleShape).background(P.Chip)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to $symbol" },
            contentAlignment = Alignment.Center,
        ) { RefIcon("back", 22f) }
        RefText(
            if (side == OrderSide.BUY) "Buy $symbol" else "Sell $symbol",
            18f,
            P.White,
            FontWeight.Medium,
            Modifier.align(Alignment.Center),
        )
        Spacer(Modifier.align(Alignment.CenterEnd).size(rd(44f)))
    }
}

@Composable
internal fun OrderPayReceiveCards(
    side: OrderSide,
    input: String,
    stock: Stock,
    stockLogoReference: StockLogoReference?,
    selectedAsset: PurchasePaymentAsset?,
    selectedDestination: PurchaseDestination?,
    receiveAmount: BigDecimal?,
    receiveValueUsd: BigDecimal? = null,
    receiveIsIndicative: Boolean = false,
    receiveSymbol: String?,
    receivePending: String,
    selectorEnabled: Boolean,
    destinationEnabled: Boolean,
    onPaymentClick: (Rect) -> Unit,
    onDestinationClick: () -> Unit,
    previewBalanceLabel: String? = null,
) {
    val display = tradeDisplayInput(input)
    val quantity = receiveAmount?.let(::tradeEstimate)
    val longestAmount = maxOf(display.length, quantity?.length ?: 0)
    val amountSize = when {
        longestAmount <= 7 -> 38f
        longestAmount <= 10 -> 32f
        else -> 26f
    }
    val cardColor = Color(0xFF1E1E1E)
    val cardHeight = 160f
    Box(Modifier.fillMaxWidth().padding(horizontal = rd(22f))) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(30f)))
                .background(Color(0xFF111213)).padding(rd(5f)),
            verticalArrangement = Arrangement.spacedBy(rd(4f)),
        ) {
            Box(
                Modifier.fillMaxWidth().height(rd(cardHeight))
                    .clip(RoundedCornerShape(topStart = rd(26f), topEnd = rd(26f),
                        bottomStart = rd(6f), bottomEnd = rd(6f)))
                    .background(cardColor)
                    .semantics {
                        contentDescription = if (side == OrderSide.BUY) {
                            "You pay, $display US dollars"
                        } else {
                            "You sell, $display ${stock.symbol}"
                        }
                    },
            ) {
                if (side == OrderSide.BUY) {
                    PaymentNetworkPill(selectedAsset, selectorEnabled, onPaymentClick,
                        Modifier.align(Alignment.TopStart).padding(start = rd(7f), top = rd(7f)))
                    previewBalanceLabel?.let { balance ->
                        RefText("Balance $balance", 10.5f, P.Muted,
                            modifier = Modifier.align(Alignment.TopEnd)
                                .padding(end = rd(14f), top = rd(18f)))
                    }
                } else {
                    StockConversionPill(stock, stockLogoReference,
                        Modifier.align(Alignment.TopStart).padding(start = rd(7f), top = rd(7f)))
                }
                CenteredConversionAmount(
                    display,
                    if (side == OrderSide.BUY) "$" else "",
                    if (side == OrderSide.SELL) stock.symbol else "",
                    amountSize,
                    Modifier.align(Alignment.Center).offset(y = rd(16f)))
            }
            Box(
                Modifier.fillMaxWidth().height(rd(cardHeight))
                    .clip(RoundedCornerShape(topStart = rd(6f), topEnd = rd(6f),
                        bottomStart = rd(26f), bottomEnd = rd(26f)))
                    .background(cardColor)
                    .semantics {
                        contentDescription = if (receiveValueUsd != null && receiveAmount != null) {
                            "You receive, estimated \$${tradePlain(receiveValueUsd)} worth of ${stock.symbol}, " +
                                "${tradePlain(receiveAmount)} ${stock.symbol}"
                        } else "You receive, estimated " +
                            (receiveAmount?.let { "${tradePlain(it)} $receiveSymbol" } ?: receivePending)
                    },
            ) {
                if (side == OrderSide.BUY) {
                    StockConversionPill(stock, stockLogoReference,
                        Modifier.align(Alignment.TopStart).padding(start = rd(7f), top = rd(7f)))
                } else {
                    DestinationConversionPill(selectedDestination,
                        Modifier.align(Alignment.TopStart).padding(start = rd(7f), top = rd(7f)))
                }
                if (quantity != null && receiveSymbol != null) {
                    CenteredConversionAmount(
                        receiveValueUsd?.let(::tradePlain) ?: quantity,
                        if (receiveValueUsd != null) "\$" else if (receiveIsIndicative) "≈" else "",
                        receiveSymbol, amountSize,
                        Modifier.align(Alignment.Center).offset(y = rd(16f)))
                } else {
                    Column(Modifier.align(Alignment.Center).offset(y = rd(16f)),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        RefText("—", 38f, P.White)
                        RefText(receivePending, 13f, P.Muted)
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = rd(12f)).size(rd(38f))
                .clip(CircleShape).background(Color(0xFF111213))
                .clickable(enabled = destinationEnabled, role = Role.Button, onClick = onDestinationClick)
                .semantics { contentDescription = "Select receive network" },
            contentAlignment = Alignment.Center,
        ) { RefIcon("deposit", 21f, P.Muted) }
    }
}

@Composable
private fun CenteredConversionAmount(
    display: String,
    prefix: String,
    ticker: String,
    size: Float,
    modifier: Modifier = Modifier,
) {
    val gap = rd(6f)
    // Center the number itself. Prefix and ticker share its baseline without shifting its axis.
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            RefText(display, size, P.White, FontWeight.Normal)
            RefText(prefix, if (prefix == "$") size else 22f, Color(0xFF898A8D))
            RefText(ticker, 17f, P.Muted)
        },
    ) { measurables, constraints ->
        val number = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val sideSpace = ((constraints.maxWidth - number.width) / 2 - gap.roundToPx()).coerceAtLeast(0)
        val sideConstraints = constraints.copy(minWidth = 0, maxWidth = sideSpace, minHeight = 0)
        val leading = measurables[1].measure(sideConstraints)
        val trailing = measurables[2].measure(sideConstraints)
        val pieces = listOf(number, leading, trailing)
        val baselines = pieces.map { it[FirstBaseline].takeIf { baseline -> baseline != AlignmentLine.Unspecified } ?: it.height }
        val baseline = baselines.maxOrNull() ?: 0
        val height = baseline + pieces.indices.maxOf { pieces[it].height - baselines[it] }
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            val numberX = (constraints.maxWidth - number.width) / 2
            number.placeRelative(numberX, baseline - baselines[0])
            leading.placeRelative(numberX - leading.width - if (prefix == "$") 0 else gap.roundToPx(), baseline - baselines[1])
            trailing.placeRelative(numberX + number.width + gap.roundToPx(), baseline - baselines[2])
        }
    }
}

/** The conversion-card chip contains the network emblem and name, with no wallet balance. */
@Composable
private fun PaymentNetworkPill(
    asset: PurchasePaymentAsset?,
    enabled: Boolean,
    onClick: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier.height(rd(40f)).clip(CircleShape).background(Color(0xFF272727))
            .border(rd(1f), P.White.copy(alpha = .065f), CircleShape)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clickable(enabled = enabled, role = Role.Button) { onClick(bounds) }
            .semantics { contentDescription = "Select assets, ${asset?.network?.displayName ?: "not selected"}" }
            .padding(start = rd(4f), end = rd(12f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaymentNetworkMark(asset?.network, 32f, true)
        Spacer(Modifier.width(rd(7f)))
        RefText(asset?.network?.displayName ?: "Select asset", 14f, P.White, FontWeight.Medium)
    }
}

@Composable
private fun StockConversionPill(stock: Stock, logo: StockLogoReference?, modifier: Modifier = Modifier) {
    Row(
        modifier.height(rd(40f)).clip(CircleShape).background(Color(0xFF272727))
            .border(rd(1f), P.White.copy(alpha = .065f), CircleShape)
            .padding(start = rd(4f), end = rd(12f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrderStockMark(stock, logo, 32f)
        Spacer(Modifier.width(rd(7f)))
        RefText(stock.symbol, 14f, P.White, FontWeight.Medium)
    }
}

@Composable
private fun DestinationConversionPill(destination: PurchaseDestination?, modifier: Modifier = Modifier) {
    Row(
        modifier.height(rd(40f)).clip(CircleShape).background(Color(0xFF272727))
            .border(rd(1f), P.White.copy(alpha = .065f), CircleShape)
            .padding(start = rd(4f), end = rd(12f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaymentNetworkMark(destination?.network, 32f, destination != null)
        Spacer(Modifier.width(rd(7f)))
        RefText(destination?.symbol ?: "Select asset", 14f, P.White, FontWeight.Medium)
    }
}

@Composable
private fun OrderRouteRow(
    side: OrderSide,
    stock: Stock,
    stockLogoReference: StockLogoReference?,
    selectedAsset: PurchasePaymentAsset?,
    paymentBalanceLabel: String? = null,
    paymentBalance: BigDecimal?,
    selectedDestination: PurchaseDestination?,
    destinations: List<PurchaseDestination>,
    paymentSelectorEnabled: Boolean,
    destinationEnabled: Boolean,
    onPaymentClick: () -> Unit,
    onStockClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(rd(88f)).padding(horizontal = rd(22f))) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (side == OrderSide.BUY) {
                PaymentRouteNode(selectedAsset, paymentSelectorEnabled, Modifier.weight(1f), onPaymentClick,
                    paymentBalanceLabel)
            } else {
                StockRouteNode(
                    stock, stockLogoReference, "Sell from",
                    paymentBalance?.let { "${tradePlain(it)} available" } ?: "Balance unavailable",
                    false, Modifier.weight(1f), {},
                )
            }
            // Equal route widths reserve the same space on either side of the screen's midpoint.
            Spacer(Modifier.width(rd(38f)))
            if (side == OrderSide.BUY) {
                StockRouteNode(stock, stockLogoReference, "Receive",
                    selectedDestination?.network?.displayName ?: destinations.singleOrNull()?.network?.displayName ?: "Best network",
                    destinationEnabled, Modifier.weight(1f), onStockClick)
            } else {
                DestinationRouteNode(selectedDestination, destinationEnabled, Modifier.weight(1f), onStockClick)
            }
        }
        Box(
            Modifier.align(Alignment.Center).size(rd(30f)).background(P.Chip, CircleShape),
            contentAlignment = Alignment.Center,
        ) { RefIcon("chevronRight", 17f, if (side == OrderSide.BUY) P.Lime else P.Pink) }
    }
}

@Composable
private fun PaymentRouteNode(
    asset: PurchasePaymentAsset?,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    balanceLabel: String? = null,
) {
    Row(
        modifier.height(rd(78f)).clip(RoundedCornerShape(rd(18f)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Select assets, pay with ${asset?.symbol ?: "no asset selected"} " +
                    "on ${asset?.network?.displayName ?: "a network"}, ${paymentValueLabel(asset)}"
                if (!enabled) disabled()
            }
            .padding(horizontal = rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaymentNetworkMark(asset?.network, 50f, asset != null)
        Spacer(Modifier.width(rd(8f)))
        Column(
            Modifier.weight(1f),
        ) {
            RefText("Pay with", 11f, P.Muted)
            Spacer(Modifier.height(rd(3f)))
            RefText(asset?.symbol ?: "Select assets", 14f, P.White, FontWeight.Medium, maxLines = 1)
            RefText(if (asset == null) "Choose from wallet"
                else "${asset.network.displayName} · ${balanceLabel ?: paymentValueLabel(asset)}",
                10.5f, P.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun PaymentOption(asset: PurchasePaymentAsset?, showLogo: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(rd(30f)), contentAlignment = Alignment.CenterStart) {
            if (showLogo && asset != null) PaymentNetworkMark(asset.network, 24f, true)
        }
        Column(Modifier.weight(1f)) {
            PaymentNameAndBalance(asset)
            RefText(asset?.network?.displayName ?: "Select asset", 10.5f, P.Muted)
        }
    }
}

@Composable
private fun PaymentNameAndBalance(asset: PurchasePaymentAsset?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText(asset?.symbol ?: "—", 14f, P.White, FontWeight.Medium)
        Spacer(Modifier.width(rd(6f)))
        RefText(paymentValueLabel(asset), 12f, P.Muted, modifier = Modifier.weight(1f))
    }
}

private fun paymentValueLabel(asset: PurchasePaymentAsset?): String = asset?.usdValue?.let {
    "\$${tradeDisplayInput(tradeMoney(it))}"
} ?: if (asset?.balance?.signum() == 0) "$0.00" else "Unavailable"

@Composable
private fun StockRouteNode(
    stock: Stock,
    logoReference: StockLogoReference?,
    label: String,
    detail: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(rd(78f)).clip(RoundedCornerShape(rd(18f)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "$label, ${stock.symbol}, $detail"
                if (!enabled) disabled()
            }
            .padding(horizontal = rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrderStockMark(stock, logoReference, 50f)
        Spacer(Modifier.width(rd(8f)))
        Column(Modifier.weight(1f)) {
            RefText(label, 11f, P.Muted)
            Spacer(Modifier.height(rd(3f)))
            RefText(stock.symbol, 14f, P.White, FontWeight.Medium)
            RefText(detail, 10.5f, P.Muted)
        }
    }
}

/** Company artwork for the conversion reference; other listings retain their catalog artwork. */
@Composable
private fun OrderStockMark(stock: Stock, logo: StockLogoReference?, markSize: Float) {
    val companySymbol = stock.symbol.removeSuffix(".US").removeSuffix("x").uppercase()
    if (companySymbol != "MSFT") {
        StockIcon(stock, markSize, logoReference = logo)
        return
    }
    Canvas(Modifier.size(rd(markSize)).semantics { contentDescription = stock.name }) {
        val s = size.minDimension
        drawCircle(Color.White, s / 2f)
        val square = s * .29f
        val inset = s * .19f
        val gap = s * .04f
        listOf(Color(0xFFF25022), Color(0xFF7FBA00), Color(0xFF00A4EF), Color(0xFFFFB900))
            .forEachIndexed { index, color ->
                drawRect(color, Offset(inset + (index % 2) * (square + gap),
                    inset + (index / 2) * (square + gap)), Size(square, square))
            }
    }
}

@Composable
private fun DestinationRouteNode(
    destination: PurchaseDestination?,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.height(rd(78f)).clip(RoundedCornerShape(rd(18f)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Receive asset, ${destination?.label ?: "best verified route"}"
                if (!enabled) disabled()
            }
            .padding(horizontal = rd(7f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaymentNetworkMark(destination?.network, 50f, destination != null)
        Spacer(Modifier.width(rd(8f)))
        Column(Modifier.weight(1f)) {
            RefText("Receive asset", 11f, P.Muted)
            Spacer(Modifier.height(rd(3f)))
            RefText(destination?.symbol ?: "Best route", 14f, P.White, FontWeight.Medium)
            RefText(destination?.network?.displayName ?: "Chosen after quote", 10.5f, P.Muted)
        }
    }
}

@Composable
private fun OrderBottomControls(
    actionLabel: String,
    canContinue: Boolean,
    keypadVisible: Boolean,
    maximumDecimals: Int,
    input: String,
    onInputChange: (String) -> Unit,
    onContinue: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().background(P.Background)) {
        Spacer(Modifier.height(rd(4f)))
        Box(
            Modifier.padding(horizontal = rd(22f)).fillMaxWidth().height(rd(53f)).clip(CircleShape)
                .background(if (canContinue && actionLabel != "Enter amount") P.Lime else P.Chip)
                .clickable(enabled = canContinue || onRefresh != null, role = Role.Button) {
                    if (canContinue) onContinue() else onRefresh?.invoke()
                }
                .semantics {
                    contentDescription = actionLabel
                    if (!canContinue && onRefresh == null) disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            RefText(actionLabel, if (actionLabel.length > 25) 15f else 18f,
                if (canContinue && actionLabel != "Enter amount") P.Background else if (onRefresh != null) P.White else P.Muted,
                FontWeight.Medium)
        }
        if (keypadVisible) {
            Spacer(Modifier.height(rd(8f)))
            TradeKeypad(
                onKey = { onInputChange(tradeAppendKey(input, it, maximumDecimals)) },
                onBackspace = { onInputChange(input.dropLast(1).ifEmpty { "0" }) },
            )
        }
        Spacer(Modifier.height(rd(7f)))
    }
}

@Composable
private fun TradeKeypad(onKey: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "backspace"),
    )
    Column(
        Modifier.fillMaxWidth().padding(horizontal = rd(22f)),
        verticalArrangement = Arrangement.spacedBy(rd(2f)),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(rd(54f))) {
                row.forEach { key ->
                    Box(
                        Modifier.weight(1f).height(rd(54f))
                            .semantics { contentDescription = if (key == "backspace") "Delete digit" else key }
                            .clickable(role = Role.Button) {
                                if (key == "backspace") onBackspace() else onKey(key)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "backspace") RefIcon("backspace", 22f, P.Muted)
                        else RefText(key, 31f, weight = FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentSelectorPopup(
    visible: Boolean,
    assets: List<PurchasePaymentAsset>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (PurchasePaymentAsset) -> Unit,
) {
    AnimatedVisibility(visible = visible,
        enter = fadeIn(tween(140)), exit = fadeOut(tween(110))) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f))
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close asset selector" },
            )
            PaymentTokenDropdown(
                assets = assets,
                network = null,
                selectedId = selectedId,
                onBack = onDismiss,
                onSelect = onSelect,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
    }
}

/** Plain inline wheel: three rows, with the focused row bright and its neighbours faded. */
@Composable
internal fun PaymentAssetWheel(
    assets: List<PurchasePaymentAsset>,
    selectedId: String?,
    onSelect: (PurchasePaymentAsset) -> Unit,
    modifier: Modifier = Modifier,
    onScrollSelect: (PurchasePaymentAsset) -> Unit = onSelect,
) {
    val funded = fundedPaymentAssets(assets)
    val rowHeight = 44f
    val circular = funded.size >= 3
    val viewportRows = if (funded.size >= 2) 3 else 1
    val selectedIndex = funded.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val centerCycle = if (circular) funded.size * 1_000 else 0
    val wheelState = key(funded.map { it.id }) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = if (circular) centerCycle + selectedIndex - 1 else selectedIndex,
        )
    }
    val currentAssets by rememberUpdatedState(funded)
    val applySelection by rememberUpdatedState(onScrollSelect)
    val activeId by rememberUpdatedState(selectedId)
    LaunchedEffect(wheelState, funded.map { it.id }) {
        var wasScrolling = false
        snapshotFlow { wheelState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) wasScrolling = true
            else if (wasScrolling && funded.isNotEmpty()) {
                wasScrolling = false
                val layout = wheelState.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.let { item ->
                    val asset = currentAssets[item.index % currentAssets.size]
                    if (asset.id != activeId) applySelection(asset)
                }
            }
        }
    }
    if (funded.isEmpty()) {
        Box(modifier.height(rd(44f)), contentAlignment = Alignment.CenterStart) {
            RefText("No funded assets", 12f, P.Muted)
        }
    } else {
        LazyColumn(
            state = wheelState,
            flingBehavior = rememberSnapFlingBehavior(wheelState, SnapPosition.Center),
            // Padding allows either of two funded assets to snap into the middle without fake rows.
            contentPadding = PaddingValues(vertical = rd(if (funded.size == 2) rowHeight else 0f)),
            modifier = modifier.height(rd(rowHeight * viewportRows)).selectableGroup()
                .semantics { paneTitle = "Choose payment asset and chain" },
        ) {
            items(if (circular) funded.size * 2_001 else funded.size) { index ->
                val asset = funded[index % funded.size]
                Column(
                    Modifier.fillMaxWidth().height(rd(rowHeight))
                        .graphicsLayer {
                            val layout = wheelState.layoutInfo
                            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                            val distance = item?.let { abs(it.offset + it.size / 2f - center) / it.size }
                                ?.coerceIn(0f, 1f) ?: 1f
                            alpha = 1f - distance * .72f
                        }
                        .selectable(asset.id == selectedId, role = Role.RadioButton) { onSelect(asset) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${asset.symbol} on ${asset.network.displayName}, ${paymentValueLabel(asset)}"
                            stateDescription = if (asset.id == selectedId) "Selected" else "Choose payment asset"
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    PaymentOption(asset, showLogo = true)
                }
            }
        }
    }
}

@Composable
internal fun PaymentTokenDropdown(
    assets: List<PurchasePaymentAsset>,
    network: PurchaseNetwork?,
    selectedId: String?,
    onBack: () -> Unit,
    onSelect: (PurchasePaymentAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val networkAssets = fundedPaymentAssets(assets).filter { network == null || it.network == network }
    Column(
        modifier.heightIn(min = rd(340f), max = rd(520f))
            .clip(RoundedCornerShape(topStart = rd(30f), topEnd = rd(30f)))
            .background(WalletStyle.Panel)
            .semantics { paneTitle = if (network == null) "Select assets" else "Choose token on ${network.displayName}" }
            .padding(horizontal = rd(26f), vertical = rd(20f)),
    ) {
        Row(
            Modifier.fillMaxWidth().height(rd(48f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                RefText("Tokens", 20f, WalletStyle.White, FontWeight.Medium)
                RefText(network?.displayName ?: "Choose an asset to pay with", 11f, WalletStyle.Muted)
            }
            Box(
                Modifier.size(rd(40f)).clip(CircleShape).background(WalletStyle.Circle)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Close asset selector" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 18f, WalletStyle.White) }
        }
        Spacer(Modifier.height(rd(12f)))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).selectableGroup(),
            contentPadding = PaddingValues(bottom = rd(12f)),
        ) {
            if (networkAssets.isEmpty()) {
                item {
                    RefText("No tokens held yet", 14f, WalletStyle.Muted,
                        modifier = Modifier.padding(top = rd(6f)))
                }
            }
            items(networkAssets.size) { index ->
                val asset = networkAssets[index]
                PaymentTokenRow(
                    asset = asset,
                    selected = asset.id == selectedId,
                    onClick = { onSelect(asset) },
                )
            }
        }
    }
}

@Composable
private fun PaymentTokenRow(
    asset: PurchasePaymentAsset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(73f)).clip(RoundedCornerShape(rd(16f)))
            .background(if (selected) P.White.copy(alpha = .08f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(asset.name)
                    append(", ")
                    append(asset.symbol)
                    append(" on ")
                    append(asset.network.displayName)
                    append(", balance ")
                    append(tradePlain(asset.balance))
                    append(" ")
                    append(asset.symbol)
                }
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(horizontal = rd(8f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaymentAssetMark(asset, 44f, selected)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(asset.symbol, 17f, WalletStyle.White, FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(rd(3f)))
            RefText("${asset.name} · ${asset.network.displayName}", 12f, WalletStyle.Muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            RefText(asset.usdValue?.let { "\$${tradeMoney(it)}" } ?: "—", 16f, WalletStyle.White)
            Spacer(Modifier.height(rd(3f)))
            RefText("${tradePlain(asset.balance)} ${asset.symbol}", 12f, WalletStyle.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun DestinationPicker(
    visible: Boolean,
    state: PurchaseUiState?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(140)), exit = fadeOut(tween(110))) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f))
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = "Close destination selector" },
            )
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = rd(22f)).fillMaxWidth()
                    .clip(RoundedCornerShape(rd(22f))).background(Color(0xFF202126))
                    .padding(vertical = rd(10f)),
            ) {
                RefText(
                    "Receive network",
                    17f,
                    P.White,
                    FontWeight.Bold,
                    Modifier.padding(horizontal = rd(16f), vertical = rd(8f)),
                )
                TradeSelectionRow(
                    "Best network",
                    "Eleven chooses the verified route",
                    state?.selectedDestinationId == null,
                ) { onSelect(null) }
                state?.options?.destinations?.forEach { destination ->
                    TradeSelectionRow(
                        destination.label,
                        destination.address.tradeCompactAddress(),
                        state.selectedDestinationId == destination.id,
                        enabled = destination.enabled,
                    ) { onSelect(destination.id) }
                }
            }
        }
    }
}

@Composable
private fun TradeSelectionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(50f))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, $subtitle"
                stateDescription = if (selected) "Selected" else if (enabled) "Not selected" else "Unavailable"
                if (!enabled) disabled()
            }
            .padding(horizontal = rd(16f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RefText(title, 14f, if (enabled) P.White else P.Muted, FontWeight.Medium)
            RefText(subtitle, 11f, P.Muted)
        }
        if (selected) RefIcon("verified", 18f, P.Lime)
    }
}

@Composable
private fun PurchaseQuoteCard(state: PurchaseUiState, onEdit: (() -> Unit)?) {
    val quote = state.quote ?: return
    val asset = state.selectedPaymentAsset ?: return
    Column(
        Modifier.padding(horizontal = rd(22f)).fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(P.Card)
            .padding(horizontal = rd(16f), vertical = rd(13f)),
        verticalArrangement = Arrangement.spacedBy(rd(8f)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RefText(
                if (BuildConfig.PURCHASE_EXECUTION_ENABLED && quote.executionEnabled) "Final route" else "Route preview",
                16f,
                P.White,
                FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (onEdit != null) {
                Box(
                    Modifier.height(rd(30f)).clip(CircleShape).background(P.Chip)
                        .clickable(role = Role.Button, onClick = onEdit)
                        .padding(horizontal = rd(11f)),
                    contentAlignment = Alignment.Center,
                ) { RefText("Edit", 12f, P.Lime, FontWeight.Medium) }
            } else {
                RefText(
                    "${quote.walletConfirmations} confirmation" + if (quote.walletConfirmations == 1) "" else "s",
                    11f,
                    P.Muted,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(14f)))
                .background(P.Lime.copy(alpha = .10f))
                .border(rd(1f), P.Lime.copy(alpha = .22f), RoundedCornerShape(rd(14f)))
                .padding(horizontal = rd(13f), vertical = rd(10f)),
        ) {
            RefText("Estimated ${quote.destination.symbol} received", 11f, P.Muted)
            RefText(
                "${tradePlain(quote.estimatedOutputAmount)} ${quote.destination.symbol}",
                22f,
                P.Lime,
                FontWeight.Bold,
            )
            RefText(
                if (state.side == OrderSide.BUY) {
                    "For ${tradePlain(quote.inputAmount)} ${asset.symbol} spent"
                } else {
                    "For ${tradePlain(quote.inputAmount)} ${asset.symbol} sold"
                },
                10.5f,
                P.Muted,
            )
        }
        TradeQuoteRow(if (state.side == OrderSide.BUY) "Spend" else "Sell",
            "${tradePlain(quote.inputAmount)} ${asset.symbol}")
        quote.executableUnitPriceUsd?.let { TradeQuoteRow("Executable unit price", "\$${tradeMoney(it)}") }
        TradeQuoteRow("Receive network", quote.destination.network.displayName)
        TradeQuoteRow("Estimated fees", quote.feesUsd?.let { "\$${tradeMoney(it)}" } ?: "Unavailable")
        TradeQuoteRow("Price impact", quote.priceImpactPercent?.let { "${tradePlain(it)}%" } ?: "Unavailable")
        TradeQuoteRow("Max slippage", "${BigDecimal(quote.slippageBps).movePointLeft(2).stripTrailingZeros().toPlainString()}%")
        TradeQuoteRow("Minimum received", "${tradePlain(quote.minimumReceived)} ${quote.destination.symbol}")
        val seconds = java.time.Duration.between(java.time.Instant.now(), quote.expiresAt).seconds.coerceAtLeast(0)
        TradeQuoteRow("Quote expires", if (seconds > 0) "in ${seconds}s" else "expired")
        RefText("Eleven handles the verified swaps and network transfer inside this route.", 11f, P.Muted)
        if (!BuildConfig.PURCHASE_EXECUTION_ENABLED || !quote.executionEnabled) {
            RefText(quote.executionReason ?: "Live execution is not enabled yet.", 11f, Color(0xFFFFC857), maxLines = 3)
        }
    }
}

@Composable
private fun TradeQuoteRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText(label, 12f, P.Muted)
        Spacer(Modifier.weight(1f))
        RefText(value, 12f, P.White, FontWeight.Medium)
    }
}

@Composable
private fun PaymentAssetMark(asset: PurchasePaymentAsset, markSize: Float, selected: Boolean) {
    Box(
        Modifier.size(rd(markSize)).clip(CircleShape)
            .border(
                rd(if (selected) 1.5f else 1f),
                if (selected) P.Lime else P.White.copy(alpha = .12f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PaymentNetworkMark(asset.network, markSize, true)
        Box(
            Modifier.align(Alignment.BottomEnd).height(rd(13f)).clip(CircleShape)
                .background(if (selected) P.Lime else P.White)
                .padding(horizontal = rd(3f)),
            contentAlignment = Alignment.Center,
        ) {
            RefText(asset.symbol, 6.5f, P.Background, FontWeight.Bold)
        }
    }
}

/** Local vector network marks keep the funding selector deterministic and offline-safe. */
@Composable
private fun PaymentNetworkMark(network: PurchaseNetwork?, markSize: Float, enabled: Boolean) {
    val markAlpha = if (enabled) 1f else .5f
    Canvas(Modifier.size(rd(markSize)).alpha(markAlpha)) {
        val s = size.minDimension
        val center = Offset(s / 2f, s / 2f)
        fun polygon(points: List<Offset>): Path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        when (network) {
            PurchaseNetwork.SOLANA -> {
                drawSolanaLogo()
            }
            PurchaseNetwork.ETHEREUM -> {
                drawCircle(Color(0xFF627EEA), s / 2f, center)
                drawPath(
                    polygon(
                        listOf(
                            Offset(s * .5f, s * .14f),
                            Offset(s * .28f, s * .52f),
                            Offset(s * .5f, s * .63f),
                            Offset(s * .72f, s * .52f),
                        ),
                    ),
                    Color.White,
                )
                drawPath(
                    polygon(
                        listOf(
                            Offset(s * .5f, s * .68f),
                            Offset(s * .29f, s * .57f),
                            Offset(s * .5f, s * .86f),
                            Offset(s * .71f, s * .57f),
                        ),
                    ),
                    Color.White.copy(alpha = .82f),
                )
            }
            PurchaseNetwork.BASE -> {
                drawCircle(Color(0xFF0052FF), s / 2f, center)
                drawCircle(Color.White, s * .29f, center)
                drawRect(Color(0xFF0052FF), Offset(s * .50f, s * .19f), Size(s * .36f, s * .62f))
            }
            PurchaseNetwork.ARBITRUM -> {
                drawCircle(Color(0xFF213147), s / 2f, center)
                val shell = polygon(
                    listOf(
                        Offset(s * .50f, s * .10f), Offset(s * .82f, s * .29f),
                        Offset(s * .82f, s * .68f), Offset(s * .50f, s * .88f),
                        Offset(s * .18f, s * .68f), Offset(s * .18f, s * .29f),
                    ),
                )
                drawPath(shell, Color(0xFF2D374B))
                drawLine(Color(0xFF28A0F0), Offset(s * .31f, s * .70f), Offset(s * .52f, s * .29f), s * .11f, StrokeCap.Butt)
                drawLine(Color(0xFF12AAFF), Offset(s * .48f, s * .76f), Offset(s * .68f, s * .37f), s * .10f, StrokeCap.Butt)
                drawLine(Color.White, Offset(s * .28f, s * .58f), Offset(s * .45f, s * .25f), s * .075f, StrokeCap.Butt)
            }
            null -> {
                drawCircle(P.Chip, s / 2f, center)
                drawCircle(P.Muted, s * .29f, center, style = Stroke(s * .045f))
                drawLine(P.Muted, Offset(s * .35f, s * .42f), Offset(s * .65f, s * .42f), s * .045f, StrokeCap.Round)
                drawLine(P.Muted, Offset(s * .35f, s * .58f), Offset(s * .65f, s * .58f), s * .045f, StrokeCap.Round)
            }
        }
    }
}

internal fun tradeAppendKey(current: String, key: String, maximumDecimals: Int = 18): String {
    if (key == ".") return if (maximumDecimals > 0 && '.' !in current) "$current." else current
    if (key.length != 1 || key[0] !in '0'..'9') return current
    val decimalIndex = current.indexOf('.')
    if (decimalIndex >= 0 && current.length - decimalIndex - 1 >= maximumDecimals) return current
    val candidate = if (current == "0") key else current + key
    return if (candidate.substringBefore('.').length <= 16) candidate else current
}

private fun tradePlain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private fun tradeEstimate(value: BigDecimal): String {
    val scale = (3 - value.precision() + value.scale()).coerceIn(3, 18)
    return tradePlain(value.setScale(scale, RoundingMode.HALF_UP))
}

private fun tradeMoney(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun String.tradeCompactAddress(): String = if (length <= 14) this else take(6) + "…" + takeLast(5)

/** Group the visible integer while preserving a trailing decimal and typed zeroes. */
internal fun tradeDisplayInput(input: String): String {
    val integer = input.substringBefore('.')
    val grouped = integer.reversed().chunked(3).joinToString(",").reversed()
    return if ('.' in input) grouped + "." + input.substringAfter('.') else grouped
}
