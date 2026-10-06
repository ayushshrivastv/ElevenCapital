package com.elevencapital.app.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.elevencapital.app.R
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/** Settled market values and observed prices are supplied by the round model. */
internal data class PredictionResultData(
    val direction: PredictionDirection,
    val stake: Int,
    val stakeUsd: BigDecimal? = null,
    val anchor: Double,
    val finalPrice: Double,
    val band: Double,
    val won: Boolean,
    val finishedAtMillis: Long? = null,
    val priceHistory: List<PredictionPricePoint> = emptyList(),
    val payoutUsd: Double? = null,
    val marketSymbol: String = "ETH",
)

private const val CARD_WIDTH = 720f
private const val CARD_HEIGHT = 1050f
internal const val PREDICTION_RESULT_ASPECT = CARD_WIDTH / CARD_HEIGHT
private val ink = AndroidColor.rgb(25, 18, 15)
private val mutedInk = AndroidColor.rgb(76, 34, 19)
private val hairline = AndroidColor.argb(85, 43, 24, 15)
private val white = AndroidColor.rgb(255, 249, 242)
private val marketSeconds = PredictionPreviewRound.DURATION.toFloat()
private data class BarcodePattern(val sourceWidth: Int, val bars: List<IntRange>)

/** The on-screen design is drawn directly by Compose; the bitmap is only for export. */
@Composable
internal fun PredictionResultCard(result: PredictionResultData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mark = remember(context) { elevenResultMark(context) }
    val barcode = remember(context) { resultBarcodePattern(context) }
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            renderPredictionResult(canvas.nativeCanvas, size.width, size.height, result, mark, barcode)
        }
    }
}

/** Caller owns the bitmap and may recycle it after encoding. */
internal fun renderPredictionResultBitmap(
    context: Context,
    result: PredictionResultData,
    width: Int = 2880,
    height: Int = 4200,
): Bitmap {
    require(width in 1..8192 && height in 1..8192) { "Result image dimensions are out of range." }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    renderPredictionResult(AndroidCanvas(bitmap), width.toFloat(), height.toFloat(), result,
        elevenResultMark(context), resultBarcodePattern(context))
    return bitmap
}

private fun elevenResultMark(context: Context): Drawable? =
    context.getDrawable(R.drawable.eleven_in_app_logo)?.mutate()?.apply {
        setTint(ink)
    }

private fun renderPredictionResult(
    canvas: AndroidCanvas,
    width: Float,
    height: Float,
    result: PredictionResultData,
    brandMark: Drawable?,
    barcode: BarcodePattern,
) {
    if (width <= 0f || height <= 0f) return
    val saved = canvas.save()
    try {
        canvas.scale(width / CARD_WIDTH, height / CARD_HEIGHT)
        val outline = Path().apply {
            addRoundRect(RectF(0f, 0f, CARD_WIDTH, CARD_HEIGHT), 54f, 54f, Path.Direction.CW)
        }
        canvas.clipPath(outline)
        drawCardBackground(canvas)
        drawPaperGrain(canvas)
        drawHero(canvas, result, brandMark)
        drawDetails(canvas, result, barcode)
    } finally {
        canvas.restoreToCount(saved)
    }
}

private fun drawCardBackground(canvas: AndroidCanvas) {
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, CARD_HEIGHT,
            intArrayOf(AndroidColor.rgb(255, 66, 3), AndroidColor.rgb(255, 75, 8),
                AndroidColor.rgb(255, 105, 28)),
            floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, CARD_WIDTH, CARD_HEIGHT, background)
}

/** The supplied linear graphic determines bar widths; its white pixels never cover the card. */
private fun resultBarcodePattern(context: Context): BarcodePattern {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.prediction_result_barcode)
        ?: return BarcodePattern(0, emptyList())
    return try {
        val row = bitmap.height / 2
        val bars = buildList {
            var left = 0
            while (left < bitmap.width) {
                val pixel = bitmap.getPixel(left, row)
                val dark = AndroidColor.red(pixel) + AndroidColor.green(pixel) + AndroidColor.blue(pixel) < 420
                if (!dark) { left++; continue }
                var right = left + 1
                while (right < bitmap.width) {
                    val next = bitmap.getPixel(right, row)
                    if (AndroidColor.red(next) + AndroidColor.green(next) + AndroidColor.blue(next) >= 420) break
                    right++
                }
                add(left until right)
                left = right
            }
        }
        BarcodePattern(bitmap.width, bars)
    } finally {
        bitmap.recycle()
    }
}

