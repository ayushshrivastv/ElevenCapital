package com.elevencapital.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elevencapital.app.R
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.Stock

/** Raster-sampled colors from the original screenshots, not the Stitch palette. */
object P {
    val Background = Color(0xFF08090E)
    val Card = Color(0xFF1A1B1F)
    val Chip = Color(0xFF24252A)
    val White = Color(0xFFF8F9FA)
    val Muted = Color(0xFF797A7F)
    val Lime = Color(0xFFADF47E)
    val Pink = Color(0xFFF37590)
    val Amber = Color(0xFFE9A830)
}

// The supplied video is 392px wide; use one common proportional reference grid.
val LocalReferenceScale = staticCompositionLocalOf { 1f }
val LocalReferenceFont = staticCompositionLocalOf { ElevenRoboto }
@Composable fun rd(value: Float): Dp = (value * LocalReferenceScale.current).dp
@Composable fun rs(value: Float): TextUnit = (value * LocalReferenceScale.current).sp

@Composable
fun RefText(
    text: String,
    size: Float = 16f,
    color: Color = P.White,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = rs(size), fontFamily = LocalReferenceFont.current,
            fontWeight = weight, lineHeight = rs(size * 1.25f)),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun BrandLogo(modifier: Modifier = Modifier, alpha: Float = 1f, onLightBackground: Boolean = false) {
    Image(
        painter = painterResource(if (onLightBackground) R.drawable.eleven_brand_logo_light else R.drawable.eleven_brand_logo),
        contentDescription = null,
        modifier = modifier.alpha(alpha),
        contentScale = ContentScale.Fit,
    )
}

/** Retains the existing call sites while rendering the complete supplied Eleven logo. */
@Composable
fun SquareLogo(size: Float, alpha: Float = 1f) {
    BrandLogo(Modifier.size(rd(size)), alpha)
}

/** The same monochrome Solana mark is used for wallet assets and payment networks. */
@Composable
fun SolanaLogo(size: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(rd(size))) { drawSolanaLogo() }
}

fun DrawScope.drawSolanaLogo() {
    val side = size.minDimension
    drawCircle(Color.Black, side / 2f, Offset(size.width / 2f, size.height / 2f))
    fun bar(points: List<Pair<Float, Float>>) {
        drawPath(Path().apply {
            moveTo(side * points[0].first, side * points[0].second)
            points.drop(1).forEach { (x, y) -> lineTo(side * x, side * y) }
            close()
        }, Color.White)
    }
    bar(listOf(.29f to .23f, .79f to .23f, .69f to .35f, .19f to .35f))
    bar(listOf(.19f to .44f, .69f to .44f, .79f to .56f, .29f to .56f))
    bar(listOf(.29f to .65f, .79f to .65f, .69f to .77f, .19f to .77f))
}

/** Stocks and portfolio positions resolve the same bundled provider marks when available. */
@Composable
fun StockIcon(
    stock: Stock,
    size: Float = 44f,
    modifier: Modifier = Modifier,
    logoReference: StockLogoReference? = stock.logo,
) {
    val context = LocalContext.current
    val reference = logoReference?.value
    // These are byte-for-byte copies of the catalog's logo URLs. Bundling them keeps the
    // market row, buy screen, and portfolio row identical when remote artwork is unavailable.
    val providerResource = when (stock.id.value) {
        "backpack:NKE.US" -> R.drawable.stock_nke_provider
        "backed:eba060bd-f7b3-49e3-8ef1-99869351b434" -> R.drawable.stock_spcxx_provider
        else -> 0
    }
    val resource = remember(reference, providerResource) {
        if (providerResource != 0) providerResource else if (reference?.startsWith("reference/") == true) {
            val name = "stock_" + reference.removePrefix("reference/").replace('/', '_')
            context.resources.getIdentifier(name, "drawable", context.packageName)
        } else 0
    }
    val mark = stock.symbol.removeSuffix(".US").removeSuffix("x").take(2).uppercase()
    @Composable fun Monogram() {
        Box(Modifier.size(rd(size)).clip(CircleShape).background(P.Chip), contentAlignment = Alignment.Center) {
            RefText(mark, size * .32f, P.White, FontWeight.Bold)
        }
    }
    Box(modifier.size(rd(size)), contentAlignment = Alignment.Center) {
        if (resource != 0) {
            val painter = painterResource(resource)
            // Oversized bundled table artwork includes a provider badge outside the
            // 84px company mark. Smaller standalone marks are displayed whole.
            val extent = if (providerResource != 0 || reference?.endsWith("/detail") == true ||
                reference?.endsWith("/standalone") == true ||
                painter.intrinsicSize.width <= 84f) 1f else
                painter.intrinsicSize.width / 84f
            Box(Modifier.size(rd(size)).clip(CircleShape)
                .background(if (providerResource != 0) Color.Black else Color.Transparent),
                contentAlignment = Alignment.TopStart) {
                Image(painter, stock.name,
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .size(rd(size * extent)),
                    contentScale = ContentScale.FillBounds)
            }
        } else if (reference?.startsWith("https://") == true) {
            // Loading/error states use the monogram. A successful image replaces it,
            // so transparent company artwork cannot reveal duplicate ticker letters.
            SubcomposeAsyncImage(
                model = reference,
                contentDescription = stock.name,
                modifier = Modifier.size(rd(size)).clip(CircleShape),
                loading = { Monogram() },
                error = { Monogram() },
                success = { SubcomposeAsyncImageContent() },
            )
        } else {
            Monogram()
        }
    }
}

