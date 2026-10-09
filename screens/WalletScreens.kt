package com.elevencapital.app.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import com.elevencapital.app.wallet.QrCodeMatrix
import com.elevencapital.app.wallet.ValidatedWalletAddress
import com.elevencapital.app.wallet.WalletAsset
import com.elevencapital.app.wallet.WalletNetwork
import com.elevencapital.app.wallet.WalletNetworkId
import kotlinx.coroutines.delay

enum class WalletPage { RECEIVE, SEND }

@Composable
fun ReceiveWalletScreen(
    wallets: List<UserWallet>,
    onBack: () -> Unit,
    walletDisplayName: String = "Main wallet",
) {
    val wallet = remember(wallets) { wallets.firstOrNull { it.chain == WalletChain.SOLANA } }
    val address = remember(wallet?.address) {
        wallet?.address?.let { raw ->
            runCatching { ValidatedWalletAddress.recipient(WalletNetworkId.SOLANA_MAINNET, raw).value }
                .getOrNull()
        }
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(address) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val qrSize = (maxWidth - rd(40f)).coerceAtMost(rd(326f))
        val bodyHeight = rd(42f) + rd(16f) + qrSize
        val topSpace = ((maxHeight - bodyHeight) / 2f - rd(72f)).coerceAtLeast(rd(24f))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = rd(20f)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReceivePageHeader(walletDisplayName, onBack)
            Spacer(Modifier.height(topSpace))
            if (address != null) {
                AddressPill(address, copied) {
                    clipboard.setText(AnnotatedString(address))
                    copied = true
                }
                Spacer(Modifier.height(rd(16f)))
                WalletQrCard(address, qrSize)
            } else {
                Box(Modifier.size(qrSize).clip(RoundedCornerShape(rd(28f)))
                    .background(ReceiveColors.Surface), contentAlignment = Alignment.Center) {
                    RefText("Connect your wallet to see its receive address", 14f, ReceiveColors.Muted)
                }
            }
            Spacer(Modifier.height(rd(32f)))
        }
    }
}

private object ReceiveColors {
    val Surface = Color(0xFF252525)
    val Muted = Color(0xFF9A9A9A)
}

@Composable
private fun ReceivePageHeader(title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(rd(72f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.align(Alignment.CenterStart).size(rd(52f)).clip(CircleShape)
                .background(ReceiveColors.Surface).clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { RefIcon("back", 23f, ReceiveColors.Muted) }
        RefText(title, 18f, Color.White, FontWeight.Medium,
            modifier = Modifier.padding(horizontal = rd(62f)))
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
private fun WalletQrCard(address: String, qrSize: Dp) {
    val qr = remember(address) { QrCodeMatrix.encodeAddress(address) }
    Box(
        Modifier.size(qrSize).clip(RoundedCornerShape(rd(34f))).background(Color.White),
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
            // Keep the four-module quiet zone around the address.
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
        Box(Modifier.size(rd(52f)).background(Color.White), contentAlignment = Alignment.Center) {
            BrandLogo(Modifier.size(rd(40f)), onLightBackground = true)
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
    get() = WalletChain.SOLANA
