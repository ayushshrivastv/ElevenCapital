package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.elevencapital.app.data.PantaMarket
import com.elevencapital.app.data.PantaPosition
import com.elevencapital.app.data.PantaPredictionApi
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Panta events replace the old local 90-second ledger; this screen never records a practice order. */
@Composable
internal fun PantaPredictionScreen(
    onBack: () -> Unit,
    api: PantaPredictionApi?,
    userId: String?,
    walletAddress: String?,
    walletUsdc: BigDecimal?,
) {
    BackHandler(onBack = onBack)
    val lifecycleOwner = LocalLifecycleOwner.current
    var markets by remember(userId) { mutableStateOf<List<PantaMarket>>(emptyList()) }
    var selectedId by remember(userId) { mutableStateOf<String?>(null) }
    var detail by remember(userId, selectedId) { mutableStateOf<PantaMarket?>(null) }
    var positions by remember(userId, walletAddress) { mutableStateOf<List<PantaPosition>>(emptyList()) }
    var error by remember(userId) { mutableStateOf<String?>(null) }
    var detailError by remember(userId, selectedId) { mutableStateOf<String?>(null) }
    var loading by remember(userId) { mutableStateOf(true) }
    var showMarkets by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, userId, walletAddress, lifecycleOwner, refresh) {
        if (api == null || userId == null) {
            loading = false
            error = "Prediction markets are unavailable."
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val next = api.markets(userId)
                    markets = next
                    selectedId = selectedId?.takeIf { id -> next.any { it.marketId == id } }
                        ?: next.firstOrNull()?.marketId
                    positions = if (walletAddress != null) api.positions(userId, walletAddress)
                        else emptyList()
                    error = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = failure.message ?: "Prediction markets are unavailable."
                } finally {
                    loading = false
                }
                delay(45_000)
            }
        }
    }
    LaunchedEffect(api, userId, selectedId, refresh, lifecycleOwner) {
        val id = selectedId ?: return@LaunchedEffect
        if (api == null || userId == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    detail = api.market(userId, id)
                    detailError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    detail = null
                    detailError = "Prices are unavailable."
                }
                delay(30_000)
            }
        }
    }

    val selected = detail?.takeIf { it.marketId == selectedId }
        ?: markets.firstOrNull { it.marketId == selectedId }
    val selectedPositions = positions.filter { it.marketId == selectedId }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        PantaPredictionHeader(onBack, walletUsdc)
        Row(Modifier.fillMaxWidth().padding(horizontal = rd(16f), vertical = rd(10f)),
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Prediction", 22f, PredictionPalette.White, FontWeight.Bold,
                Modifier.weight(1f))
            Box(Modifier.height(rd(40f)).predictionGlass()
                .clickable(role = Role.Button) { showMarkets = true }
                .semantics { contentDescription = "Browse prediction markets" }
                .padding(horizontal = rd(16f)), contentAlignment = Alignment.Center) {
                RefText("Markets", 13f, PredictionPalette.White, FontWeight.Medium)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = rd(16f))) {
            when {
                selected != null -> PantaPredictionDetail(selected, selectedPositions,
                    detailError, Modifier.fillMaxSize())
                loading -> RefText("Loading markets…", 16f, PredictionPalette.Muted,
                    modifier = Modifier.align(Alignment.Center))
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    RefText("Markets unavailable", 20f, PredictionPalette.White, FontWeight.Bold)
                    Spacer(Modifier.height(rd(8f)))
                    RefText(error ?: "No markets are available right now.", 13f, PredictionPalette.Muted,
                        maxLines = 3)
                    Spacer(Modifier.height(rd(16f)))
                    Box(Modifier.height(rd(42f)).predictionGlass()
                        .clickable(role = Role.Button) { refresh++ }
                        .padding(horizontal = rd(20f)), contentAlignment = Alignment.Center) {
                        RefText("Refresh", 13f, PredictionPalette.White, FontWeight.Medium)
                    }
                }
            }
        }
        PantaPredictionActions(selected)
        Box(Modifier.fillMaxWidth().padding(top = rd(8f), bottom = rd(16f)),
            contentAlignment = Alignment.Center) {
            RefText("Powered by Panta", 12f, PredictionPalette.White, FontWeight.Medium)
        }
    }
    if (showMarkets) PredictionSheet("Markets", onDismiss = { showMarkets = false },
        glassPanel = true) {
        if (markets.isEmpty()) {
            RefText(if (loading) "Loading markets…" else error ?: "No markets are available.",
                14f, PredictionPalette.Muted, maxLines = 3)
        } else {
            markets.forEachIndexed { index, market ->
                if (index > 0) Spacer(Modifier.height(rd(8f)))
                val shape = RoundedCornerShape(rd(17f))
                Column(Modifier.fillMaxWidth().predictionGlass(shape)
                    .clickable(role = Role.Button) {
                        selectedId = market.marketId
                        detail = null
                        showMarkets = false
                    }.padding(rd(14f))) {
                    RefText(market.title, 15f, PredictionPalette.White, FontWeight.Medium,
                        maxLines = 3)
                    Spacer(Modifier.height(rd(5f)))
                    RefText(market.category.ifBlank { market.phase }, 12f,
                        PredictionPalette.Muted)
                }
            }
        }
    }
}

