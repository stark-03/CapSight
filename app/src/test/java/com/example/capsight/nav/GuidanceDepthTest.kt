package com.example.capsight.nav

import com.example.capsight.depth.DepthCue
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * FR-OBS-04 / FR-PATH-03: the depth cue reaches the policy as unlabeled obstacles - a centre one
 * gives "Stop. Obstacle ahead." unless YOLO already explains it, side ones block that side for the
 * side chooser - through the normal priority, hold and hysteresis. Defaults: stop 1.2 m, warn 2.5 m,
 * corridor half-width 0.55 m, hold 6 ticks.
 */
class GuidanceDepthTest {

    private val centre = DepthCue(centre = true)

    private fun ob(label: String, forward: Double, lateral: Double = 0.0) =
        RelativeObstacle(label, forward, lateral, hypot(forward, lateral))

    @Test
    fun `sustained near centre with nothing detected - Stop, Obstacle ahead`() {
        val g = Guidance(GuidanceConfig())
        val d = g.tick(emptyList(), nowMs = 0, depth = centre)
        assertEquals(Priority.STOP, d.priority)
        assertEquals("Stop. Obstacle ahead.", d.message)
        assertEquals(Phrases.stop(DepthCue.LABEL), d.message)
        assertTrue(g.depthStopActive)
        assertFalse(g.depthExplained)
    }

    @Test
    fun `a YOLO obstacle held in the corridor within the stop distance explains it`() {
        val g = Guidance(GuidanceConfig())
        val d = g.tick(listOf(ob("chair", 1.0, 0.2)), nowMs = 0, depth = centre)
        assertEquals("Stop. Chair ahead.", d.message)
        assertTrue(g.depthExplained)
        assertFalse(g.depthStopActive)
        // The chair is missed for a frame: still held, so still explained - no change of wording.
        assertEquals("Stop. Chair ahead.", g.tick(emptyList(), nowMs = 100, depth = centre).message)
        assertFalse(g.depthStopActive)
    }

    @Test
    fun `stairs within the stop distance explain it too - the stairs warning stays`() {
        val g = Guidance(GuidanceConfig())
        val d = g.tick(emptyList(), nowMs = 0, freshStairs = listOf(ob("stairs", 1.0)), depth = centre)
        assertEquals(Priority.STAIRS, d.priority)
    }

    @Test
    fun `a detection farther than the stop distance or off to the side does not explain it`() {
        val far = Guidance(GuidanceConfig()).tick(listOf(ob("chair", 2.0)), nowMs = 0, depth = centre)
        assertEquals("Stop. Obstacle ahead.", far.message)
        val aside = Guidance(GuidanceConfig()).tick(listOf(ob("chair", 1.0, 1.0)), nowMs = 0, depth = centre)
        assertEquals("Stop. Obstacle ahead.", aside.message)
    }

    @Test
    fun `cleared cue is held for the hold ticks, then path clear`() {
        val g = Guidance(GuidanceConfig(holdTicks = 6))
        g.tick(emptyList(), nowMs = 0, depth = centre)
        val msgs = (1..7).map { g.tick(emptyList(), nowMs = it * 100L).message }
        assertEquals(List(6) { "Stop. Obstacle ahead." } + Phrases.PATH_CLEAR, msgs)
    }

    @Test
    fun `depth side obstacles never replace a held YOLO obstacle during a dropout`() {
        val g = Guidance(GuidanceConfig())
        val wallRight = DepthCue(right = true)
        assertEquals("Chair ahead. Move left.", g.tick(listOf(ob("chair", 2.0)), nowMs = 0, depth = wallRight).message)
        // The detector misses the chair; the wall is still seen. Never "Path clear." here.
        assertEquals("Chair ahead. Move left.", g.tick(emptyList(), nowMs = 100, depth = wallRight).message)
    }

    @Test
    fun `FR-PATH-03 a near side wall stops Move into it`() {
        val chair = listOf(ob("chair", 2.0))
        assertEquals("Chair ahead. Move right.", Guidance(GuidanceConfig()).tick(chair, nowMs = 0).message)
        assertEquals(
            "Chair ahead. Move left.",
            Guidance(GuidanceConfig()).tick(chair, nowMs = 0, depth = DepthCue(right = true)).message,
        )
        assertEquals(
            "Chair ahead. Move right.",
            Guidance(GuidanceConfig()).tick(chair, nowMs = 0, depth = DepthCue(left = true)).message,
        )
        val both = Guidance(GuidanceConfig()).tick(chair, nowMs = 0, depth = DepthCue(left = true, right = true))
        assertEquals(Priority.BLOCKED, both.priority)
        assertEquals(Phrases.PATH_BLOCKED, both.message)
    }

    @Test
    fun `side chooser switches away at once when depth blocks the chosen side`() {
        val g = Guidance(GuidanceConfig())
        val chair = listOf(ob("chair", 2.0))
        assertEquals("Chair ahead. Move right.", g.tick(chair, nowMs = 0).message)
        // Within the 2 s side hold, but the right side is now a near wall.
        assertEquals("Chair ahead. Move left.", g.tick(chair, nowMs = 300, depth = DepthCue(right = true)).message)
    }

    @Test
    fun `side cue alone with a clear centre says path clear`() {
        val d = Guidance(GuidanceConfig()).tick(emptyList(), nowMs = 0, depth = DepthCue(left = true, right = true))
        assertEquals(Phrases.PATH_CLEAR, d.message)
    }

    @Test
    fun `side obstacles land in their side lane`() {
        val half = 0.55
        val zones = ZoneAnalyzer.analyze(
            listOf(
                ob(DepthCue.LABEL, 1.2, ZoneAnalyzer.sideLaneCentreM(half, Side.LEFT)),
                ob(DepthCue.LABEL, 1.2, ZoneAnalyzer.sideLaneCentreM(half, Side.RIGHT)),
            ),
            ZoneParams(half, 2.5),
        )
        assertTrue(zones.left.blocked)
        assertTrue(zones.right.blocked)
        assertFalse(zones.centre.blocked)
    }

    @Test
    fun `no cue - identical to guidance without depth`() {
        val a = Guidance(GuidanceConfig())
        val b = Guidance(GuidanceConfig())
        val frames = listOf(listOf(ob("chair", 2.0, 0.3)), emptyList(), listOf(ob("person", 1.0)), emptyList())
        frames.forEachIndexed { i, f ->
            assertEquals(a.tick(f, nowMs = i * 100L), b.tick(f, nowMs = i * 100L, depth = DepthCue.NONE))
            assertEquals(a.heldObstacles, b.heldObstacles)
        }
    }
}