private fun drawPaperGrain(canvas: AndroidCanvas) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(16, 61, 20, 10)
        strokeWidth = 1f
    }
    for (index in 0..18) {
        val start = index * 58f - 360f
        canvas.drawLine(start, 0f, start + 600f, CARD_HEIGHT, paint)
    }
}

private fun drawHero(canvas: AndroidCanvas, result: PredictionResultData, brandMark: Drawable?) {
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(370f, 320f, 430f,
            intArrayOf(withAlpha(white, 44), withAlpha(white, 8), AndroidColor.TRANSPARENT),
            floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawCircle(370f, 320f, 430f, glow)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(ink, 22)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    for (radius in listOf(190f, 280f, 370f)) {
        canvas.drawOval(RectF(360f - radius, 304f - radius * .74f,
            360f + radius, 304f + radius * .74f), ring)
    }
    brandMark?.let { mark ->
        mark.setBounds(56, 54, 97, 95)
        mark.draw(canvas)
    }
    drawText(canvas, "ELEVEN", 110f, 84f, 22f, ink, bold = true)
    drawTextRight(canvas, "${result.marketSymbol}  /  90 SEC", 664f, 83f, 19f, ink)
    rule(canvas, 56f, 119f, 664f, 119f, hairline, 1.5f)

    drawTextCenter(canvas, if (result.won) "WIN" else "LOSS", 360f, 327f,
        if (result.won) 162f else 148f, ink, bold = true, condensed = true)
    drawTextCenter(canvas, "${result.direction.label.uppercase(Locale.US)} POSITION", 360f, 377f,
        22f, mutedInk, bold = true)

    drawObservedChart(canvas, result)
    rule(canvas, 56f, 618f, 664f, 618f, hairline, 1.5f)
}

/** The line appears only when the settled round includes observed price history. */
private fun drawObservedChart(canvas: AndroidCanvas, result: PredictionResultData) {
    val observations = result.priceHistory
        .filter { it.second in 0..marketSeconds.toInt() && it.price.isFinite() }
        .sortedBy { it.second }.distinctBy { it.second }
    if (observations.size < 2 || !result.anchor.isFinite() || !result.finalPrice.isFinite()) return

    val left = 68f
    val right = 652f
    val top = 430f
    val bottom = 586f
    val lowest = min(result.anchor, min(result.finalPrice, observations.minOf { it.price }))
    val highest = max(result.anchor, max(result.finalPrice, observations.maxOf { it.price }))
    val padding = max(max((highest - lowest) * .17, result.band.coerceAtLeast(0.0) * .35), .4)
    val floor = lowest - padding
    val span = highest - lowest + padding * 2.0
    fun x(second: Int): Float = left + (second / marketSeconds) * (right - left)
    fun y(price: Double): Float = bottom - ((price - floor) / span * (bottom - top)).toFloat()

    val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(ink, 22)
        strokeWidth = 1f
    }
    for (index in 0..3) {
        val row = top + index * (bottom - top) / 3f
        canvas.drawLine(left, row, right, row, grid)
    }
    val anchor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(ink, 80)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(7f, 7f), 0f)
    }
    canvas.drawLine(left, y(result.anchor), right, y(result.anchor), anchor)

    val line = Path()
    observations.forEachIndexed { index, point ->
        if (index == 0) line.moveTo(x(point.second), y(point.price))
        else line.lineTo(x(point.second), y(point.price))
    }
    val last = observations.last()
    val first = observations.first()
    val fill = Path(line).apply {
        lineTo(x(last.second), bottom)
        lineTo(x(first.second), bottom)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, top, 0f, bottom,
            intArrayOf(withAlpha(ink, 50), withAlpha(ink, 8), AndroidColor.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
    }
    canvas.drawPath(fill, paint)
    paint.shader = null
    paint.style = Paint.Style.STROKE
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    for ((stroke, alpha) in listOf(11f to 25, 5f to 85, 2.6f to 255)) {
        paint.strokeWidth = stroke
        paint.color = withAlpha(ink, alpha)
        canvas.drawPath(line, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = withAlpha(ink, 60)
    canvas.drawCircle(x(last.second), y(last.price), 14f, paint)
    paint.color = ink
    canvas.drawCircle(x(last.second), y(last.price), 5f, paint)
    paint.color = white
    canvas.drawCircle(x(last.second), y(last.price), 2f, paint)
}

private fun drawDetails(canvas: AndroidCanvas, result: PredictionResultData, barcode: BarcodePattern) {
    drawText(canvas, "${result.marketSymbol}  /  90 SEC  ·  OPEN ${result.anchor.asMoney()}",
        56f, 660f, 19f, mutedInk, bold = true)
    drawText(canvas, "THE MARKET", 55f, 730f, 59f, ink, bold = true, condensed = true)
    drawText(canvas, "HAS SPOKEN.", 55f, 791f, 59f, ink, bold = true, condensed = true)
    drawText(canvas, "POSITION", 475f, 709f, 19f, mutedInk, bold = true)
    drawText(canvas, result.direction.label.uppercase(Locale.US), 471f, 764f, 51f,
        ink, bold = true, condensed = true)

    rule(canvas, 56f, 821f, 664f, 821f, hairline, 1.5f)
    drawText(canvas, "STAKE", 56f, 853f, 19f, mutedInk, bold = true)
    drawTextFit(canvas, predictionMoney(result.stakeUsd?.toDouble() ?: result.stake.toDouble()),
        56f, 905f, 44f, 272f)
    drawText(canvas, "PAYOUT", 373f, 853f, 19f, mutedInk, bold = true)
    val payout = result.payoutUsd?.takeIf { it.isFinite() && it >= 0.0 }
    drawTextFit(canvas, payout?.let { predictionMoney(it) } ?: "—", 373f, 905f, 44f, 291f)

    rule(canvas, 56f, 925f, 664f, 925f, hairline, 1.5f)
    drawBarcode(canvas, barcode, 56f, 945f, 265f, 58f)
    drawText(canvas, "FINAL ${result.marketSymbol}", 373f, 960f, 18f, mutedInk, bold = true)
    drawTextFit(canvas, result.finalPrice.asMoney(), 373f, 1008f, 35f, 291f)

    result.finishedAtMillis?.let { timestamp ->
        val date = SimpleDateFormat("dd MMM yyyy  ·  HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
        drawText(canvas, date, 56f, 1027f, 15f, mutedInk)
    }
}

private fun drawBarcode(canvas: AndroidCanvas, pattern: BarcodePattern,
    left: Float, top: Float, width: Float, height: Float) {
    if (pattern.sourceWidth <= 0) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    pattern.bars.forEach { bar ->
        val x1 = left + bar.first.toFloat() / pattern.sourceWidth * width
        val x2 = left + (bar.last + 1).toFloat() / pattern.sourceWidth * width
        canvas.drawRect(x1, top, x2, top + height, paint)
    }
}

private fun withAlpha(color: Int, alpha: Int): Int = AndroidColor.argb(
    alpha, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))

private fun textPaint(size: Float, color: Int, bold: Boolean = false, condensed: Boolean = false): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif",
            if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

private fun drawText(canvas: AndroidCanvas, value: String, x: Float, baseline: Float,
    size: Float, color: Int, bold: Boolean = false, condensed: Boolean = false) {
    canvas.drawText(value, x, baseline, textPaint(size, color, bold, condensed))
}

private fun drawTextCenter(canvas: AndroidCanvas, value: String, center: Float, baseline: Float,
    size: Float, color: Int, bold: Boolean = false, condensed: Boolean = false) {
    val paint = textPaint(size, color, bold, condensed)
    canvas.drawText(value, center - paint.measureText(value) / 2f, baseline, paint)
}

private fun drawTextRight(canvas: AndroidCanvas, value: String, right: Float, baseline: Float,
    size: Float, color: Int, bold: Boolean = false) {
    val paint = textPaint(size, color, bold)
    canvas.drawText(value, right - paint.measureText(value), baseline, paint)
}

private fun drawTextFit(canvas: AndroidCanvas, value: String, x: Float, baseline: Float,
    initialSize: Float, maxWidth: Float) {
    val paint = textPaint(initialSize, ink, bold = true, condensed = true)
    while (paint.measureText(value) > maxWidth && paint.textSize > 20f) paint.textSize -= 1f
    canvas.drawText(value, x, baseline, paint)
}

private fun rule(canvas: AndroidCanvas, x1: Float, y1: Float, x2: Float, y2: Float,
    color: Int, width: Float) {
    canvas.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
    })
}

private fun Double.asMoney(): String = if (isFinite()) predictionMoney(this) else "—"
