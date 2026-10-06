package com.example.capsight.perception

import kotlin.math.atan
import kotlin.math.min

/**
 * Horizontal field of view of an upright frame, from a phone camera's own characteristics
 * (focal length, sensor size). Lets the phone-camera test source get correct distances without
 * measuring (CLAUDE.md §8.1 is for the cap's lens).
 *
 * The camera crops the sensor to the output aspect ratio (centred, as large as possible), and
 * the frame is rotated upright, so a portrait frame's horizontal axis is the sensor's SHORT side.
 * Pure Kotlin.
 */
object CameraFov {
    /**
     * @param focalMm lens focal length
     * @param activeWidthMm / activeHeightMm physical size of the sensor's active array, landscape
     * @param imageWidth / imageHeight the output image in sensor orientation (before rotation)
     * @param rotationDegrees how much the image is rotated to be upright (0, 90, 180, 270)
     */
    fun horizontalFovDeg(
        focalMm: Double,
        activeWidthMm: Double,
        activeHeightMm: Double,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
    ): Double {
        require(focalMm > 0 && activeWidthMm > 0 && activeHeightMm > 0 && imageWidth > 0 && imageHeight > 0)
        val sensorAspect = activeWidthMm / activeHeightMm
        val imageAspect = imageWidth.toDouble() / imageHeight
        // Fraction of each sensor side the crop keeps.
        val keepW = min(1.0, imageAspect / sensorAspect)
        val keepH = min(1.0, sensorAspect / imageAspect)
        val sideMm = if (rotationDegrees % 180 == 0) activeWidthMm * keepW else activeHeightMm * keepH
        return Math.toDegrees(2.0 * atan(sideMm / (2.0 * focalMm)))
    }
}
