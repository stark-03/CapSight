package com.example.capsight.perception

import com.example.capsight.detect.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.tan

/** FR-DIST-02 ground-plane distance and FR-DIST-03 fusion (nearer wins). */
class GroundPlaneTest {

    private val w = 640
    private val h = 480
    private val hFov = 65.0
    private val f = Egocentric.focalFromFov(hFov, w)
    private val camH = 1.30

    private fun pose(pitch: Double, roll: Double = 0.0) = GroundParams(camH, Tilt(pitch, roll))

    /** Image row where the floor at [dist] m appears with the camera pitched [pitch] deg down. */
    private fun floorRow(dist: Double, pitch: Double): Double =
        h / 2.0 + f * tan(atan(camH / dist) - Math.toRadians(pitch))

    private fun chairStandingAt(sizeDist: Double, bottomRow: Double, centreX: Double = 320.0): Detection {
        val hPx = f * SizePriors.HEIGHTS_M.getValue("chair") / sizeDist
        val wPx = f * SizePriors.WIDTHS_M.getValue("chair") / sizeDist
        return Detection(
            56, "chair", 0.9f,
            (centreX - wPx / 2).toFloat(), (bottomRow - hPx).toFloat(), (centreX + wPx / 2).toFloat(), bottomRow.toFloat(),
        )
    }

    @Test
    fun `vertical field of view gives the same focal length as the horizontal one`() {
        val vFov = GroundPlane.verticalFovDeg(hFov, w, h)
        assertTrue(vFov < hFov)
        assertEquals(f, (h / 2.0) / tan(Math.toRadians(vFov) / 2.0), 1e-9)
        assertEquals(0.0, GroundPlane.angleBelowCentreDeg(240.0, h, vFov), 1e-9)
        assertEquals(vFov / 2, GroundPlane.angleBelowCentreDeg(480.0, h, vFov), 1e-9)
        assertEquals(-vFov / 2, GroundPlane.angleBelowCentreDeg(0.0, h, vFov), 1e-9)
    }

    @Test
    fun `floor distance with a level camera and with the camera tilted down`() {
        for (pitch in listOf(0.0, 10.0, 25.0)) {
            for (dist in listOf(1.5, 3.0, 5.0)) {
                val row = floorRow(dist, pitch)
                if (row >= h - SizePriors.EDGE_PX) continue // not in view at this tilt
                val d = chairStandingAt(dist, row)
                assertEquals("pitch $pitch dist $dist", dist, GroundPlane.distance(d, w, h, hFov, pose(pitch))!!, 1e-3)
            }
        }
    }

    @Test
    fun `no floor distance when the geometry does not apply`() {
        val ok = chairStandingAt(3.0, floorRow(3.0, 10.0))
        assertTrue(GroundPlane.distance(ok, w, h, hFov, pose(10.0)) != null)
        assertNull("no tilt sensor", GroundPlane.distance(ok, w, h, hFov, null))
        assertNull("roll 30 deg", GroundPlane.distance(ok, w, h, hFov, pose(10.0, roll = 30.0)))
        assertNull("roll -25 deg", GroundPlane.distance(ok, w, h, hFov, pose(10.0, roll = -25.0)))
        assertTrue("roll 24 deg is accepted", GroundPlane.distance(ok, w, h, hFov, pose(10.0, roll = 24.0)) != null)
        assertNull("bottom touches the frame edge", GroundPlane.distance(chairStandingAt(1.0, 479.0), w, h, hFov, pose(10.0)))
        // Box bottom above the horizon (a TV on the wall, camera level).
        assertNull("above horizon", GroundPlane.distance(chairStandingAt(3.0, 200.0), w, h, hFov, pose(0.0)))
        // 1.5 deg below the horizon: too shallow to trust.
        val shallow = h / 2.0 + f * tan(Math.toRadians(1.5))
        assertNull("ray within 2 deg of horizon", GroundPlane.distance(chairStandingAt(3.0, shallow), w, h, hFov, pose(0.0)))
        val steepEnough = h / 2.0 + f * tan(Math.toRadians(2.5))
        assertTrue(GroundPlane.distance(chairStandingAt(3.0, steepEnough), w, h, hFov, pose(0.0)) != null)
    }

    private val params = PerceptionParams(
        fovDeg = hFov, minConfidence = 0.4f, skipUnreliable = true,
        corridorHalfWidthM = 0.55, warnDistanceM = 2.5, stopDistanceM = 1.2,
    )

    @Test
    fun `fusion takes the nearer estimate and says which`() {
        // Size says 3.0 m, the floor says 2.0 m: the floor wins (the chair is nearer than its size suggests).
        val nearFloor = chairStandingAt(3.0, floorRow(2.0, 10.0), centreX = 400.0)
        val a = Perception.perceive(listOf(nearFloor), w, h, params.copy(ground = pose(10.0))).single()
        assertEquals(DistanceSource.FUSED_FLOOR, a.distanceSource)
        assertEquals(2.0, a.relative!!.forwardM, 1e-3)
        assertEquals(3.0, a.sizeM!!, 1e-3)
        assertEquals(2.0, a.floorM!!, 1e-3)
        assertEquals(Threat.WARN, a.threat)
        // Same bearing as the size estimate: lateral scales with forward.
        assertEquals(-(400.0 - 320.0) / f, a.relative!!.lateralM / a.relative!!.forwardM, 1e-6)

        // Size says 2.0 m, the floor says 4.0 m: size wins.
        val farFloor = chairStandingAt(2.0, floorRow(4.0, 10.0))
        val b = Perception.perceive(listOf(farFloor), w, h, params.copy(ground = pose(10.0))).single()
        assertEquals(DistanceSource.FUSED_SIZE, b.distanceSource)
        assertEquals(2.0, b.relative!!.forwardM, 1e-3)
    }

    @Test
    fun `without a tilt, or with too much roll, size-based distance is used alone`() {
        val d = chairStandingAt(3.0, floorRow(2.0, 10.0))
        val noTilt = Perception.perceive(listOf(d), w, h, params).single()
        assertEquals(DistanceSource.SIZE, noTilt.distanceSource)
        assertNull(noTilt.floorM)
        assertEquals(3.0, noTilt.relative!!.forwardM, 1e-3)
        val rolled = Perception.perceive(listOf(d), w, h, params.copy(ground = pose(10.0, roll = 40.0))).single()
        assertEquals(DistanceSource.SIZE, rolled.distanceSource)
        assertEquals(3.0, rolled.relative!!.forwardM, 1e-3)
    }
}
