package com.elevencapital.app.screens

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exports the same vector-drawn result at 2880 × 4200, four times the design artboard. */
internal suspend fun savePredictionResult(context: Context, result: PredictionResultData) {
    withContext(Dispatchers.IO) {
        val fileName = resultFileName(result)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Eleven")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
                "Unable to create the result image."
            }
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { writeResultPng(context, result, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1) {
                    "Unable to finish saving the result image."
                }
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
        } else {
            val pictures = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
            val directory = File(pictures, "Eleven")
            check(directory.exists() || directory.mkdirs()) { "Unable to create the Pictures folder." }
            File(directory, fileName).outputStream().use { writeResultPng(context, result, it) }
        }
    }
}

internal suspend fun sharePredictionResultOnX(context: Context, result: PredictionResultData) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "prediction_results")
        check(directory.exists() || directory.mkdirs()) { "Unable to prepare the result image." }
        val file = File.createTempFile("eleven-result-", ".png", directory)
        try {
            file.outputStream().use { writeResultPng(context, result, it) }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
        FileProvider.getUriForFile(context, "${context.packageName}.prediction-results", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "Eleven result", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent(send).setPackage("com.twitter.android"))
    } catch (_: Exception) {
        context.startActivity(Intent.createChooser(send, "Share on X"))
    }
}

private fun writeResultPng(context: Context, result: PredictionResultData, stream: OutputStream) {
    val bitmap = renderPredictionResultBitmap(context, result)
    try {
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
            "Unable to encode the result image."
        }
    } finally {
        bitmap.recycle()
    }
}

private fun resultFileName(result: PredictionResultData): String {
    val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(
        Date(result.finishedAtMillis ?: System.currentTimeMillis()))
    val asset = result.marketSymbol.filter { it.isLetterOrDigit() }.ifEmpty { "Market" }
    return "Eleven-$asset-$time.png"
}
