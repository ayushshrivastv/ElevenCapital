package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.ProfileAvatar
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import java.math.BigDecimal
import java.math.RoundingMode

/** Market data is provider-backed; the only holder shown is this user's recorded purchase. */
@Composable
fun CommunityTokenMarketOverview(
    marketToken: MeteoraMarketToken?,
    launchDraft: LaunchTokenDraft?,
    purchase: CommunityScannedTokenPurchase?,
    showOpeningMetrics: Boolean = false,
    avatarVariant: Int = 0,
    modifier: Modifier = Modifier,
) {
    val opening = if (showOpeningMetrics) spxtrOpeningOverview(launchDraft) else null
    Column(modifier.fillMaxWidth()) {
        RefText("Market overview", 20f, W.White, FontWeight.Bold,
            Modifier.semantics { heading() })
        Spacer(Modifier.height(rd(16f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
            CommunityOverviewMetric(
                if (opening != null) "Price" else "24h volume",
                opening?.priceUsd ?: overviewUsd(marketToken?.volume24hUsd),
                Modifier.weight(1f),
            )
            CommunityOverviewMetric(
                if (opening != null) "24h change" else "Market cap",
                opening?.change24h ?: overviewUsd(marketToken?.marketCapUsd),
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(rd(10f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
            CommunityOverviewMetric(
                if (opening != null) "Total supply" else "Liquidity",
                opening?.totalSupply ?: overviewUsd(marketToken?.liquidityUsd),
                Modifier.weight(1f),
            )
            CommunityOverviewMetric(
                opening?.targetLabel ?: "24h change",
                opening?.targetValue ?: overviewPercent(marketToken?.change24hPercent),
                Modifier.weight(1f),
                if (opening != null) W.White else overviewChangeColor(marketToken?.change24hPercent),
            )
        }

        val ownedTokens = purchase?.receivedTokens?.takeIf { it.signum() > 0 }
        if (ownedTokens != null) {
            Spacer(Modifier.height(rd(28f)))
            RefText("Your Position", 20f, W.White, FontWeight.SemiBold,
                Modifier.semantics { heading() })
            Spacer(Modifier.height(rd(14f)))
            val ticker = if (showOpeningMetrics) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL else
                launchDraft?.symbol?.trim()?.takeIf(String::isNotBlank)
                ?: marketToken?.symbol?.trim()?.takeIf(String::isNotBlank)
                ?: purchase?.symbol?.trim()?.takeIf(String::isNotBlank)
                ?: "tokens"
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(18f))).background(W.Panel)
                    .padding(horizontal = rd(18f), vertical = rd(16f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(rd(36f)).clip(CircleShape).background(W.Circle),
                    contentAlignment = Alignment.Center,
                ) { ProfileAvatar(avatarVariant, Modifier.size(rd(36f))) }
                Spacer(Modifier.width(rd(12f)))
                Column(Modifier.weight(1f)) {
                    RefText("You", 16f, W.White, FontWeight.SemiBold)
                    val pnl = overviewProfitLoss(ownedTokens, marketToken?.priceUsd,
                        purchase?.spentUsd)
                    if (pnl != null) {
                        Spacer(Modifier.height(rd(3f)))
                        RefText(pnl.first, 12f, pnl.second, FontWeight.Medium)
                    }
                }
                RefText("${overviewTokenAmount(ownedTokens)} $ticker", 15f, W.White,
                    FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/** Opening figures come from the app's fixed SPXTR quote and selected launch settings. */
internal data class SpxtrOpeningOverview(
    val priceUsd: String,
    val change24h: String,
    val totalSupply: String,
    val targetLabel: String,
    val targetValue: String,
)

internal fun spxtrOpeningOverview(draft: LaunchTokenDraft?): SpxtrOpeningOverview {
    val settings = draft ?: LaunchTokenDraft()
    val perUsd = requireNotNull(spxtrPreviewReceivedTokensUsd(BigDecimal.ONE))
    val price = BigDecimal.ONE.divide(perUsd, 9, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
    val hasMarketCapTarget = launchUsesMarketCapCurve(settings)
    return SpxtrOpeningOverview(
        priceUsd = "\$$price",
        change24h = "0.00%",
        totalSupply = launchTokenSupplyReview(settings.totalSupply),
        targetLabel = if (hasMarketCapTarget) "Graduation cap" else "Curve target",
        targetValue = "${if (hasMarketCapTarget) settings.graduationMarketCapSol else settings.migrationThresholdSol} SOL",
    )
}

@Composable
private fun CommunityOverviewMetric(
    label: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = W.White,
) {
    Column(
        modifier.clip(RoundedCornerShape(rd(16f))).background(P.Card)
            .padding(horizontal = rd(15f), vertical = rd(16f)),
    ) {
        RefText(label, 14f, P.Muted)
        Spacer(Modifier.height(rd(9f)))
        RefText(value, 20f, valueColor, FontWeight.Medium, maxLines = 2)
    }
}

private fun overviewUsd(value: BigDecimal?): String {
    if (value == null || value.signum() < 0) return "—"
    val units = listOf(
        BigDecimal("1000000000") to "B",
        BigDecimal("1000000") to "M",
        BigDecimal("1000") to "K",
    )
    val unit = units.firstOrNull { value >= it.first }
    val amount = if (unit == null) value else value.divide(unit.first, 1, RoundingMode.HALF_UP)
    val decimals = if (unit == null && amount < BigDecimal.ONE) 2 else if (unit == null) 0 else 1
    return "\$" + amount.setScale(decimals, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + (unit?.second ?: "")
}

private fun overviewPercent(value: BigDecimal?): String = value?.let {
    (if (it.signum() >= 0) "+" else "−") +
        it.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
} ?: "—"

private fun overviewChangeColor(value: BigDecimal?): Color = when {
    value == null || value.signum() == 0 -> P.Muted
    value.signum() < 0 -> P.Pink
    else -> P.Lime
}

private fun overviewTokenAmount(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

private fun overviewProfitLoss(
    tokens: BigDecimal,
    livePriceUsd: BigDecimal?,
    costUsd: BigDecimal?,
): Pair<String, Color>? {
    if (livePriceUsd == null || costUsd == null ||
        livePriceUsd.signum() <= 0 || costUsd.signum() <= 0) return null
    val delta = tokens.multiply(livePriceUsd).subtract(costUsd)
    val percent = delta.multiply(BigDecimal("100"))
        .divide(costUsd, 2, RoundingMode.HALF_UP)
    val color = when {
        delta.signum() > 0 -> P.Lime
        delta.signum() < 0 -> P.Pink
        else -> P.Muted
    }
    val sign = if (delta.signum() > 0) "+" else if (delta.signum() < 0) "−" else ""
    return "P/L $sign\$${delta.abs().setScale(2, RoundingMode.HALF_UP)} ($sign${percent.abs()}%)" to color
}
