package com.example.capsight.detect

/**
 * Turns the raw YOLOv8 output into frame-space detections (FR-DET-01, FR-DET-03).
 *
 * The output tensor is `[1, 4 + classes, candidates]` (here `[1, 84, 2100]`), channel-major:
 * value `c` of candidate `i` is at `c * candidates + i`. Rows 0-3 are cx, cy, w, h; the rest
 * are class scores (already sigmoid-ed). The LiteRT export writes boxes normalised to 0-1;
 * older TFLite exports wrote input pixels. Both are handled: if every cx is at most
 * [NORMALISED_LIMIT] the boxes are taken as normalised.
 *
 * Pure Kotlin, no allocation per candidate below the score threshold.
 */
class YoloDecoder(
    private val numClasses: Int = CocoLabels.NAMES.size,
    private val iou: Float = Nms.DEFAULT_IOU,
) {
    /**
     * @param output the flat output tensor
     * @param minScore drop candidates whose best class score is below this (setting, default 0.40)
     * @param letterbox how the frame was fitted into the model input
     * @param classes keep only these class ids; null keeps all
     */
    fun decode(output: FloatArray, minScore: Float, letterbox: Letterbox, classes: Set<Int>?): List<Detection> {
        val channels = BOX_CHANNELS + numClasses
        require(output.size % channels == 0) { "output size ${output.size} is not a multiple of $channels" }
        val n = output.size / channels

        var maxCx = 0f
        for (i in 0 until n) maxCx = maxOf(maxCx, output[i])
        val toPixels = if (maxCx <= NORMALISED_LIMIT) letterbox.size.toFloat() else 1f

        val candidates = ArrayList<Detection>()
        for (i in 0 until n) {
            var best = -1
            var bestScore = minScore
            for (c in 0 until numClasses) {
                val s = output[(BOX_CHANNELS + c) * n + i]
                if (s >= bestScore && (classes == null || c in classes)) {
                    best = c
                    bestScore = s
                }
            }
            if (best < 0) continue
            val cx = output[i] * toPixels
            val cy = output[n + i] * toPixels
            val w = output[2 * n + i] * toPixels
            val h = output[3 * n + i] * toPixels
            val left = letterbox.toFrameX(cx - w / 2)
            val top = letterbox.toFrameY(cy - h / 2)
            val right = letterbox.toFrameX(cx + w / 2)
            val bottom = letterbox.toFrameY(cy + h / 2)
            if (right - left < MIN_BOX_PX || bottom - top < MIN_BOX_PX) continue
            candidates += Detection(best, CocoLabels.name(best), bestScore, left, top, right, bottom)
        }
        return Nms.apply(candidates, iou)
    }

    companion object {
        private const val BOX_CHANNELS = 4

        /** Normalised boxes have cx <= 1; pixel boxes reach the input size (320). */
        const val NORMALISED_LIMIT = 2f

        /** A box thinner than this after clipping to the frame is noise. */
        private const val MIN_BOX_PX = 1f
    }
}
