package com.example.capsight.path

import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** FR-PATH-01/02/04: three lanes, side choice, hysteresis. */
class ZoneAnalyzerTest {

    private val params = ZoneParams(corridorHalfWidthM = 0.55, warnDistanceM = 2.5)
    private val hyst = HysteresisParams(minHoldMs = 2_000, switchMarginM = 0.5)

    /** Positive lateral = user's left. */
    private fun ob(forward: Double, lateral: Double, label: String = "chair") =
        RelativeObstacle(label, forward, lateral, hypot(forward, lateral))

    private fun corridor(obs: List<RelativeObstacle>) = obs.filter { kotlin.math.abs(it.lateralM) <= 0.55 }

    @Test
    fun `FR-PATH-01 nearest obstacle per lane`() {
        val z = ZoneAnalyzer.analyze(listOf(ob(2.0, 0.0), ob(1.5, 0.1, "person"), ob(3.0, 1.0), ob(1.0, -1.2)), params)
        assertEquals(1.5, z.centre.nearestM!!, 1e-9)
        assertEquals("person", z.centre.nearestLabel)
        assertTrue(z.centre.blocked)
        assertEquals(3.0, z.left.nearestM!!, 1e-9)
        assertFalse(z.left.blocked)
        assertEquals(1.0, z.right.nearestM!!, 1e-9)
        assertTrue(z.right.blocked)
    }

    @Test
    fun `obstacles behind or far to the side are ignored`() {
        val z = ZoneAnalyzer.analyze(listOf(ob(-1.0, 0.0), ob(2.0, 3.0), ob(2.0, -2.0)), params)
        assertEquals(Zone.EMPTY, z.left)
        assertEquals(Zone.EMPTY, z.centre)
        assertEquals(Zone.EMPTY, z.right)
    }

    @Test
    fun `lane boundaries - corridor edge belongs to the centre`() {
        val z = ZoneAnalyzer.analyze(listOf(ob(2.0, 0.55), ob(2.0, -0.56)), params)
        assertEquals(2.0, z.centre.nearestM!!, 1e-9)
        assertEquals(2.0, z.right.nearestM!!, 1e-9)
        assertNull(z.left.nearestM)
    }

    @Test
    fun `FR-PATH-02 centre blocked, right blocked - go left`() {
        val obs = listOf(ob(2.0, 0.0), ob(1.5, -1.0))
        val z = ZoneAnalyzer.analyze(obs, params)
        assertEquals(Side.LEFT, ZoneAnalyzer.bestSide(z, corridor(obs), 0.55))
    }

    @Test
    fun `FR-PATH-02 side with more clearance wins even when neither is blocked`() {
        val obs = listOf(ob(2.0, 0.0), ob(3.0, 1.0), ob(4.0, -1.0))
        val z = ZoneAnalyzer.analyze(obs, params)
        assertEquals(Side.RIGHT, ZoneAnalyzer.bestSide(z, corridor(obs), 0.55))
    }

    @Test
    fun `FR-PATH-02 both sides blocked - no clear path`() {
        val obs = listOf(ob(2.0, 0.0), ob(1.5, 1.0), ob(2.2, -1.0))
        val z = ZoneAnalyzer.analyze(obs, params)
        assertTrue(z.allBlocked)
        assertNull(ZoneAnalyzer.bestSide(z, corridor(obs), 0.55))
    }

    @Test
    fun `tie falls back to free_side - away from the obstacle, dead centre goes right`() {
        val leftish = listOf(ob(2.0, 0.3))
        assertEquals(Side.RIGHT, ZoneAnalyzer.bestSide(ZoneAnalyzer.analyze(leftish, params), leftish, 0.55))
        val rightish = listOf(ob(2.0, -0.3))
        assertEquals(Side.LEFT, ZoneAnalyzer.bestSide(ZoneAnalyzer.analyze(rightish, params), rightish, 0.55))
        val centre = listOf(ob(2.0, 0.0))
        assertEquals(Side.RIGHT, ZoneAnalyzer.bestSide(ZoneAnalyzer.analyze(centre, params), centre, 0.55))
    }

    // ------------------------------------------------------------------ hysteresis

    private fun chooseAt(c: SideChooser, obs: List<RelativeObstacle>, now: Long) =
        c.choose(ZoneAnalyzer.analyze(obs, params), corridor(obs), 0.55, now)

    @Test
    fun `FR-PATH-04 small flips within the hold are ignored`() {
        val c = SideChooser(hyst)
        // Obstacle wobbles either side of the centre line: free_side would flip each frame.
        val wobble = listOf(listOf(ob(2.0, 0.05)), listOf(ob(2.0, -0.05)))
        val first = chooseAt(c, wobble[0], 0)
        val sides = (1..15).map { i -> chooseAt(c, wobble[i % 2], i * 100L) }
        assertTrue(sides.all { it == first })
    }

    @Test
    fun `switches at once when the chosen side becomes blocked`() {
        val c = SideChooser(hyst)
        assertEquals(Side.RIGHT, chooseAt(c, listOf(ob(2.0, 0.3)), 0))
        assertEquals(Side.LEFT, chooseAt(c, listOf(ob(2.0, 0.3), ob(1.5, -1.0)), 300))
    }

    @Test
    fun `switches within the hold when the other side is much clearer`() {
        val c = SideChooser(hyst)
        // Right lane empty, left lane has something at 4 m: go right.
        assertEquals(Side.RIGHT, chooseAt(c, listOf(ob(2.0, 0.0), ob(4.0, 1.0)), 0))
        // Right now has something at 2.6 m (not blocked); left at 4 m is 1.4 m clearer -> switch.
        assertEquals(Side.LEFT, chooseAt(c, listOf(ob(2.0, 0.0), ob(4.0, 1.0), ob(2.6, -1.0)), 500))
    }

    @Test
    fun `after the hold a better side is taken`() {
        val c = SideChooser(hyst)
        assertEquals(Side.RIGHT, chooseAt(c, listOf(ob(2.0, 0.0), ob(4.0, -1.0), ob(3.8, 1.0)), 0))
        // Left becomes slightly clearer: ignored inside the hold, taken after it.
        val later = listOf(ob(2.0, 0.0), ob(3.8, -1.0), ob(4.0, 1.0))
        assertEquals(Side.RIGHT, chooseAt(c, later, 1_000))
        assertEquals(Side.LEFT, chooseAt(c, later, 2_100))
    }

    @Test
    fun `clear centre forgets the side, all blocked gives none`() {
        val c = SideChooser(hyst)
        chooseAt(c, listOf(ob(2.0, 0.3)), 0)
        assertNull(chooseAt(c, emptyList(), 100))
        assertNull(c.current)
        assertNull(chooseAt(c, listOf(ob(2.0, 0.0), ob(1.5, 1.0), ob(2.2, -1.0)), 200))
    }
}
