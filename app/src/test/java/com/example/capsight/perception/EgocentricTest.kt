package com.example.capsight.perception

import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-OBS-01/03, FR-DIST-01: port of reference/tests/test_egocentric.py (geometry sections; the
 * GuidanceLoop sections are ported with decide() in build step 6).
 */
class EgocentricTest {

    // A 640-wide frame with a 65 degree horizontal field of view, as in the Python tests.
    private val w = 640
    private val h = 480
    private val fov = 65.0
    private val f = Egocentric.focalFromFov(fov, w)
    private val tol = 1e-5 // Detection boxes are Float

    private fun det(label: String, x1: Double, y1: Double, x2: Double, y2: Double, conf: Float = 0.9f) =
        Detection(CocoLabels.NAMES.indexOf(label), label, conf, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())

    private val chairH = SizePriors.HEIGHTS_M.getValue("chair")
    private val personH = SizePriors.HEIGHTS_M.getValue("person")

    // ------------------------------------------------ focal length from field of view

    @Test
    fun `focal length from field of view`() {
        assertTrue("got $f", f > 480.0 && f < 530.0)
        assertTrue(Egocentric.focalFromFov(90.0, w) < Egocentric.focalFromFov(45.0, w))
        assertEquals(2.0, Egocentric.focalFromFov(fov, 1280) / Egocentric.focalFromFov(fov, 640), 1e-9)
        assertEquals(320.0, Egocentric.focalFromFov(90.0, 640), 1e-6)
    }

    // ------------------------------------------------------- distance from apparent size

    @Test
    fun `FR-DIST-01 chair distance from its pixel height`() {
        for (dTrue in listOf(1.0, 2.0, 4.0)) {
            val pxH = f * chairH / dTrue
            assertEquals("chair at $dTrue m", dTrue, Egocentric.estimateDistance(det("chair", 300.0, 100.0, 340.0, 100 + pxH), f)!!, tol)
        }
    }

    @Test
    fun `taller box is nearer, unknown or flat boxes have no distance`() {
        assertTrue(
            Egocentric.estimateDistance(det("chair", 0.0, 0.0, 40.0, 300.0), f)!! <
                Egocentric.estimateDistance(det("chair", 0.0, 0.0, 40.0, 100.0), f)!!,
        )
        assertNull(Egocentric.estimateDistance(det("unicorn", 0.0, 0.0, 40.0, 100.0), f))
        assertNull(Egocentric.estimateDistance(det("chair", 10.0, 50.0, 40.0, 50.0), f))
        assertTrue("person" in SizePriors.HEIGHTS_M)
    }

    @Test
    fun `size priors are plausible and complete`() {
        assertTrue(SizePriors.HEIGHTS_M.values.all { it > 0.02 && it < 2.5 })
        assertTrue(SizePriors.WIDTHS_M.values.all { it > 0.02 && it < 2.5 })
        assertEquals(SizePriors.HEIGHTS_M.keys, SizePriors.WIDTHS_M.keys)
        assertTrue(SizePriors.HEIGHTS_M.keys.containsAll(SizePriors.UNRELIABLE_SMALL))
    }

    @Test
    fun `every navigation class has a size prior`() {
        val names = CocoLabels.NAVIGATION.map { CocoLabels.name(it) }.toSet()
        assertEquals(names, SizePriors.HEIGHTS_M.keys)
    }

    // ------------------------------------------- clipped boxes: too close to fit in frame

    @Test
    fun `person filling the frame is bounded by the fill distance`() {
        val fillBound = f * personH / h
        val full = det("person", 250.0, 0.0, 400.0, h.toDouble())
        val got = Egocentric.estimateDistance(full, f, frameH = h)!!
        assertTrue("got $got bound $fillBound", got <= fillBound + 1e-9)
        assertTrue(got > 0)
    }

    @Test
    fun `person cut off by the frame edge is reported nearer, not further`() {
        val cutBottom = det("person", 250.0, 120.0, 400.0, h.toDouble())
        val naive = Egocentric.estimateDistance(cutBottom, f)!!
        val corrected = Egocentric.estimateDistance(cutBottom, f, frameH = h)!!
        assertTrue("naive $naive corrected $corrected", corrected < naive)
    }

    @Test
    fun `fully visible object is left alone by the edge correction`() {
        val whole = det("person", 250.0, 60.0, 400.0, 60 + f * personH / 3.0)
        assertEquals(Egocentric.estimateDistance(whole, f)!!, Egocentric.estimateDistance(whole, f, frameH = h)!!, 1e-9)
    }

    @Test
    fun `close cropped person is not mistaken for 3 m away (real webcam case)`() {
        val head = det("person", 190.0, 100.0, 450.0, 100.0 + 264)
        val got = Egocentric.estimateDistance(head, f, frameH = h)!!
        assertTrue("got $got", got < 2.5)
        assertTrue("got $got", got > 0.4 && got < 1.5)
    }

    @Test
    fun `fully visible person still uses the height estimate`() {
        val d3 = 3.0
        val p = det(
            "person", 300.0, 50.0,
            300 + f * SizePriors.WIDTHS_M.getValue("person") / d3, 50 + f * personH / d3,
        )
        assertEquals(d3, Egocentric.estimateDistance(p, f, frameH = h)!!, tol)
    }

