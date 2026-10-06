package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.R
import com.elevencapital.app.data.PredictionPythFeed
import com.elevencapital.app.data.PredictionBtcFeed
import com.elevencapital.app.data.PythEthSnapshot
import com.elevencapital.app.data.PythEthQuote
import kotlinx.coroutines.CancellationException
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val predictionGlassFill = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .16f),
    .45f to Color.White.copy(alpha = .075f),
    1f to Color.White.copy(alpha = .045f),
)
private val predictionGlassEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .34f),
    1f to Color.White.copy(alpha = .09f),
)

@Composable
internal fun Modifier.predictionGlass(shape: Shape = CircleShape): Modifier =
    clip(shape).background(predictionGlassFill).border(rd(1f), predictionGlassEdge, shape)

/** Pyth-backed BTC prices with app-local practice USDC accounting. */
@Composable
internal fun PredictionMarketScreen(
    onBack: () -> Unit,
    onBalance: () -> Unit = {},
    poolProfitLoss: Double = 0.0,
    ledger: PredictionWalletLedger = PredictionWalletLedger.empty(),
    onLedgerChange: (PredictionWalletLedger, PredictionWalletLedger) -> Boolean = { _, _ -> false },
    usdcCollateral: Boolean = false,
    btcMarket: Boolean = false,
) {
    BackHandler(onBack = onBack)
    val marketFeed = remember(btcMarket) {
        if (btcMarket) PredictionBtcFeed.session else PredictionPythFeed.session
    }
    val marketSymbol = if (btcMarket) "BTC" else "ETH"
    var snapshot by remember(marketFeed) { mutableStateOf(marketFeed.currentSnapshot) }
    var round by remember(marketFeed) {
        mutableStateOf(warmedPredictionRound(snapshot, marketFeed.currentRoundTerms, btcMarket))
    }
    var clockMillis by remember(marketFeed) { mutableLongStateOf(snapshot.nowMillis()) }
    var workingLedger by remember { mutableStateOf(ledger) }
    val saveLedger by rememberUpdatedState(onLedgerChange)
    LaunchedEffect(ledger) { workingLedger = ledger }
    fun save(next: PredictionWalletLedger): Boolean {
        if (next === workingLedger) return true
        if (!saveLedger(workingLedger, next)) return false
        workingLedger = next
        return true
    }
    var stake by remember { mutableIntStateOf(5) }
    var choice by remember { mutableStateOf(PredictionDirection.ABOVE) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var stakeSheetRoundId by remember { mutableStateOf<Long?>(null) }
    var settledResult by remember { mutableStateOf<PredictionResultData?>(null) }
    var resultVisible by remember { mutableStateOf(false) }
    var priceChanged by remember { mutableStateOf(false) }
    var liveHistory by remember(marketFeed) { mutableStateOf<List<PythEthQuote>>(emptyList()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, marketFeed) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                marketFeed.snapshots.collect { update ->
                    snapshot = update
                    val now = update.nowMillis()
                    update.freshQuote(now)?.let { quote ->
                        val recent = liveHistory.filter { it.publishTime >= now / 1_000L - 120L }
                        val last = recent.lastOrNull()
                        liveHistory = when {
                            last == null || last.publishTime < quote.publishTime -> recent + quote
                            last.publishTime == quote.publishTime -> recent.dropLast(1) + quote
                            else -> recent
                        }.takeLast(180)
                    }
                    val chartQuote = if (btcMarket) update.freshQuote(now) else
                        update.usableQuote(now, round?.band ?: PredictionPreviewRound.BAND)
                    chartQuote?.let { quote ->
                        round = round?.ingest(quote.oracleQuote(), now,
                            allowWatchOnlyObservation = btcMarket)
                    }
                }
            }
            launch {
                while (isActive) {
                    clockMillis = snapshot.nowMillis()
                    round = round?.syncTo(clockMillis)
                    delay(250)
                }
            }
            launch {
                suspend fun boundary(timestamp: Long, bandUsd: Double,
                    entryBtcMarket: Boolean): PredictionOracleQuote? = try {
                    marketFeed.client.boundary(timestamp).oracleQuote()
                        .takeIf { it.isBoundaryQuote(timestamp, bandUsd, entryBtcMarket) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                while (isActive) {
                    if (!snapshot.hasServerTime) { delay(1_000); continue }
                    var now = snapshot.nowMillis()
                    // Old simulated positions are refunded; a live position requires an actual boundary report.
                    runCatching { workingLedger.advanceToPool(now) }.getOrNull()?.let(::save)
                    val pending = workingLedger.openEntries.filter {
                        it.isLive && (it.poolId + 1) * 90_000L <= now
                    }.groupBy { it.poolId }
                    for ((pool, entries) in pending) {
                        val finalQuote = boundary((pool + 1) * 90L, entries.first().bandUsd,
                            entries.first().btcMarket)
                        now = snapshot.nowMillis()
                        var settled = false
                        if (finalQuote != null) {
                            val next = runCatching { workingLedger.settleLivePool(pool, finalQuote, now) }.getOrNull()
                            if (next != null && save(next)) {
                                settled = true
                                val entry = entries.last()
                                val anchor = entry.anchorOracleQuote
                                if (anchor != null) {
                                    val completed = runCatching {
                                        (round?.takeIf { it.roundId == pool && it.band == entry.bandUsd &&
                                            it.forecastSigmaUsd == entry.forecastSigmaUsd && it.anchorOracleQuote == anchor }
                                            ?: PredictionPreviewRound.liveForPool(pool, anchor, now, entry.bandUsd,
                                                entry.forecastSigmaUsd, allowWatchOnlyAnchor = entry.btcMarket))
                                            .settleLive(finalQuote, now)
                                    }.getOrNull()
                                    if (completed != null) {
                                        val won = completed.outcome == entry.direction
                                        settledResult = PredictionResultData(
                                            direction = entry.direction, stake = entry.stakeUsd.toInt(),
                                            anchor = completed.anchor, finalPrice = completed.price, band = completed.band,
                                            won = won, finishedAtMillis = (pool + 1) * 90_000L,
                                            priceHistory = completed.points,
                                            payoutUsd = if (won) entry.quotedWinningPayoutUsd.toDouble() else 0.0,
                                            marketSymbol = marketSymbol,
                                        )
                                        resultVisible = true
                                    }
                                }
                            }
                        }
                        if (!settled) {
                            runCatching { workingLedger.cancelLivePool(pool, now) }.getOrNull()?.let(::save)
                        }
                    }
                    now = snapshot.nowMillis()
                    val pool = Math.floorDiv(now, 90_000L)
                    if (round?.roundId != pool) {
                        // An anchor must come from the fixed round boundary, never from the user's entry time.
                        val entry = workingLedger.openEntries.firstOrNull { it.poolId == pool && it.isLive }
                        val savedTerms = workingLedger.currentLivePoolTerms
                            ?.takeIf { workingLedger.currentPoolId == pool }
                        val terms = if (entry != null || savedTerms != null) null else
                            try { marketFeed.roundTerms(pool) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { null }
                        val anchor = entry?.anchorOracleQuote ?: savedTerms?.anchorOracleQuote ?: terms?.anchor?.oracleQuote()
                        val band = entry?.bandUsd ?: savedTerms?.bandUsd ?: terms?.bandUsd?.toDouble()
                        val sigma = entry?.forecastSigmaUsd ?: savedTerms?.forecastSigmaUsd ?:
                            terms?.forecastSigmaUsd?.toDouble()
                        val roundBtcMarket = entry?.btcMarket ?: savedTerms?.btcMarket ?: btcMarket
                        if (anchor != null && band != null && Math.floorDiv(snapshot.nowMillis(), 90_000L) == pool) {
                            var next = runCatching {
                                PredictionPreviewRound.liveForPool(pool, anchor, snapshot.nowMillis(), band, sigma,
                                    allowWatchOnlyAnchor = roundBtcMarket)
                            }.getOrNull()
                            val quote = if (btcMarket) snapshot.freshQuote() else snapshot.usableQuote(bandUsd = band)
                            if (next != null && quote != null) next = next.ingest(quote.oracleQuote(),
                                snapshot.nowMillis(), allowWatchOnlyObservation = btcMarket)
                            if (next != null) {
                                if (entry != null) next = next.copy(direction = entry.direction,
                                    stake = entry.stakeUsd.intValueExact(), entryPrice = entry.entryPriceUsd.toDouble(),
                                    entryQuote = entry.entryQuote.toDouble())
                                round = next
                                if (sheet == "stake") sheet = null
                            }
                        }
                    }
                    delay(1_000)
                }
            }
        }
    }
    // A previous pool may remain in memory while its settlement finishes. Never display or trade on it.
    val current = round?.takeIf {
        it.roundId == Math.floorDiv(clockMillis, PredictionPreviewRound.DURATION_MILLIS)
    }
    val savedTradeOut = remember(workingLedger, current?.roundId) {
        val poolId = current?.roundId
        if (poolId == null) null else workingLedger.history.lastOrNull { row ->
            row.kind == PredictionHistoryKind.TRADE_OUT && row.poolId == poolId &&
                row.status == PredictionHistoryStatus.CONFIRMED
        }
    }
    val currentTradeOut = savedTradeOut?.takeIf { row ->
        current?.roundId?.let { poolId ->
            !current.settled && clockMillis < (poolId + 1) * PredictionPreviewRound.DURATION_MILLIS
        } == true
    }
    val tradeReady = snapshot.usableQuote(clockMillis, current?.band ?: PredictionPreviewRound.BAND,
        btcMarket) != null &&
        current?.canTradeLiveAt(clockMillis) == true
    val stakeReady = tradeReady && current?.canEnterLiveAt(clockMillis) == true
    val entryWindowOpen = snapshot.hasServerTime &&
        Math.floorMod(clockMillis, PredictionPreviewRound.DURATION_MILLIS) <
            PredictionPreviewRound.ENTRY_WINDOW_MILLIS
    val openingWaitMillis = PredictionPreviewRound.ENTRY_OPEN_DELAY_MILLIS -
        Math.floorMod(clockMillis, PredictionPreviewRound.DURATION_MILLIS)
    val openingWaitSeconds = if (snapshot.hasServerTime && openingWaitMillis > 0)
        ((openingWaitMillis + 999L) / 1_000L).toInt() else null
    val nextRoundSeconds = if (snapshot.hasServerTime)
        ((PredictionPreviewRound.DURATION_MILLIS - Math.floorMod(clockMillis,
            PredictionPreviewRound.DURATION_MILLIS) + 999L) / 1_000L).toInt() else null
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        PredictionBrandHeader(onBack, onBalance, workingLedger.availableQuoteUsd, usdcCollateral)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (current != null) {
                PredictionChart(current, snapshot, marketSymbol, Modifier.fillMaxSize())
                val activePnl = if (current.active) (current.positionValue ?: current.stake.toDouble()) - current.stake else 0.0
                PredictionQuoteHeader(current, poolProfitLoss + activePnl, btcMarket,
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = rd(14f), vertical = rd(8f)))
                if (current.active || currentTradeOut != null) {
                    PredictionRoundBanner(current, marketSymbol,
                        currentTradeOut?.takeUnless { current.active }?.amountUsd?.toDouble(),
                        Modifier.align(Alignment.TopCenter).padding(top = rd(if (btcMarket) 85f else 65f),
                            start = rd(14f), end = rd(14f)))
                }
            } else {
                PredictionBetweenRounds(snapshot, clockMillis, liveHistory, marketSymbol, btcMarket,
                    Modifier.fillMaxSize())
            }
        }
        if (current != null && (current.active || currentTradeOut != null)) {
            PredictionPositionBar(current, tradeOutValue = currentTradeOut?.amountUsd,
                tradeOutPnl = currentTradeOut?.realizedPnlUsd,
                marketSymbol = marketSymbol, enabled = tradeReady) {
                val now = snapshot.nowMillis()
                val latest = current.syncTo(now)
                val entry = workingLedger.openEntries.firstOrNull { it.poolId == latest.roundId }
                if (snapshot.usableQuote(now, latest.band, btcMarket) != null &&
                    entry != null && latest.canTradeLiveAt(now)) {
                    val next = runCatching { workingLedger.tradeOutLive(entry.id, latest, now) }.getOrNull()
                    if (next != null && save(next)) {
                        round = latest.tradeOut()
                    }
                }
            }
        } else if (!entryWindowOpen && snapshot.hasServerTime) {
            PredictionWatchPanel(nextRoundSeconds,
                if (settledResult == null) null else { { resultVisible = true } })
        } else if (settledResult != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = rd(16f), vertical = rd(8f)),
                verticalAlignment = Alignment.CenterVertically) {
                RefText("Previous round result", 14f, PredictionPalette.White, modifier = Modifier.weight(1f))
                Box(Modifier.height(rd(42f)).predictionGlass()
                    .clickable(role = Role.Button) { resultVisible = true }
                    .padding(horizontal = rd(13f)), contentAlignment = Alignment.Center) {
                    RefText("View result", 12f, PredictionPalette.White, FontWeight.Medium)
                }
            }
        }
        PredictionControls(stake, { stake = it }, {
            val now = snapshot.nowMillis()
            val latest = current?.syncTo(now)
            if (!resultVisible && snapshot.usableQuote(now,
                    latest?.band ?: PredictionPreviewRound.BAND, btcMarket) != null &&
                latest?.canStake == true && latest.canEnterLiveAt(now)) {
                round = latest
                stakeSheetRoundId = latest.roundId
                priceChanged = false
                sheet = "stake"
            }
        }, stakeEnabled = stakeReady && current?.canStake == true,
            stakeLabel = when {
                openingWaitSeconds != null -> "Stake in ${openingWaitSeconds}s"
                current != null && !entryWindowOpen -> "Stake closed"
                else -> "Stake"
            })
    }
    when (sheet) {
        "stake" -> if (current != null) PredictionStakeSheet(
            price = current.anchor, spotPrice = current.price,
            band = current.band, amount = stake,
            sharePrices = current.sharePrices, remainingSeconds = current.remaining,
            selected = choice, onSelect = { choice = it; priceChanged = false }, priceChanged = priceChanged,
            collateralSymbol = if (usdcCollateral) "USDC" else null,
            marketSymbol = marketSymbol,
            onStake = {
                val now = snapshot.nowMillis()
                val latest = current.syncTo(now)
                if (snapshot.usableQuote(now, latest.band, btcMarket) != null &&
                    latest.canStake && latest.canEnterLiveAt(now) && latest.roundId == stakeSheetRoundId) {
                    val acceptedQuote = latest.sharePrices[choice]
                    if (abs(acceptedQuote - current.sharePrices[choice]) > .001) priceChanged = true
                    else {
                        val next = runCatching {
                            workingLedger.enterLive(UUID.randomUUID().toString(), latest, choice,
                                BigDecimal.valueOf(stake.toLong()), now)
                        }.getOrNull()
                        if (next != null && save(next)) {
                            round = latest.start(choice, stake, latest.price)
                            priceChanged = false
                            sheet = null
                        }
                    }
                }
            }, onDismiss = { sheet = null }, enabled = stakeReady && current.canStake,
            canAfford = workingLedger.availableQuoteUsd >= BigDecimal.valueOf(stake.toLong()),
            onFund = { sheet = null; onBalance() })
    }
    settledResult?.takeIf { resultVisible }?.let { result ->
        PredictionResultSheet(result, onNextRound = { resultVisible = false; sheet = null },
            onDismiss = { resultVisible = false })
    }
}