/** Small vector affordances use the reference geometry, without Material defaults. */
@Composable
fun RefIcon(name: String, size: Float = 24f, color: Color = P.White, modifier: Modifier = Modifier) {
    Canvas(modifier.size(rd(size))) {
        scale(this.size.width / 24f, this.size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun path(data: String, fill: Boolean = false, width: Float = 1.8f) {
                drawPath(PathParser().parsePathString(data).toPath(), color,
                    style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else
                        Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            when (name) {
                "pause" -> { drawCircle(color, 9f, Offset(12f, 12f), style = stroke); path("M9 8v8 M15 8v8", width = 1.6f) }
                "person" -> { drawCircle(color, 3.4f, Offset(12f, 6f)); path("M5 21v-4c0-7 14-7 14 0v4Z", true) }
                "sort" -> path("M3 8h17 M16 4l4 4-4 4 M21 16H4 M8 12l-4 4 4 4", width = 1.3f)
                "sparkle" -> path("M12 1l3 8 8 3-8 3-3 8-3-8-8-3 8-3Z", true)
                "eye" -> { path("M1 12C6 3 18 3 23 12 18 21 6 21 1 12Z", width = 1.3f); drawCircle(color, 3.4f, Offset(12f,12f), style = Stroke(1.3f)) }
                "eyeOff" -> {
                    path("M1 12C6 3 18 3 23 12 18 21 6 21 1 12Z", width = 1.3f)
                    drawCircle(color, 3.4f, Offset(12f,12f), style = Stroke(1.3f))
                    drawLine(color, Offset(3f, 3f), Offset(21f, 21f), 1.8f, StrokeCap.Round)
                }
                "bell" -> {
                    path("M6 10c0-4 2-7 6-7s6 3 6 7v5l2 3H4l2-3Z", width = 1.5f)
                    path("M9.5 20c.7 2 4.3 2 5 0", width = 1.5f)
                }
                "heart" -> path("M12 21C-8 8 6-2 12 7 18-2 32 8 12 21Z", width = 1.3f)
                "back" -> path("M21 12H3 M9 6l-6 6 6 6")
                "send" -> path("M12 21V3 M5 10l7-7 7 7")
                "deposit" -> path("M12 3v18 M5 14l7 7 7-7")
                "swap" -> path("M4 7h15 M15 3l4 4-4 4 M20 17H5 M9 13l-4 4 4 4")
                "trade" -> path("M20 7H9a6 6 0 0 0-6 6 M16 3l4 4-4 4 M4 17h11a6 6 0 0 0 6-6 M8 13l-4 4 4 4")
                "chevronDown" -> path("M6 9l6 6 6-6")
                "chevronUp" -> path("M6 15l6-6 6 6")
                "chevronRight" -> path("M9 5l7 7-7 7")
                "plus" -> path("M12 3v18 M3 12h18", width = 1.5f)
                "addCircle" -> {
                    drawCircle(color, 10f, Offset(12f, 12f))
                    drawLine(WalletStyle.Cream, Offset(8f, 12f), Offset(16f, 12f), 1.6f, StrokeCap.Round)
                    drawLine(WalletStyle.Cream, Offset(12f, 8f), Offset(12f, 16f), 1.6f, StrokeCap.Round)
                }
                "arrowUpRight" -> path("M6 18L18 6 M6 6h12v12")
                "check" -> path("M5 12l4 4L19 6", width = 2.4f)
                "edit" -> { path("M5 16l-1 4 4-1L20 7l-3-3Z", true); path("M3 23h18", width = 1.3f) }
                "walletHome" -> {
                    path("M3 10Q3 8 5 7L10 3Q12 1 14 3L20 7Q22 8 22 10V20Q22 23 19 23H6Q3 23 3 20Z", true)
                    drawLine(WalletStyle.Cream, Offset(12f, 16f), Offset(12f, 19f), 2f, StrokeCap.Round)
                }
                "walletMarkets" -> {
                    path("M2 4Q2 2 4 2H19Q21 2 21 4V12H14V17H4Q2 17 2 15Z", true)
                    drawLine(WalletStyle.Circle, Offset(2f, 6f), Offset(21f, 6f), 2.3f)
                    path("M16 22v-8 M13 17l3-3 3 3 M22 12v8 M19 17l3 3 2-2", width = 1.7f)
                }
                "walletActivity" -> {
                    path("M12 2C-1 2-1 19 7 20l-2 4 7-3C27 21 27 2 12 2Z", true)
                    listOf(7f, 12f, 17f).forEach { drawCircle(WalletStyle.Circle, .9f, Offset(it, 11.5f)) }
                }
                "walletProfile" -> {
                    drawCircle(color.copy(alpha = .35f), 11f, Offset(12f, 12f))
                    drawCircle(color, 3.2f, Offset(12f, 8f))
                    drawOval(color, Offset(6f, 13f), Size(12f, 7f))
                }
                "minus" -> path("M3 12h18")
                "moreVertical" -> { listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.1f, Offset(12f, it)) } }
                "more" -> { listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.1f, Offset(it, 12f)) } }
                "star", "starFilled" -> path("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01Z", name == "starFilled")
                "verified" -> {
                    path("M12 2l3 2 3.6.4.5 3.6 2 3-2 3-.5 3.6-3.6.5-3 2-3-2-3.6-.5L5 14l-2-3 2-3 .4-3.6L9 4Z", width = 1.6f)
                    path("M7.5 11.5l3 3 5.5-6", width = 1.6f)
                }
                "copy" -> { path("M9 3h11v14 M4 7h12v14H4Z", width = 1.6f) }
                "share" -> path("M8 5H5v16h14V5h-3 M12 15V2 M8 6l4-4 4 4")
                "history" -> { path("M3 10a9 9 0 1 1 1 7 M3 3v7h7 M12 6v7l5 2") }
                "globe" -> { drawCircle(color, 10f, Offset(12f,12f), style=stroke); path("M2 12h20 M12 2c-7 6-7 14 0 20 M12 2c7 6 7 14 0 20 M3 7.5c6 2 12 2 18 0 M3 16.5c6-2 12-2 18 0") }
                "search" -> { path("M12 4A8 8 0 1 0 20 12 M18 18l4 4"); path("M18 1l1.5 4.5L24 7l-4.5 1.5L18 13l-1.5-4.5L12 7l4.5-1.5Z",true) }
                "scan" -> { path("M4 8a8 8 0 0 1 4-4 M16 4a8 8 0 0 1 4 4 M20 16a8 8 0 0 1-4 4 M8 20a8 8 0 0 1-4-4 M2 12h20") }
                "home", "homeFilled" -> {
                    path("M3 10l9-8 9 8v12h-7v-8h-4v8H3Z", name == "homeFilled")
                }
                "markets" -> path("M3 2v20h19 M5 16l5-7 5 4 7-8")
                "accountFilled" -> { path("M21 6H3V3h17 M3 6v15h19V6Z", true); drawCircle(P.Chip,1.1f,Offset(17f,14f)) }
                "account", "wallet" -> { path("M21 6H3V3h17 M3 6v15h19V6Z"); drawCircle(color,1f,Offset(17f,14f)) }
                "candle" -> { path("M7 2v4 M7 18v4 M4 6h6v12H4Z M17 2v7 M17 17v5 M14 9h6v8h-6Z") }
                "priceMarker" -> {
                    path("M12 2C6 2 3 8 5 13l7 10 7-10C21 8 18 2 12 2Z", true)
                    val inner = Color(0xFF08090E)
                    drawLine(inner, Offset(12f,5f),Offset(12f,16f),1.6f)
                    drawPath(PathParser().parsePathString("M15 7c-8-4-9 5-3 4 6-1 5 8-3 4").toPath(),inner,style=Stroke(1.4f))
                }
                "backspace" -> { path("M9 4h13v16H9l-7-8Z",true); drawLine(P.Background,Offset(12f,8f),Offset(18f,16f),2f);drawLine(P.Background,Offset(18f,8f),Offset(12f,16f),2f) }
                "downTriangle" -> path("M5 8h14l-7 8Z",true)
                else -> Unit
            }
        }
    }
}
