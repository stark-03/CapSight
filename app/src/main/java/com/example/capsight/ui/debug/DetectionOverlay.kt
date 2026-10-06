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
import com.example.capsight.perception.PerceivedObject
import com.example.capsight.perception.Threat
import java.util.Locale
import kotlin.math.min

/** One frame's perceived objects plus the field of view used to measure them. */
data class PerceivedView(
    val objects: List<PerceivedObject> = emptyList(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val fovDeg: Double = 0.0,
    val fovSource: String = "",
)

private val PersonColour = Color(0xFF00E5FF)
private val ObstacleColour = Color(0xFFFFD600)
private val OtherColour = Color(0xFFFF4081)
private val WarnColour = Color(0xFFFF9100)
private val StopColour = Color(0xFFFF1744)

/** "chair 0.82 2.1 m" - the distance only when perception could measure one. */
fun objectLabel(p: PerceivedObject): String {
    val d = p.detection
    val dist = p.relative?.let { String.format(Locale.US, " %.1f m", it.forwardM) }.orEmpty()
    return "${d.label} ${String.format(Locale.US, "%.2f", d.score)}$dist"
}

/**
 * Boxes over the frame (FR-APP-05). Red: in the corridor within the stop distance; orange: in the
 * corridor within the warning distance; otherwise coloured by class. Uses the same fit as the
 * Image below it (ContentScale.Fit, centred), so frame-pixel boxes land on the right spot.
 */
@Composable
fun DetectionOverlay(view: PerceivedView, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.Black, fontSize = 12.sp)
    Canvas(modifier) {
        if (view.frameWidth <= 0 || view.frameHeight <= 0) return@Canvas
        val scale = min(size.width / view.frameWidth, size.height / view.frameHeight)
        val ox = (size.width - view.frameWidth * scale) / 2f
        val oy = (size.height - view.frameHeight * scale) / 2f
        for (p in view.objects) {
            val d = p.detection
            val colour = when (p.threat) {
                Threat.STOP -> StopColour
                Threat.WARN -> WarnColour
                Threat.NONE -> when (d.classId) {
                    CocoLabels.PERSON -> PersonColour
                    in CocoLabels.OBSTACLES -> ObstacleColour
                    else -> OtherColour
                }
            }
            val strokeDp = if (p.threat == Threat.NONE) 2.dp else 4.dp
            val topLeft = Offset(ox + d.left * scale, oy + d.top * scale)
            drawRect(colour, topLeft, Size(d.width * scale, d.height * scale), style = Stroke(strokeDp.toPx()))
            val layout = measurer.measure(objectLabel(p), labelStyle)
            val labelY = (topLeft.y - layout.size.height).coerceAtLeast(0f)
            drawRect(colour, Offset(topLeft.x, labelY), Size(layout.size.width + 8f, layout.size.height.toFloat()))
            drawText(layout, topLeft = Offset(topLeft.x + 4f, labelY))
        }
    }
}
