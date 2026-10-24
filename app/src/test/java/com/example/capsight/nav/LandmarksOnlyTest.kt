package com.example.capsight.nav

import com.example.capsight.ble.Landmark
import com.example.capsight.ble.LandmarkType
import com.example.capsight.depth.DepthCue
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import com.example.capsight.voice.SpeechArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md §5.1 landmarks-only mode (no camera by choice): guidance stays silent - no camera-lost,
 * fail-safe, obstacle, stairs or "Path clear." phrases - while BLE landmark phrases still reach the
 * speaker with their normal priorities and cooldowns. Camera modes keep their fail-safes.
 */
class LandmarksOnlyTest {

    private val cfg = GuidanceConfig()
    private val chairAt1m = RelativeObstacle("chair", 1.0, 0.0, 1.0)
    private val stairsAt2m = RelativeObstacle("stairs", 2.0, 0.0, 2.0)
    private val depthNear = DepthCue(centre = true, left = true, right = true)

    private val cameraPhrases = setOf(
        Phrases.CAMERA_LOST, Phrases.FAIL_SAFE, Phrases.PATH_CLEAR, Phrases.PATH_BLOCKED, Phrases.STAIRS,
        Phrases.CAMERA_CONNECTED,
    )

    @Test
    fun `policy is silent with the camera off whatever else is set`() {
        val inputs = listOf(
            GuidanceInput(cameraOff = true),
            GuidanceInput(cameraOff = true, cameraLost = true),
            GuidanceInput(cameraOff = true, systemFault = true),
            GuidanceInput(cameraOff = true, perceptionReady = false),
            GuidanceInput(cameraOff = true, obstacles = listOf(chairAt1m), stairs = listOf(stairsAt2m)),
        )
        for (inp in inputs) {
            val d = Policy.decide(inp, cfg.decision)
            assertFalse("$inp -> ${d.message}", d.speak)
            assertEquals(Priority.IDLE, d.priority)
            assertEquals(Policy.CAMERA_OFF_REASON, d.reason)
        }
    }

    @Test
    fun `guidance with the camera off never speaks over many ticks`() {
        val g = Guidance(cfg)
        for (t in 0 until 100) {
            val d = g.tick(
                fresh = listOf(chairAt1m),
                nowMs = t * 100L,
                freshStairs = listOf(stairsAt2m),
                cameraLost = true,
                systemFault = true,
                perceptionReady = t % 2 == 0,
                depth = depthNear,
                cameraOff = true,
            )
            assertFalse("tick $t said ${d.message}", d.speak)
            assertFalse(d.message in cameraPhrases)
            assertTrue(g.heldObstacles.isEmpty())
            assertFalse(g.depthStopActive)
        }
    }

    @Test
    fun `switching the camera off drops held obstacles at once`() {
        val g = Guidance(cfg)
        assertEquals(Priority.STOP, g.tick(listOf(chairAt1m), 0).priority)
        assertFalse(g.tick(emptyList(), 100, cameraOff = true).speak)
        // Back on with nothing seen: the chair is not replayed from the hold.
        assertEquals(Phrases.PATH_CLEAR, g.tick(emptyList(), 200).message)
    }

    @Test
    fun `camera modes keep their fail-safes`() {
        val lost = Guidance(cfg).tick(emptyList(), 0, cameraLost = true)
        assertEquals(Priority.STOP, lost.priority)
        assertEquals(Phrases.CAMERA_LOST, lost.message)
        val fault = Guidance(cfg).tick(emptyList(), 0, systemFault = true)
        assertEquals(Phrases.FAIL_SAFE, fault.message)
    }

    /** What GuidanceLoop + the landmark scanner do with one speaker slot, ticking at 10 Hz. */
    @Test
    fun `landmark phrases get through while guidance stays silent`() {
        val g = Guidance(cfg)
        val arbiter = SpeechArbiter()
        val heard = mutableListOf<String>()
        val door = Landmark(LandmarkType.DOOR, "Room 304")
        val stairs = Landmark(LandmarkType.STAIRS, "")
        val cooldown = cfg.decision.phraseCooldownMs
        for (t in 0 until 50) {
            val now = t * 100L
            val d = g.tick(listOf(chairAt1m), now, cameraLost = true, systemFault = true, cameraOff = true)
            if (d.speak) arbiter.offer(d.message, d.priority, d.cooldownMs, now)
            when (t) {
                5 -> assertTrue(arbiter.offer(door.phrase, door.priority, cooldown, now))
                20 -> assertTrue(arbiter.offer(stairs.phrase, stairs.priority, cooldown, now))
                // Same door again within the phrase cooldown: suppressed as usual.
                21 -> assertFalse(arbiter.offer(door.phrase, door.priority, cooldown, now))
            }
            arbiter.takeNext(now)?.let { heard += it.text }
        }
        assertEquals(listOf("Room 304 door nearby.", Phrases.STAIRS_NEARBY), heard)
        assertEquals(Priority.STAIRS, stairs.priority)
        assertEquals(Priority.INFO, door.priority)
        assertNull(arbiter.pending)
    }
}
