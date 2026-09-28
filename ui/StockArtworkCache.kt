package com.elevencapital.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.elevencapital.app.R
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Only the twenty unchanged bundled company originals are retained for the process. */
internal object StockArtworkCache {
    private val companyResources = mapOf(
        "MSFT" to R.drawable.stock_robinhood_company_msft,
        "AVGO" to R.drawable.stock_robinhood_company_avgo,
        "TSLA" to R.drawable.stock_robinhood_company_tsla,
        "GOOGL" to R.drawable.stock_robinhood_company_googl,
        "META" to R.drawable.stock_robinhood_company_meta,
        "NFLX" to R.drawable.stock_robinhood_company_nflx,
        "AAPL" to R.drawable.stock_robinhood_company_aapl,
        "NVDA" to R.drawable.stock_robinhood_company_nvda,
        "AMZN" to R.drawable.stock_robinhood_company_amzn,
        "LLY" to R.drawable.stock_robinhood_company_lly,
        "COIN" to R.drawable.stock_robinhood_company_coin,
        "AMD" to R.drawable.stock_robinhood_company_amd,
        "PLTR" to R.drawable.stock_robinhood_company_pltr,
        "ORCL" to R.drawable.stock_robinhood_company_orcl,
        "ADBE" to R.drawable.stock_robinhood_company_adbe,
        "CRM" to R.drawable.stock_robinhood_company_crm,
        "INTC" to R.drawable.stock_robinhood_company_intc,
        "COST" to R.drawable.stock_robinhood_company_cost,
        "TSM" to R.drawable.stock_robinhood_company_tsm,
        "CSCO" to R.drawable.stock_robinhood_company_csco,
    )
    private const val MAX_SINGLE_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024
    private val started = AtomicBoolean(false)
    private val bitmaps = ConcurrentHashMap<Int, ImageBitmap>()

    fun companyResource(symbol: String): Int = companyResources[symbol.uppercase(Locale.ROOT)] ?: 0

    fun bitmap(resource: Int): ImageBitmap? = bitmaps[resource]

    /** One background worker, with application resources only; no Activity is captured. */
    fun warm(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val resources = context.applicationContext.resources
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var retainedBytes = 0L
            for (resource in companyResources.values) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                    BitmapFactory.decodeResource(resources, resource, bounds)
                    // Allow even an eight-byte pixel format before allocating. Do not sample,
                    // scale, recolor or modify the original company artwork.
                    val maximumBytes = bounds.outWidth.toLong() * bounds.outHeight.toLong() * 8L
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        maximumBytes > MAX_SINGLE_BYTES || retainedBytes + maximumBytes > MAX_TOTAL_BYTES) continue
                    val decoded = BitmapFactory.decodeResource(resources, resource,
                        BitmapFactory.Options().apply { inScaled = false }) ?: continue
                    val bytes = decoded.allocationByteCount.toLong()
                    if (bytes > MAX_SINGLE_BYTES || retainedBytes + bytes > MAX_TOTAL_BYTES) {
                        decoded.recycle()
                        continue
                    }
                    decoded.prepareToDraw()
                    bitmaps[resource] = decoded.asImageBitmap()
                    retainedBytes += bytes
                } catch (_: RuntimeException) {
                    // The existing resource painter remains available when warming fails.
                }
            }
        }, "stock-artwork-warm").apply { isDaemon = true }.start()
    }
}
