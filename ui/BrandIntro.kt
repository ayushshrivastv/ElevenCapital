package com.elevencapital.app.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val INTRO_MS = 1_450
private const val MORPH_START_MS = 360f
private const val WAVE_MS = 220f
private const val TILE_MS = 630f
private const val LOGO_FADE_START_MS = 1_090f
private const val LOGO_FADE_MS = 200f
private const val LOGO_GRID = 40

// Sampled from the supplied dark Eleven mark. The final artwork remains the native vector.
private val logoRows = longArrayOf(
    0x00007e0000, 0x0001fe0000, 0x0003e00000, 0x0007800000, 0x000e07c000,
    0x001c3ff000, 0x003c7ff800, 0x0078fcfc00, 0x0070f01e00, 0x00f1c00f00,
    0x00e1c00f00, 0x01e3800780, 0x01c3000380, 0x03c70003c0, 0x07860001c0,
    0x07800001e0, 0x0f000000e0, 0x0e000000f0, 0x1e00000070, 0x1c00000038,
    0x3c0000003c, 0x380000001c, 0x380000051c, 0x380000030e, 0x380000038e,
    0x38000001c6, 0x3c000001c7, 0x1c000000c7, 0x1e000000e7, 0x0f000000e7,
    0x87fffff8e7, 0x43fffff9e6, 0x61fffff1c6, 0x701fffc3c4, 0x3c0000038c,
    0x1f00000f88, 0x0fffffff00, 0x07fffffe00, 0x01fffff800, 0x00000fc000,
)

private data class Tile(
    val sourceX: Int,
    val sourceY: Int,
    val width: Int,
    val height: Int,
    val destinationX: Float,
    val destinationY: Float,
    val diagonal: Float,
)

private class IntroArtwork(val word: Bitmap, val tiles: List<Tile>)

private fun prepareArtwork(screenWidth: Int, screenHeight: Int, density: Float): IntroArtwork {
    val text = "Eleven Capital"
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = min(30f * density, min(screenWidth * .078f, screenHeight * .13f))
    }
    val width = ceil(paint.measureText(text) + 12f * density).toInt().coerceAtLeast(1)
    val height = ceil(paint.textSize * 1.55f).toInt().coerceAtLeast(1)
    val word = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    AndroidCanvas(word).drawText(text, (width - paint.measureText(text)) / 2f,
        height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)

    val step = max(4f * density, paint.textSize / 10f).roundToInt().coerceAtLeast(1)
    val sources = mutableListOf<Pair<Rect, Float>>()
    for (y in 0 until height step step) for (x in 0 until width step step) {
        val right = min(x + step, width)
        val bottom = min(y + step, height)
        var occupied = false
        for (py in y until bottom) {
            for (px in x until right) {
                if (word.getPixel(px, py) ushr 24 > 24) {
                    occupied = true
                    break
                }
            }
            if (occupied) break
        }
        if (occupied) sources += Rect(x, y, right, bottom) to (x.toFloat() / width + y.toFloat() / height)
    }
    sources.sortBy { it.second }

    val targets = buildList {
        for (y in 0 until LOGO_GRID) for (x in 0 until LOGO_GRID) {
            if ((logoRows[y] and (1L shl (LOGO_GRID - 1 - x))) != 0L) {
                add(Triple(x, y, x.toFloat() / LOGO_GRID + y.toFloat() / LOGO_GRID))
            }
        }
    }.sortedBy { it.third }
    val lastSource = sources.lastIndex.coerceAtLeast(1)
    val lastTarget = targets.lastIndex.coerceAtLeast(0)
    val tiles = sources.mapIndexed { index, (rect, diagonal) ->
        val target = targets[(index * lastTarget / lastSource).coerceIn(0, lastTarget)]
        Tile(rect.left, rect.top, rect.width(), rect.height(), target.first.toFloat(),
            target.second.toFloat(), diagonal)
    }
    return IntroArtwork(word, tiles)
}

