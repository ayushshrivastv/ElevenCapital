package com.elevencapital.app.screens

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.elevencapital.app.R

@Composable
internal fun SpxtrArtwork(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.token_spxtr_christmas),
        contentDescription = "$TOKEN_LAUNCH_UI_PREVIEW_NAME artwork",
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