@Composable
private fun PantaPredictionHeader(onBack: () -> Unit, walletUsdc: BigDecimal?) {
    val balance = walletUsdc?.setScale(2, RoundingMode.DOWN)?.toPlainString() ?: "—"
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
        Row(Modifier.height(rd(42f)).predictionGlass().padding(horizontal = rd(12f)),
            verticalAlignment = Alignment.CenterVertically) {
            RefText("Balance $balance USDC", 13f, PredictionPalette.White, FontWeight.Medium)
        }
    }
}

@Composable
private fun PantaPredictionDetail(market: PantaMarket, positions: List<PantaPosition>,
    detailError: String?, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(rd(22f)))
        RefText(market.category.uppercase().ifBlank { "MARKET" }, 12f,
            PredictionPalette.Muted, FontWeight.Medium)
        Spacer(Modifier.height(rd(8f)))
        RefText(market.title, 29f, PredictionPalette.White, FontWeight.Bold, maxLines = 4)
        Spacer(Modifier.height(rd(14f)))
        RefText(market.description, 14f, PredictionPalette.Muted, maxLines = 5)
        Spacer(Modifier.height(rd(26f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
            PantaOutcomePrice("YES", market.yesPrice, PredictionPalette.Lime, Modifier.weight(1f))
            PantaOutcomePrice("NO", market.noPrice, PredictionPalette.Red, Modifier.weight(1f))
        }
        if (detailError != null) {
            Spacer(Modifier.height(rd(10f)))
            RefText(detailError, 12f, PredictionPalette.Muted)
        }
        Spacer(Modifier.height(rd(24f)))
        PantaMarketFact("Phase", market.phase.replaceFirstChar { it.uppercase() })
        market.volumeUsdc?.let { volume ->
            Spacer(Modifier.height(rd(10f)))
            PantaMarketFact("Volume", "$${volume.setScale(0, RoundingMode.DOWN).toPlainString()} USDC")
        }
        market.endTimeSeconds?.let { seconds ->
            Spacer(Modifier.height(rd(10f)))
            val date = remember(seconds) {
                DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
            }
            PantaMarketFact("Closes", date)
        }
        if (positions.isNotEmpty()) {
            Spacer(Modifier.height(rd(28f)))
            RefText("Your position", 17f, PredictionPalette.White, FontWeight.Bold)
            Spacer(Modifier.height(rd(10f)))
            positions.forEach { position ->
                PantaMarketFact(position.side.uppercase(),
                    "${position.shares.stripTrailingZeros().toPlainString()} shares")
                Spacer(Modifier.height(rd(8f)))
            }
        }
        Spacer(Modifier.height(rd(20f)))
    }
}

@Composable
private fun PantaOutcomePrice(label: String, price: BigDecimal?, color: Color,
    modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(rd(20f))
    Column(modifier.predictionGlass(shape).padding(rd(18f))) {
        RefText(label, 13f, color, FontWeight.Bold)
        Spacer(Modifier.height(rd(10f)))
        RefText(price?.multiply(BigDecimal(100))?.setScale(1, RoundingMode.HALF_UP)
            ?.toPlainString()?.plus("%") ?: "—", 29f, PredictionPalette.White, FontWeight.Bold)
    }
}

@Composable
private fun PantaMarketFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RefText(label, 13f, PredictionPalette.Muted, modifier = Modifier.weight(1f))
        RefText(value, 13f, PredictionPalette.White, FontWeight.Medium, maxLines = 2)
    }
}

@Composable
private fun PantaPredictionActions(market: PantaMarket?) {
    if (market == null) return
    val eligible = market.phase == "primary" && market.status == "open"
    Column(Modifier.fillMaxWidth().padding(horizontal = rd(16f))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
            listOf("Buy YES" to PredictionPalette.Lime, "Buy NO" to PredictionPalette.Red)
                .forEach { (label, color) ->
                    Box(Modifier.weight(1f).height(rd(54f))
                        .background(color.copy(alpha = .22f), RoundedCornerShape(rd(30f)))
                        .border(rd(1f), color.copy(alpha = .42f), RoundedCornerShape(rd(30f)))
                        .semantics { contentDescription = "$label unavailable" },
                        contentAlignment = Alignment.Center) {
                        RefText(label, 16f, color.copy(alpha = .65f), FontWeight.Bold)
                    }
                }
        }
        Spacer(Modifier.height(rd(9f)))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RefText(if (eligible) "Trading is unavailable" else "This market is not open for primary orders",
                12f, PredictionPalette.Muted, maxLines = 2)
        }
    }
}