    @Test
    fun `chair with its base hidden uses its width and lands near 2 m`() {
        val d2 = 2.0
        val cut = det(
            "chair", 300.0, 100.0,
            300 + f * SizePriors.WIDTHS_M.getValue("chair") / d2,
            100 + f * chairH / d2 * 0.35,
        )
        val naive = chairH / (chairH / d2 * 0.35)
        val got = Egocentric.estimateDistance(cut, f, frameH = h)!!
        assertTrue("naive $naive got $got", got < naive)
        assertTrue("got $got", got > 1.4 && got < 2.6)
    }

    // ---------------------------------------------- bearing: where it sits across the frame

    private fun chairAt(x1: Double, x2: Double, dist: Double) = det("chair", x1, 100.0, x2, 100 + f * chairH / dist)

    @Test
    fun `dead centre is straight ahead`() {
        val rel = Egocentric.toRelative(chairAt(w / 2.0 - 20, w / 2.0 + 20, 2.0), w, f)!!
        assertEquals(0.0, rel.lateralM, 1e-9)
        assertEquals(2.0, rel.forwardM, tol)
    }

    @Test
    fun `left of frame is the user's left, right is right, mirror images`() {
        val left = Egocentric.toRelative(chairAt(40.0, 80.0, 2.0), w, f)!!
        val right = Egocentric.toRelative(chairAt(w - 80.0, w - 40.0, 2.0), w, f)!!
        assertTrue(left.lateralM > 0)
        assertTrue(right.lateralM < 0)
        assertEquals(left.lateralM, -right.lateralM, tol)
        assertEquals(Side.LEFT, left.side)
        assertEquals(Side.RIGHT, right.side)
        assertEquals("chair", left.label)
    }

    @Test
    fun `unknown class yields no relative obstacle`() {
        assertNull(Egocentric.toRelative(det("unicorn", 40.0, 100.0, 80.0, 300.0), w, f))
    }

    @Test
    fun `same bearing further away means a larger lateral offset`() {
        val near = Egocentric.toRelative(chairAt(40.0, 80.0, 1.0), w, f)!!
        val far = Egocentric.toRelative(chairAt(40.0, 80.0, 4.0), w, f)!!
        assertTrue(kotlin.math.abs(far.lateralM) > kotlin.math.abs(near.lateralM))
    }

    // ------------------------------------------------------------- corridor filtering

    @Test
    fun `FR-OBS-01 straight ahead is in the corridor, far to the side is not`() {
        val ahead = Egocentric.toRelative(chairAt(w / 2.0 - 20, w / 2.0 + 20, 2.0), w, f)!!
        assertTrue(UserFrame.inCorridor(ahead, 0.55, 3.5))
        val farSide = Egocentric.toRelative(chairAt(0.0, 30.0, 3.0), w, f)!!
        assertTrue("lateral ${farSide.lateralM}", !UserFrame.inCorridor(farSide, 0.55, 3.5))
    }

    // ----------------------------------------------------- detections -> obstacle list

    @Test
    fun `only obstacles with a size prior survive, labels preserved`() {
        val dets = listOf(
            chairAt(w / 2.0 - 20, w / 2.0 + 20, 2.0),
            det("unicorn", 10.0, 10.0, 50.0, 200.0),
            det("bottle", 200.0, 100.0, 220.0, 100 + f * SizePriors.HEIGHTS_M.getValue("bottle") / 3.0),
            det("chair", 10.0, 10.0, 50.0, 10.0),
        )
        val obs = Egocentric.obstacles(dets, w, f)
        assertEquals(listOf("bottle", "chair"), obs.map { it.label }.sorted())
    }

    @Test
    fun `positions round-trip through relativeToUser with the user at the origin facing +Y`() {
        val chair = Egocentric.obstacles(listOf(chairAt(w / 2.0 - 20, w / 2.0 + 20, 2.0)), w, f).single()
        val back = UserFrame.relativeToUser(Vec2(0.0, 0.0), Vec2(0.0, 1.0), chair.worldPosition(), "chair")!!
        assertEquals(2.0, back.forwardM, tol)
        assertEquals(0.0, back.lateralM, 1e-9)

        val left = Egocentric.obstacles(listOf(chairAt(40.0, 80.0, 2.0)), w, f).single()
        val backLeft = UserFrame.relativeToUser(Vec2(0.0, 0.0), Vec2(0.0, 1.0), left.worldPosition(), "chair")!!
        assertTrue(backLeft.lateralM > 0)
        assertEquals(left.lateralM, backLeft.lateralM, 1e-9)
    }

    // ------------------------------------------------ FR-OBS-03 flat desk objects

    @Test
    fun `FR-OBS-03 flat desk objects are skipped by default but can be included`() {
        val kbd = det("keyboard", 300.0, 200.0, 380.0, 200 + f * SizePriors.HEIGHTS_M.getValue("keyboard") / 2.0)
        assertEquals(0, Egocentric.obstacles(listOf(kbd), w, f).size)
        assertEquals(1, Egocentric.obstacles(listOf(kbd), w, f, skipUnreliable = false).size)
        assertTrue("chair" !in SizePriors.UNRELIABLE_SMALL)
        assertTrue("person" !in SizePriors.UNRELIABLE_SMALL)
    }

    // ---------------------------------------------------------------- confidence gate

    @Test
    fun `FR-DET-03 low-confidence detections are dropped`() {
        assertEquals(0, Egocentric.obstacles(listOf(det("chair", 300.0, 100.0, 340.0, 300.0, conf = 0.10f)), w, f, minConf = 0.40f).size)
        assertEquals(1, Egocentric.obstacles(listOf(det("chair", 300.0, 100.0, 340.0, 300.0, conf = 0.9f)), w, f, minConf = 0.40f).size)
    }
}
