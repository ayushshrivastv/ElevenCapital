package com.elevencapital.app.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.rd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val resultGlassFill = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .20f),
    .45f to Color.White.copy(alpha = .10f),
    1f to Color.White.copy(alpha = .055f),
)
private val resultGlassEdge = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .48f),
    1f to Color.White.copy(alpha = .12f),
)

/** The result sits on one orange surface, with two translucent actions below it. */
@Composable
internal fun PredictionResultSheet(
    result: PredictionResultData,
    onDismiss: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onNextRound: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .90f))
                .statusBarsPadding().navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ).semantics { dismiss { onDismiss(); true } })
            PredictionResultContent(result,
                onShare = {
                    if (!exporting) {
                        exporting = true
                        scope.launch {
                            try { sharePredictionResultOnX(context, result) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                Toast.makeText(context, "Could not share result", Toast.LENGTH_SHORT).show()
                            } finally { exporting = false }
                        }
                    }
                },
                onDownload = {
                    if (!exporting) {
                        exporting = true
                        scope.launch {
                            try {
                                savePredictionResult(context, result)
                                Toast.makeText(context, "Saved to Pictures", Toast.LENGTH_SHORT).show()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                Toast.makeText(context, "Could not save result", Toast.LENGTH_SHORT).show()
                            } finally { exporting = false }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun PredictionResultContent(
    result: PredictionResultData,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cardWidth = (maxWidth * .92f).coerceAtMost((maxHeight - rd(116f)) *
            PREDICTION_RESULT_ASPECT).coerceAtMost(rd(440f))
        Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(rd(14f))) {
            PredictionResultCard(result, Modifier.fillMaxWidth()
                .aspectRatio(PREDICTION_RESULT_ASPECT)
                .clip(RoundedCornerShape(rd(29f)))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .semantics {
                    contentDescription = buildString {
                        append(if (result.won) "Won" else "Lost")
                        append(" ${result.marketSymbol} prediction, ")
                        append(result.direction.label)
                        append(" position, stake ")
                        append(result.stakeUsd?.let { predictionMoney(it.toDouble()) }
                            ?: predictionMoney(result.stake.toDouble()))
                        result.payoutUsd?.let { append(", payout ${predictionMoney(it)}") }
                    }
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(12f))) {
                ResultGlassAction("X", "Share on X", onShare, Modifier.weight(1f))
                ResultGlassAction("↓", "Download", onDownload, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResultGlassAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(rd(21f))
    Column(modifier.height(rd(76f)).clip(shape)
        .background(resultGlassFill)
        .border(rd(1f), resultGlassEdge, shape)
        .clickable(onClick = onClick)
        .padding(top = rd(8f), bottom = rd(9f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RefText(glyph, 22f, Color.White, FontWeight.Medium)
        RefText(label, 14f, Color.White, FontWeight.Medium)
    }
}
