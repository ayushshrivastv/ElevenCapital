package com.elevencapital.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.elevencapital.app.R

/** Approved Roboto, bundled once so text does not depend on the manufacturer's font. */
val ElevenRoboto: FontFamily = FontFamily(
    robotoFace(FontWeight.Normal),
    robotoFace(FontWeight.Medium),
    robotoFace(FontWeight.SemiBold),
    robotoFace(FontWeight.Bold),
)

@OptIn(ExperimentalTextApi::class)
private fun robotoFace(weight: FontWeight): Font = Font(
    resId = R.font.roboto,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.width(100f),
    ),
)
