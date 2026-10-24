package com.example.capsight.depth

/**
 * Zone scores of one depth map (FR-OBS-04 / FR-PATH-03): how near the left, centre and right of the
 * body-height band are, relative to the floor just ahead. ~1.0 = as near as that floor patch
 * (about arm's length); a long open corridor scores ~0.45.
 */
data class DepthScores(val left: Double, val centre: Double, val right: Double, val floorRef: Double)

/**
 * Scores a MiDaS relative INVERSE depth map (bigger = nearer), exactly as ml/depth/eval_photos.py
 * (the reference, see ml/depth/README.md): floor reference = median of rows 220-250, cols 96-160;
 * body band = rows 60-180; zone score = 90th percentile of the band in cols 0-85 (L), 85-171 (C),
 * 171-256 (R) divided by the floor reference. Indices are for a 256x256 map and scale with the map
 * size. Pure Kotlin (no android.*), JVM-tested.
 */
object DepthScorer {

    /** The reference script's map size; the index ranges below are in this space. */
    const val REF_SIZE = 256

    private const val FLOOR_ROW_START = 220
    private const val FLOOR_ROW_END = 250
    private const val FLOOR_COL_START = 96
    private const val FLOOR_COL_END = 160
    private const val BAND_ROW_START = 60
    private const val BAND_ROW_END = 180
    private const val LEFT_END = 85
    private const val CENTRE_END = 171
    private const val ZONE_PERCENTILE = 90.0

    /**
     * @param map row-major, [width] x [height] values, image left = column 0 (camera view, not mirrored)
     * @return the scores, or null when the floor reference is not positive (or not finite) or a score
     *   is not finite: no basis for "near", so no depth cue at all.
     */
    fun score(map: FloatArray, width: Int, height: Int): DepthScores? {
        require(width > 0 && height > 0 && map.size >= width * height) { "depth map ${width}x$height, ${map.size} values" }
        fun col(c: Int) = c * width / REF_SIZE
        fun row(r: Int) = r * height / REF_SIZE

        val floor = median(region(map, width, row(FLOOR_ROW_START), row(FLOOR_ROW_END), col(FLOOR_COL_START), col(FLOOR_COL_END)))
        if (floor.isNaN() || floor.isInfinite() || floor <= 0.0) return null

        val bandTop = row(BAND_ROW_START)
        val bandBottom = row(BAND_ROW_END)
        fun zone(c0: Int, c1: Int) = percentile(region(map, width, bandTop, bandBottom, col(c0), col(c1)), ZONE_PERCENTILE) / floor
        val s = DepthScores(
            left = zone(0, LEFT_END),
            centre = zone(LEFT_END, CENTRE_END),
            right = zone(CENTRE_END, REF_SIZE),
            floorRef = floor,
        )
        return if (listOf(s.left, s.centre, s.right).all { it.isFinite() }) s else null
    }

    /** Values of rows [r0, r1) x cols [c0, c1), like numpy d[r0:r1, c0:c1]. */
    private fun region(map: FloatArray, width: Int, r0: Int, r1: Int, c0: Int, c1: Int): DoubleArray {
        val out = DoubleArray(maxOf(0, r1 - r0) * maxOf(0, c1 - c0))
        var k = 0
        for (r in r0 until r1) {
            val base = r * width
            for (c in c0 until c1) out[k++] = map[base + c].toDouble()
        }
        return out
    }

    /** numpy.median: the mean of the two middle values for an even count. NaN when empty. */
    fun median(values: DoubleArray): Double = percentile(values, HALF_PERCENT)

    /** numpy.percentile with its default linear interpolation. NaN when empty. */
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.copyOf().also { it.sort() }
        val pos = p / FULL_PERCENT * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private const val HALF_PERCENT = 50.0
    private const val FULL_PERCENT = 100.0
}
