package com.elevencapital.app.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.wallet.QrCodeMatrix
import com.elevencapital.app.wallet.ValidatedWalletAddress
import com.elevencapital.app.wallet.WalletAsset
import com.elevencapital.app.wallet.WalletNetwork
import com.elevencapital.app.wallet.WalletNetworkId
import kotlinx.coroutines.delay

enum class WalletPage { RECEIVE, SEND }

private enum class ReceiveNetworkId { SOLANA, ETHEREUM, ARBITRUM }

private data class ReceiveNetwork(
    val id: ReceiveNetworkId,
    val displayName: String,
    val label: String,
    val walletChain: WalletChain,
    val addressNetworkId: WalletNetworkId,
    val assets: String,
    val instructions: String,
)

// These choices only affect receiving. Send keeps its separate, validated asset allowlist.
private val receiveNetworks = listOf(
    ReceiveNetwork(ReceiveNetworkId.SOLANA, "Solana", "Solana Mainnet", WalletChain.SOLANA,
        WalletNetworkId.SOLANA_MAINNET, "SOL / USDC", "Receive SOL or USDC on Solana Mainnet."),
    ReceiveNetwork(ReceiveNetworkId.ETHEREUM, "Ethereum", "Ethereum Mainnet", WalletChain.ETHEREUM,
        WalletNetworkId.ETHEREUM_MAINNET, "ETH / USDC", "Receive ETH or USDC on Ethereum Mainnet."),
    ReceiveNetwork(ReceiveNetworkId.ARBITRUM, "Arbitrum", "Arbitrum One", WalletChain.ETHEREUM,
        WalletNetworkId.ETHEREUM_MAINNET, "ETH / native USDC", "Receive ETH or native USDC on Arbitrum One."),
)

