package com.elevencapital.app.tokenlaunch

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

const val MAX_TOKEN_ARTWORK_BYTES = 2 * 1024 * 1024
private const val TOKEN_ARTWORK_EDGE = 1_024
private const val MIN_TOKEN_ARTWORK_EDGE = 256

internal data class EncodedTokenArtwork(
    val base64: String,
    val mediaType: String = "image/jpeg",
) {
    init {
        require(mediaType == "image/jpeg")
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
        require(bytes != null && bytes.size in 4..MAX_TOKEN_ARTWORK_BYTES)
        require(bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        require(Base64.getEncoder().encodeToString(bytes) == base64)
    }
}

internal data class TokenArtworkFit(val width: Int, val height: Int, val canvasEdge: Int)

internal fun fitTokenArtwork(width: Int, height: Int): TokenArtworkFit {
    require(width > 0 && height > 0)
    val longestEdge = maxOf(width, height)
    val canvasEdge = minOf(longestEdge, TOKEN_ARTWORK_EDGE)
    fun scaled(edge: Int) = ((edge.toLong() * canvasEdge + longestEdge / 2) / longestEdge)
        .toInt().coerceAtLeast(1)
    return TokenArtworkFit(scaled(width), scaled(height), canvasEdge)
}

internal fun interface TokenArtworkEncoder {
    suspend fun encode(imageUri: String): EncodedTokenArtwork
}

internal class AndroidTokenArtworkEncoder(
    private val resolver: ContentResolver,
) : TokenArtworkEncoder {
    override suspend fun encode(imageUri: String): EncodedTokenArtwork = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(imageUri)
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IllegalArgumentException()
            val source = ImageDecoder.createSource(resolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                if (width <= 0 || height <= 0 || width > 50_000 || height > 50_000 ||
                    width.toLong() * height.toLong() > 400_000_000L ||
                    !info.mimeType.startsWith("image/")) throw IllegalArgumentException()
                val fitted = fitTokenArtwork(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                decoder.setTargetSize(fitted.width, fitted.height)
            }
            currentCoroutineContext().ensureActive()
            // Preserve the selected photo's proportions.  A token image need not be
            // square; the UI scales it into the square artwork frame without cropping.
            val flattened = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(flattened).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, 0f, 0f, null)
            }
            if (decoded !== flattened) decoded.recycle()
            try {
                EncodedTokenArtwork(Base64.getEncoder().encodeToString(compressJpeg(flattened)))
            } finally {
                flattened.recycle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw invalidTokenArtwork()
        }
    }

    private suspend fun compressJpeg(source: Bitmap): ByteArray {
        var bitmap = source
        var ownsBitmap = false
        try {
            while (true) {
                for (quality in listOf(92, 84, 76, 68, 60, 52)) {
                    currentCoroutineContext().ensureActive()
                    val output = ByteArrayOutputStream(MAX_TOKEN_ARTWORK_BYTES)
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IllegalStateException()
                    val bytes = output.toByteArray()
                    if (bytes.size <= MAX_TOKEN_ARTWORK_BYTES) return bytes
                }
                val longestEdge = maxOf(bitmap.width, bitmap.height)
                val nextEdge = (longestEdge * 3 / 4).coerceAtLeast(MIN_TOKEN_ARTWORK_EDGE)
                if (nextEdge >= longestEdge) throw IllegalArgumentException()
                fun scaled(edge: Int) = ((edge.toLong() * nextEdge + longestEdge / 2) / longestEdge)
                    .toInt().coerceAtLeast(1)
                val resized = Bitmap.createScaledBitmap(
                    bitmap, scaled(bitmap.width), scaled(bitmap.height), true,
                )
                if (ownsBitmap) bitmap.recycle()
                bitmap = resized
                ownsBitmap = true
            }
        } finally {
            if (ownsBitmap) bitmap.recycle()
        }
    }
}

private fun invalidTokenArtwork() = TokenLaunchException(
    TokenLaunchFailureCode.INVALID_REQUEST,
    "That image couldn't be prepared. Choose another image and try again.",
    retryable = true,
)
