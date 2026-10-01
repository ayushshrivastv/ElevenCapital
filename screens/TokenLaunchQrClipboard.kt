package com.elevencapital.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.elevencapital.app.R
import com.elevencapital.app.wallet.QrCodeMatrix
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copies the same scannable QR artwork shown on the launch confirmation screen. */
internal suspend fun copyTokenLaunchQrCode(context: Context, matrix: QrCodeMatrix) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "token_qr")
        check(directory.exists() || directory.mkdirs()) { "Unable to prepare the QR image." }
        val file = File.createTempFile("eleven-token-qr-", ".png", directory)
        val bitmap = renderTokenLaunchQrBitmap(context, matrix)
        try {
            file.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to save the QR image."
                }
            }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        } finally {
            bitmap.recycle()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.token-qr", file)
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Token QR code", uri))
}

internal fun renderTokenLaunchQrBitmap(context: Context, matrix: QrCodeMatrix): Bitmap {
    val side = 768
    val quiet = 4
    val cell = side.toFloat() / (matrix.size + quiet * 2)
    val logo = requireNotNull(ContextCompat.getDrawable(context, R.drawable.eleven_in_app_logo))
        .mutate().apply { setTint(Color.BLACK) }
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
        val finder = (x < 7 && y < 7) || (x >= matrix.size - 7 && y < 7) ||
            (x < 7 && y >= matrix.size - 7)
        if (matrix[x, y] && !finder) {
            val left = (x + quiet) * cell
            val top = (y + quiet) * cell
            if (x == 6 || y == 6 || (x in 32..36 && y in 32..36)) {
                canvas.drawRect(left, top, left + cell, top + cell, black)
            } else {
                canvas.drawCircle(left + cell * .5f, top + cell * .5f, cell * .47f, black)
            }
        }
    }
    for ((x, y) in listOf(0 to 0, matrix.size - 7 to 0, 0 to matrix.size - 7)) {
        val left = (x + quiet) * cell
        val top = (y + quiet) * cell
        canvas.drawRoundRect(left, top, left + 7 * cell, top + 7 * cell,
            3.5f * cell, 3.5f * cell, black)
        canvas.drawRoundRect(left + cell, top + cell, left + 6 * cell, top + 6 * cell,
            2.5f * cell, 2.5f * cell, white)
        canvas.drawRoundRect(left + 2 * cell, top + 2 * cell, left + 5 * cell, top + 5 * cell,
            1.5f * cell, 1.5f * cell, black)
    }
    val logoBackground = side * .165f
    val center = side / 2f
    canvas.drawRect(center - logoBackground / 2f, center - logoBackground / 2f,
        center + logoBackground / 2f, center + logoBackground / 2f, white)
    val logoSide = side * .124f
    logo.setBounds(
        (center - logoSide / 2f).toInt(), (center - logoSide / 2f).toInt(),
        (center + logoSide / 2f).toInt(), (center + logoSide / 2f).toInt(),
    )
    logo.draw(canvas)
    return bitmap
}