private fun PythEthQuote.oracleQuote() = PredictionOracleQuote(priceUsd.toDouble(), confidenceUsd.toDouble(), publishTime)

private fun warmedPredictionRound(
    snapshot: PythEthSnapshot,
    terms: com.elevencapital.app.data.PythEthRoundTerms?,
    btcMarket: Boolean,
): PredictionPreviewRound? {
    val now = snapshot.nowMillis()
    val pool = Math.floorDiv(now, PredictionPreviewRound.DURATION_MILLIS)
    if (terms?.poolId != pool) return null
    return runCatching {
        val band = terms.bandUsd.toDouble()
        val opening = PredictionPreviewRound.liveForPool(pool, terms.anchor.oracleQuote(), now,
            band, terms.forecastSigmaUsd.toDouble(), allowWatchOnlyAnchor = btcMarket)
        val observed = snapshot.freshQuote(now)?.oracleQuote()
        if (observed != null && (btcMarket || observed.isUsableAt(now, band)))
            opening.ingest(observed, now, allowWatchOnlyObservation = btcMarket) else opening
    }.getOrNull()
}

private fun PythEthSnapshot.usableQuote(nowMillis: Long = nowMillis(),
    bandUsd: Double = PredictionPreviewRound.BAND,
    btcMarket: Boolean = false): PythEthQuote? =
    freshQuote(nowMillis)?.takeIf { it.oracleQuote().isUsableAt(nowMillis, bandUsd, btcMarket) }

