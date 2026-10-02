package com.example.capsight.detect

/** Per-class greedy non-maximum suppression (CLAUDE.md §7.2: the model has no NMS). */
object Nms {
    const val DEFAULT_IOU = 0.45f

    /** Highest score first; drops a box that overlaps a kept box of the same class by more than [iou]. */
    fun apply(detections: List<Detection>, iou: Float = DEFAULT_IOU): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in detections.sortedByDescending { it.score }) {
            if (kept.none { it.classId == d.classId && it.iou(d) > iou }) kept += d
        }
        return kept
    }
}
