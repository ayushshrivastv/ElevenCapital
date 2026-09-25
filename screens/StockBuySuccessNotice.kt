package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.StockIcon
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.core.stock.Stock

/** Transient app-level result; tapping it opens the verified Solscan transaction when available. */
@Composable
internal fun StockBuySuccessNotice(
    stock: Stock,
    modifier: Modifier = Modifier,
    solscanUrl: String? = null,
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier.clip(CircleShape).background(WalletStyle.Panel)
            .clickable(enabled = solscanUrl != null, role = Role.Button) {
                solscanUrl?.let(uriHandler::openUri)
            }
            .semantics {
                contentDescription = "${stock.name} buy successful" +
                    if (solscanUrl != null) ". Open transaction on Solscan" else ""
            }
            .padding(start = rd(5f), end = rd(19f), top = rd(5f), bottom = rd(5f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rd(11f)),
    ) {
        Box(
            Modifier.size(rd(43f)).clip(CircleShape).background(WalletStyle.Circle),
            contentAlignment = Alignment.Center,
        ) { StockIcon(stock, 34f) }
        RefText("Buy Successful", 14f, WalletStyle.White, FontWeight.SemiBold)
    }
}
