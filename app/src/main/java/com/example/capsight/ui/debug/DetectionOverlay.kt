package com.example.capsight.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.DetectionState
import java.util.Locale
import kotlin.math.min

private val PersonColour = Color(0xFF00E5FF)
private val ObstacleColour = Color(0xFFFFD600)
private val OtherColour = Color(0xFFFF4081)

/**
 * Detection boxes over the frame (FR-APP-05). Must use the same fit as the Image below
 * it (ContentScale.Fit, centred), so boxes in frame pixels land on the right spot.
 */
@Composable
fun DetectionOverlay(state: DetectionState, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.Black, fontSize = 12.sp)
    Canvas(modifier) {
        if (state.frameWidth <= 0 || state.frameHeight <= 0) return@Canvas
        val scale = min(size.width / state.frameWidth, size.height / state.frameHeight)
        val ox = (size.width - state.frameWidth * scale) / 2f
        val oy = (size.height - state.frameHeight * scale) / 2f
        val stroke = Stroke(width = 2.dp.toPx())
        for (d in state.detections) {
            val colour = when (d.classId) {
                CocoLabels.PERSON -> PersonColour
                in CocoLabels.OBSTACLES -> ObstacleColour
                else -> OtherColour
            }
            val topLeft = Offset(ox + d.left * scale, oy + d.top * scale)
            drawRect(colour, topLeft, Size(d.width * scale, d.height * scale), style = stroke)
            val text = "${d.label} ${String.format(Locale.US, "%.2f", d.score)}"
            val layout = measurer.measure(text, labelStyle)
            val labelY = (topLeft.y - layout.size.height).coerceAtLeast(0f)
            drawRect(
                colour,
                Offset(topLeft.x, labelY),
                Size(layout.size.width.toFloat() + 8f, layout.size.height.toFloat()),
            )
            drawText(layout, topLeft = Offset(topLeft.x + 4f, labelY))
        }
    }
}
