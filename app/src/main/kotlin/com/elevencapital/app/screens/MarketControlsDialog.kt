package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import com.elevencapital.app.ui.*

/** Search stays available in the market controls after the stock header is simplified. */
@Composable
fun MarketControlsDialog(
    query: MarketQuery,
    onChange: (MarketQuery) -> Unit,
    watchlistOnly: Boolean,
    onWatchlistOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = rd(610f))
            .clip(RoundedCornerShape(rd(22f))).background(P.Card).padding(rd(20f))) {
            RefText("Search & filters", 20f, weight = FontWeight.Bold)
            Spacer(Modifier.height(rd(18f)))
            BasicTextField(
                value = query.text,
                onValueChange = { onChange(query.copy(text = it)) },
                singleLine = true,
                textStyle = TextStyle(color = P.White, fontSize = rs(17f), fontFamily = LocalReferenceFont.current),
                cursorBrush = SolidColor(P.Lime),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Search by stock name or symbol" }
                    .clip(CircleShape).background(P.Chip).padding(horizontal = rd(16f), vertical = rd(14f)),
                decorationBox = { field ->
                    Box { if (query.text.isEmpty()) RefText("Name or symbol", 17f, P.Muted); field() }
                },
            )
            Spacer(Modifier.height(rd(12f)))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                RefText("Stocks", 14f, P.Muted)
                MarketControlOption("All stocks", !watchlistOnly) { onWatchlistOnlyChange(false) }
                MarketControlOption("Watchlist", watchlistOnly) { onWatchlistOnlyChange(true) }
                Spacer(Modifier.height(rd(12f)))
                RefText("Source", 14f, P.Muted)
                MarketSource.entries.forEach { source ->
                    MarketControlOption(source.label, query.source == source) { onChange(query.copy(source = source)) }
                }
                Spacer(Modifier.height(rd(12f)))
                RefText("Price change", 14f, P.Muted)
                MarketMovement.entries.forEach { movement ->
                    MarketControlOption(movement.label, query.movement == movement) { onChange(query.copy(movement = movement)) }
                }
                Spacer(Modifier.height(rd(12f)))
                RefText("Sort", 14f, P.Muted)
                MarketSort.entries.forEach { sort ->
                    MarketControlOption(sort.label, query.sort == sort) { onChange(query.copy(sort = sort)) }
                }
            }
            Spacer(Modifier.height(rd(18f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(10f))) {
                Box(Modifier.weight(1f).height(rd(44f)).clip(CircleShape).background(P.Chip)
                    .clickable(role = Role.Button) {
                        onChange(MarketQuery())
                        onWatchlistOnlyChange(false)
                    }, contentAlignment = Alignment.Center) { RefText("Reset", 16f) }
                Box(Modifier.weight(1f).height(rd(44f)).clip(CircleShape).background(P.Lime)
                    .clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
                    RefText("Done", 16f, P.Background)
                }
            }
        }
    }
}

@Composable
private fun MarketControlOption(label: String, active: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(rd(42f)).clip(RoundedCornerShape(rd(10f)))
        .background(if (active) P.Chip else P.Card).semantics { selected = active }
        .clickable(role = Role.RadioButton, onClick = onSelect).padding(horizontal = rd(10f)),
        verticalAlignment = Alignment.CenterVertically) {
        RefText(label, 16f, if (active) P.Lime else P.White, modifier = Modifier.weight(1f))
        if (active) Box(Modifier.size(rd(6f)).background(P.Lime, CircleShape))
    }
}
