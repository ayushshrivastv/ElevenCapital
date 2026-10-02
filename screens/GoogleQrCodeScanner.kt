package com.elevencapital.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

internal sealed interface QrCodeScanResult {
    data class Scanned(val rawValue: String) : QrCodeScanResult
    data object Canceled : QrCodeScanResult
    data class Failed(val cause: Exception) : QrCodeScanResult
}

/**
 * Returns a launcher for Google Play services' permissionless QR scanner UI.
 * Cancellation is kept separate from failure so backing out never surfaces as an error.
 */
@Composable
internal fun rememberGoogleQrCodeScanner(
    onResult: (QrCodeScanResult) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnResult = rememberUpdatedState(onResult)
    val scanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }

    return remember(scanner) {
        {
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue?.trim().orEmpty()
                    currentOnResult.value(
                        if (rawValue.isNotEmpty()) {
                            QrCodeScanResult.Scanned(rawValue)
                        } else {
                            QrCodeScanResult.Failed(
                                IllegalStateException("Scanned QR code did not contain text."),
                            )
                        },
                    )
                }
                .addOnCanceledListener {
                    currentOnResult.value(QrCodeScanResult.Canceled)
                }
                .addOnFailureListener { error ->
                    currentOnResult.value(QrCodeScanResult.Failed(error))
                }
        }
    }
}
