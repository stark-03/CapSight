package com.example.capsight.nav

import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.Detection
import com.example.capsight.perception.Egocentric
import com.example.capsight.perception.SizePriors
import com.example.capsight.voice.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-OBS-02, FR-NAV-01: port of the "GuidanceLoop wired for first-person" and "dropout hold"
 * sections of reference/tests/test_egocentric.py. Detections go through the real perception code.
 */
class GuidanceTest {

    private val w = 640
    private val h = 480
    private val f = Egocentric.focalFromFov(65.0, w)
    private val chairH = SizePriors.HEIGHTS_M.getValue("chair")
    private val chairW = SizePriors.WIDTHS_M.getValue("chair")

    // Python GCFG: stop 1.5 m, warn 3.5 m.
    private fun guidance(holdTicks: Int = 6) = Guidance(
        GuidanceConfig(decision = DecisionConfig(stopDistanceM = 1.5, warnDistanceM = 3.5), holdTicks = holdTicks),
    )

    private fun det(label: String, x1: Double, y1: Double, x2: Double, y2: Double) =
        Detection(CocoLabels.NAMES.indexOf(label), label, 0.9f, x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())

    private fun measure(dets: List<Detection>) = Egocentric.obstacles(dets, w, f, minConf = 0.40f, frameH = h)

    private fun chairBox(dist: Double): Detection {
        val bh = f * chairH / dist
        val bw = f * chairW / dist
        return det("chair", w / 2 - bw / 2, 150.0, w / 2 + bw / 2, 150 + bh)
    }

    private fun once(dets: List<Detection>) = guidance().tick(measure(dets), nowMs = 0)

    // ---------------------------------------------------------- wired for first-person

    @Test
    fun `a chair at 1 m is a named stop`() {
        val d = once(listOf(det("chair", w / 2 - 30.0, 100.0, w / 2 + 30.0, 100 + f * chairH / 1.0)))
        assertEquals(Priority.STOP, d.priority)
        assertTrue(d.message, "Chair" in d.message)
    }

    @Test
    fun `a chair at 3 m off to the left is an avoid or clear, not a stop`() {
        val d = once(listOf(det("chair", 120.0, 100.0, 180.0, 100 + f * chairH / 3.0)))
        assertTrue("${d.priority} ${d.message}", d.priority == Priority.AVOID || d.priority == Priority.CLEAR)
    }

    @Test
    fun `an empty room is all clear`() {
        assertEquals("Path clear.", once(emptyList()).message)
    }

    @Test
    fun `a person in the way is an obstacle and is named`() {
        val d = once(listOf(det("person", w / 2 - 40.0, 50.0, w / 2 + 40.0, 50 + f * 1.70 / 1.2)))
        assertTrue(d.message, "Person" in d.message)
    }

    // ------------------------------------------- dropout hold: no false all-clear

    /** Walk 3.5 m -> 0.5 m, dropping the detection on the given tick indices. */
    private fun walk(dropAt: Set<Int>, holdTicks: Int): List<String> {
        val g = guidance(holdTicks)
        val msgs = mutableListOf<String>()
        var d = 3.5
        var i = 0
        while (d > 0.5) {
            val dets = if (i in dropAt) emptyList() else listOf(chairBox(d))
            msgs += g.tick(measure(dets), nowMs = i * 100L).message
            d -= 0.12
            i++
        }
        return msgs
    }

    private val drops = setOf(9, 14, 20, 21) // tick 20 is about 1.1 m away
    private fun flips(ms: List<String>) = ms.zipWithNext().count { (a, b) -> a != b }

    @Test
    fun `FR-OBS-02 without a hold a dropout causes a false all-clear`() {
        assertTrue(walk(drops, holdTicks = 0).drop(18).contains("Path clear."))
    }

    @Test
    fun `FR-OBS-02 with a hold there is no all-clear once the chair is close`() {
        val withHold = walk(drops, holdTicks = 6)
        assertFalse(withHold.drop(15).toString(), withHold.drop(15).contains("Path clear."))
    }

    @Test
    fun `the hold reduces message changes`() {
        assertTrue(flips(walk(drops, 6)) < flips(walk(drops, 0)))
    }

    @Test
    fun `a clean walk gives the natural clear - avoid - stop progression`() {
        val clean = walk(emptySet(), 6)
        assertTrue("got ${flips(clean)}: $clean", flips(clean) <= 3)
        assertTrue(clean.last(), clean.last().startsWith("Stop"))
    }

    @Test
    fun `the hold expires - an obstacle really gone does clear`() {
        val g = guidance(holdTicks = 4)
        val near = measure(listOf(chairBox(1.0)))
        assertTrue("Chair" in g.tick(near, 0).message)
        repeat(3) { g.tick(emptyList(), 100L * (it + 1)) }
        assertTrue("Chair" in g.tick(emptyList(), 400).message)
        var last = ""
        repeat(5) { last = g.tick(emptyList(), 500L + it * 100).message }
        assertEquals("Path clear.", last)
    }

    @Test
    fun `FR-DET-04 stairs ahead give the P1 caution and are held through a dropout`() {
        val g = guidance()
        val stairs = listOf(com.example.capsight.perception.RelativeObstacle("stairs", 3.0, 0.0, 3.0))
        assertEquals("Caution. Stairs ahead.", g.tick(emptyList(), 0, freshStairs = stairs).message)
        // One missed frame: still a caution, never "Path clear."
        assertEquals("Caution. Stairs ahead.", g.tick(emptyList(), 100).message)
    }

    @Test
    fun `a pothole in the corridor is avoided like any obstacle`() {
        val pothole = listOf(com.example.capsight.perception.RelativeObstacle("pothole", 2.0, -0.2, 2.0))
        assertEquals("Pothole ahead. Move left.", guidance().tick(pothole, 0).message)
    }

    @Test
    fun `camera lost wins even while an obstacle is held`() {
        val g = guidance()
        g.tick(measure(listOf(chairBox(2.0))), 0)
        assertEquals(Priority.STOP, g.tick(emptyList(), 100, cameraLost = true).priority)
    }
}
