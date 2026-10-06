package com.elevencapital.app.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun PredictionAmountSheet(amount: Int, onAmount: (Int) -> Unit, onDismiss: () -> Unit) {
    PredictionSheet("Stake value", onDismiss, glassPanel = true) {
        RefText("\$$amount", 48f, PredictionPalette.Lime, FontWeight.Bold)
        Spacer(Modifier.height(rd(8f)))
        RefText("Choose your stake amount.", 14f, PredictionPalette.Muted)
        Spacer(Modifier.height(rd(24f)))
        listOf(listOf(1, 5, 10), listOf(25, 50, 100)).forEachIndexed { rowIndex, values ->
            if (rowIndex > 0) Spacer(Modifier.height(rd(10f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                values.forEach { value ->
                    val active = value == amount
                    val shape = RoundedCornerShape(rd(16f))
                    Box(
                        Modifier.weight(1f).height(rd(60f)).predictionGlass(shape)
                            .then(if (active) Modifier.border(rd(1f), PredictionPalette.Lime, shape)
                                else Modifier)
                            .semantics { selected = active; contentDescription = "Stake value \$$value" }
                            .clickable(role = Role.RadioButton) { onAmount(value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        RefText("\$$value", 22f, if (active) PredictionPalette.Lime else PredictionPalette.White,
                            FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(rd(22f)))
        PredictionSheetButton("Done", onDismiss, glass = true)
    }
}

@Composable
internal fun PredictionStakeSheet(
    price: Double,
    spotPrice: Double,
    band: Double,
    amount: Int,
    sharePrices: PredictionSharePrices,
    remainingSeconds: Int,
    selected: PredictionDirection,
    onSelect: (PredictionDirection) -> Unit,
    priceChanged: Boolean = false,
    onStake: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    canAfford: Boolean = true,
    onFund: () -> Unit = {},
    collateralSymbol: String? = null,
    marketSymbol: String = "ETH",
    actionLabel: String? = null,
    statusMessage: String? = null,
) {
    val lowerPrice = remember(price, band) { predictionMoney(price - band) }
    val upperPrice = remember(price, band) { predictionMoney(price + band) }
    val quote = sharePrices[selected]
    val payout = if (quote.isFinite() && quote > 0.0) amount / quote else null
    val potentialProfit = payout?.minus(amount)
    PredictionSheet("Stake", onDismiss,
        headerIcon = { PredictionGlyph("flame", 22f, PredictionPalette.Lime) },
        headerTrailing = {
            PredictionMetricCard("Stake value", if (collateralSymbol == null) "\$$amount"
                else "$amount $collateralSymbol", Modifier.width(rd(136f)),
                valueSize = if (collateralSymbol == null) 24f else 20f, minHeight = 70f)
        }, showClose = false, swipeDismiss = true, compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(8f))) {
            PredictionDirection.entries.forEach { direction ->
                val active = selected == direction
                val label = when (direction) {
                    PredictionDirection.ABOVE -> "Upper"
                    PredictionDirection.AROUND -> "Range"
                    PredictionDirection.BELOW -> "Below"
                }
                val value = predictionMoney(when (direction) {
                    PredictionDirection.ABOVE -> price + band
                    PredictionDirection.AROUND -> price
                    PredictionDirection.BELOW -> price - band
                }).removePrefix("$")
                val shape = RoundedCornerShape(rd(13f))
                Column(Modifier.weight(1f)
                    .semantics {
                        this.selected = active
                        contentDescription = "$label, ${when (direction) {
                            PredictionDirection.ABOVE -> "$marketSymbol above $upperPrice at round end"
                            PredictionDirection.AROUND -> "$marketSymbol between $lowerPrice and $upperPrice at round end"
                            PredictionDirection.BELOW -> "$marketSymbol below $lowerPrice at round end"
                        }}"
                    }
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(direction) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val labelColor = if (enabled) {
                        if (active) PredictionPalette.Lime else PredictionPalette.White
                    } else PredictionPalette.Muted
                    Row(horizontalArrangement = Arrangement.spacedBy(rd(4f)),
                        verticalAlignment = Alignment.CenterVertically) {
                        RefIcon(when (direction) {
                            PredictionDirection.ABOVE -> "chevronUp"
                            PredictionDirection.AROUND -> "minus"
                            PredictionDirection.BELOW -> "chevronDown"
                        }, 12f, labelColor)
                        RefText(label, 13f, labelColor, FontWeight.Medium)
                    }
                    Spacer(Modifier.height(rd(6f)))
                    Box(Modifier.fillMaxWidth().height(rd(42f)).clip(shape)
                        .background(Color(0xFF2A2A2A))
                        .border(rd(if (active) 1.5f else 1f),
                            if (active) PredictionPalette.Lime else PredictionPalette.Border, shape)
                        .padding(horizontal = rd(6f)), contentAlignment = Alignment.Center) {
                        RefText(value, 16f, if (active) PredictionPalette.Lime else PredictionPalette.White,
                            FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(rd(10f)))
        val profitColor = if ((potentialProfit ?: 0.0) < 0.0) PredictionPalette.Red else PredictionPalette.Lime
        val profitText = potentialProfit?.let {
            "${if (it >= 0) "+" else "−"}${predictionMoney(kotlin.math.abs(it))}"
        } ?: "—"
        PredictionMetricCard("Profit", profitText, Modifier.fillMaxWidth(),
            valueColor = profitColor, valueSize = 24f, minHeight = 72f)
        if (statusMessage != null) {
            Spacer(Modifier.height(rd(9f)))
            RefText(statusMessage, 12f, PredictionPalette.Orange, maxLines = 3)
        }
        if (priceChanged) {
            Spacer(Modifier.height(rd(9f)))
            RefText("Profit changed. Review it and tap Stake again.", 12f,
                PredictionPalette.Orange, maxLines = 2)
        }
        Spacer(Modifier.height(rd(10f)))
        PredictionSheetButton(if (canAfford) {
            actionLabel ?: if (collateralSymbol == null) "Stake \$$amount" else "Stake $amount $collateralSymbol"
        } else "Add funds",
            if (canAfford) onStake else onFund, enabled, compact = true)
    }
}

@Composable
internal fun PredictionRulesSheet(onDismiss: () -> Unit, marketSymbol: String = "ETH") {
    PredictionSheet("How it works", onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
            PredictionRuleSummary("Market window", "${PredictionPreviewRound.DURATION} seconds", Modifier.weight(1f))
            PredictionRuleSummary("Result", "Final $marketSymbol price", Modifier.weight(1f))
        }
        Spacer(Modifier.height(rd(20f)))
        RefText("Pick where $marketSymbol will finish", 19f, PredictionPalette.White, FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(rd(7f)))
        RefText("The pool clock runs for everyone. Its starting $marketSymbol price and band stay locked while potential profit changes with $marketSymbol and time left.",
            14f, PredictionPalette.Muted, maxLines = 3)
        Spacer(Modifier.height(rd(12f)))
        RefText("Choose Above, Range or Below. If $marketSymbol finishes in your chosen area, you receive the profit shown when you stake. Otherwise, you lose your stake.",
            14f, PredictionPalette.Muted, maxLines = 4)
        Spacer(Modifier.height(rd(18f)))
        PredictionPositionRule("Above", "send", PredictionPalette.Lime,
            "$marketSymbol finishes above the upper edge of the starting-price band.")
        Spacer(Modifier.height(rd(10f)))
        PredictionPositionRule(PredictionDirection.AROUND.label, "minus", PredictionPalette.Orange,
            "$marketSymbol finishes inside the highlighted band around its starting price.")
        Spacer(Modifier.height(rd(10f)))
        PredictionPositionRule("Below", "deposit", PredictionPalette.Purple,
            "$marketSymbol finishes below the lower edge of the starting-price band.")
        Spacer(Modifier.height(rd(20f)))
        RefText("The market window is ${PredictionPreviewRound.DURATION} seconds in total. $marketSymbol’s final position decides the result.",
            14f, PredictionPalette.Muted, maxLines = 3)
        Spacer(Modifier.height(rd(12f)))
        RefText("Your potential profit is locked when you stake. Trading out closes that position at its current profit or loss; rejoining gives you a new profit amount.",
            14f, PredictionPalette.Muted, maxLines = 4)
        Spacer(Modifier.height(rd(22f)))
        PredictionSheetButton("Got it", onDismiss)
    }
}

@Composable
internal fun PredictionSheet(
    title: String,
    onDismiss: () -> Unit,
    headerIcon: (@Composable () -> Unit)? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    showClose: Boolean = true,
    swipeDismiss: Boolean = false,
    useWalletStyle: Boolean = false,
    glassPanel: Boolean = false,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismissSheet by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissDistance = with(density) { rd(64f).toPx() }
    val flingDistance = with(density) { rd(12f).toPx() }
    val dismissVelocity = with(density) { rd(900f).toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableIntStateOf(0) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val dragState = rememberDraggableState { delta ->
        dragOffset = (dragOffset + delta).coerceIn(0f, sheetHeight.toFloat().coerceAtLeast(1f))
    }
    val panelGesture = if (swipeDismiss) Modifier
        .onSizeChanged { sheetHeight = it.height }
        .graphicsLayer { translationY = dragOffset }
        .draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            startDragImmediately = settling?.isActive == true,
            onDragStarted = { settling?.cancel() },
            onDragStopped = { velocity ->
                val close = dragOffset >= dismissDistance ||
                    (dragOffset >= flingDistance && velocity >= dismissVelocity)
                settling = scope.launch {
                    animate(dragOffset, if (close) sheetHeight.toFloat() else 0f,
                        animationSpec = tween(if (close) 180 else 220)) { value, _ -> dragOffset = value }
                    if (close) dismissSheet()
                }
            },
        )
        .semantics { dismiss { dismissSheet(); true } }
    else Modifier
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxSize().background(PredictionPalette.Background.copy(
                alpha = if (glassPanel) .48f else .72f))
                .semantics { contentDescription = "Dismiss sheet" }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    role = Role.Button, onClick = onDismiss))
            val shape = RoundedCornerShape(topStart = rd(if (compact) 24f else 28f),
                topEnd = rd(if (compact) 24f else 28f))
            val panelSurface = if (glassPanel) Modifier
                .background(Color.Black.copy(alpha = .68f))
                .predictionGlass(shape)
            else Modifier.background(if (useWalletStyle) W.Panel else PredictionPalette.Panel)
                .border(rd(1f), if (useWalletStyle) W.Circle else PredictionPalette.Border, shape)
            Column(Modifier.fillMaxWidth().heightIn(max = rd(720f))
                .then(panelGesture).clip(shape)
                .then(panelSurface)
                .verticalScroll(rememberScrollState(), enabled = !swipeDismiss).navigationBarsPadding()
                .padding(start = rd(if (compact) 20f else 24f),
                    end = rd(if (compact) 20f else 24f),
                    top = rd(if (compact) 10f else 12f),
                    bottom = rd(if (compact) 18f else 24f))) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(rd(38f)).height(rd(4f))
                    .background(PredictionPalette.Muted.copy(alpha = .45f), CircleShape))
                Spacer(Modifier.height(rd(if (compact) 13f else 17f)))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (headerIcon != null) {
                        headerIcon()
                        Spacer(Modifier.width(rd(9f)))
                    }
                    RefText(title, if (compact) 21f else 23f,
                        if (useWalletStyle) W.Cream else PredictionPalette.White,
                        FontWeight.Bold, Modifier.weight(1f))
                    headerTrailing?.invoke()
                    if (showClose) {
                        Box(Modifier.size(rd(44f)).then(if (glassPanel) Modifier.predictionGlass()
                            else Modifier.clip(CircleShape)
                                .background(if (useWalletStyle) W.Circle else PredictionPalette.Background))
                            .semantics { contentDescription = "Close $title" }
                            .clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                            RefIcon("plus", 21f, if (useWalletStyle) W.Cream else PredictionPalette.White, Modifier.rotate(45f))
                        }
                    }
                }
                Spacer(Modifier.height(rd(if (compact) 16f else 22f)))
                content()
            }
        }
    }
}

