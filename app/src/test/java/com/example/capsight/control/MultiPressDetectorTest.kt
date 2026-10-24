package com.example.capsight.control

import com.example.capsight.control.VolumeKey.DOWN
import com.example.capsight.control.VolumeKey.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-APP-06 (CLAUDE.md §8.2): 2 or 3 presses of one volume key are a gesture, decided 500 ms after
 * the last press; 1 or 4+ presses, holding the key, or mixing keys are only volume changes.
 */
class MultiPressDetectorTest {

    private val d = MultiPressDetector(windowMs = 600, settleMs = 500, minGapMs = 90)

    /** Feeds presses like VolumeGesture does (poll before every press); returns every gesture recognised. */
    private fun run(vararg presses: Pair<VolumeKey, Long>, untilMs: Long = 10_000): List<Pair<Gesture, Long>> {
        val out = mutableListOf<Pair<Gesture, Long>>()
        fun pollUntil(t: Long) {
            while (true) {
                val at = d.pollAtMs ?: return
                if (at > t) return
                d.poll(at)?.let { out += it to at }
            }
        }
        for ((key, t) in presses) {
            pollUntil(t)
            d.poll(t)?.let { out += it to t }
            d.press(key, t)
        }
        pollUntil(untilMs)
        return out
    }

    @Test
    fun `two presses are decided only after the settle time`() {
        assertTrue("first press opens a burst", d.press(UP, 1_000))
        assertFalse(d.press(UP, 1_250))
        assertEquals(1_750L, d.pollAtMs)
        assertNull("2 vs 3 not known yet", d.poll(1_749))
        assertEquals(Gesture.UP_TWICE, d.poll(1_750))
        assertNull("only once", d.poll(1_800))
        assertNull(d.pollAtMs)
    }

    @Test
    fun `a third press before the settle time makes it three`() {
        d.press(DOWN, 0)
        d.press(DOWN, 300)
        assertNull(d.poll(700))
        d.press(DOWN, 700)
        assertNull("the 2-press decision was replaced", d.poll(1_100))
        assertEquals(Gesture.DOWN_THREE, d.poll(1_200))
    }

    @Test
    fun `each gesture maps to its key and count`() {
        assertEquals(listOf(Gesture.UP_TWICE), run(UP to 0L, UP to 200L).map { it.first })
        assertEquals(listOf(Gesture.UP_THREE), run(UP to 2_000L, UP to 2_200L, UP to 2_400L).map { it.first })
        assertEquals(listOf(Gesture.DOWN_TWICE), run(DOWN to 4_000L, DOWN to 4_200L).map { it.first })
        assertEquals(listOf(Gesture.DOWN_THREE), run(DOWN to 6_000L, DOWN to 6_200L, DOWN to 6_400L).map { it.first })
    }

    @Test
    fun `a single press is never a gesture`() {
        assertTrue(run(UP to 0L).isEmpty())
        assertEquals(0, d.presses)
        assertTrue("next press opens a new burst", d.press(UP, 5_000))
    }

    @Test
    fun `four or more presses are only volume changes`() {
        assertTrue(run(UP to 0L, UP to 200L, UP to 400L, UP to 600L).isEmpty())
        assertTrue(run(DOWN to 2_000L, DOWN to 2_150L, DOWN to 2_300L, DOWN to 2_450L, DOWN to 2_600L).isEmpty())
    }

    @Test
    fun `holding the key (auto-repeat every 50 ms) is not a gesture`() {
        // Key down, first repeat after 500 ms, then every 50 ms: gaps below the minimum gap.
        val presses = listOf(UP to 0L) + (500L..1_500L step 50).map { UP to it }
        assertTrue(run(*presses.toTypedArray()).isEmpty())
    }

    @Test
    fun `auto-repeat flagged by the Activity spoils the burst even with tap-like timing`() {
        d.press(DOWN, 0)
        d.press(DOWN, 300, repeat = true)
        assertNull(d.poll(800))
        assertNull(d.pollAtMs)
    }

    @Test
    fun `a burst that starts with a repeat is ignored`() {
        d.press(UP, 0, repeat = true)
        d.press(UP, 200)
        assertNull(d.poll(700))
    }

    @Test
    fun `two presses closer than the minimum gap are a hold, at the minimum gap they count`() {
        assertTrue(run(UP to 0L, UP to 89L).isEmpty())
        assertEquals(listOf(Gesture.UP_TWICE), run(UP to 2_000L, UP to 2_090L).map { it.first })
    }

    @Test
    fun `the other key cancels the burst and starts its own`() {
        // Down x2 interrupted by Up: no stop. Up, Up after it: start without camera.
        assertEquals(listOf(Gesture.UP_TWICE), run(DOWN to 0L, DOWN to 200L, UP to 400L, UP to 600L).map { it.first })
        // Up x2 then one Down: nothing at all.
        assertTrue(run(UP to 2_000L, UP to 2_200L, DOWN to 2_400L).isEmpty())
    }

    @Test
    fun `the other key opens a new burst for the volume restore`() {
        d.press(DOWN, 0)
        assertTrue(d.press(UP, 200))
        assertFalse(d.press(UP, 400))
    }

    @Test
    fun `a gap of 499 ms still joins, 500 ms starts a new burst`() {
        assertEquals(listOf(Gesture.UP_TWICE), run(UP to 0L, UP to 499L).map { it.first })
        // 500 ms after the first press its burst (one press) is decided: two single presses.
        assertTrue(run(UP to 2_000L, UP to 2_500L).isEmpty())
    }

    @Test
    fun `a press later than the window never joins even if poll was not called`() {
        d.press(UP, 0)
        assertTrue("601 ms later is a new burst", d.press(UP, 601))
        assertEquals(1, d.presses)
    }

    @Test
    fun `gestures in a row are each recognised once`() {
        val got = run(UP to 0L, UP to 200L, DOWN to 1_500L, DOWN to 1_700L, DOWN to 1_900L, UP to 4_000L, UP to 4_250L)
        assertEquals(listOf(Gesture.UP_TWICE, Gesture.DOWN_THREE, Gesture.UP_TWICE), got.map { it.first })
        assertEquals(listOf(700L, 2_400L, 4_750L), got.map { it.second })
    }

    @Test
    fun `a gesture is decided at the next press if the timer was late`() {
        d.press(UP, 0)
        d.press(UP, 200)
        // No poll at 700 (e.g. a late timer); the next press comes at 900: the burst is over.
        assertEquals(Gesture.UP_TWICE, d.poll(900))
        assertTrue(d.press(DOWN, 900))
    }

    @Test
    fun `gesture lookup`() {
        assertEquals(Gesture.UP_THREE, Gesture.of(UP, 3))
        assertEquals(Gesture.DOWN_TWICE, Gesture.of(DOWN, 2))
        assertNull(Gesture.of(UP, 1))
        assertNull(Gesture.of(DOWN, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `settle longer than the window is rejected`() {
        MultiPressDetector(windowMs = 400, settleMs = 500)
    }
}
