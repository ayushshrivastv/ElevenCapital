package com.elevencapital.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing

/** Frame-measured durations; easing approximation explicitly accepted by the user. */
object ReferenceMotion {
    const val RouteMillis = 300
    const val SectionMillis = 240
    const val ChartMillis = 350
    const val ChartDelayMillis = 500
    val Easing = FastOutSlowInEasing
}
