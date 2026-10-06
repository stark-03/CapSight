package com.example.capsight.perception

import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-OBS-01, FR-DIST-01: detections become obstacles with distance and threat level. */
class PerceptionTest {

    private val w = 640
    private val h = 480
    private val params = PerceptionParams(
        fovDeg = 65.0, minConfidence = 0.40f, skipUnreliable = true,
        corridorHalfWidthM = 0.55, warnDistanceM = 2.5, stopDistanceM = 1.2,
    )
    private val f = Egocentric.focalFromFov(params.fovDeg, w)

    private fun centredChair(dist: Double, conf: Float = 0.9f): Detection {
        val hPx = f * SizePriors.HEIGHTS_M.getValue("chair") / dist
        val wPx = f * SizePriors.WIDTHS_M.getValue("chair") / dist
        return Detection(56, "chair", conf, (w / 2 - wPx / 2).toFloat(), 100f, (w / 2 + wPx / 2).toFloat(), (100 + hPx).toFloat())
    }

    private fun one(d: Detection) = Perception.perceive(listOf(d), w, h, params).single()

    @Test
    fun `chair ahead at 1 m is a stop, at 2 m a warning, at 3 m nothing`() {
        assertEquals(Threat.STOP, one(centredChair(1.0)).threat)
        assertEquals(Threat.WARN, one(centredChair(2.0)).threat)
        val far = one(centredChair(3.0))
        assertEquals(Threat.NONE, far.threat)
        assertEquals(3.0, far.relative!!.forwardM, 1e-4)
        assertFalse(far.inCorridor)
    }

    @Test
    fun `objects without a distance are kept for display but carry no threat`() {
        val kbd = Detection(CocoLabels.NAMES.indexOf("keyboard"), "keyboard", 0.9f, 300f, 300f, 380f, 310f)
        val weak = centredChair(1.0, conf = 0.2f)
        val out = Perception.perceive(listOf(kbd, weak), w, h, params)
        assertEquals(2, out.size)
        out.forEach {
            assertNull(it.relative)
            assertEquals(Threat.NONE, it.threat)
        }
    }

    @Test
    fun `off-centre chair close by is outside the corridor`() {
        // 1 m away, fully inside the 480-px frame, centred 20 px from the left edge.
        val hPx = f * 0.9 / 1.0
        val side = Detection(56, "chair", 0.9f, 0f, 20f, 40f, (20 + hPx).toFloat())
        val p = one(side)
        assertTrue(p.relative!!.lateralM > params.corridorHalfWidthM)
        assertEquals(Threat.NONE, p.threat)
    }

    @Test
    fun `wider field of view makes the same box nearer`() {
        val d = centredChair(2.0)
        val narrow = Perception.perceive(listOf(d), w, h, params.copy(fovDeg = 54.0)).single().relative!!.forwardM
        val wide = Perception.perceive(listOf(d), w, h, params.copy(fovDeg = 75.0)).single().relative!!.forwardM
        assertTrue("narrow $narrow wide $wide", wide < narrow)
    }

    @Test
    fun `no frame size means nothing to perceive`() {
        assertTrue(Perception.perceive(listOf(centredChair(1.0)), 0, 0, params).isEmpty())
    }
}