private fun clamp(value: Float) = value.coerceIn(0f, 1f)
private fun smooth(value: Float): Float {
    val t = clamp(value)
    return t * t * (3f - 2f * t)
}

/** The supplied wordmark-to-pixels transition, ending in the real Eleven vector mark. */
@Composable
fun BrandIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val progress = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(animationsEnabled) {
        if (animationsEnabled) progress.animateTo(1f, tween(INTRO_MS, easing = LinearEasing))
        latestOnFinished()
    }

    val elapsed = progress.value * INTRO_MS
    val logoAlpha = smooth((elapsed - LOGO_FADE_START_MS) / LOGO_FADE_MS)
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
        .semantics { contentDescription = "Opening Eleven Capital" },
        contentAlignment = Alignment.Center) {
        val screenWidth = constraints.maxWidth.coerceAtLeast(1)
        val screenHeight = constraints.maxHeight.coerceAtLeast(1)
        val artwork = remember(screenWidth, screenHeight, density) {
            prepareArtwork(screenWidth, screenHeight, density)
        }
        val markSize = min(176f * density, screenWidth * .55f)

        Canvas(Modifier.fillMaxSize()) {
            val left = (size.width - artwork.word.width) / 2f
            val top = (size.height - artwork.word.height) / 2f
            if (elapsed <= MORPH_START_MS) {
                drawContext.canvas.nativeCanvas.drawBitmap(artwork.word, left, top,
                    AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG).apply {
                        alpha = 255
                    })
            } else if (elapsed < MORPH_START_MS + WAVE_MS + TILE_MS) {
                val destinationLeft = (size.width - markSize) / 2f + markSize * 8f / 108f
                val destinationTop = (size.height - markSize) / 2f + markSize * 10f / 108f
                val targetUnitX = markSize * 92f / 108f / LOGO_GRID
                val targetUnitY = markSize * 88f / 108f / LOGO_GRID
                val minDiagonal = artwork.tiles.firstOrNull()?.diagonal ?: 0f
                val diagonalSpan = (artwork.tiles.lastOrNull()?.diagonal ?: 1f) - minDiagonal
                val pixelPaint = AndroidPaint().apply {
                    color = AndroidColor.WHITE
                    isFilterBitmap = false
                }
                for (tile in artwork.tiles) {
                    val delay = (tile.diagonal - minDiagonal) / diagonalSpan.coerceAtLeast(.001f) * WAVE_MS
                    val phase = clamp((elapsed - MORPH_START_MS - delay) / TILE_MS)
                    val block = smooth(phase / .22f)
                    val movement = smooth((phase - .22f) / .78f)
                    val x = left + tile.sourceX +
                        (destinationLeft + tile.destinationX * targetUnitX - left - tile.sourceX) * movement
                    val y = top + tile.sourceY +
                        (destinationTop + tile.destinationY * targetUnitY - top - tile.sourceY) * movement
                    val w = tile.width + (targetUnitX - tile.width) * movement
                    val h = tile.height + (targetUnitY - tile.height) * movement
                    val gap = kotlin.math.sin(Math.PI.toFloat() * movement) * .42f
                    val insetX = w * gap / 2f
                    val insetY = h * gap / 2f
                    if (block < 1f) {
                        pixelPaint.alpha = ((1f - block) * (1f - logoAlpha) * 255).roundToInt()
                        drawContext.canvas.nativeCanvas.drawBitmap(artwork.word,
                            Rect(tile.sourceX, tile.sourceY, tile.sourceX + tile.width,
                                tile.sourceY + tile.height), RectF(x, y, x + w, y + h), pixelPaint)
                    }
                    if (block > 0f) {
                        pixelPaint.alpha = (block * (1f - logoAlpha) * 255).roundToInt()
                        drawContext.canvas.nativeCanvas.drawRect(x + insetX, y + insetY,
                            x + w - insetX, y + h - insetY, pixelPaint)
                    }
                }
            }
        }
        BrandLogo(Modifier.size((markSize / density).dp), alpha = logoAlpha)
    }
}
