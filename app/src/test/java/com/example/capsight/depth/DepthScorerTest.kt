package com.example.capsight.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-OBS-04: zone scoring on synthetic inverse-depth maps, the same rule as ml/depth/eval_photos.py.
 * Maps are 256x256, bigger = nearer. Rows >= 180 are floor; the body band is rows 60-180.
 */
class DepthScorerTest {

    private val n = DepthScorer.REF_SIZE
    private val eps = 1e-9

    /** Floor (rows >= 180) at [floor], everything above at [far], then [near] applied on top. */
    private fun map(floor: Float = 10f, far: Float = 2f, near: (row: Int, col: Int) -> Float? = { _, _ -> null }) =
        FloatArray(n * n) { i ->
            val r = i / n
            val c = i % n
            near(r, c) ?: if (r >= 180) floor else far
        }

    private fun score(m: FloatArray): DepthScores = checkNotNull(DepthScorer.score(m, n, n)) { "no score" }

    @Test
    fun `uniform far scene - every zone well below 1`() {
        val s = score(map())
        assertEquals(10.0, s.floorRef, eps)
        assertEquals(0.2, s.left, eps)
        assertEquals(0.2, s.centre, eps)
        assertEquals(0.2, s.right, eps)
    }

    @Test
    fun `wall filling the centre - centre near, sides far`() {
        val s = score(map { r, c -> if (r < 180 && c in 85 until 171) 12f else null })
        assertEquals(1.2, s.centre, eps)
        assertEquals(0.2, s.left, eps)
        assertEquals(0.2, s.right, eps)
    }

    @Test
    fun `narrow near strip under a tenth of the zone does not move p90, a wider one does`() {
        // Centre zone is 86 columns: 8 columns = 9.3% of the band, 12 columns = 14%.
        val thin = score(map { r, c -> if (r < 180 && c in 124 until 132) 20f else null })
        assertEquals(0.2, thin.centre, eps)
        val wide = score(map { r, c -> if (r < 180 && c in 122 until 134) 20f else null })
        assertEquals(2.0, wide.centre, eps)
    }

    @Test
    fun `side wall left or right scores only that side`() {
        val left = score(map { r, c -> if (r < 180 && c < 85) 11f else null })
        assertEquals(1.1, left.left, eps)
        assertEquals(0.2, left.centre, eps)
        assertEquals(0.2, left.right, eps)
        val right = score(map { r, c -> if (r < 180 && c >= 171) 11f else null })
        assertEquals(0.2, right.left, eps)
        assertEquals(1.1, right.right, eps)
    }

    @Test
    fun `zone boundaries are cols 0-85, 85-171, 171-256`() {
        fun cols(range: IntRange) = score(map { r, c -> if (r < 180 && c in range) 20f else null })
        // Nine near columns just inside each edge are over a tenth of their zone, so p90 sees them.
        cols(76..84).let { assertEquals(2.0, it.left, eps); assertEquals(0.2, it.centre, eps) }
        cols(85..93).let { assertEquals(0.2, it.left, eps); assertEquals(2.0, it.centre, eps) }
        cols(162..170).let { assertEquals(2.0, it.centre, eps); assertEquals(0.2, it.right, eps) }
        cols(171..179).let { assertEquals(0.2, it.centre, eps); assertEquals(2.0, it.right, eps) }
    }

    @Test
    fun `the body band is rows 60-180 - ceiling and floor rows are ignored`() {
        fun rows(range: IntRange) = score(map { r, _ -> if (r in range) 20f else null })
        assertEquals(0.2, rows(45..59).centre, eps)
        assertEquals(2.0, rows(60..74).centre, eps)
        assertEquals(2.0, rows(165..179).centre, eps)
    }

    @Test
    fun `floor reference is the median of rows 220-250, cols 96-160`() {
        // Floor outside the patch does not matter; the patch's median does.
        val s = score(map { r, c -> if (r >= 180 && (r !in 220 until 250 || c !in 96 until 160)) 1f else null })
        assertEquals(10.0, s.floorRef, eps)
        val half = score(map { r, c -> if (r in 220 until 235 && c in 96 until 160) 4f else null })
        assertEquals(7.0, half.floorRef, eps) // even count: mean of the two middle values (4 and 10)
    }

    @Test
    fun `zero, negative or NaN floor gives no score`() {
        assertNull(DepthScorer.score(map(floor = 0f), n, n))
        assertNull(DepthScorer.score(map(floor = -3f), n, n))
        assertNull(DepthScorer.score(map(floor = Float.NaN), n, n))
    }

    @Test
    fun `percentile and median match numpy`() {
        val v = DoubleArray(10) { (it + 1).toDouble() }
        assertEquals(9.1, DepthScorer.percentile(v, 90.0), eps) // np.percentile(range(1, 11), 90)
        assertEquals(5.5, DepthScorer.median(v), eps)
        assertEquals(3.0, DepthScorer.median(doubleArrayOf(5.0, 1.0, 3.0)), eps)
        assertTrue(DepthScorer.median(DoubleArray(0)).isNaN())
    }

    @Test
    fun `a smaller map scales the index ranges`() {
        val m = 128
        val small = FloatArray(m * m) { i -> if (i / m >= 90) 10f else if ((i % m) in 43 until 85) 12f else 2f }
        val s = checkNotNull(DepthScorer.score(small, m, m))
        assertEquals(1.2, s.centre, eps)
        assertEquals(0.2, s.left, eps)
    }
}
