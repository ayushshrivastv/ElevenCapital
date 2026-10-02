package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.elevencapital.app.communitybuy.CommunityBuyStatus
import com.elevencapital.app.communitybuy.PreparedCommunityBuy
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SolanaLogo
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import java.math.BigDecimal
import java.math.RoundingMode

enum class CommunityBuyPhase { AMOUNT, PREPARING, REVIEW, COMMITTING, SIGNING, SUBMITTING, CONFIRMING, CONFIRMED, FAILED }

data class CommunityBuyUiState(
    val phase: CommunityBuyPhase = CommunityBuyPhase.AMOUNT,
    val prepared: PreparedCommunityBuy? = null,
    val confirmed: CommunityBuyStatus? = null,
    val message: String? = null,
    val submissionMayHaveReachedServer: Boolean = false,
) {
    init { require(phase != CommunityBuyPhase.CONFIRMED || confirmed?.isVerifiedPurchase == true) }
}

/** User-entered SOL is converted exactly to lamports; no rounding or excess decimal places. */
internal fun parseCommunityBuySol(value: String): Long? {
    if (!value.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,9})?"))) return null
    return runCatching { BigDecimal(value).movePointRight(9).longValueExact() }.getOrNull()
        ?.takeIf { it in 100_000L..1_000_000_000L }
}

