package com.example.capsight.perception

import com.example.capsight.detect.Detection
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.tan

/**
 * First-person geometry: the camera is carried BY the user (port of reference/blenav/egocentric.py,
 * FR-OBS-01/03, FR-DIST-01).
 *
 * Distance comes from apparent size: `pixel_height = focal_px * real_height_m / distance_m`. The
 * user sits at the origin facing forward, so the result is directly a [RelativeObstacle].
 *
 * LIMITATION, stated plainly: only classes in [SizePriors.HEIGHTS_M] get a distance. A wall, a
 * step, a glass door or anything the detector does not know is invisible here (FR-OBS-04 needs
 * the depth model).
 *
 * Pure Kotlin. Detection boxes are Float, so results agree with Python to ~1e-5.
 */
object Egocentric {

    /** Focal length in pixels from the horizontal field of view. */
    fun focalFromFov(fovDeg: Double, frameWidthPx: Int): Double =
        (frameWidthPx / 2.0) / tan(Math.toRadians(fovDeg) / 2.0)

    /**
     * Forward distance in metres from apparent size, or null if it cannot be known.
     *
     * Pass [frameH] to correct objects too close to fit: a person overflowing the frame is boxed
     * only partly, measures smaller and would be reported FURTHER away exactly when nearest. An
     * object that overflows the frame projects taller than it, so it must be nearer than the
     * distance at which it would exactly fill it - a rigorous bound, not a guess.
     */
    fun estimateDistance(d: Detection, focalPx: Double, frameH: Int? = null): Double? {
        val realH = SizePriors.HEIGHTS_M[d.label] ?: return null
        val pxH = (d.bottom - d.top).toDouble()
        if (pxH <= 0.0) return null
        var dist = focalPx * realH / pxH

        // Truncated without touching an edge (a desk hides legs): a whole person is far taller
        // than wide, so a near-square box means the height is not the whole object. Width
        // survives truncation, so use it.
        val pxW = (d.right - d.left).toDouble()
        val realW = SizePriors.WIDTHS_M[d.label]
        if (realW != null && pxW > 0.0) {
            val expectedAspect = realW / realH
            if (pxW / pxH > expectedAspect * SizePriors.TRUNCATION_ASPECT_FACTOR) {
                dist = focalPx * realW / pxW
            }
        }

        if (frameH != null && frameH > 0) {
            val touchesTop = d.top <= SizePriors.EDGE_PX
            val touchesBottom = d.bottom >= frameH - SizePriors.EDGE_PX
            if (touchesTop || touchesBottom) dist = min(dist, focalPx * realH / frameH)
        }
        return dist
    }

    /**
     * The detection in the user's forward/lateral frame. Pinhole geometry: size gives depth
     * along the optical axis, offset from the frame centre gives the sideways part.
     * Positive lateral is the user's LEFT.
     */
    fun toRelative(d: Detection, frameWidthPx: Int, focalPx: Double, frameH: Int? = null): RelativeObstacle? {
        val forward = estimateDistance(d, focalPx, frameH) ?: return null
        val centreX = (d.left + d.right) / 2.0
        val dxPx = centreX - frameWidthPx / 2.0
        // Image x grows to the right, lateral is positive to the left.
        val lateral = -forward * dxPx / focalPx
        return RelativeObstacle(d.label, forward, lateral, hypot(forward, lateral))
    }

    /** Detections -> obstacles with a position, dropping weak, flat and unknown ones. */
    fun obstacles(
        dets: List<Detection>,
        frameWidthPx: Int,
        focalPx: Double,
        minConf: Float = 0f,
        skipUnreliable: Boolean = true,
        frameH: Int? = null,
    ): List<RelativeObstacle> = dets.mapNotNull { d ->
        when {
            d.score < minConf -> null
            skipUnreliable && d.label in SizePriors.UNRELIABLE_SMALL -> null
            else -> toRelative(d, frameWidthPx, focalPx, frameH)
        }
    }
}
