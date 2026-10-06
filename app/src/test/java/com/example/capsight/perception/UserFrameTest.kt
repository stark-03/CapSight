package com.example.capsight.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the obstacle-geometry and free_side sections of reference/tests/test_world.py. */
class UserFrameTest {

    private val pos = Vec2(0.0, 0.0)
    private val heading = Vec2(0.0, 1.0) // at the origin, walking +Y

    private fun rel(x: Double, y: Double, label: String = "obstacle") =
        UserFrame.relativeToUser(pos, heading, Vec2(x, y), label)!!

    @Test
    fun `dead ahead is forward 2 m, lateral 0`() {
        val r = rel(0.0, 2.0, "chair")
        assertEquals(2.0, r.forwardM, 1e-9)
        assertEquals(0.0, r.lateralM, 1e-9)
    }

    @Test
    fun `minus X is the left, plus X is the right`() {
        assertEquals(Side.LEFT, rel(-1.0, 2.0).side)
        assertEquals(Side.RIGHT, rel(1.0, 2.0).side)
    }

    @Test
    fun `corridor rules`() {
        val ahead = rel(0.0, 2.0)
        val behind = rel(0.0, -2.0)
        assertTrue(behind.forwardM < 0)
        assertTrue(!UserFrame.inCorridor(behind, 0.6, 4.0))
        assertTrue(UserFrame.inCorridor(ahead, 0.6, 4.0))
        assertTrue(!UserFrame.inCorridor(rel(3.0, 2.0), 0.6, 4.0))
        assertTrue(!UserFrame.inCorridor(ahead, 0.6, 1.0))
    }

    @Test
    fun `no heading means no relative obstacle`() {
        assertNull(UserFrame.relativeToUser(pos, null, Vec2(0.0, 2.0)))
    }

    @Test
    fun `free side steers away from the blocked side`() {
        val left = rel(-1.0, 2.0)
        val right = rel(1.0, 2.0)
        assertEquals(Side.RIGHT, UserFrame.freeSide(listOf(left), 0.6))
        assertEquals(Side.LEFT, UserFrame.freeSide(listOf(right), 0.6))
        // Equal counts: away from the nearest one.
        assertEquals(Side.LEFT, UserFrame.freeSide(listOf(rel(1.0, 1.0), rel(-1.0, 3.0)), 0.6))
        assertEquals(Side.RIGHT, UserFrame.freeSide(listOf(rel(-1.0, 1.0), rel(1.0, 3.0)), 0.6))
    }

    @Test
    fun `ties and empty lists go right (keep-right convention)`() {
        assertEquals(Side.RIGHT, UserFrame.freeSide(emptyList(), 0.6))
        assertEquals(Side.RIGHT, UserFrame.freeSide(listOf(rel(0.0, 2.0)), 0.6))
    }
}
