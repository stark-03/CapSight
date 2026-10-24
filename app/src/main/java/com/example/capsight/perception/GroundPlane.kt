package com.example.capsight.perception

import com.example.capsight.detect.Detection
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/** Camera pose for ground-plane distance (FR-DIST-02). */
data class GroundParams(
    /** Height of the camera's lens above the floor (setting, default 1.30 m). */
    val cameraHeightM: Double,
    val tilt: Tilt,
)

/** Where an object's final distance came from (FR-DIST-03), for the debug view. */
enum class DistanceSource(val label: String) {
    /** Only the size estimate: no tilt sensor, roll too large, or the box bottom is not on the floor. */
    SIZE("size"),

    /** Both estimates existed; the size one was nearer and is used. */
    FUSED_SIZE("fused:size"),

    /** Both estimates existed; the floor one was nearer and is used. */
    FUSED_FLOOR("fused:floor"),
}

/**
 * Distance from where an object meets the floor (FR-DIST-02). With the camera at a known height
 * and tilt, the ray through the bottom of a box that stands on the floor hits the floor at
 * `cameraHeight / tan(angle below the horizon)`. Needs no size prior, so it does not inherit the
 * ±25% of [SizePriors] - but it is only as good as the height and tilt. Pure Kotlin.
 */
object GroundPlane {

    /** Beyond this roll the frame's vertical axis is too far from the true vertical. */
    const val MAX_ROLL_DEG = 25.0

    /** A ray this close to the horizon hits the floor too far away to trust (tiny angle errors explode). */
    const val MIN_BELOW_HORIZON_DEG = 2.0

    /** Vertical field of view from the horizontal one and the frame's aspect (square pixels). */
    fun verticalFovDeg(horizontalFovDeg: Double, frameWidth: Int, frameHeight: Int): Double =
        Math.toDegrees(2.0 * atan(tan(Math.toRadians(horizontalFovDeg) / 2.0) * frameHeight / frameWidth))

    /** Angle of image row [y] below the optical axis (negative above it). */
    fun angleBelowCentreDeg(y: Double, frameHeight: Int, verticalFovDeg: Double): Double {
        val focalV = (frameHeight / 2.0) / tan(Math.toRadians(verticalFovDeg) / 2.0)
        return Math.toDegrees(atan((y - frameHeight / 2.0) / focalV))
    }

    /** Whether this pose allows ground-plane distance at all (roll within the limit). */
    fun usable(g: GroundParams?): Boolean = g != null && g.cameraHeightM > 0.0 && abs(g.tilt.rollDeg) < MAX_ROLL_DEG

    /**
     * Forward distance to where [d] meets the floor, or null when the geometry does not apply:
     * roll too large, box bottom touching the bottom edge (the real bottom is out of frame), or the
     * ray through the box bottom less than [MIN_BELOW_HORIZON_DEG] below the horizon (that
     * includes every box bottom above the horizon line - it cannot be standing on the floor).
     */
    fun distance(d: Detection, frameWidth: Int, frameHeight: Int, horizontalFovDeg: Double, g: GroundParams?): Double? {
        if (g == null || !usable(g) || frameWidth <= 0 || frameHeight <= 0) return null
        if (d.bottom >= frameHeight - SizePriors.EDGE_PX) return null
        val vFov = verticalFovDeg(horizontalFovDeg, frameWidth, frameHeight)
        val belowHorizon = g.tilt.pitchDownDeg + angleBelowCentreDeg(d.bottom.toDouble(), frameHeight, vFov)
        if (belowHorizon < MIN_BELOW_HORIZON_DEG || belowHorizon >= RIGHT_ANGLE_DEG) return null
        return g.cameraHeightM / tan(Math.toRadians(belowHorizon))
    }

    private const val RIGHT_ANGLE_DEG = 90.0
}