@Composable
fun CommunityBuyFlow(
    mintAddress: String,
    state: CommunityBuyUiState,
    blockedReason: String? = null,
    onPrepare: (Long) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var amount by rememberSaveable(mintAddress) { mutableStateOf("") }
    var amountError by rememberSaveable(mintAddress) { mutableStateOf(false) }
    var mintCopied by rememberSaveable(mintAddress) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val amountLamports = parseCommunityBuySol(amount)
    val pending = state.phase in setOf(CommunityBuyPhase.PREPARING, CommunityBuyPhase.COMMITTING,
        CommunityBuyPhase.SIGNING, CommunityBuyPhase.SUBMITTING, CommunityBuyPhase.CONFIRMING)
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize().background(W.Background).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(18f)),
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Buy token", 24f, W.White, FontWeight.SemiBold,
                Modifier.weight(1f).semantics { heading() })
            Box(Modifier.size(rd(42f)).background(W.Panel, CircleShape)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close token buy" }, contentAlignment = Alignment.Center) {
                RefText("×", 29f, W.White, FontWeight.Normal)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = rd(24f)), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(rd(25f)))
            Box(Modifier.size(rd(76f)).background(W.Cream, CircleShape), contentAlignment = Alignment.Center) {
                SolanaLogo(60f)
            }
            Spacer(Modifier.height(rd(16f)))
            RefText("Meteora token", 23f, W.White, FontWeight.SemiBold)
            Spacer(Modifier.height(rd(5f)))
            RefText(shortBuyAddress(mintAddress), 13f, W.Muted, FontWeight.Medium)
            Spacer(Modifier.height(rd(5f)))
            Row(Modifier.clickable(role = Role.Button) {
                clipboard.setText(AnnotatedString(mintAddress))
                mintCopied = true
            }.semantics { contentDescription = "Copy full token mint address" },
                verticalAlignment = Alignment.CenterVertically) {
                RefIcon("copy", 13f, W.Cream)
                Spacer(Modifier.width(rd(5f)))
                RefText(if (mintCopied) "Mint copied" else "Copy mint", 12f, W.Cream, FontWeight.Medium)
            }
            Spacer(Modifier.height(rd(7f)))
            RefText("Solana Mainnet · Meteora DBC", 13f, W.Muted, FontWeight.Medium)
            Spacer(Modifier.height(rd(4f)))
            RefText("Token identity not verified", 11f, W.Muted)
            Spacer(Modifier.height(rd(36f)))
            if (blockedReason != null) {
                RefText(blockedReason, 15f, W.Muted, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            } else when (state.phase) {
                CommunityBuyPhase.AMOUNT, CommunityBuyPhase.FAILED -> {
                    RefText("Amount to buy", 16f, W.White, FontWeight.Medium,
                        Modifier.fillMaxWidth())
                    Spacer(Modifier.height(rd(10f)))
                    Row(Modifier.fillMaxWidth().background(W.Panel, RoundedCornerShape(rd(20f)))
                        .padding(horizontal = rd(20f), vertical = rd(18f)),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = amount,
                            onValueChange = { next ->
                                if (next.length <= 14 && next.all { it.isDigit() || it == '.' }) {
                                    amount = next
                                    amountError = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            textStyle = TextStyle(color = W.White, fontSize = rs(25f),
                                fontWeight = FontWeight.SemiBold),
                            cursorBrush = SolidColor(P.Lime),
                            decorationBox = { inner ->
                                Box {
                                    if (amount.isBlank()) RefText("0.00", 25f, W.Muted, FontWeight.SemiBold)
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(rd(12f)))
                        RefText("SOL", 17f, W.White, FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(rd(9f)))
                    RefText(if (amountError) "Enter 0.0001–1 SOL." else "You will review the quote before signing.",
                        12f, if (amountError) P.Pink else W.Muted, modifier = Modifier.fillMaxWidth())
                    if (state.phase == CommunityBuyPhase.FAILED) {
                        Spacer(Modifier.height(rd(18f)))
                        RefText(state.message ?: "The buy was not completed.", 13f, P.Pink,
                            modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    }
                }
                CommunityBuyPhase.REVIEW -> state.prepared?.let { prepared ->
                    RefText("Review buy", 20f, W.White, FontWeight.SemiBold,
                        Modifier.fillMaxWidth())
                    Spacer(Modifier.height(rd(15f)))
                    Column(Modifier.fillMaxWidth().background(W.Panel, RoundedCornerShape(rd(22f)))
                        .padding(rd(18f)), verticalArrangement = Arrangement.spacedBy(rd(15f))) {
                        BuyReviewRow("You pay", formatUnits(prepared.request.amountInLamports, 9) + " SOL")
                        BuyReviewRow("Expected", formatUnits(prepared.expectedAmountOut,
                            prepared.tokenDecimals) + " tokens")
                        BuyReviewRow("Minimum", formatUnits(prepared.minimumAmountOut,
                            prepared.tokenDecimals) + " tokens")
                        BuyReviewRow("Max wallet debit", formatUnits(prepared.maximumWalletDebitLamports, 9) + " SOL")
                        BuyReviewRow("Slippage", "${prepared.request.slippageBps / 100.0}%")
                    }
                    Spacer(Modifier.height(rd(14f)))
                    RefText("Approve the exact mainnet transaction in Privy. This quote expires shortly.",
                        12f, W.Muted, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                }
                CommunityBuyPhase.CONFIRMED -> {
                    RefIcon("check", 56f, P.Lime)
                    Spacer(Modifier.height(rd(17f)))
                    RefText("Buy confirmed", 23f, W.White, FontWeight.SemiBold)
                    Spacer(Modifier.height(rd(8f)))
                    RefText("Your token is now in Community.", 14f, W.Muted)
                    state.confirmed?.message?.takeIf { it.startsWith("Warning:") }?.let { warning ->
                        Spacer(Modifier.height(rd(14f)))
                        RefText(warning, 13f, P.Pink, modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    }
                }
                else -> {
                    Spacer(Modifier.height(rd(36f)))
                    RefText(when (state.phase) {
                        CommunityBuyPhase.PREPARING -> "Checking Meteora pool…"
                        CommunityBuyPhase.COMMITTING -> "Preparing wallet approval…"
                        CommunityBuyPhase.SIGNING -> "Approve in Privy…"
                        CommunityBuyPhase.SUBMITTING -> "Submitting buy…"
                        else -> "Waiting for Solana confirmation…"
                    }, 18f, W.White, FontWeight.SemiBold)
                    Spacer(Modifier.height(rd(10f)))
                    RefText(state.message ?: if (pending) "Do not start another buy while this one is pending."
                        else "", 13f, W.Muted, maxLines = 3)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = rd(24f), vertical = rd(22f)),
            horizontalArrangement = Arrangement.End) {
            val (label, enabled, action) = if (blockedReason != null) Triple("Close", true) {
                onClose()
            } else when (state.phase) {
                CommunityBuyPhase.AMOUNT -> Triple("Review buy", amountLamports != null) {
                    if (amountLamports == null) amountError = true else onPrepare(amountLamports)
                }
                CommunityBuyPhase.FAILED -> if (state.submissionMayHaveReachedServer) {
                    Triple("Close", true) { onClose() }
                } else Triple("Review buy", amountLamports != null) {
                    if (amountLamports == null) amountError = true else onPrepare(amountLamports)
                }
                CommunityBuyPhase.REVIEW -> Triple("Buy with Privy", state.prepared != null) { onConfirm() }
                CommunityBuyPhase.CONFIRMED -> Triple("Done", true) { onClose() }
                else -> Triple("Please wait", false) {}
            }
            Box(Modifier.background(if (enabled) P.Lime else W.Panel, RoundedCornerShape(rd(25f)))
                .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = action)
                    else Modifier.semantics { disabled() })
                .padding(horizontal = rd(28f), vertical = rd(15f)), contentAlignment = Alignment.Center) {
                RefText(label, 16f, if (enabled) W.Ink else W.Muted, FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BuyReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText(label, 13f, W.Muted, FontWeight.Medium, Modifier.weight(1f))
        RefText(value, 13f, W.White, FontWeight.SemiBold)
    }
}

private fun shortBuyAddress(mint: String): String =
    if (mint.length > 12) "${mint.take(6)}…${mint.takeLast(6)}" else mint

private fun formatUnits(value: Long, decimals: Int): String = BigDecimal(value)
    .movePointLeft(decimals).setScale(minOf(decimals, 9), RoundingMode.DOWN)
    .stripTrailingZeros().toPlainString()

private fun formatUnits(value: java.math.BigInteger, decimals: Int): String = BigDecimal(value)
    .movePointLeft(decimals).setScale(minOf(decimals, 9), RoundingMode.DOWN)
    .stripTrailingZeros().toPlainString()
