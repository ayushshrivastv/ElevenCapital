package com.elevencapital.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.elevencapital.app.R

const val PROFILE_AVATAR_COUNT = 5

/** One account's orb, reused in the Home greeting and Account dock. */
@Composable
fun ProfileAvatar(variant: Int, modifier: Modifier = Modifier) {
    val image = when (variant) {
        1 -> R.drawable.profile_orb_1
        2 -> R.drawable.profile_orb_2
        3 -> R.drawable.profile_orb_3
        4 -> R.drawable.profile_orb_4
        else -> R.drawable.profile_orb_0
    }
    val zoom = when (variant) {
        2 -> 1.09f
        3 -> 1.10f
        else -> 1.18f
    }
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        // The provided original has an inset black canvas. The same crop fills every
        // circular avatar without altering the source image or clipping its visible rim.
        Image(
            painter = painterResource(image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(zoom),
            contentScale = ContentScale.Crop,
        )
    }
}