@Composable
private fun PredictionSheetButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    useWalletStyle: Boolean = false,
    glass: Boolean = false,
    compact: Boolean = false,
) {
    Box(Modifier.fillMaxWidth().height(rd(if (compact) 48f else 52f))
        .then(if (glass) Modifier.predictionGlass()
        else Modifier.clip(CircleShape).background(if (enabled) {
            if (useWalletStyle) W.Cream else PredictionPalette.Lime
        } else if (useWalletStyle) W.Circle else PredictionPalette.Border))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        RefText(label, 17f, if (glass) {
            if (enabled) PredictionPalette.White else PredictionPalette.Muted
        } else if (enabled) {
            if (useWalletStyle) W.Ink else PredictionPalette.Background
        } else PredictionPalette.Muted, FontWeight.Bold)
    }
}

@Composable
private fun PredictionRuleSummary(label: String, value: String, modifier: Modifier) {
    PredictionMetricCard(label, value, modifier,
        valueSize = 15f, minHeight = 76f, valueMaxLines = 2)
}

@Composable
private fun PredictionPositionRule(label: String, icon: String, color: Color, description: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(16f)))
        .background(color.copy(alpha = .06f)).border(rd(1f), color.copy(alpha = .22f), RoundedCornerShape(rd(16f)))
        .padding(rd(14f)), horizontalArrangement = Arrangement.spacedBy(rd(12f)),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(rd(36f)).background(color.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            RefIcon(icon, 20f, color)
        }
        Column(Modifier.weight(1f)) {
            RefText(label, 16f, color, FontWeight.Bold)
            Spacer(Modifier.height(rd(3f)))
            RefText(description, 13f, PredictionPalette.Muted, maxLines = 3)
        }
    }
}