@Composable
private fun PredictionBrandHeader(onBack: () -> Unit, onBalance: () -> Unit, balance: BigDecimal,
    usdcCollateral: Boolean) {
    val balanceLabel = remember(balance) {
        DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
            .format(balance.setScale(2, RoundingMode.HALF_UP))
    }
    val displayBalance = if (usdcCollateral) "$balanceLabel USDC" else "$" + balanceLabel
    Row(Modifier.fillMaxWidth().height(rd(48f)).padding(horizontal = rd(16f)),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(rd(44f)).predictionGlass()
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = "Back to stocks" }, contentAlignment = Alignment.Center) {
            RefIcon("back", 23f, PredictionPalette.White)
        }
        Spacer(Modifier.width(rd(5f)))
        BrandLogo(Modifier.size(rd(25f)))
        Spacer(Modifier.width(rd(8f)))
        RefText("ELEVEN", 16f, PredictionPalette.White, FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Row(Modifier.height(rd(42f)).predictionGlass()
            .clickable(role = Role.Button, onClick = onBalance)
            .semantics { contentDescription = "Balance $displayBalance" }
            .padding(horizontal = rd(12f)), verticalAlignment = Alignment.CenterVertically) {
            PredictionBanknoteIcon()
            Spacer(Modifier.width(rd(7f)))
            RefText("Balance $displayBalance", 13f, PredictionPalette.White, FontWeight.Medium)
        }
    }
}

