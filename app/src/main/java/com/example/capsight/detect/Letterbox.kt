package com.example.capsight.detect

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fit a frame into the square model input without distortion (CLAUDE.md §7.2): scale the
 * long side to [size], centre it, pad the rest with grey 114. Same arithmetic as the
 * Ultralytics letterbox, so boxes map back exactly. Pure Kotlin.
 */
data class Letterbox(
    val srcWidth: Int,
    val srcHeight: Int,
    val size: Int,
    val scale: Float,
    /** Scaled image size inside the square. */
    val scaledWidth: Int,
    val scaledHeight: Int,
    /** Offset of the scaled image inside the square. */
    val padX: Int,
    val padY: Int,
) {
    /** Model-input x (pixels in the square) to frame x, clamped to the frame. */
    fun toFrameX(x: Float): Float = ((x - padX) / scale).coerceIn(0f, srcWidth.toFloat())

    fun toFrameY(y: Float): Float = ((y - padY) / scale).coerceIn(0f, srcHeight.toFloat())

    companion object {
        /** Ultralytics' pad colour, as a 0-255 grey level. */
        const val PAD_GREY = 114

        fun compute(srcWidth: Int, srcHeight: Int, size: Int): Letterbox {
            require(srcWidth > 0 && srcHeight > 0 && size > 0) { "bad sizes $srcWidth x $srcHeight -> $size" }
            val scale = size.toFloat() / max(srcWidth, srcHeight)
            val w = (srcWidth * scale).roundToInt().coerceIn(1, size)
            val h = (srcHeight * scale).roundToInt().coerceIn(1, size)
            return Letterbox(srcWidth, srcHeight, size, scale, w, h, (size - w) / 2, (size - h) / 2)
        }
    }
}
