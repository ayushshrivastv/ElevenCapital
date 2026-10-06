package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.R
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay

private val balanceCream = Color(0xFFF8F8EB)
private val balanceMuted = Color(0xFF8F8F8F)
private val balanceDivider = Color(0xFF242424)
private val balanceRed = Color(0xFFFF717B)
private val balanceGreen = Color(0xFFB8E77A)

enum class PredictionBalanceTransferDirection { ADD, WITHDRAW }
enum class PredictionBalanceTransferPhase { SUBMITTING, CONFIRMING, CONFIRMED, FAILED }

/** CONFIRMED means the app-local practice ledger save completed. */
data class PredictionBalanceTransferStatus(
    val requestId: String,
    val phase: PredictionBalanceTransferPhase,
    val transactionHash: String? = null,
    val message: String? = null,
)

data class PredictionBalanceHistoryItem(
    val id: String,
    val title: String,
    val detail: String,
    val amountUsd: BigDecimal,
    val incoming: Boolean,
    val assetAmount: BigDecimal? = null,
    val performance: Boolean = false,
)

private enum class BalanceView { HOME, AMOUNT, PROCESSING, SUCCESS }

/** Local practice balance for the Pyth-backed prediction experience. */
@Composable
fun PredictionBalanceScreen(
    predictionBalanceUsd: BigDecimal,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSubmitTransfer: (PredictionBalanceTransferDirection, BigDecimal, String) -> Unit,
    performanceUsd: BigDecimal = BigDecimal.ZERO,
    performancePercent: BigDecimal? = null,
    history: List<PredictionBalanceHistoryItem> = emptyList(),
    transferStatus: PredictionBalanceTransferStatus? = null,
    sourceUsdcAvailable: BigDecimal = BigDecimal.ZERO,
) {
    var view by remember { mutableStateOf(BalanceView.HOME) }
    var direction by remember { mutableStateOf(PredictionBalanceTransferDirection.ADD) }
    var amountText by remember { mutableStateOf("0") }
    var submittedRequestId by remember { mutableStateOf<String?>(null) }
    var submissionError by remember { mutableStateOf<String?>(null) }
    val matchingStatus = transferStatus?.takeIf { it.requestId == submittedRequestId }

    LaunchedEffect(matchingStatus?.phase, matchingStatus?.requestId) {
        if (view == BalanceView.PROCESSING &&
            matchingStatus?.phase == PredictionBalanceTransferPhase.CONFIRMED) {
            view = BalanceView.SUCCESS
            onRefresh()
        }
    }
    LaunchedEffect(view) {
        if (view == BalanceView.SUCCESS) {
            delay(1_800)
            if (view == BalanceView.SUCCESS) view = BalanceView.HOME
        }
    }
    LaunchedEffect(view, direction) {
        if (view == BalanceView.AMOUNT) {
            delay(200)
            onRefresh()
        }
    }

    fun open(directionChoice: PredictionBalanceTransferDirection) {
        direction = directionChoice
        amountText = "0"
        submittedRequestId = null
        submissionError = null
        view = BalanceView.AMOUNT
    }

    fun back() {
        when (view) {
            BalanceView.HOME -> onBack()
            else -> view = BalanceView.HOME
        }
    }
    BackHandler(view != BalanceView.HOME) { back() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
    val compactAmount = maxHeight < rd(670f)
    val homeScrollState = rememberScrollState()
    val amountScrollState = rememberScrollState()
    if (view != BalanceView.SUCCESS) Column(Modifier.fillMaxSize().background(Color.Black)
        .blur(if (view == BalanceView.PROCESSING) rd(9f) else rd(0f))) {
        Row(Modifier.fillMaxWidth().height(rd(68f)).padding(horizontal = rd(22f)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(42f)).background(Color(0xFF171717), CircleShape)
                .clickable(role = Role.Button, onClick = ::back)
                .semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
                RefIcon("back", 22f, Color.White)
            }
            Spacer(Modifier.weight(1f))
            RefText(when (view) {
                BalanceView.HOME -> "Prediction balance"
                BalanceView.AMOUNT -> if (direction == PredictionBalanceTransferDirection.ADD) "Add money" else "Withdraw"
                BalanceView.PROCESSING -> if (direction == PredictionBalanceTransferDirection.ADD)
                    "Add money" else "Withdraw"
                BalanceView.SUCCESS -> "Transfer complete"
            }, 16f, Color.White, FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(rd(42f)), contentAlignment = Alignment.Center) {
                if (view == BalanceView.HOME) BrandLogo(Modifier.size(rd(25f)))
            }
        }
        Crossfade(targetState = view == BalanceView.HOME, animationSpec = tween(180),
            modifier = Modifier.weight(1f).fillMaxWidth(), label = "balance-page") { showHome ->
            if (showHome) Column(Modifier.fillMaxSize().verticalScroll(homeScrollState)
                .padding(horizontal = rd(22f))) {
                Spacer(Modifier.height(rd(60f)))
                RefText(balanceDollars(predictionBalanceUsd), balanceAmountSize(predictionBalanceUsd),
                    Color.White, FontWeight.Medium)
                Spacer(Modifier.height(rd(10f)))
                val pnlColor = when (performanceUsd.signum()) {
                    1 -> balanceGreen
                    -1 -> balanceRed
                    else -> balanceMuted
                }
                val pnlLabel = (if (performanceUsd.signum() > 0) "+" else if (performanceUsd.signum() < 0) "−" else "") +
                    balanceDollars(performanceUsd.abs()) +
                    ((performancePercent ?: BigDecimal.ZERO.takeIf { performanceUsd.signum() == 0 })?.let {
                        " (" + (if (it.signum() > 0) "+" else "") +
                            it.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%)"
                    } ?: "")
                RefText(pnlLabel, 22f, pnlColor, FontWeight.Medium)
                Spacer(Modifier.height(rd(29f)))
                val transferPending = transferStatus?.phase in setOf(
                    PredictionBalanceTransferPhase.SUBMITTING,
                    PredictionBalanceTransferPhase.CONFIRMING)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(12f))) {
                    Box(Modifier.weight(1f)) {
                        BalanceCreamButton("Add", "addCircle", enabled = !transferPending) {
                            open(PredictionBalanceTransferDirection.ADD)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        BalanceCreamButton("Withdraw", "arrowUpRight", enabled = !transferPending) {
                            open(PredictionBalanceTransferDirection.WITHDRAW)
                        }
                    }
                }
                if (transferPending) {
                    Spacer(Modifier.height(rd(14f)))
                    RefText("Transfer processing", 12f, balanceMuted)
                    Spacer(Modifier.height(rd(8f)))
                    BalanceOutlineButton("Refresh transfer", onRefresh)
                }
                Spacer(Modifier.height(rd(66f)))
                RefText("Prediction history", 18f, Color.White, FontWeight.SemiBold)
                Spacer(Modifier.height(rd(12f)))
                if (history.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(rd(86f)), contentAlignment = Alignment.CenterStart) {
                        RefText("No prediction transactions yet", 13f, balanceMuted)
                    }
                } else {
                    history.forEach { item ->
                        PredictionBalanceHistoryRow(item)
                    }
                }
                Spacer(Modifier.height(rd(26f)))
            } else Column(Modifier.fillMaxSize()
                .then(if (compactAmount) Modifier.verticalScroll(amountScrollState) else Modifier)
                .padding(horizontal = rd(22f))) {
                val enteredUsd = amountText.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                val sourceUsd = if (direction == PredictionBalanceTransferDirection.ADD)
                    sourceUsdcAvailable else predictionBalanceUsd
                val canContinue = enteredUsd != null && enteredUsd <= sourceUsd
                if (compactAmount) Spacer(Modifier.height(rd(18f)))
                else Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RefText(amountText, if (amountText.length > 9) 54f else 78f,
                        Color.White, FontWeight.Medium)
                }
                Spacer(Modifier.height(rd(15f)))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.clip(RoundedCornerShape(rd(7f))).background(Color(0xFF151515))
                        .padding(horizontal = rd(12f), vertical = rd(5f))) {
                        RefText("USDC", 13f, Color.White, FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(rd(if (compactAmount) 15f else 30f)))
                Row(Modifier.fillMaxWidth().height(rd(74f))
                    .clip(RoundedCornerShape(rd(17f))).background(Color(0xFF111111))
                    .clickable(role = Role.Button, onClick = onRefresh)
                    .semantics { contentDescription = "Refresh balance" }
                    .padding(horizontal = rd(16f)), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(rd(39f)).background(Color(0xFF242424), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.token_usdc_official), null,
                            Modifier.size(rd(27f)))
                    }
                    Spacer(Modifier.size(rd(13f)))
                    Column {
                        RefText("Practice USDC · Solana", 15f, Color.White, FontWeight.SemiBold)
                        RefText(balanceDollars(sourceUsd) + " Available", 12f, balanceMuted)
                    }
                }
                if (enteredUsd != null && enteredUsd > sourceUsd) {
                    Spacer(Modifier.height(rd(8f)))
                    RefText("Amount exceeds your balance", 12f, balanceRed)
                }
                if (compactAmount) Spacer(Modifier.height(rd(12f)))
                else Spacer(Modifier.weight(1f))
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"),
                    listOf("7", "8", "9"), listOf(".", "0", "⌫")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                        row.forEach { digit ->
                            Box(Modifier.weight(1f).height(rd(if (compactAmount) 51f else 62f))
                                .clickable(role = Role.Button) { amountText = nextBalanceAmount(amountText, digit) }
                                .semantics { contentDescription = if (digit == "⌫") "Delete digit" else "Enter " + digit },
                                contentAlignment = Alignment.Center) {
                                RefText(digit, 27f, Color.White)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(rd(8f)))
                BalanceCreamButton("Continue", null, enabled = canContinue) {
                    val requestedUsd = requireNotNull(enteredUsd)
                    val requestId = UUID.randomUUID().toString()
                    submittedRequestId = requestId
                    submissionError = null
                    view = BalanceView.PROCESSING
                    try {
                        onSubmitTransfer(direction, requestedUsd, requestId)
                    } catch (_: Exception) {
                        submissionError = "Transfer could not start"
                    }
                }
                Spacer(Modifier.height(rd(8f)))
            }
        }
    }
    if (view == BalanceView.PROCESSING) {
        PredictionTransferProcessingOverlay(
            direction = direction,
            failed = matchingStatus?.phase == PredictionBalanceTransferPhase.FAILED || submissionError != null,
            onRetry = { submissionError = null; view = BalanceView.AMOUNT },
            onBalance = { view = BalanceView.HOME },
        )
    }
    if (view == BalanceView.SUCCESS) {
        PredictionTransferSuccess(direction)
    }
    }
}