@Composable
fun ReceiveWalletScreen(
    wallets: List<UserWallet>,
    onBack: () -> Unit,
    walletDisplayName: String = "Main wallet",
) {
    val availableNetworks = remember(wallets) {
        receiveNetworks.filter { network -> wallets.any { it.chain == network.walletChain } }
    }
    var selectedNetworkId by remember(wallets) {
        mutableStateOf(
            availableNetworks.firstOrNull { it.id == ReceiveNetworkId.SOLANA }?.id
                ?: availableNetworks.firstOrNull()?.id
                ?: ReceiveNetworkId.SOLANA,
        )
    }
    val selectedNetwork = receiveNetworks.first { it.id == selectedNetworkId }
    val networkWallets = remember(wallets, selectedNetworkId) {
        wallets.filter { it.chain == selectedNetwork.walletChain }
    }
    var selectedWalletKey by remember(selectedNetworkId, networkWallets) {
        mutableStateOf(networkWallets.firstOrNull()?.stableWalletKey().orEmpty())
    }
    val wallet = networkWallets.firstOrNull { it.stableWalletKey() == selectedWalletKey }
        ?: networkWallets.firstOrNull()
    val address = remember(wallet?.address, selectedNetworkId) {
        wallet?.address?.let { rawAddress ->
            // A receive target must be a valid recipient, not merely a correctly-sized key.
            // This also rejects zero/burn/program addresses if an SDK response is corrupted.
            runCatching { ValidatedWalletAddress.recipient(selectedNetwork.addressNetworkId, rawAddress).value }.getOrNull()
        }
    }
    val invalidWalletAddress = wallet != null && address == null
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember(address) { mutableStateOf(false) }
    var showWalletDetails by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }

    // These neutral colors and dimensions follow the supplied receive-page reference.
    BoxWithConstraints(Modifier.fillMaxSize().background(ReceiveColors.Background)) {
        val topSpace = (maxHeight * .10f).coerceAtLeast(rd(24f))
        val bottomSpace = (maxHeight - rd(656f) - topSpace).coerceAtLeast(rd(22f))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = rd(20f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReceivePageHeader(walletDisplayName, onBack, onDetails = { showWalletDetails = true })
            Spacer(Modifier.height(topSpace))
            if (address != null) {
                AddressPill(address, copied) {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                }
            } else {
                Box(Modifier.height(rd(42f)), contentAlignment = Alignment.Center) {
                    RefText("Receive crypto", 14f, ReceiveColors.Muted)
                }
            }
            Spacer(Modifier.height(rd(12f)))
            if (address != null) {
                WalletQrCard(address)
            } else {
                Box(
                    Modifier.size(rd(326f)).clip(RoundedCornerShape(rd(34f))).background(ReceiveColors.Surface)
                        .padding(rd(28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        if (invalidWalletAddress) "Reconnect your wallet to receive funds."
                        else "Your wallet address will appear here when connected.",
                        style = TextStyle(
                            color = ReceiveColors.Muted, fontSize = rs(16f),
                            fontFamily = LocalReferenceFont.current, textAlign = TextAlign.Center,
                            lineHeight = rs(22f),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(rd(14f)))
            // Show only chains with a real receive wallet. Each mark is drawn as a vector so the
            // compact, overlapping network stack remains sharp on high-density displays.
            Box(Modifier.width(rd(if (availableNetworks.isEmpty()) 0f else 42f + (availableNetworks.size - 1) * 28f))
                .height(rd(44f))) {
                availableNetworks.forEachIndexed { index, network ->
                    ReceiveNetworkChoice(
                        network = network,
                        active = network.id == selectedNetworkId,
                        modifier = Modifier.offset(x = rd(index * 28f))
                            .zIndex(if (network.id == selectedNetworkId) 100f else index.toFloat()),
                    ) { selectedNetworkId = network.id }
                }
            }
            RefText("Supported networks", 12f, ReceiveColors.Muted)
            Spacer(Modifier.height(rd(4f)))
            RefText(
                if (availableNetworks.isEmpty()) "Connect a wallet to receive crypto"
                else "${selectedNetwork.label} · ${selectedNetwork.assets}",
                12f, ReceiveColors.Muted,
            )
            Spacer(Modifier.height(bottomSpace))
            Box(
                Modifier.width(rd(150f)).height(rd(52f)).clip(CircleShape)
                    .background(if (address != null) Color.White else ReceiveColors.Surface)
                    .clickable(enabled = address != null, role = Role.Button) {
                        address?.let { value ->
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${selectedNetwork.label} address:\n$value")
                            }
                            context.startActivity(Intent.createChooser(share, "Share wallet address"))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                RefText("Share", 16f, if (address != null) Color.Black else ReceiveColors.Muted, FontWeight.Medium)
            }
            Spacer(Modifier.height(rd(32f)))
        }
    }

    if (showWalletDetails) {
        Dialog(onDismissRequest = { showWalletDetails = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(28f))).background(ReceiveColors.Surface)
                    .verticalScroll(rememberScrollState()).padding(rd(24f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RefText("Receive on", 20f, Color.White, FontWeight.Medium)
                Spacer(Modifier.height(rd(20f)))
                WalletSegmentedControl(availableNetworks, selectedNetwork, ReceiveNetwork::displayName) {
                    selectedNetworkId = it.id
                }
                if (networkWallets.size > 1) {
                    Spacer(Modifier.height(rd(20f)))
                    RefText("Choose wallet", 14f, ReceiveColors.Muted)
                    networkWallets.forEach { candidate ->
                        Spacer(Modifier.height(rd(10f)))
                        WalletAddressChoice(candidate, candidate == wallet) {
                            selectedWalletKey = candidate.stableWalletKey()
                        }
                    }
                }
                Spacer(Modifier.height(rd(22f)))
                BasicText(
                    address ?: "Connect your wallet to see its receive address.",
                    Modifier.fillMaxWidth().clickable(enabled = address != null, role = Role.Button) {
                        address?.let { clipboard.setText(AnnotatedString(it)); copied = true }
                    }.padding(vertical = rd(10f)),
                    style = TextStyle(
                        color = Color.White, fontSize = rs(14f), fontFamily = LocalReferenceFont.current,
                        textAlign = TextAlign.Center, lineHeight = rs(21f),
                    ),
                )
                if (address != null) {
                    RefText(if (copied) "Copied" else "Tap address to copy", 12f, ReceiveColors.Muted)
                }
                Spacer(Modifier.height(rd(12f)))
                BasicText(
                    selectedNetwork.instructions,
                    style = TextStyle(
                        color = ReceiveColors.Muted, fontSize = rs(13f),
                        fontFamily = LocalReferenceFont.current, textAlign = TextAlign.Center,
                        lineHeight = rs(19f),
                    ),
                )
                Spacer(Modifier.height(rd(24f)))
                WalletPrimaryButton("Done", true) { showWalletDetails = false }
            }
        }
    }
}

private object ReceiveColors {
    val Background = Color(0xFF101010)
    val Surface = Color(0xFF252525)
    val Muted = Color(0xFF9A9A9A)
}

@Composable
private fun ReceivePageHeader(title: String, onBack: () -> Unit, onDetails: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(rd(72f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.align(Alignment.CenterStart).size(rd(52f)).clip(CircleShape)
                .background(ReceiveColors.Surface).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { RefIcon("back", 23f, ReceiveColors.Muted) }
        RefText(title, 18f, Color.White, FontWeight.Medium,
            modifier = Modifier.padding(horizontal = rd(62f)))
        Box(
            Modifier.align(Alignment.CenterEnd).size(rd(52f)).clip(CircleShape)
                .background(ReceiveColors.Surface).clickable(role = Role.Button, onClick = onDetails)
                .semantics { contentDescription = "Receive network and wallet details" },
            contentAlignment = Alignment.Center,
        ) { RefIcon("scan", 23f, ReceiveColors.Muted) }
    }
}

@Composable
private fun ReceiveNetworkChoice(
    network: ReceiveNetwork,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(rd(42f)).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = network.label; selected = active },
        contentAlignment = Alignment.Center,
    ) {
        val isSolana = network.id == ReceiveNetworkId.SOLANA
        val isArbitrum = network.id == ReceiveNetworkId.ARBITRUM
        Box(
            Modifier.size(rd(34f)).clip(RoundedCornerShape(rd(9f)))
                .background(when {
                    isSolana -> Color.Black
                    isArbitrum -> Color(0xFF213147)
                    else -> Color(0xFF6477E8)
                })
                .border(rd(if (active) 2f else 1.5f),
                    if (active) Color.White else Color(0xFFC4C4C4), RoundedCornerShape(rd(9f))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(rd(22f))) {
                val w = size.width
                val h = size.height
                if (isArbitrum) {
                    // The three slanted bars identify Arbitrum at the same scale as the other networks.
                    listOf(
                        0.17f to Color.White,
                        0.40f to Color(0xFF28A0F0),
                        0.63f to Color(0xFF28A0F0),
                    ).forEach { (left, color) ->
                        drawPath(Path().apply {
                            moveTo(w * left, h * .75f)
                            lineTo(w * (left + .14f), h * .75f)
                            lineTo(w * (left + .37f), h * .22f)
                            lineTo(w * (left + .23f), h * .22f)
                            close()
                        }, color)
                    }
                } else if (!isSolana) {
                    // Ethereum's two facets, drawn independently to preserve the diamond shape.
                    drawPath(Path().apply {
                        moveTo(w * .5f, h * .02f); lineTo(w * .15f, h * .56f)
                        lineTo(w * .5f, h * .75f); close()
                    }, Color.White)
                    drawPath(Path().apply {
                        moveTo(w * .5f, h * .02f); lineTo(w * .85f, h * .56f)
                        lineTo(w * .5f, h * .75f); close()
                    }, Color.White.copy(alpha = .77f))
                    drawPath(Path().apply {
                        moveTo(w * .15f, h * .63f); lineTo(w * .5f, h * .98f)
                        lineTo(w * .5f, h * .82f); close()
                    }, Color.White.copy(alpha = .84f))
                    drawPath(Path().apply {
                        moveTo(w * .85f, h * .63f); lineTo(w * .5f, h * .98f)
                        lineTo(w * .5f, h * .82f); close()
                    }, Color.White)
                } else {
                    // White Solana mark on true black, matching the supplied artwork.
                    repeat(3) { index ->
                        val y = h * (.09f + index * .31f)
                        val reverse = index == 1
                        drawPath(Path().apply {
                            moveTo(w * if (reverse) .10f else .27f, y)
                            lineTo(w * if (reverse) .73f else .90f, y)
                            lineTo(w * if (reverse) .90f else .73f, y + h * .20f)
                            lineTo(w * if (reverse) .27f else .10f, y + h * .20f)
                            close()
                        }, Color.White)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WalletPageHeader(title: String, onBack: () -> Unit, backEnabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().height(rd(68f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(rd(44f)).clip(CircleShape).background(P.Card)
                .clickable(enabled = backEnabled, role = Role.Button, onClick = onBack)
                .semantics {
                    contentDescription = "Back"
                    if (!backEnabled) disabled()
                },
            contentAlignment = Alignment.Center,
        ) { RefIcon("back", 23f) }
        RefText(title, 21f, weight = FontWeight.Bold,
            modifier = Modifier.weight(1f).padding(start = rd(14f)))
        Box(Modifier.size(rd(44f)).clip(CircleShape).background(P.Card), contentAlignment = Alignment.Center) {
            RefIcon("scan", 23f)
        }
    }
}

@Composable
internal fun <T> WalletSegmentedControl(
    entries: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(48f)).clip(CircleShape).background(P.Card).padding(rd(4f)),
        horizontalArrangement = Arrangement.spacedBy(rd(4f)),
    ) {
        entries.forEach { entry ->
            val active = entry == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(CircleShape)
                    .background(if (active) P.Chip else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(entry) },
                contentAlignment = Alignment.Center,
            ) { RefText(label(entry), 15f, if (active) P.White else P.Muted, if (active) FontWeight.Bold else FontWeight.Normal) }
        }
    }
}

@Composable
internal fun AssetChip(asset: WalletAsset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(rd(42f)).clip(CircleShape).background(if (selected) P.Chip else P.Card)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = rd(13f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NetworkAssetDot(asset)
        Spacer(Modifier.width(rd(8f)))
        RefText(asset.symbol, 15f, if (selected) P.White else P.Muted,
            if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
internal fun NetworkAssetDot(asset: WalletAsset) {
    val color = when (asset.symbol) {
        "USDC" -> Color(0xFF2775CA)
        "ETH" -> Color(0xFF7B86F8)
        else -> Color(0xFF6EE7C4)
    }
    Box(Modifier.size(rd(24f)).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        RefText(asset.symbol.take(1), 11f, P.White, FontWeight.Bold)
    }
}

@Composable
private fun AddressPill(
    address: String,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Box(
        Modifier.height(rd(42f)).clip(CircleShape).clickable(role = Role.Button, onClick = onCopy)
            .semantics { contentDescription = if (copied) "Address copied" else "Copy wallet address $address" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).background(ReceiveColors.Surface)
                .padding(horizontal = rd(11f), vertical = rd(8f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefText(if (copied) "Copied" else "${address.take(6)}…${address.takeLast(5)}",
                13f, if (copied) Color.White else ReceiveColors.Muted, FontWeight.Medium)
            Spacer(Modifier.width(rd(7f)))
            RefIcon("copy", 15f, ReceiveColors.Muted)
        }
    }
}

@Composable
private fun WalletQrCard(address: String) {
    val qr = remember(address) { QrCodeMatrix.encodeAddress(address) }
    Box(
        Modifier.size(rd(326f)).clip(RoundedCornerShape(rd(34f))).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "QR code for wallet address $address" }) {
            val quiet = 4
            val dimension = qr.size + quiet * 2
            val cell = size.minDimension / dimension
            val drawnSize = cell * dimension
            val origin = Offset(
                (size.width - drawnSize) / 2f,
                (size.height - drawnSize) / 2f,
            )
            // Keep the four-module quiet zone. Rounded modules supply the reference's
            // dotted texture; the small central mark below uses the encoder's level-H ECC.
            for (y in 0 until qr.size) for (x in 0 until qr.size) {
                val finder = (x < 7 && y < 7) || (x >= qr.size - 7 && y < 7) ||
                    (x < 7 && y >= qr.size - 7)
                if (qr[x, y] && !finder) {
                    // Timing and alignment patterns retain square edges for scanner detection.
                    if (x == 6 || y == 6 || (x in 32..36 && y in 32..36)) {
                        drawRect(Color.Black, origin + Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell, cell))
                    } else {
                        drawCircle(Color.Black, cell * .47f,
                            origin + Offset((x + quiet + .5f) * cell, (y + quiet + .5f) * cell))
                    }
                }
            }
            for ((x, y) in listOf(0 to 0, qr.size - 7 to 0, 0 to qr.size - 7)) {
                val corner = origin + Offset((x + quiet) * cell, (y + quiet) * cell)
                drawRoundRect(Color.Black, corner, Size(cell * 7, cell * 7), CornerRadius(cell * 3.5f))
                drawRoundRect(Color.White, corner + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell * 2.5f))
                drawRoundRect(Color.Black, corner + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell * 1.5f))
            }
        }
        Box(
            Modifier.size(rd(34f)).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            BrandLogo(Modifier.size(rd(30f)), onLightBackground = true)
        }
    }
}

@Composable
internal fun WalletPrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(rd(54f)).clip(CircleShape)
            .background(if (enabled) P.White else P.Card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { RefText(label, 17f, if (enabled) P.Background else P.Muted, FontWeight.Bold) }
}

internal fun compactAddress(address: String): String =
    if (address.length <= 20) address else "${address.take(8)}…${address.takeLast(7)}"

internal val WalletNetwork.walletChain: WalletChain
    get() = when (id) {
        WalletNetworkId.ETHEREUM_MAINNET -> WalletChain.ETHEREUM
        WalletNetworkId.SOLANA_MAINNET -> WalletChain.SOLANA
    }
