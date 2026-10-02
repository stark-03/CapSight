package com.example.capsight.detect

/** One detected object, box in frame pixels (x right, y down). Pure Kotlin. */
data class Detection(
    val classId: Int,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)

    fun iou(other: Detection): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (area + other.area - inter)
    }
}