@Composable
private fun BalanceCreamButton(
    label: String,
    icon: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(rd(57f)).background(if (enabled) balanceCream else Color(0xFF353535),
        CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            RefIcon(icon, 18f, if (enabled) Color.Black else balanceMuted)
            Spacer(Modifier.size(rd(9f)))
        }
        RefText(label, 15f, if (enabled) Color.Black else balanceMuted, FontWeight.SemiBold)
    }
}

@Composable
private fun BalanceOutlineButton(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(rd(52f)).background(Color(0xFF1D1D1D), CircleShape)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        RefText(label, 14f, Color.White, FontWeight.Medium)
    }
}

@Composable
private fun PredictionBalanceHistoryRow(item: PredictionBalanceHistoryItem) {
    Row(Modifier.fillMaxWidth().height(rd(70f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(rd(36f)).background(Color(0xFF1B1B1B), CircleShape),
            contentAlignment = Alignment.Center) {
            RefIcon(if (item.incoming) "deposit" else "arrowUpRight", 18f,
                if (item.incoming) balanceGreen else Color.White)
        }
        Spacer(Modifier.size(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(item.title, 14f, Color.White, FontWeight.Medium, maxLines = 1)
            RefText(item.detail, 11f, balanceMuted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            RefText((if (item.incoming) "+" else "−") + balanceDollars(item.amountUsd.abs()),
                14f, if (item.incoming) balanceGreen else if (item.performance) balanceRed else Color.White,
                FontWeight.Medium)
            item.assetAmount?.let { RefText(it.stripTrailingZeros().toPlainString(), 10f, balanceMuted) }
        }
    }
    Box(Modifier.fillMaxWidth().height(rd(1f)).background(balanceDivider))
}

@Composable
private fun PredictionTransferProcessingOverlay(
    direction: PredictionBalanceTransferDirection,
    failed: Boolean,
    onRetry: () -> Unit,
    onBalance: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "wallet-transfer")
    val rotation by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "transfer-spinner")
    val glow by transition.animateFloat(.55f, 1f,
        infiniteRepeatable(tween(1_250), RepeatMode.Reverse), label = "transfer-glow")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (failed) .88f else .68f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center) {
        if (!failed) Canvas(Modifier.fillMaxSize()) {
            val radius = size.width * .95f
            val purple = Offset(size.width * .1f, size.height * .99f)
            val peach = Offset(size.width * .9f, size.height * 1.04f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8059BC).copy(alpha = .25f * glow),
                Color.Transparent), center = purple, radius = radius), radius, purple)
            drawCircle(Brush.radialGradient(listOf(Color(0xFFE9A579).copy(alpha = .22f * glow),
                Color.Transparent), center = peach, radius = radius), radius, peach)
        }
        Column(Modifier.padding(horizontal = rd(36f)), horizontalAlignment = Alignment.CenterHorizontally) {
            if (failed) {
                Box(Modifier.size(rd(22f)).background(balanceRed, CircleShape),
                    contentAlignment = Alignment.Center) {
                    RefIcon("plus", 14f, Color.Black, Modifier.graphicsLayer { rotationZ = 45f })
                }
                Spacer(Modifier.height(rd(15f)))
                RefText("Transfer could not complete", 17f, Color.White, FontWeight.SemiBold)
                Spacer(Modifier.height(rd(27f)))
                BalanceCreamButton("Try again", null, onClick = onRetry)
                Spacer(Modifier.height(rd(11f)))
                BalanceOutlineButton("Back to balance", onBalance)
            } else {
                Canvas(Modifier.size(rd(19f)).graphicsLayer { rotationZ = rotation }) {
                    val stroke = size.minDimension * .12f
                    drawArc(Color.White.copy(alpha = .2f), 0f, 360f, false,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(Color.White, -90f, 245f, false,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Spacer(Modifier.height(rd(14f)))
                RefText(if (direction == PredictionBalanceTransferDirection.ADD) "Adding practice USDC"
                    else "Updating practice balance", 16f, Color.White, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PredictionTransferSuccess(direction: PredictionBalanceTransferDirection) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rd(9f))) {
            Box(Modifier.size(rd(20f)).background(Color.White, CircleShape),
                contentAlignment = Alignment.Center) {
                RefIcon("check", 12f, Color.Black)
            }
            RefText(if (direction == PredictionBalanceTransferDirection.ADD) "Practice balance updated"
                else "Practice withdrawal complete", 20f, Color.White, FontWeight.SemiBold)
        }
    }
}

private fun balanceAmountSize(amount: BigDecimal): Float = when {
    balanceDollars(amount).length >= 12 -> 42f
    balanceDollars(amount).length >= 9 -> 52f
    else -> 70f
}

private fun balanceDollars(amount: BigDecimal): String =
    "$" + DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).format(amount)

private fun nextBalanceAmount(current: String, digit: String): String = when (digit) {
    "⌫" -> current.dropLast(1).ifEmpty { "0" }
    "." -> if (current.contains('.')) current else current + "."
    else -> {
        val candidate = if (current == "0") digit else current + digit
        if (candidate.length > 12 || candidate.substringAfter('.', "").length > 2) current else candidate
    }
}
