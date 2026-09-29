package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.elevencapital.app.ui.rd

// Static brushes add a faint highlight without a backdrop blur or animation.
private val stockGlassFill = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .055f),
    .52f to Color.White.copy(alpha = .025f),
    1f to Color.White.copy(alpha = .008f),
)
private val stockGlassEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .14f),
    1f to Color.White.copy(alpha = .035f),
)

@Composable
internal fun Modifier.stockGlass(
    shape: Shape = RoundedCornerShape(rd(16f)),
    baseColor: Color = Color.Transparent,
): Modifier = clip(shape)
    .background(baseColor)
    .background(stockGlassFill)
    .border(rd(.7f), stockGlassEdge, shape)
