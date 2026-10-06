package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import java.util.Locale

/** The inset label/value treatment used for prediction amounts and summaries. */
@Composable
internal fun PredictionMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PredictionPalette.Lime,
    valueSize: Float = 25f,
    minHeight: Float = 77f,
    fill: Color = Color(0xFF262626),
    valueMaxLines: Int = 1,
    supportingText: String? = null,
) {
    val shape = RoundedCornerShape(rd(13f))
    Column(modifier.heightIn(min = rd(minHeight)).clip(shape).background(fill)
        .padding(start = rd(15f), end = rd(14f), top = rd(11f), bottom = rd(10f)),
        verticalArrangement = Arrangement.SpaceBetween) {
        Column {
            RefText(label.uppercase(Locale.ROOT), 11f, PredictionPalette.Muted,
                modifier = Modifier.fillMaxWidth())
            supportingText?.let {
                Spacer(Modifier.height(rd(2f)))
                RefText(it, 10f, PredictionPalette.Muted, maxLines = 1)
            }
        }
        RefText(value, valueSize, valueColor, FontWeight.Bold,
            modifier = Modifier.align(Alignment.End), maxLines = valueMaxLines)
    }
}
