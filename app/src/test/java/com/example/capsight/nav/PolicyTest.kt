package com.example.capsight.nav

import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import com.example.capsight.perception.UserFrame
import com.example.capsight.perception.Vec2
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-NAV-01: port of reference/tests/test_guidance.py (first-person policy; the BLE, calibration
 * and path-deviation cases belong to the removed ceiling mode). Thresholds as in the Python test
 * (stop 1.5 m, warn 3.5 m). Objects are always named, as first-person mode did with
 * speak_object_names=True, and the clear phrase is the SRS "Path clear.".
 */
class PolicyTest {

    private val cfg = DecisionConfig(stopDistanceM = 1.5, warnDistanceM = 3.5, stairsWarnM = 4.0, corridorHalfWidthM = 0.55)

    /** A floor position (x right, y ahead) as seen by a user at the origin walking +Y. */
    private fun at(label: String, x: Double, y: Double): RelativeObstacle =
        UserFrame.relativeToUser(Vec2(0.0, 0.0), Vec2(0.0, 1.0), Vec2(x, y), label)!!

    private fun decide(
        obstacles: List<RelativeObstacle> = emptyList(),
        stairs: List<RelativeObstacle> = emptyList(),
        c: DecisionConfig = cfg,
    ) = Policy.decide(GuidanceInput(obstacles = obstacles, stairs = stairs), c)

    // --------------------------------------- obstacle directly ahead -> "Stop. X ahead."

    @Test
    fun `chair inside the stop range is a stop, named`() {
        val d = decide(listOf(at("chair", 0.0, 1.2)))
        assertEquals(Priority.STOP, d.priority)
        assertEquals("Stop. Chair ahead.", d.message)
    }

    @Test
    fun `chair exactly at the stop distance still stops`() {
        assertEquals(Priority.STOP, decide(listOf(at("chair", 0.0, 1.5))).priority)
    }

    // --------------------------------------------------- warn early, then guide around

    @Test
    fun `chair at 3 m is an early warning that names a direction`() {
        val d = decide(listOf(at("chair", 0.0, 3.0)))
        assertEquals(Priority.AVOID, d.priority)
        assertTrue(d.message, d.message.endsWith("Move left.") || d.message.endsWith("Move right."))
    }

    @Test
    fun `obstacle on the right - move left, on the left - move right`() {
        assertEquals("Chair ahead. Move left.", decide(listOf(at("chair", 0.4, 2.5))).message)
        assertEquals("Chair ahead. Move right.", decide(listOf(at("chair", -0.4, 2.5))).message)
    }

    @Test
    fun `beyond the warning range, far to the side, or behind - no warning`() {
        assertEquals(Priority.CLEAR, decide(listOf(at("chair", 0.0, 5.0))).priority)
        assertEquals(Priority.CLEAR, decide(listOf(at("chair", 2.5, 1.0))).priority)
        assertEquals(Priority.CLEAR, decide(listOf(at("chair", 0.0, -2.0))).priority)
    }

    // ------------------------------------------------------------------------ stairs

    @Test
    fun `stairs warn, outrank an obstacle at the same distance, but not an imminent collision`() {
        val s = listOf(at("stairs", 0.0, 3.0))
        assertEquals(Priority.STAIRS, decide(stairs = s).priority)
        assertEquals("Caution. Stairs ahead.", decide(stairs = s).message)
        assertEquals(Priority.STAIRS, decide(listOf(at("chair", 0.0, 3.0)), s).priority)
        assertEquals(Priority.STOP, decide(listOf(at("chair", 0.0, 1.0)), s).priority)
    }

    // ------------------------------------------------------------- a person is an obstacle

    @Test
    fun `another person at 1_2 m is a stop`() {
        val d = decide(listOf(at("person", 0.0, 1.2)))
        assertEquals(Priority.STOP, d.priority)
        assertEquals("Stop. Person ahead.", d.message)
    }

    // ---------------------------------------------------------------------- all clear

    @Test
    fun `empty room is Path clear, repeated slowly`() {
        val d = decide()
        assertEquals(Priority.CLEAR, d.priority)
        assertEquals("Path clear.", d.message)
        assertTrue(d.cooldownMs >= 6_000)
    }

    @Test
    fun `stop repeats urgently - short cooldown`() {
        assertTrue(decide(listOf(at("chair", 0.0, 1.0))).cooldownMs <= 2_000)
    }

    // ---------------------------------------------------------------- naming obstacles

    @Test
    fun `names the nearest obstacle and reads multi-word labels naturally`() {
        assertTrue(decide(listOf(at("chair", 0.0, 3.0), at("person", 0.0, 1.8))).message.startsWith("Person"))
        assertTrue(decide(listOf(at("potted plant", 0.0, 2.5))).message.startsWith("Potted plant"))
        val stop = decide(listOf(at("couch", 0.0, 1.0)))
        assertEquals("Stop. Couch ahead.", stop.message)
    }

    // ------------------------------------------------------- new rules (not in Python)

    @Test
    fun `FR-RX-03 camera lost outranks everything`() {
        val d = Policy.decide(GuidanceInput(obstacles = listOf(at("chair", 0.0, 1.0)), cameraLost = true), cfg)
        assertEquals(Priority.STOP, d.priority)
        assertEquals(Phrases.CAMERA_LOST, d.message)
    }

    @Test
    fun `FR-NAV-05 a detector fault says Stop rather than nothing`() {
        val d = Policy.decide(GuidanceInput(systemFault = true), cfg)
        assertEquals(Priority.STOP, d.priority)
        assertEquals("Stop.", d.message)
    }

    @Test
    fun `no fresh perception is silence, never a false Path clear`() {
        val d = Policy.decide(GuidanceInput(perceptionReady = false), cfg)
        assertFalse(d.speak)
        assertEquals(Priority.IDLE, d.priority)
    }

    @Test
    fun `P3 centre and both sides blocked - Path blocked`() {
        val d = decide(listOf(at("chair", 0.0, 2.0), at("person", -1.0, 1.8), at("bench", 1.0, 2.2)))
        assertEquals(Priority.BLOCKED, d.priority)
        assertEquals("Path blocked. Stop and turn.", d.message)
    }

    @Test
    fun `P3 does not hide a stop`() {
        val d = decide(listOf(at("chair", 0.0, 1.0), at("person", -1.0, 1.8), at("bench", 1.0, 2.2)))
        assertEquals(Priority.STOP, d.priority)
    }

    @Test
    fun `FR-PATH-02 one side blocked - steer to the other`() {
        // Chair dead ahead, person in the right lane: go left even though free_side would say right.
        val d = decide(listOf(at("chair", 0.0, 2.5), at("person", 1.0, 2.0)))
        assertEquals("Chair ahead. Move left.", d.message)
    }

    @Test
    fun `a side chosen with hysteresis is honoured`() {
        val d = Policy.decide(GuidanceInput(obstacles = listOf(at("chair", 0.4, 2.5)), steerSide = Side.RIGHT), cfg)
        assertEquals("Chair ahead. Move right.", d.message)
    }

    @Test
    fun `CLAUDE_md defaults - stop 1_2 m, warn 2_5 m`() {
        val defaults = DecisionConfig()
        assertEquals(Priority.AVOID, decide(listOf(at("chair", 0.0, 1.3)), c = defaults).priority)
        assertEquals(Priority.STOP, decide(listOf(at("chair", 0.0, 1.2)), c = defaults).priority)
        assertEquals(Priority.CLEAR, decide(listOf(at("chair", 0.0, 2.6)), c = defaults).priority)
    }
}
