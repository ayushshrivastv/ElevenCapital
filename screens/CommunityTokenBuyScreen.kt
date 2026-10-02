package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.elevencapital.app.R
import com.elevencapital.app.tokenlaunch.TokenLaunchArtworkStore
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SolanaLogo
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import java.math.BigDecimal
import java.math.RoundingMode

private val tokenBuyCard = Color(0xFF1E1E1E)
private val tokenBuyFrame = Color(0xFF111213)
private val tokenBuyInputPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{0,4})?")
private val tokenBuyValidPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")
private val tokenSellInputPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{0,8})?")
private val tokenSellValidPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,8})?")

enum class CommunityPayAsset { SOL, USDC }

internal fun preferredCommunityPayAsset(
    availableSol: BigDecimal,
    availableUsdc: BigDecimal,
    solPriceUsd: BigDecimal,
): CommunityPayAsset = if (availableUsdc > availableSol.multiply(solPriceUsd))
    CommunityPayAsset.USDC else CommunityPayAsset.SOL

internal data class CommunityTokenBuyQuote(
    val spentUsd: BigDecimal,
    val receivedTokens: BigDecimal,
)

internal fun communityTokenBuyQuote(
    paidAmount: BigDecimal,
    payAsset: CommunityPayAsset,
    solPriceUsd: BigDecimal,
): CommunityTokenBuyQuote? {
    if (paidAmount.signum() <= 0 || solPriceUsd.signum() <= 0) return null
    val spentUsd = if (payAsset == CommunityPayAsset.SOL) paidAmount.multiply(solPriceUsd) else paidAmount
    val tokens = spxtrPreviewReceivedTokensUsd(spentUsd) ?: return null
    return CommunityTokenBuyQuote(spentUsd, tokens)
}