@Composable
private fun PredictionQuoteHeader(round: PredictionPreviewRound, poolProfitLoss: Double,
    btcMarket: Boolean, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(rd(42f)).predictionGlass()
                .padding(end = rd(5f)), verticalAlignment = Alignment.CenterVertically) {
                if (btcMarket) {
                    Image(painterResource(R.drawable.token_btc_official), "Bitcoin",
                        Modifier.padding(start = rd(4f)).size(rd(34f)).clip(CircleShape),
                        contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(rd(8f)))
                } else {
                    Box(Modifier.padding(start = rd(6f)).size(rd(30f)).clip(CircleShape)
                        .background(Color(0xFFF8F8F4)), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.token_eth_official), "Ethereum",
                            Modifier.size(rd(22f)), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(rd(7f)))
                }
                val priceColor = when {
                    !round.started || round.price == round.anchor -> PredictionPalette.White
                    round.price > round.anchor -> if (btcMarket) PredictionPalette.Lime else P.Lime
                    else -> if (btcMarket) PredictionPalette.Red else P.Pink
                }
                RefText(predictionMoney(round.price), 15f, priceColor, FontWeight.Medium)
                Spacer(Modifier.width(rd(9f)))
                val pl = poolProfitLoss.takeIf { it.isFinite() } ?: 0.0
                val plColor = when {
                    pl > .005 -> PredictionPalette.Lime
                    pl < -.005 -> PredictionPalette.Red
                    else -> PredictionPalette.White
                }
                val plLabel = when {
                    pl > .005 -> "+${predictionMoney(pl)}"
                    pl < -.005 -> "−${predictionMoney(-pl)}"
                    else -> predictionMoney(0.0)
                }
                Row(Modifier.height(rd(32f)).predictionGlass().padding(horizontal = rd(9f)),
                    verticalAlignment = Alignment.CenterVertically) {
                    RefText("P/L", 11f, PredictionPalette.Muted, FontWeight.Medium)
                    Spacer(Modifier.width(rd(5f)))
                    RefText(plLabel, 13f, plColor, FontWeight.Medium)
                }
            }
            Spacer(Modifier.weight(1f))
            val timerColor = if (round.running && round.remaining <= 15) PredictionPalette.Red else PredictionPalette.Lime
            Row(Modifier.height(rd(42f)).predictionGlass()
                .padding(horizontal = rd(10f)), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(rd(23f))) {
                    val thickness = size.minDimension * .07f
                    drawCircle(timerColor.copy(alpha = .15f), (size.minDimension - thickness) / 2, style = Stroke(thickness))
                    drawArc(timerColor, -90f, 360f * round.remaining / PredictionPreviewRound.DURATION,
                        false, topLeft = Offset(thickness / 2, thickness / 2),
                        size = Size(size.width - thickness, size.height - thickness),
                        style = Stroke(thickness, cap = StrokeCap.Round))
                    drawCircle(timerColor, size.minDimension * .12f)
                }
                Spacer(Modifier.width(rd(7f)))
                RefText("${round.remaining}s", 14f, PredictionPalette.White, FontWeight.Medium)
            }
        }
        if (btcMarket && round.elapsedMillis >= PredictionPreviewRound.ENTRY_OPEN_DELAY_MILLIS) {
            Spacer(Modifier.height(rd(4f)))
            Row(Modifier.height(rd(22f)).predictionGlass().padding(horizontal = rd(9f)),
                verticalAlignment = Alignment.CenterVertically) {
                RefText("Opening Round Price", 10f, PredictionPalette.Muted, FontWeight.Medium)
                Spacer(Modifier.width(rd(6f)))
                RefText(predictionMoney(round.anchor), 11f, PredictionPalette.White, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PredictionBetweenRounds(snapshot: PythEthSnapshot, nowMillis: Long,
    liveHistory: List<PythEthQuote>, marketSymbol: String, btcMarket: Boolean, modifier: Modifier) {
    val liveQuote = snapshot.freshQuote(nowMillis)
    val roundLength = PredictionPreviewRound.DURATION_MILLIS
    val windowStart = Math.floorDiv(nowMillis, roundLength) * roundLength
    val elapsedMillis = Math.floorMod(nowMillis, roundLength)
    val remaining = if (snapshot.hasServerTime)
        ((roundLength - elapsedMillis + 999L) / 1_000L).toInt() else null
    val observations = liveHistory.filter {
        it.publishTime * 1_000L in windowStart..nowMillis
    }.let { history ->
        if (liveQuote != null && liveQuote.publishTime * 1_000L in windowStart..nowMillis &&
            history.lastOrNull()?.publishTime != liveQuote.publishTime) history + liveQuote else history
    }
    // A skipped pool has no trusted boundary price. Begin at its first actual report instead.
    val firstObservedMillis = observations.firstOrNull()?.publishTime?.times(1_000L) ?: windowStart
    val targetViewport = if (observations.isNotEmpty()) {
        val low = observations.minOf { it.priceUsd.toDouble() }
        val high = observations.maxOf { it.priceUsd.toDouble() }
        val span = max(high - low, max(1.5, high * .0002)) * 1.5
        val center = (low + high) / 2
        PredictionChartViewport((center - span / 2).toFloat(),
            (center + span / 2).toFloat())
    } else PredictionChartViewport(0f, 1f)
    val viewport = remember(windowStart, firstObservedMillis, observations.isNotEmpty()) {
        Animatable(targetViewport, PredictionChartViewportConverter)
    }
    LaunchedEffect(windowStart, firstObservedMillis, targetViewport) {
        viewport.animateTo(targetViewport, tween(750, easing = LinearEasing))
    }
    val clockSnapshot by rememberUpdatedState(snapshot)
    val playheadSecond = remember(windowStart, firstObservedMillis) {
        mutableFloatStateOf(((nowMillis - firstObservedMillis) / 1_000f).coerceAtLeast(0f))
    }
    LaunchedEffect(windowStart, firstObservedMillis) {
        val remainingSeconds = ((windowStart + roundLength - firstObservedMillis) / 1_000f)
            .coerceAtLeast(1f)
        while (isActive) {
            withFrameNanos { }
            val second = ((clockSnapshot.nowMillis() - firstObservedMillis) / 1_000f)
                .coerceIn(0f, remainingSeconds)
            // A delayed stream heartbeat must not pull the already drawn head backwards.
            if (second > playheadSecond.floatValue) playheadSecond.floatValue = second
            if (second >= remainingSeconds) break
        }
    }
    val latestObservation = observations.lastOrNull()
    val firstPrice = observations.firstOrNull()?.priceUsd?.toDouble() ?: 0.0
    val targetHeadPrice = latestObservation?.let { (it.priceUsd.toDouble() - firstPrice).toFloat() } ?: 0f
    val headPrice = remember(windowStart, firstObservedMillis) { Animatable(targetHeadPrice) }
    LaunchedEffect(windowStart, firstObservedMillis, latestObservation?.publishTime, targetHeadPrice) {
        headPrice.animateTo(targetHeadPrice, tween(750, easing = LinearEasing))
    }
    val referenceUnit = with(LocalDensity.current) { rd(1f).toPx() }
    val context = LocalContext.current
    val axisPaint = remember(context, referenceUnit) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = PredictionPalette.White.copy(alpha = .75f).toArgb()
            textSize = referenceUnit * 10f
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = context.resources.getFont(R.font.roboto)
        }
    }
    val axisFormat = remember { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)) }
    val guideDashes = remember(referenceUnit) {
        PathEffect.dashPathEffect(floatArrayOf(referenceUnit * 3f, referenceUnit * 4f))
    }
    Box(modifier) {
        Box(Modifier.fillMaxSize().drawWithCache {
            val hatchStart = size.width * .5f
            val hatching = Path().apply {
                var x = hatchStart - size.height
                while (x < size.width) {
                    moveTo(x, size.height); lineTo(x + size.height, 0f)
                    x += referenceUnit * 15f
                }
            }
            val grid = Path().apply {
                for (level in 0..3) {
                    val y = size.height * (.23f + .67f * level / 3)
                    moveTo(0f, y); lineTo(size.width, y)
                }
                moveTo(hatchStart, 0f); lineTo(hatchStart, size.height)
            }
            onDrawBehind {
                clipRect(left = hatchStart) {
                    drawPath(hatching, PredictionPalette.Border.copy(alpha = .55f),
                        style = Stroke(referenceUnit * .6f))
                }
                drawPath(grid, PredictionPalette.Border.copy(alpha = .75f),
                    style = Stroke(referenceUnit * .45f))
            }
        })
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "$marketSymbol live price chart from observed quotes"
        }) {
            val top = size.height * .23f
            val bottom = size.height * .90f
            val left = size.width * .01f
            val right = size.width * .95f
            if (observations.isNotEmpty()) {
                val rendered = viewport.value
                val visibleSpan = (rendered.high - rendered.low).toDouble().coerceAtLeast(.01)
                val availableSeconds = ((windowStart + roundLength - firstObservedMillis) / 1_000f)
                    .coerceAtLeast(1f)
                val currentSecond = playheadSecond.floatValue.coerceIn(0f, availableSeconds)
                fun second(quote: PythEthQuote): Float =
                    (quote.publishTime * 1_000L - firstObservedMillis) / 1_000f
                fun y(price: Double): Float = bottom -
                    ((price - rendered.low) / visibleSpan).toFloat() * (bottom - top)
                fun x(second: Float): Float = left +
                    (second / availableSeconds).coerceIn(0f, 1f) * (right - left)
                val displayedHeadPrice = firstPrice + headPrice.value
                val line = Path().apply {
                    moveTo(left, y(observations.first().priceUsd.toDouble()))
                    observations.drop(1).dropLast(1).forEach { quote ->
                        if (second(quote) <= currentSecond)
                            lineTo(x(second(quote)), y(quote.priceUsd.toDouble()))
                    }
                    latestObservation?.takeIf { second(it) <= currentSecond }?.let { quote ->
                        lineTo(x(second(quote)), y(displayedHeadPrice))
                    }
                    lineTo(x(currentSecond), y(displayedHeadPrice))
                }
                val head = Offset(x(currentSecond), y(displayedHeadPrice))
                clipRect(top = top, bottom = bottom) {
                    drawLine(PredictionPalette.Lime.copy(alpha = .32f), head,
                        Offset(size.width, head.y), referenceUnit,
                        pathEffect = guideDashes)
                    clipRect(left = left, right = right) {
                        drawPath(line, PredictionPalette.Lime,
                            style = Stroke(referenceUnit * 1.8f,
                                cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawCircle(PredictionPalette.Lime.copy(alpha = .10f), referenceUnit * 8f, head)
                    drawCircle(PredictionPalette.Lime.copy(alpha = .38f), referenceUnit * 4f, head)
                    drawCircle(PredictionPalette.Lime, referenceUnit * 2.7f, head)
                }
                val baselineOffset = -(axisPaint.ascent() + axisPaint.descent()) / 2
                for (level in 0..3) {
                    val value = rendered.high.toDouble() - visibleSpan * level / 3
                    drawContext.canvas.nativeCanvas.drawText(axisFormat.format(value),
                        size.width - referenceUnit * 12f,
                        top + (bottom - top) * level / 3 + baselineOffset, axisPaint)
                }
            }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .padding(horizontal = rd(14f), vertical = rd(8f)),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(rd(42f)).predictionGlass().padding(end = rd(5f)),
                verticalAlignment = Alignment.CenterVertically) {
                if (btcMarket) {
                    Image(painterResource(R.drawable.token_btc_official), "Bitcoin",
                        Modifier.padding(start = rd(4f)).size(rd(34f)).clip(CircleShape),
                        contentScale = ContentScale.Fit)
                    Spacer(Modifier.width(rd(8f)))
                } else {
                    Box(Modifier.padding(start = rd(6f)).size(rd(30f)).clip(CircleShape)
                        .background(Color(0xFFF8F8F4)),
                        contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.token_eth_official), "Ethereum",
                            Modifier.size(rd(22f)), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(rd(7f)))
                }
                RefText(liveQuote?.priceUsd?.toDouble()?.let(::predictionMoney) ?: "—", 15f,
                    if (btcMarket) Color(0xFFF7931A) else PredictionPalette.White,
                    FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            val timerColor = when {
                remaining == null -> PredictionPalette.Muted
                remaining <= 15 -> PredictionPalette.Red
                else -> PredictionPalette.Lime
            }
            Row(Modifier.height(rd(42f)).predictionGlass().padding(horizontal = rd(10f)),
                verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(rd(23f))) {
                    val thickness = size.minDimension * .07f
                    drawCircle(timerColor.copy(alpha = .15f), (size.minDimension - thickness) / 2,
                        style = Stroke(thickness))
                    if (remaining != null) {
                        drawArc(timerColor, -90f,
                            360f * remaining / PredictionPreviewRound.DURATION,
                            false, topLeft = Offset(thickness / 2, thickness / 2),
                            size = Size(size.width - thickness, size.height - thickness),
                            style = Stroke(thickness, cap = StrokeCap.Round))
                    }
                    drawCircle(timerColor, size.minDimension * .12f)
                }
                Spacer(Modifier.width(rd(7f)))
                RefText(remaining?.let { "${it}s" } ?: "—", 14f,
                    PredictionPalette.White, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PredictionRoundBanner(round: PredictionPreviewRound, marketSymbol: String,
    closedValue: Double?, modifier: Modifier) {
    Row(modifier.fillMaxWidth().predictionGlass(RoundedCornerShape(rd(20f)))
        .padding(horizontal = rd(13f), vertical = rd(12f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(rd(29f)).predictionGlass(), contentAlignment = Alignment.Center) {
            if (closedValue != null || round.settled) RefIcon("check", 15f, PredictionPalette.Muted)
            else PredictionGlyph("lock", 15f, PredictionPalette.Muted)
        }
        Spacer(Modifier.width(rd(11f)))
        Column {
            RefText(when {
                round.settled -> "Round complete"
                closedValue != null -> "Position closed"
                else -> "Position open"
            }, 13f, PredictionPalette.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            RefText(when {
                round.settled -> "$marketSymbol finished ${if (round.outcome == PredictionDirection.AROUND) "inside" else round.outcome.label.lowercase()} the starting-price band."
                closedValue != null -> "Traded out at ${predictionMoney(closedValue)} · ${round.remaining}s left"
                else -> "${round.remaining}s left"
            }, 10f, PredictionPalette.Muted, maxLines = 2)
        }
    }
}

@Composable
private fun PredictionPositionBar(round: PredictionPreviewRound,
    tradeOutValue: BigDecimal? = null, tradeOutPnl: BigDecimal? = null,
    marketSymbol: String = "ETH", enabled: Boolean = true, onSell: () -> Unit) {
    val stake = round.stake.toDouble()
    val value = round.positionValue ?: stake
    val profit = value - stake
    val profitColor = if (profit < -.005) P.Pink else PredictionPalette.Lime
    val profitLabel = when {
        profit > .005 -> "+${predictionMoney(profit)}"
        profit < -.005 -> "−${predictionMoney(-profit)}"
        else -> predictionMoney(0.0)
    }
    val shape = RoundedCornerShape(rd(18f))
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(14f), vertical = rd(8f))
        .predictionGlass(shape).padding(horizontal = rd(14f), vertical = rd(12f))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                RefText(if (round.active) "Prediction Placed: ${round.direction?.label.orEmpty()}"
                    else "Position traded out",
                    14f, PredictionPalette.White, FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.height(rd(5f)))
                RefText(if (round.active) "Entry $marketSymbol ${predictionMoney(round.entryPrice ?: round.anchor)} · Stake ${predictionMoney(stake)}"
                    else "${round.remaining}s left in this round",
                    11f, PredictionPalette.Muted, maxLines = 1)
            }
            Spacer(Modifier.width(rd(10f)))
            PredictionMetricCard(if (round.active) "Profit" else "Trade out value",
                if (round.active) profitLabel else predictionMoney(tradeOutValue?.toDouble() ?: 0.0),
                Modifier.width(rd(139f)),
                valueColor = if (round.active) profitColor else PredictionPalette.White,
                valueSize = 18f, minHeight = 61f)
        }
        Spacer(Modifier.height(rd(11f)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val exitPnl = tradeOutPnl?.toDouble()
            val footer = if (round.active) "Pool start ${predictionMoney(round.anchor)}" else
                "P/L ${if ((exitPnl ?: 0.0) >= 0) "+" else "−"}${predictionMoney(kotlin.math.abs(exitPnl ?: 0.0))}"
            RefText(footer, 11f,
                PredictionPalette.Muted, modifier = Modifier.weight(1f), maxLines = 1)
            if (round.active) {
                Box(Modifier.height(rd(40f)).predictionGlass()
                    .clickable(enabled = enabled, role = Role.Button, onClick = onSell)
                    .padding(horizontal = rd(16f)), contentAlignment = Alignment.Center) {
                    RefText("Trade out", 13f, PredictionPalette.White, FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun PredictionControls(stake: Int,
    onAmount: (Int) -> Unit, onStake: () -> Unit,
    stakeEnabled: Boolean = true, stakeLabel: String = "Stake") {
    var amountMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(stakeEnabled) {
        if (!stakeEnabled) amountMenuOpen = false
    }
    Row(Modifier.fillMaxWidth().height(rd(50f)).padding(horizontal = rd(14f)),
        verticalAlignment = Alignment.CenterVertically) {
        PredictionControlPill("Rules", "Prediction rules", null) { PredictionInfoIcon(15f) }
        Spacer(Modifier.weight(1f))
        PredictionControlPill(stakeLabel, if (stakeEnabled) "Stake on Above, Range or Below" else stakeLabel,
            onStake, true, enabled = stakeEnabled) {
            PredictionGlyph("flame", 17f,
                if (stakeEnabled) PredictionPalette.Lime else PredictionPalette.Muted)
        }
        Spacer(Modifier.width(rd(7f)))
        Box {
            PredictionControlPill("$$stake", "Choose stake value, $$stake", { amountMenuOpen = true }, true,
                enabled = stakeEnabled) {
                PredictionBanknoteIcon(if (stakeEnabled) PredictionPalette.White else PredictionPalette.Muted)
            }
            if (amountMenuOpen) {
                PredictionAmountMenu(stake, onSelect = { value ->
                    onAmount(value)
                    amountMenuOpen = false
                }, onDismiss = { amountMenuOpen = false })
            }
        }
    }
}

@Composable
private fun PredictionAmountMenu(amount: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val gapPx = with(LocalDensity.current) { rd(6f).roundToPx() }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.right - popupContentSize.width)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height - gapPx)
                    .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    Popup(onDismissRequest = onDismiss, popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true,
            dismissOnClickOutside = true)) {
        val panelShape = RoundedCornerShape(rd(20f))
        Column(Modifier.width(rd(116f)).clip(panelShape)
            .background(Color(0xFF222222))
            .border(rd(1f), Color.White.copy(alpha = .08f), panelShape)
            .padding(rd(6f))) {
            listOf(5, 20, 50, 100).forEach { value ->
                val active = amount == value
                Box(Modifier.fillMaxWidth().height(rd(50f))
                    .clip(RoundedCornerShape(rd(14f)))
                    .background(if (active) Color(0xFF343B2D) else Color.Transparent)
                    .semantics { selected = active; contentDescription = "Stake value $$value" }
                    .clickable(role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center) {
                    if (active) {
                        Box(Modifier.align(Alignment.CenterStart).padding(start = rd(14f))
                            .size(rd(8f)).background(PredictionPalette.Lime, CircleShape))
                    }
                    RefText("$$value", 18f,
                        if (active) PredictionPalette.Lime else PredictionPalette.White,
                        FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun PredictionWatchPanel(remainingSeconds: Int?, onViewResult: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = rd(14f), vertical = rd(7f))
        .height(rd(62f)).predictionGlass(RoundedCornerShape(rd(20f)))
        .padding(horizontal = rd(13f)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(rd(29f)).predictionGlass(), contentAlignment = Alignment.Center) {
            RefIcon("eye", 15f, PredictionPalette.Muted)
        }
        Spacer(Modifier.width(rd(11f)))
        Column(Modifier.weight(1f)) {
            RefText("Watch only", 13f, PredictionPalette.White, FontWeight.Medium)
            Spacer(Modifier.height(rd(3f)))
            RefText(remainingSeconds?.let { "Next round in ${it}s" } ?: "Waiting for round time",
                11f, PredictionPalette.Muted)
        }
        if (onViewResult != null) {
            Box(Modifier.height(rd(36f)).predictionGlass()
                .clickable(role = Role.Button, onClick = onViewResult)
                .padding(horizontal = rd(10f)), contentAlignment = Alignment.Center) {
                RefText("View result", 11f, PredictionPalette.White, FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PredictionControlPill(label: String, accessibility: String, onClick: (() -> Unit)?,
    dropdown: Boolean = false, enabled: Boolean = true, icon: @Composable () -> Unit) {
    Row(Modifier.height(rd(40f)).predictionGlass()
        .then(onClick?.let { Modifier.clickable(enabled = enabled, role = Role.Button, onClick = it) } ?: Modifier)
        .semantics { contentDescription = accessibility }
        .padding(horizontal = rd(10f)), verticalAlignment = Alignment.CenterVertically) {
        icon(); Spacer(Modifier.width(rd(6f)))
        RefText(label, 14f, if (enabled) PredictionPalette.White else PredictionPalette.Muted,
            FontWeight.Medium)
        if (dropdown) {
            Spacer(Modifier.width(rd(8f)))
            Box(Modifier.width(rd(1f)).height(rd(16f)).background(PredictionPalette.Border))
            Spacer(Modifier.width(rd(8f)))
            RefIcon("chevronUp", 10f, if (enabled) PredictionPalette.White else PredictionPalette.Muted)
        }
    }
}

@Composable
internal fun PredictionInfoIcon(size: Float) {
    Canvas(Modifier.size(rd(size))) {
        val r = this.size.minDimension / 2
        drawCircle(PredictionPalette.Muted, r * .83f, style = Stroke(r * .13f))
        drawCircle(PredictionPalette.Muted, r * .09f, Offset(r, r * .58f))
        drawLine(PredictionPalette.Muted, Offset(r, r * .9f), Offset(r, r * 1.45f), r * .14f, StrokeCap.Round)
    }
}

@Composable
private fun PredictionBanknoteIcon(color: Color = PredictionPalette.White) {
    Canvas(Modifier.size(rd(17f))) {
        drawRoundRect(color, Offset(0f, size.height * .19f), Size(size.width, size.height * .62f),
            CornerRadius(size.width * .1f), style = Stroke(1.7f))
        drawCircle(color, size.width * .13f, style = Stroke(1.6f))
        drawCircle(color, size.width * .04f, Offset(size.width * .16f, size.height * .5f))
        drawCircle(color, size.width * .04f, Offset(size.width * .84f, size.height * .5f))
    }
}

@Composable
internal fun PredictionGlyph(name: String, size: Float, color: Color) {
    val path = remember(name) {
        Path().apply {
            if (name == "lock") {
                moveTo(7f, 10f); lineTo(7f, 7f)
                cubicTo(7f, 0f, 17f, 0f, 17f, 7f); lineTo(17f, 10f)
            } else {
                moveTo(13f, 2f)
                cubicTo(18f, 8f, 9f, 9f, 15f, 13f)
                cubicTo(16f, 10f, 19f, 10f, 19f, 15f)
                cubicTo(19f, 24f, 5f, 24f, 5f, 15f)
                cubicTo(5f, 12f, 7f, 10f, 8f, 8f)
                cubicTo(7f, 15f, 15f, 13f, 13f, 2f); close()
            }
        }
    }
    Canvas(Modifier.size(rd(size))) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(path, color, style = stroke)
            if (name == "lock") {
                drawRoundRect(color, Offset(4f, 10f), Size(16f, 12f), CornerRadius(3f), style = stroke)
                drawLine(color, Offset(12f, 14f), Offset(12f, 18f), 1.8f, StrokeCap.Round)
            }
        }
    }
}

private data class PredictionChartViewport(val low: Float, val high: Float)

private val PredictionChartViewportConverter = TwoWayConverter<PredictionChartViewport, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.low, it.high) },
    convertFromVector = { PredictionChartViewport(it.v1, it.v2) },
)

@Composable
private fun PredictionChart(round: PredictionPreviewRound, snapshot: PythEthSnapshot,
    marketSymbol: String, modifier: Modifier) {
    val points = round.points
    val observedLow = points.minOf { it.price }
    val observedHigh = points.maxOf { it.price }
    val visibleSpan = remember(round.roundId, round.band) {
        max(observedHigh, round.anchor + round.band * 2) - min(observedLow, round.anchor - round.band * 2) +
            max(1.5, round.band * 2)
    }
    val overscan = max(40.0, round.band * 2)
    var allowedLow by remember(round.roundId) {
        mutableDoubleStateOf((min(observedLow, round.price - visibleSpan / 2) - overscan).coerceAtLeast(0.0))
    }
    var allowedHigh by remember(round.roundId) {
        mutableDoubleStateOf(max(observedHigh, round.price + visibleSpan / 2) + overscan)
    }
    var followCenter by remember(round.roundId) { mutableDoubleStateOf(round.price) }
    var manualCenter by remember(round.roundId) { mutableStateOf<Double?>(null) }
    val isPanned by remember { derivedStateOf { manualCenter != null } }
    fun clampCenter(center: Double): Double =
        center.coerceIn(allowedLow + visibleSpan / 2, allowedHigh - visibleSpan / 2)
    LaunchedEffect(round.price, observedLow, observedHigh) {
        val requiredLow = (min(observedLow, round.price - visibleSpan / 2) - overscan).coerceAtLeast(0.0)
        val requiredHigh = max(observedHigh, round.price + visibleSpan / 2) + overscan
        if (requiredLow < allowedLow) allowedLow = requiredLow
        if (requiredHigh > allowedHigh) allowedHigh = requiredHigh
        val edge = visibleSpan * .30
        followCenter = clampCenter(when {
            round.price < followCenter - edge -> round.price + edge
            round.price > followCenter + edge -> round.price - edge
            else -> followCenter
        })
    }
    val low = followCenter - visibleSpan / 2
    val high = followCenter + visibleSpan / 2
    val targetViewport = PredictionChartViewport(low.toFloat(), high.toFloat())
    val viewport = remember(round.roundId) { Animatable(targetViewport, PredictionChartViewportConverter) }
    LaunchedEffect(round.roundId, targetViewport) {
        viewport.animateTo(targetViewport, tween(if (round.settled) 180 else 500, easing = LinearEasing))
    }
    // Advance the horizontal playhead from the backend's monotonic clock on every display frame.
    // Price observations remain at their real publication timestamps; no price ticks are generated here.
    val clockSnapshot by rememberUpdatedState(snapshot)
    val playheadSecond = remember(round.roundId) { mutableFloatStateOf(round.elapsedMillis / 1_000f) }
    val roundStartMillis = (round.roundId ?: Math.floorDiv(snapshot.nowMillis(),
        PredictionPreviewRound.DURATION_MILLIS)) * PredictionPreviewRound.DURATION_MILLIS
    LaunchedEffect(round.roundId, round.settled) {
        if (round.settled) {
            playheadSecond.floatValue = PredictionPreviewRound.DURATION.toFloat()
        } else {
            while (isActive) {
                withFrameNanos { }
                val second = ((clockSnapshot.nowMillis() - roundStartMillis) / 1_000f)
                    .coerceIn(0f, PredictionPreviewRound.DURATION.toFloat())
                // The received server clock may shift slightly with network transit; keep x monotonic.
                if (second > playheadSecond.floatValue) playheadSecond.floatValue = second
                if (second >= PredictionPreviewRound.DURATION) break
            }
        }
    }
    val latestPoint = points.last()
    val targetHeadPrice = (latestPoint.price - round.anchor).toFloat()
    val headPrice = remember(round.roundId) { Animatable(targetHeadPrice) }
    LaunchedEffect(round.roundId, latestPoint.second, targetHeadPrice) {
        if (round.settled) headPrice.snapTo(targetHeadPrice)
        else headPrice.animateTo(targetHeadPrice, tween(750, easing = LinearEasing))
    }
    val referenceUnit = with(LocalDensity.current) { rd(1f).toPx() }
    val line = remember { Path() }
    val cone = remember { Path() }
    val bellWidths = remember { FloatArray(41) { index ->
        val fy = -1f + index / 20f
        exp(-fy * fy * 2.6f)
    } }
    val guideDashes = remember(referenceUnit) {
        PathEffect.dashPathEffect(floatArrayOf(referenceUnit * 3f, referenceUnit * 4f))
    }
    val context = LocalContext.current
    val axisPaint = remember(context, referenceUnit) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = PredictionPalette.White.copy(alpha = .75f).toArgb()
            textSize = referenceUnit * 10f
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = context.resources.getFont(R.font.roboto)
        }
    }
    val axisFormat = remember { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        val plotHeightPx = with(LocalDensity.current) { maxHeight.toPx() * .67f }.coerceAtLeast(1f)
        val currentPlotHeight by rememberUpdatedState(plotHeightPx)
        val pan = rememberScrollableState { delta ->
            val oldCenter = manualCenter ?: ((viewport.value.low + viewport.value.high) / 2).toDouble()
            val newCenter = clampCenter(oldCenter + delta * visibleSpan / currentPlotHeight)
            manualCenter = newCenter
            ((newCenter - oldCenter) * currentPlotHeight / visibleSpan).toFloat()
        }
        // Fixed geometry is cached; only the price path and scale animate each frame.
        Box(Modifier.fillMaxSize().drawWithCache {
            val hatchStart = size.width * .5f
            val hatching = Path().apply {
                var x = hatchStart - size.height
                while (x < size.width) {
                    moveTo(x, size.height); lineTo(x + size.height, 0f)
                    x += referenceUnit * 15f
                }
            }
            val grid = Path().apply {
                for (level in 0..3) {
                    val y = size.height * (.23f + .67f * level / 3)
                    moveTo(0f, y); lineTo(size.width, y)
                }
                moveTo(hatchStart, 0f); lineTo(hatchStart, size.height)
            }
            onDrawBehind {
                clipRect(left = hatchStart) {
                    drawPath(hatching, PredictionPalette.Border.copy(alpha = .55f),
                        style = Stroke(referenceUnit * .6f))
                }
                drawPath(grid, PredictionPalette.Border.copy(alpha = .75f),
                    style = Stroke(referenceUnit * .45f))
            }
        })
        Canvas(Modifier.fillMaxSize().scrollable(pan, Orientation.Vertical)
            .semantics { contentDescription = "$marketSymbol price chart. Scroll vertically to inspect prices." }) {
            val rendered = viewport.value
            val center = manualCenter ?: ((rendered.low + rendered.high) / 2).toDouble()
            val viewportLow = center - visibleSpan / 2
            val viewportHigh = center + visibleSpan / 2
            val plotTop = size.height * .23f
            val plotBottom = size.height * .9f
            val plotHeight = plotBottom - plotTop
            val left = size.width * .01f
            val right = size.width * .95f
            val plotWidth = right - left
            val hatchStart = size.width * .5f
            fun y(value: Double) = plotBottom - ((value - viewportLow) / visibleSpan).toFloat() * plotHeight
            val currentSecond = playheadSecond.floatValue.coerceIn(points.first().second.toFloat(),
                PredictionPreviewRound.DURATION.toFloat())
            val windowStart = (currentSecond - PredictionPreviewRound.DURATION).coerceAtLeast(0f)
            fun x(second: Float) = left + (second - windowStart) / PredictionPreviewRound.DURATION * plotWidth
            val headX = x(currentSecond).coerceIn(left, right)
            val renderedPrice = round.anchor + headPrice.value
            val headY = y(renderedPrice)
            clipRect(left = hatchStart, top = plotTop, bottom = plotBottom) {
                cone.reset()
                cone.apply {
                    moveTo(size.width, headY - plotHeight * .38f)
                    for (step in 0..40) {
                        val fy = -1f + step / 20f
                        val width = size.width * .11f * bellWidths[step]
                        lineTo(size.width - width, headY + fy * plotHeight * .38f)
                    }
                    lineTo(size.width, headY + plotHeight * .38f); close()
                }
                drawPath(cone, PredictionPalette.Purple.copy(alpha = .85f))
            }
            clipRect(top = plotTop, bottom = plotBottom) {
            if (round.started) {
                for (price in listOf(round.anchor - round.band, round.anchor + round.band)) {
                    drawLine(PredictionPalette.Lime.copy(alpha = .3f), Offset(0f, y(price)), Offset(size.width, y(price)), 1f,
                        pathEffect = guideDashes)
                }
            }
            drawLine(PredictionPalette.Lime.copy(alpha = .32f), Offset(headX, headY), Offset(size.width, headY), 1f,
                pathEffect = guideDashes)
            line.reset()
            line.apply {
                moveTo(x(points.first().second.toFloat()), y(points.first().price))
                // Keep older reports fixed. Ease only the newest report into its timestamp,
                // then carry its observed price horizontally to the live clock position.
                for (index in 1 until points.lastIndex) {
                    val point = points[index]
                    if (point.second > currentSecond) break
                    lineTo(x(point.second.toFloat()), y(point.price))
                }
                if (latestPoint.second <= currentSecond)
                    lineTo(x(latestPoint.second.toFloat()), headY)
                lineTo(headX, headY)
            }
            clipRect(left = left, right = right) {
                drawPath(line, PredictionPalette.Lime, style = Stroke(referenceUnit * 1.8f,
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawCircle(PredictionPalette.Lime.copy(alpha = .10f), referenceUnit * 8f, Offset(headX, headY))
            drawCircle(PredictionPalette.Lime.copy(alpha = .38f), referenceUnit * 4f, Offset(headX, headY))
            drawCircle(PredictionPalette.Lime, referenceUnit * 2.7f, Offset(headX, headY))
            }
            // Labels share the exact animated/panned range; native text avoids page recomposition on every drag frame.
            val baselineOffset = -(axisPaint.ascent() + axisPaint.descent()) / 2
            for (level in 0..3) {
                val value = viewportHigh - visibleSpan * level / 3
                drawContext.canvas.nativeCanvas.drawText(axisFormat.format(value), size.width - referenceUnit * 12f,
                    plotTop + plotHeight * level / 3 + baselineOffset, axisPaint)
            }
        }
        if (isPanned) {
            Row(Modifier.align(Alignment.BottomStart).padding(start = rd(14f), bottom = rd(12f))
                .height(rd(36f)).predictionGlass()
                .clickable(role = Role.Button) {
                    scope.launch {
                        pan.stopScroll()
                        val center = manualCenter ?: return@launch
                        viewport.snapTo(PredictionChartViewport((center - visibleSpan / 2).toFloat(),
                            (center + visibleSpan / 2).toFloat()))
                        manualCenter = null
                        viewport.animateTo(targetViewport, tween(300, easing = LinearEasing))
                    }
                }.padding(horizontal = rd(11f)), verticalAlignment = Alignment.CenterVertically) {
                RefIcon("priceMarker", 13f, PredictionPalette.Lime)
                Spacer(Modifier.width(rd(6f)))
                RefText("Follow $marketSymbol", 11f, PredictionPalette.White, FontWeight.Medium)
            }
        }
    }
}
