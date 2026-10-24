package com.example.capsight.perception

import com.example.capsight.detect.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-DIST-04: distances smoothed per object, but never late for an approaching hazard. */
class DistanceTrackerTest {

    private val tracker = DistanceTracker(TrackParams(minIou = 0.3f, alpha = 0.5, maxUnseenMs = 1_000, snapCloserFraction = 0.30))

    private fun box(label: String, left: Float, top: Float = 100f, size: Float = 100f) =
        Detection(if (label == "person") 0 else 56, label, 0.9f, left, top, left + size, top + size)

    private fun obs(d: Detection, m: Double) = DistanceTracker.Observation(d, m)

    @Test
    fun `a new object starts at its raw distance`() {
        val out = tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0)
        assertEquals(2.0, out.single().distanceM, 1e-9)
        assertEquals(1, tracker.size)
    }

    @Test
    fun `the same object is averaged with alpha 0_5`() {
        val first = tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0).single()
        val second = tracker.update(listOf(obs(box("chair", 105f), 2.4)), 100).single()
        assertEquals(first.trackId, second.trackId)
        assertEquals(2.2, second.distanceM, 1e-9)
        // Moving further is always smoothed.
        assertEquals(2.6, tracker.update(listOf(obs(box("chair", 108f), 3.0)), 200).single().distanceM, 1e-9)
    }

    @Test
    fun `a small approach is smoothed, a jump nearer by more than 30 percent is taken at once`() {
        tracker.update(listOf(obs(box("person", 100f), 3.0)), 0)
        // 2.2 m is 26.7% nearer: still smoothed.
        assertEquals(2.6, tracker.update(listOf(obs(box("person", 102f), 2.2)), 100).single().distanceM, 1e-9)
        // 1.5 m is 42% nearer than 2.6: safety rule, raw value immediately.
        assertEquals(1.5, tracker.update(listOf(obs(box("person", 104f), 1.5)), 200).single().distanceM, 1e-9)
    }

    @Test
    fun `different label or too little overlap starts a new track`() {
        val chair = tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0).single()
        val person = tracker.update(listOf(obs(box("person", 100f), 4.0)), 100).single()
        assertNotEquals(chair.trackId, person.trackId)
        assertEquals(4.0, person.distanceM, 1e-9)
        // IoU of boxes 100 px wide shifted by 60 px = 40/160 = 0.25 < 0.3.
        val moved = tracker.update(listOf(obs(box("chair", 160f), 3.0)), 200).single()
        assertNotEquals(chair.trackId, moved.trackId)
        assertEquals(3.0, moved.distanceM, 1e-9)
    }

    @Test
    fun `greedy matching gives each track to the best-overlapping detection`() {
        val a = tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0).single()
        val b = tracker.update(listOf(obs(box("chair", 300f), 4.0)), 0).last()
        // Next frame, listed in the other order, each slightly moved.
        val out = tracker.update(listOf(obs(box("chair", 295f), 4.2), obs(box("chair", 104f), 2.2)), 100)
        assertEquals(b.trackId, out[0].trackId)
        assertEquals(a.trackId, out[1].trackId)
        assertEquals(4.1, out[0].distanceM, 1e-9)
        assertEquals(2.1, out[1].distanceM, 1e-9)
    }

    @Test
    fun `two detections cannot share one track`() {
        tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0)
        val out = tracker.update(listOf(obs(box("chair", 102f), 2.4), obs(box("chair", 110f), 5.0)), 100)
        assertNotEquals(out[0].trackId, out[1].trackId)
        assertEquals("best overlap keeps the track", 2.2, out[0].distanceM, 1e-9)
        assertEquals("the other starts fresh", 5.0, out[1].distanceM, 1e-9)
    }

    @Test
    fun `a track unseen for more than 1 s is forgotten`() {
        val first = tracker.update(listOf(obs(box("chair", 100f), 2.0)), 0).single()
        assertEquals(first.trackId, tracker.update(listOf(obs(box("chair", 100f), 2.4)), 1_000).single().trackId)
        tracker.update(emptyList(), 2_001)
        assertEquals(0, tracker.size)
        val again = tracker.update(listOf(obs(box("chair", 100f), 3.0)), 2_002).single()
        assertNotEquals(first.trackId, again.trackId)
        assertEquals(3.0, again.distanceM, 1e-9)
    }

    @Test
    fun `perception smooths once per detector result and scales the sideways offset too`() {
        val params = PerceptionParams(
            fovDeg = 65.0, minConfidence = 0.4f, skipUnreliable = true,
            corridorHalfWidthM = 0.55, warnDistanceM = 2.5, stopDistanceM = 1.2,
        )
        val f = Egocentric.focalFromFov(65.0, 640)
        fun chairAt(dist: Double, centreX: Double): Detection {
            val h = f * 0.9 / dist
            val w = f * 0.5 / dist
            return Detection(56, "chair", 0.9f, (centreX - w / 2).toFloat(), 100f, (centreX + w / 2).toFloat(), (100 + h).toFloat())
        }
        val sp = SmoothedPerception()
        sp.perceive(1L, listOf(chairAt(2.0, 400.0)), 640, 480, params)
        val second = sp.perceive(2L, listOf(chairAt(2.4, 400.0)), 640, 480, params).single()
        val r = second.relative!!
        assertEquals(2.2, r.forwardM, 1e-3)
        assertEquals(2.4, second.rawForwardM!!, 1e-3)
        // Same ray: lateral/forward unchanged by smoothing.
        assertEquals(-(400.0 - 320.0) / f, r.lateralM / r.forwardM, 1e-6)
        // The 10 Hz loop asks again for the same result: no second smoothing step.
        assertEquals(2.2, sp.perceive(2L, listOf(chairAt(2.4, 400.0)), 640, 480, params).single().relative!!.forwardM, 1e-3)
        assertTrue(second.trackId != null)
    }
}
