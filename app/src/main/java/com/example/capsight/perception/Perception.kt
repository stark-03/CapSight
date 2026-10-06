package com.example.capsight.perception

import com.example.capsight.detect.Detection

/** How a detection matters for navigation right now. */
enum class Threat { NONE, WARN, STOP }

/** A detection with what perception made of it, for the debug view and the guidance loop. */
data class PerceivedObject(
    val detection: Detection,
    /** Null when it has no distance: unknown class, flat desk item, or below confidence. */
    val relative: RelativeObstacle?,
    val inCorridor: Boolean,
    val threat: Threat,
)

/** Thresholds perception needs (CLAUDE.md §8 defaults live in settings). */
data class PerceptionParams(
    val fovDeg: Double,
    val minConfidence: Float,
    val skipUnreliable: Boolean,
    val corridorHalfWidthM: Double,
    val warnDistanceM: Double,
    val stopDistanceM: Double,
)

/** Detections of one frame -> obstacles with distance and corridor status (M4, M5). Pure Kotlin. */
object Perception {
    fun perceive(dets: List<Detection>, frameWidth: Int, frameHeight: Int, p: PerceptionParams): List<PerceivedObject> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        val focal = Egocentric.focalFromFov(p.fovDeg, frameWidth)
        return dets.map { d ->
            val rel = Egocentric.obstacles(listOf(d), frameWidth, focal, p.minConfidence, p.skipUnreliable, frameHeight)
                .firstOrNull()
            val inCorridor = rel != null && UserFrame.inCorridor(rel, p.corridorHalfWidthM, p.warnDistanceM)
            val threat = when {
                !inCorridor -> Threat.NONE
                rel!!.forwardM <= p.stopDistanceM -> Threat.STOP
                else -> Threat.WARN
            }
            PerceivedObject(d, rel, inCorridor, threat)
        }
    }
}