/** A local amount and review flow. The caller decides how a completed Buy is recorded. */
@Composable
fun CommunityTokenBuyScreen(
    tokenName: String,
    symbol: String,
    artworkUri: String?,
    availableSol: BigDecimal,
    availableUsdc: BigDecimal,
    solPriceUsd: BigDecimal,
    onClose: () -> Unit,
    onConfirm: (spentUsd: BigDecimal, receivedTokens: BigDecimal,
        payAsset: CommunityPayAsset, paidAmount: BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    processing: Boolean = false,
    error: String? = null,
) {
    var amountInput by rememberSaveable(symbol) { mutableStateOf("0") }
    var reviewing by rememberSaveable(symbol) { mutableStateOf(false) }
    var payAsset by rememberSaveable(symbol) {
        mutableStateOf(preferredCommunityPayAsset(availableSol, availableUsdc, solPriceUsd))
    }
    val amount = parseCommunityTokenBuyAmount(amountInput)
    val quote = amount?.let { communityTokenBuyQuote(it, payAsset, solPriceUsd) }
    val available = if (payAsset == CommunityPayAsset.SOL) availableSol else availableUsdc
    val exceedsBalance = amount != null && amount > available
    val valid = amount != null && quote != null && !exceedsBalance
    val ticker = symbol.trim().removePrefix("$").ifBlank { "Token" }
    val back: () -> Unit = {
        if (!processing) {
            if (reviewing) reviewing = false else onClose()
        }
    }
    BackHandler(onBack = back)

    Column(modifier.fillMaxSize().background(P.Background)) {
        Row(
            Modifier.fillMaxWidth().height(rd(74f)).padding(horizontal = rd(22f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(46f)).clip(CircleShape).background(W.Panel)
                    .clickable(enabled = !processing, role = Role.Button, onClick = back)
                    .semantics { contentDescription = if (reviewing) "Edit buy amount" else "Close token buy" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 22f, W.White) }
            Spacer(Modifier.width(rd(16f)))
            RefText(if (reviewing) "Review buy" else "Buy token", 20f, W.White,
                FontWeight.SemiBold, Modifier.semantics { heading() })
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(bottom = rd(18f)),
        ) {
            Spacer(Modifier.height(rd(22f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = rd(22f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenBuyArtwork(tokenName, ticker, artworkUri)
                Spacer(Modifier.width(rd(13f)))
                Column {
                    RefText(tokenName.ifBlank { ticker }, 19f, W.White, FontWeight.SemiBold,
                        maxLines = 1)
                    RefText("$$ticker", 13f, W.Muted, FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(rd(28f)))

            Column(
                Modifier.fillMaxWidth().padding(horizontal = rd(22f))
                    .clip(RoundedCornerShape(rd(30f))).background(tokenBuyFrame).padding(rd(5f)),
                verticalArrangement = Arrangement.spacedBy(rd(4f)),
            ) {
                TokenBuyAmountCard(
                    title = "You pay", amount = amountInput, ticker = payAsset.name,
                    icon = { CommunityPayIcon(payAsset, 24f) },
                    balance = "Balance ${tokenBuyNumber(available)} ${payAsset.name}",
                    topCorners = true,
                    onAssetClick = if (reviewing) null else {
                        { payAsset = if (payAsset == CommunityPayAsset.SOL)
                            CommunityPayAsset.USDC else CommunityPayAsset.SOL; amountInput = "0" }
                    },
                    footer = quote?.spentUsd?.let { "≈ \$${tokenBuyMoney(it)}" },
                )
                TokenBuyAmountCard(
                    title = "You receive", amount = quote?.receivedTokens?.let(::tokenBuyNumber) ?: "—",
                    ticker = ticker, icon = { TokenBuyArtwork(tokenName, ticker, artworkUri, 24f) },
                    balance = null, topCorners = false,
                    footer = quote?.spentUsd?.let { "≈ \$${tokenBuyMoney(it)}" },
                )
            }
            if (reviewing) {
                Spacer(Modifier.height(rd(15f)))
                Column(Modifier.fillMaxWidth().padding(horizontal = rd(24f))) {
                    RefText("You will receive", 13f, W.Muted)
                    Spacer(Modifier.height(rd(4f)))
                    RefText("≈ ${quote?.receivedTokens?.let(::tokenBuyNumber) ?: "—"} $ticker",
                        17f, W.White, FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    RefText("≈ \$${quote?.spentUsd?.let(::tokenBuyMoney) ?: "—"}",
                        13f, W.Muted, modifier = Modifier.fillMaxWidth())
                }
            } else if (exceedsBalance) {
                Spacer(Modifier.height(rd(15f)))
                RefText("Insufficient ${payAsset.name} balance", 13f, P.Pink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = rd(24f)))
            }
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(rd(15f)))
                RefText(error, 13f, P.Pink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = rd(24f)), maxLines = 3)
            }
        }

        Column(Modifier.fillMaxWidth().background(P.Background)) {
            val enabled = valid && !processing
            val label = when {
                processing -> "Buying…"
                reviewing -> "Confirm Purchase"
                amount == null -> "Enter amount"
                exceedsBalance -> "Insufficient ${payAsset.name} balance"
                quote == null -> "Amount unavailable"
                else -> "Review Purchase"
            }
            Box(
                Modifier.padding(horizontal = rd(22f)).fillMaxWidth().height(rd(53f))
                    .clip(CircleShape).background(if (enabled) P.Lime else P.Chip)
                    .clickable(enabled = enabled, role = Role.Button) {
                        if (reviewing) {
                            val ready = requireNotNull(quote)
                            onConfirm(ready.spentUsd, ready.receivedTokens, payAsset,
                                requireNotNull(amount))
                        } else reviewing = true
                    }
                    .semantics {
                        contentDescription = label
                        if (!enabled) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) { RefText(label, 18f, if (enabled) P.Background else P.Muted, FontWeight.Medium) }
            if (!reviewing) {
                Spacer(Modifier.height(rd(8f)))
                TokenBuyKeypad(
                    onKey = { amountInput = appendCommunityTokenBuyKey(amountInput, it) },
                    onBackspace = { amountInput = amountInput.dropLast(1).ifEmpty { "0" } },
                )
            }
            Spacer(Modifier.height(rd(7f)))
        }
    }
}

/** Reject malformed amounts; four fractional digits match the amount keypad. */
internal fun parseCommunityTokenBuyAmount(raw: String): BigDecimal? =
    raw.takeIf { it.length <= 14 && tokenBuyValidPattern.matches(it) }
        ?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

internal fun appendCommunityTokenBuyKey(current: String, key: String): String {
    if (key != "." && (key.length != 1 || !key[0].isDigit())) return current
    val next = when {
        key == "." && current.contains('.') -> return current
        key == "." -> "$current."
        current == "0" -> key
        else -> current + key
    }
    return next.takeIf { it.length <= 14 && tokenBuyInputPattern.matches(it) } ?: current
}

private fun tokenBuyNumber(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

private fun tokenBuyMoney(value: BigDecimal): String {
    val digits = when {
        value.abs() >= BigDecimal("0.1") -> 2
        value.abs() >= BigDecimal("0.01") -> 4
        else -> 8
    }
    val rounded = value.setScale(digits, RoundingMode.HALF_UP)
    return if (digits == 2) rounded.toPlainString() else rounded.stripTrailingZeros().toPlainString()
}

@Composable
private fun TokenBuyArtwork(tokenName: String, symbol: String, artworkUri: String?, size: Float = 46f) {
    val context = LocalContext.current
    val model = artworkUri?.takeIf(String::isNotBlank)?.let { uri ->
        TokenLaunchArtworkStore.localArtworkFile(context, uri) ?: uri
    }
    Box(
        Modifier.size(rd(size)).clip(CircleShape).background(W.Circle),
        contentAlignment = Alignment.Center,
    ) {
        if (symbol.equals(TOKEN_LAUNCH_UI_PREVIEW_SYMBOL, ignoreCase = true)) {
            SpxtrArtwork(Modifier.fillMaxSize())
        } else if (model != null) {
            AsyncImage(model = model, contentDescription = "$tokenName artwork",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else RefText(symbol.take(1).uppercase(), 20f, W.White, FontWeight.Bold)
    }
}

@Composable
private fun TokenBuyAmountCard(
    title: String,
    amount: String,
    ticker: String,
    icon: @Composable () -> Unit,
    balance: String?,
    topCorners: Boolean,
    onAssetClick: (() -> Unit)? = null,
    onMaxClick: (() -> Unit)? = null,
    footer: String? = null,
) {
    val shape = if (topCorners) RoundedCornerShape(
        topStart = rd(26f), topEnd = rd(26f), bottomStart = rd(6f), bottomEnd = rd(6f),
    ) else RoundedCornerShape(
        topStart = rd(6f), topEnd = rd(6f), bottomStart = rd(26f), bottomEnd = rd(26f),
    )
    Box(
        Modifier.fillMaxWidth().height(rd(158f)).clip(shape).background(tokenBuyCard)
            .semantics { contentDescription = "$title, $amount $ticker" },
    ) {
        Row(
            Modifier.align(Alignment.TopStart).padding(start = rd(17f), top = rd(17f))
                .then(if (onAssetClick != null) Modifier.clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onAssetClick)
                    .semantics { contentDescription = "Change payment asset" }
                    .padding(end = rd(8f), bottom = rd(5f)) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(rd(8f)))
            RefText("$title · $ticker", 14f, W.White, FontWeight.Medium)
            if (onAssetClick != null) {
                Spacer(Modifier.width(rd(5f)))
                RefIcon("chevronDown", 14f, W.Muted)
            }
        }
        if (balance != null || onMaxClick != null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(end = rd(16f), top = rd(14f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (balance != null) RefText(balance, 11f, P.Muted, maxLines = 1)
                if (onMaxClick != null) {
                    Spacer(Modifier.width(rd(7f)))
                    Box(
                        Modifier.width(rd(45f)).height(rd(29f)).clip(CircleShape)
                            .background(W.Circle)
                            .clickable(role = Role.Button, onClick = onMaxClick)
                            .semantics { contentDescription = "Sell max" },
                        contentAlignment = Alignment.Center,
                    ) { RefText("MAX", 11f, P.Lime, FontWeight.Bold) }
                }
            }
        }
        RefText(amount,
            when {
                amount.length > 18 -> 20f
                amount.length > 10 -> 28f
                else -> 38f
            }, W.White,
            FontWeight.Normal, modifier = Modifier.align(Alignment.Center), maxLines = 1)
        if (footer != null && amount != "—") {
            RefText(footer, 11f, P.Muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = rd(13f)))
        }
    }
}

@Composable
private fun CommunityPayIcon(asset: CommunityPayAsset, size: Float) {
    Box(Modifier.size(rd(size)).clip(CircleShape).background(W.Circle),
        contentAlignment = Alignment.Center) {
        when (asset) {
            CommunityPayAsset.SOL -> SolanaLogo(size)
            CommunityPayAsset.USDC -> Image(
                painterResource(R.drawable.token_usdc_official), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
        }
    }
}

internal fun parseCommunityTokenSellAmount(raw: String): BigDecimal? =
    raw.takeIf { it.length <= 18 && tokenSellValidPattern.matches(it) }
        ?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

/** Max keeps the exact held quantity, even when its precision exceeds manual keypad limits. */
internal fun resolveCommunityTokenSellAmount(raw: String, heldTokens: BigDecimal): BigDecimal? =
    if (heldTokens.signum() > 0 && raw == tokenBuyNumber(heldTokens)) heldTokens
    else parseCommunityTokenSellAmount(raw)

internal fun appendCommunityTokenSellKey(current: String, key: String): String {
    if (key != "." && (key.length != 1 || !key[0].isDigit())) return current
    val next = when {
        key == "." && current.contains('.') -> return current
        key == "." -> "$current."
        current == "0" -> key
        else -> current + key
    }
    return next.takeIf { it.length <= 18 && tokenSellInputPattern.matches(it) } ?: current
}

/** Local-only sell rehearsal; no wallet instruction or network request is dispatched here. */
@Composable
fun CommunityTokenSellScreen(
    tokenName: String,
    symbol: String,
    artworkUri: String?,
    heldTokens: BigDecimal,
    solPriceUsd: BigDecimal,
    onClose: () -> Unit,
    onConfirm: (soldTokens: BigDecimal, proceedsUsd: BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    processing: Boolean = false,
    error: String? = null,
) {
    var amountInput by rememberSaveable(symbol) { mutableStateOf("0") }
    var reviewing by rememberSaveable(symbol) { mutableStateOf(false) }
    val sold = resolveCommunityTokenSellAmount(amountInput, heldTokens)
    val exceedsHolding = sold != null && sold > heldTokens
    val tokensPerUsd = spxtrPreviewReceivedTokensUsd(BigDecimal.ONE)
    val proceedsUsd = if (sold != null && tokensPerUsd != null && tokensPerUsd.signum() > 0)
        sold.divide(tokensPerUsd, 9, RoundingMode.DOWN) else null
    val proceedsSol = if (proceedsUsd != null && solPriceUsd.signum() > 0)
        proceedsUsd.divide(solPriceUsd, 9, RoundingMode.HALF_UP) else null
    val valid = sold != null && proceedsUsd != null && proceedsUsd.signum() > 0 &&
        proceedsSol != null && proceedsSol.signum() > 0 && !exceedsHolding
    val ticker = symbol.trim().removePrefix("$").ifBlank { "Token" }
    val back: () -> Unit = {
        if (!processing) { if (reviewing) reviewing = false else onClose() }
    }
    BackHandler(onBack = back)

    Column(modifier.fillMaxSize().background(P.Background)) {
        Row(Modifier.fillMaxWidth().height(rd(74f)).padding(horizontal = rd(22f)),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(46f)).clip(CircleShape).background(W.Panel)
                .clickable(enabled = !processing, role = Role.Button, onClick = back)
                .semantics { contentDescription = if (reviewing) "Edit sell amount" else "Close token sell" },
                contentAlignment = Alignment.Center) { RefIcon("back", 22f, W.White) }
            Spacer(Modifier.width(rd(16f)))
            RefText(if (reviewing) "Review sale" else "Sell token", 20f, W.White,
                FontWeight.SemiBold, Modifier.semantics { heading() })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(bottom = rd(18f))) {
            Spacer(Modifier.height(rd(22f)))
            Row(Modifier.fillMaxWidth().padding(horizontal = rd(22f)),
                verticalAlignment = Alignment.CenterVertically) {
                TokenBuyArtwork(tokenName, ticker, artworkUri)
                Spacer(Modifier.width(rd(13f)))
                Column {
                    RefText(tokenName.ifBlank { ticker }, 19f, W.White, FontWeight.SemiBold, maxLines = 1)
                    RefText("$$ticker", 13f, W.Muted, FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(rd(28f)))
            Column(Modifier.fillMaxWidth().padding(horizontal = rd(22f))
                .clip(RoundedCornerShape(rd(30f))).background(tokenBuyFrame).padding(rd(5f)),
                verticalArrangement = Arrangement.spacedBy(rd(4f))) {
                TokenBuyAmountCard(
                    title = "You sell", amount = amountInput, ticker = ticker,
                    icon = { TokenBuyArtwork(tokenName, ticker, artworkUri, 24f) },
                    balance = "Available ${tokenBuyNumber(heldTokens)}",
                    topCorners = true,
                    onMaxClick = if (!reviewing && heldTokens.signum() > 0) {
                        { amountInput = tokenBuyNumber(heldTokens) }
                    } else null,
                    footer = proceedsUsd?.let { "≈ \$${tokenBuyMoney(it)}" },
                )
                TokenBuyAmountCard(
                    title = "You receive", amount = proceedsSol?.let(::tokenBuyNumber) ?: "—",
                    ticker = "SOL", icon = { CommunityPayIcon(CommunityPayAsset.SOL, 24f) },
                    balance = null, topCorners = false,
                    footer = proceedsUsd?.let { "≈ \$${tokenBuyMoney(it)}" },
                )
            }
            if (reviewing && sold != null && proceedsUsd != null) {
                Spacer(Modifier.height(rd(15f)))
                Column(Modifier.fillMaxWidth().padding(horizontal = rd(24f))) {
                    RefText("You will sell", 13f, W.Muted)
                    Spacer(Modifier.height(rd(4f)))
                    RefText("${tokenBuyNumber(sold)} $ticker", 17f, W.White, FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(), maxLines = 2)
                    RefText("≈ \$${tokenBuyMoney(proceedsUsd)} in SOL", 13f, W.Muted,
                        modifier = Modifier.fillMaxWidth())
                }
            } else if (exceedsHolding) {
                Spacer(Modifier.height(rd(15f)))
                RefText("Not enough $ticker", 13f, P.Pink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = rd(24f)))
            }
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(rd(15f)))
                RefText(error, 13f, P.Pink,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = rd(24f)), maxLines = 3)
            }
        }
        Column(Modifier.fillMaxWidth().background(P.Background)) {
            val enabled = valid && !processing
            val label = when {
                processing -> "Selling…"
                reviewing -> "Confirm Sale"
                sold == null -> "Enter amount"
                exceedsHolding -> "Not enough $ticker"
                else -> "Review Sale"
            }
            Box(Modifier.padding(horizontal = rd(22f)).fillMaxWidth().height(rd(53f))
                .clip(CircleShape).background(if (enabled) P.SellRed else P.Chip)
                .clickable(enabled = enabled, role = Role.Button) {
                    if (reviewing) onConfirm(requireNotNull(sold), requireNotNull(proceedsUsd))
                    else reviewing = true
                }.semantics {
                    contentDescription = label
                    if (!enabled) disabled()
                }, contentAlignment = Alignment.Center) {
                RefText(label, 18f, if (enabled) P.White else P.Muted, FontWeight.Medium)
            }
            if (!reviewing) {
                Spacer(Modifier.height(rd(8f)))
                TokenBuyKeypad(
                    onKey = { amountInput = appendCommunityTokenSellKey(amountInput, it) },
                    onBackspace = { amountInput = amountInput.dropLast(1).ifEmpty { "0" } },
                )
            }
            Spacer(Modifier.height(rd(7f)))
        }
    }
}

@Composable
private fun TokenBuyKeypad(onKey: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"), listOf("4", "5", "6"),
        listOf("7", "8", "9"), listOf(".", "0", "backspace"),
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
                            .clickable(role = Role.Button) {
                                if (key == "backspace") onBackspace() else onKey(key)
                            }
                            .semantics {
                                contentDescription = if (key == "backspace") "Delete digit" else key
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
