package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import coil3.compose.AsyncImage
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.tokenlaunch.TokenLaunchArtworkStore
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.ChartRange

/** Creator token landing. The caller can supply a real market view when one is available. */
@Composable
fun CommunityTokenDetailScreen(
    tokenName: String,
    symbol: String,
    artworkUri: String?,
    owned: Boolean,
    canPost: Boolean,
    chatIsLive: Boolean,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
    onBuy: () -> Unit,
    onSell: () -> Unit = {},
    showBuy: Boolean = true,
    showSell: Boolean = false,
    allowRepeatBuy: Boolean = false,
    buyInProgress: Boolean = false,
    sellInProgress: Boolean = false,
    actionError: String? = null,
    statusMessage: String? = null,
    description: String? = null,
    launchDraft: LaunchTokenDraft? = null,
    marketToken: MeteoraMarketToken? = null,
    localPurchase: CommunityScannedTokenPurchase? = null,
    modifier: Modifier = Modifier,
    marketContent: (@Composable () -> Unit)? = null,
    showLocalTrend: Boolean = false,
    avatarVariant: Int = 0,
) {
    BackHandler(onBack = onClose)
    val displayName = if (showLocalTrend) TOKEN_LAUNCH_UI_PREVIEW_NAME else tokenName
    val displaySymbol = if (showLocalTrend) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL else symbol
    val canBuy = (!owned || allowRepeatBuy) && showBuy
    Box(modifier.fillMaxSize().background(W.Background)) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(rd(74f))
                .padding(horizontal = rd(22f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(46f)).clip(CircleShape).background(W.Panel)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close token details" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 22f, W.White) }
            Spacer(Modifier.width(rd(16f)))
            RefText("Creator token", 20f, W.White, FontWeight.SemiBold,
                Modifier.semantics { heading() })
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = rd(22f), end = rd(22f),
                    bottom = rd(if (canBuy || showSell) 116f else 30f)),
        ) {
            Spacer(Modifier.height(rd(28f)))
            CommunityDetailArtwork(displayName, artworkUri, showLocalTrend)
            Spacer(Modifier.height(rd(22f)))
            RefText(displayName.ifBlank { "Creator token" }, 31f, W.White, FontWeight.Bold,
                maxLines = 2)
            Spacer(Modifier.height(rd(4f)))
            RefText(
                displaySymbol.trim().removePrefix("$").takeIf(String::isNotBlank)?.let { "\$$it" }
                    ?: "Solana token",
                16f, W.Muted, FontWeight.Medium,
            )
            if (!statusMessage.isNullOrBlank()) {
                Spacer(Modifier.height(rd(14f)))
                RefText(statusMessage, 14f, W.Muted, maxLines = 3)
            }

            if (marketContent != null) {
                Spacer(Modifier.height(rd(28f)))
                marketContent()
            } else if (showLocalTrend) {
                Spacer(Modifier.height(rd(28f)))
                CommunityLocalTrend()
            }

            if ((canBuy || showSell) && !actionError.isNullOrBlank()) {
                Spacer(Modifier.height(rd(28f)))
                RefText(actionError, 13f, W.Muted,
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }

            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(rd(26f)))
                RefText("About", 20f, W.White, FontWeight.SemiBold)
                Spacer(Modifier.height(rd(10f)))
                RefText(description.trim(), 15f, W.Muted, maxLines = Int.MAX_VALUE)
            }

            Spacer(Modifier.height(rd(28f)))
            CommunityTokenMarketOverview(
                marketToken = marketToken,
                launchDraft = launchDraft,
                purchase = localPurchase,
                showOpeningMetrics = showLocalTrend,
                avatarVariant = avatarVariant,
            )

            if (launchDraft != null) {
                Spacer(Modifier.height(rd(28f)))
                CommunityLaunchDetails(launchDraft)
            }

            Spacer(Modifier.height(rd(31f)))
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenChat)
                    .semantics { contentDescription = "Open chat for ${displayName.ifBlank { "this token" }}" }
                    .padding(vertical = rd(17f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(rd(3f))) {
                    RefText(if (chatIsLive) "Live Chat" else "Chat", 20f, W.White, FontWeight.SemiBold)
                    RefText(
                        if (!chatIsLive) "Coming soon" else if (canPost) "Join the conversation"
                            else "Read the conversation",
                        13f, W.Muted, maxLines = 2,
                    )
                }
                Spacer(Modifier.width(rd(8f)))
                RefIcon("chevronRight", 20f, W.White)
            }
            Box(Modifier.fillMaxWidth().height(rd(1f)).background(W.Panel))
        }
        }
        if (canBuy || showSell) {
            CommunityDetailTradeActions(
                showBuy = canBuy,
                showSell = showSell,
                buyInProgress = buyInProgress,
                sellInProgress = sellInProgress,
                onBuy = onBuy,
                onSell = onSell,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun CommunityDetailTradeActions(
    showBuy: Boolean,
    showSell: Boolean,
    buyInProgress: Boolean,
    sellInProgress: Boolean,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(bottom = rd(18f))
            .shadow(rd(10f), CircleShape)
            .clip(CircleShape)
            .background(W.Panel)
            .padding(rd(5f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSell) Row(
            Modifier.width(rd(122f)).height(rd(58f))
                .clip(CircleShape)
                .background(if (sellInProgress) W.Circle else P.SellRed)
                .clickable(enabled = !sellInProgress, role = Role.Button, onClick = onSell)
                .semantics { contentDescription = if (sellInProgress) "Selling token" else "Sell token" },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefText(if (sellInProgress) "Selling…" else "Sell", 18f, W.White, FontWeight.SemiBold)
        }
        if (showBuy) {
            if (showSell) Spacer(Modifier.width(rd(5f)))
            Row(
                Modifier.width(rd(122f)).height(rd(58f))
                    .clip(CircleShape)
                    .background(if (buyInProgress) W.Circle else P.Lime)
                    .clickable(enabled = !buyInProgress, role = Role.Button, onClick = onBuy)
                    .semantics { contentDescription = if (buyInProgress) "Buying token" else "Buy token" },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!buyInProgress) {
                    RefIcon("plus", 24f, W.Ink)
                    Spacer(Modifier.width(rd(8f)))
                }
                RefText(if (buyInProgress) "Buying…" else "Buy", 18f,
                    if (buyInProgress) W.Muted else W.Ink, FontWeight.Medium)
            }
        }
    }
}

/** A flat, stable newly-launched curve for the app-scoped SPXTR token. */
internal fun spxtrLocalTrendValues(@Suppress("UNUSED_PARAMETER") range: ChartRange): List<Float> =
    List(20) { .5f }

@Composable
private fun CommunityLocalTrend() {
    val ranges = listOf(
        ChartRange.ONE_HOUR to "1H",
        ChartRange.ONE_DAY to "1D",
        ChartRange.ONE_WEEK to "1W",
        ChartRange.ONE_MONTH to "1M",
        ChartRange.YEAR_TO_DATE to "YTD",
    )
    var selectedRange by remember { mutableStateOf(ChartRange.ONE_DAY) }
    val values = remember(selectedRange) { spxtrLocalTrendValues(selectedRange) }

    Column(Modifier.fillMaxWidth()) {
        RefText("Chart", 20f, W.White, FontWeight.SemiBold)
        Spacer(Modifier.height(rd(16f)))
        Box(
            Modifier.fillMaxWidth().height(rd(222f))
                .semantics { contentDescription = "${ranges.first { it.first == selectedRange }.second} token chart" },
        ) {
            Box(Modifier.align(Alignment.Center)) { SquareLogo(40f, .07f) }
            Canvas(Modifier.fillMaxSize()) {
                val inset = 8f * density
                val top = 16f * density
                val bottom = size.height - 18f * density
                drawLine(W.White.copy(alpha = .045f), Offset(0f, bottom), Offset(size.width, bottom), 1f)
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = inset + (size.width - inset * 2f) * index / (values.size - 1)
                    val y = bottom - (bottom - top) * value
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, P.Lime, style = Stroke(width = 2.5f * density, cap = StrokeCap.Round))
                drawCircle(P.Lime, radius = 3.5f * density,
                    center = Offset(size.width - inset, bottom - (bottom - top) * values.last()))
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(start = rd(8f), top = rd(8f))
                    .clip(CircleShape).background(W.Panel)
                    .padding(horizontal = rd(13f), vertical = rd(7f)),
                contentAlignment = Alignment.Center,
            ) {
                RefText("Newly Launched", 12f, P.Lime, FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(rd(13f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ranges.forEach { (range, label) ->
                val active = range == selectedRange
                Box(
                    Modifier.clip(RoundedCornerShape(rd(10f)))
                        .background(if (active) W.Panel else Color.Transparent)
                        .clickable(role = Role.Tab) { selectedRange = range }
                        .semantics { selected = active; contentDescription = "$label token chart" }
                        .padding(horizontal = rd(12f), vertical = rd(8f)),
                    contentAlignment = Alignment.Center,
                ) { RefText(label, 14f, if (active) W.White else W.Muted, FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun CommunityLaunchDetails(draft: LaunchTokenDraft) {
    var expanded by rememberSaveable(draft.symbol) { mutableStateOf(false) }
    RefText("Launch details", 20f, W.White, FontWeight.SemiBold,
        Modifier.semantics { heading() })
    Spacer(Modifier.height(rd(7f)))
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefText("Meteora DBC", 13f, W.Muted, FontWeight.Medium)
            Spacer(Modifier.width(rd(8f)))
            RefIcon("chevronRight", 13f, W.Muted)
            Spacer(Modifier.width(rd(8f)))
            RefText("DAMM v2", 13f, W.Muted, FontWeight.Medium)
        }
        Spacer(Modifier.height(rd(19f)))
        CommunityLaunchDetailRow("Paired with", "SOL")
        Spacer(Modifier.height(rd(13f)))
        CommunityLaunchDetailRow("Curve", draft.curvePreset)
        Spacer(Modifier.height(rd(13f)))
        if (launchUsesMarketCapCurve(draft)) {
            CommunityLaunchDetailRow("Graduation", "${draft.graduationMarketCapSol} SOL cap")
        } else {
            CommunityLaunchDetailRow("Graduation", "${draft.migrationThresholdSol} SOL in pool")
        }
        if (expanded) {
            Spacer(Modifier.height(rd(13f)))
            CommunityLaunchDetailRow("Total supply", launchTokenSupplyReview(draft.totalSupply))
            Spacer(Modifier.height(rd(13f)))
            CommunityLaunchDetailRow("Trading fee", launchFeePercent(draft.feeBps))
            if (launchUsesMarketCapCurve(draft)) {
                Spacer(Modifier.height(rd(13f)))
                CommunityLaunchDetailRow("Starting cap", "${draft.initialMarketCapSol} SOL")
            }
            Spacer(Modifier.height(rd(13f)))
            CommunityLaunchDetailRow("Migration supply", "${draft.supplyOnMigrationPercent}%")
            if (draft.launchProtectionEnabled) {
                Spacer(Modifier.height(rd(13f)))
                CommunityLaunchDetailRow("Early fees", "10% to ${launchFeePercent(draft.feeBps)}")
            }
            if (draft.vestCreatorTokens) {
                Spacer(Modifier.height(rd(13f)))
                CommunityLaunchDetailRow("Vested supply", "${draft.vestedSupplyPercent}%")
                Spacer(Modifier.height(rd(13f)))
                CommunityLaunchDetailRow("Vesting", "${draft.vestingDurationMonths} months · ${draft.vestingFrequency}")
            }
        }
        Spacer(Modifier.height(rd(15f)))
        RefText(if (expanded) "Show less" else "Show all details", 13f, W.Cream, FontWeight.Medium,
            Modifier.clickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = if (expanded) "Collapse launch details" else "Expand launch details" })
        Spacer(Modifier.height(rd(14f)))
        Box(Modifier.fillMaxWidth().height(rd(1f)).background(W.Panel))
    }
}

@Composable
private fun CommunityLaunchDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText(label, 14f, W.Muted, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(rd(10f)))
        RefText(value, 14f, W.White, FontWeight.Medium, maxLines = 2)
    }
}

internal fun launchFeePercent(feeBps: String): String =
    feeBps.toBigDecimalOrNull()?.movePointLeft(2)?.stripTrailingZeros()?.toPlainString()
        ?.let { "$it%" } ?: "$feeBps bps"

@Composable
private fun CommunityDetailArtwork(tokenName: String, artworkUri: String?, isSpxtr: Boolean) {
    val context = LocalContext.current
    val model = remember(context, artworkUri) {
        artworkUri?.takeIf(String::isNotBlank)?.let { uri ->
            TokenLaunchArtworkStore.localArtworkFile(context, uri) ?: uri
        }
    }
    var artworkReady by remember(model) { mutableStateOf(false) }
    Box(
        Modifier.size(rd(96f)).clip(RoundedCornerShape(rd(22f))).background(W.Panel),
        contentAlignment = Alignment.Center,
    ) {
        if (isSpxtr) {
            SpxtrArtwork(Modifier.fillMaxSize())
            return@Box
        }
        if (!artworkReady) {
            RefText(tokenName.trim().take(1).uppercase().ifBlank { "$" },
                35f, W.White, FontWeight.Bold)
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = "${tokenName.ifBlank { "Token" }} artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = { artworkReady = false },
                onError = { artworkReady = false },
                onSuccess = { artworkReady = true },
            )
        }
    }
}
