package com.elevencapital.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.elevencapital.app.R

/** In-app artwork for the intro, session shield, and phone-unlock screen. */
@Composable
fun BrandArtwork(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.eleven_in_app_logo),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(Color.White),
    )
}

/** Hold the in-app mark while the first app frame is prepared for the credential prompt. */
@Composable
fun BrandIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(Color.Black).semantics {
            contentDescription = "Opening Eleven Capital"
        },
        contentAlignment = Alignment.Center,
    ) {
        BrandArtwork(Modifier.size(176.dp))
    }
    LaunchedEffect(Unit) {
        // MainActivity composes its locked surface behind this still image after two frames.
        // Removing it on the following frame keeps the artwork steady as the PIN opens.
        repeat(3) { withFrameNanos { } }
        onFinished()
    }
}
