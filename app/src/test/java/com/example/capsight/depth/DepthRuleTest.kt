package com.example.capsight.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-OBS-04 / FR-PATH-03: the conservative depth rule - 1 s sustain, hysteresis, staleness. */
class DepthRuleTest {

    private fun s(c: Double, l: Double = 0.3, r: Double = 0.3) = DepthScores(l, c, r, floorRef = 10.0)

    /** Feeds [scores] as depth results every [periodMs] from [startMs]; returns the cue after each. */
    private fun DepthRule.feed(scores: List<DepthScores>, startMs: Long = 1_000L, periodMs: Long = 250L) =
        scores.mapIndexed { i, sc ->
            val at = startMs + i * periodMs
            update(sc, at, at)
        }

    @Test
    fun `centre needs consecutive near results covering 1 s`() {
        val cues = DepthRule().feed(List(5) { s(1.1) }) // at 0, 250, 500, 750, 1000 ms
        assertEquals(listOf(false, false, false, false, true), cues.map { it.centre })
    }

    @Test
    fun `one result below the threshold restarts the sustain`() {
        val r = DepthRule()
        val cues = r.feed(listOf(s(1.1), s(1.1), s(1.1), s(0.99), s(1.1), s(1.1), s(1.1), s(1.1), s(1.1)))
        assertEquals(listOf(false, false, false, false, false, false, false, false, true), cues.map { it.centre })
    }

    @Test
    fun `re-reading the same result every tick does not count as sustained`() {
        val r = DepthRule()
        r.update(s(1.2), 1_000L, 1_000L)
        // The 10 Hz loop asks 14 times about the same result: no new evidence, no Stop.
        val cue = (1..14).map { r.update(s(1.2), 1_000L, 1_000L + it * 100L) }.last()
        assertFalse(cue.centre)
    }

    @Test
    fun `hysteresis - stays on down to threshold minus 0_1, clears below it`() {
        val r = DepthRule()
        r.feed(List(5) { s(1.1) })
        assertTrue(r.update(s(0.95), 2_250L, 2_250L).centre)
        assertTrue(r.update(s(0.90), 2_500L, 2_500L).centre)
        assertFalse(r.update(s(0.89), 2_750L, 2_750L).centre)
        // Once cleared, it needs another full second to come back.
        assertFalse(r.update(s(1.1), 3_000L, 3_000L).centre)
    }

    @Test
    fun `stale depth gives no cue and forgets the evidence`() {
        val r = DepthRule()
        r.feed(List(5) { s(1.1) }) // active at 2000 ms
        assertTrue(r.update(s(1.1), 2_000L, 3_500L).centre) // 1.5 s old: still fresh enough
        assertEquals(DepthCue.NONE, r.update(s(1.1), 2_000L, 3_501L))
        assertFalse(r.update(s(1.1), 3_600L, 3_600L).centre)
    }

    @Test
    fun `a long gap between results breaks the run`() {
        val r = DepthRule()
        r.update(s(1.1), 1_000L, 1_000L)
        r.update(s(1.1), 1_500L, 1_500L)
        // Next result 1.6 s later (no tick in between): not consecutive.
        assertFalse(r.update(s(1.1), 3_100L, 3_100L).centre)
        assertTrue(r.feed(List(4) { s(1.1) }, startMs = 3_350L).last().centre)
    }

    @Test
    fun `no scores, no result yet, or switched off - no cue`() {
        assertEquals(DepthCue.NONE, DepthRule().update(null, 1_000L, 1_000L))
        assertEquals(DepthCue.NONE, DepthRule().update(s(2.0, 2.0, 2.0), 0L, 1_000L))
        val off = DepthRule(DepthParams(enabled = false))
        assertEquals(DepthCue.NONE, off.feed(List(8) { s(2.0, 2.0, 2.0) }).last())
    }

    @Test
    fun `sides block at once and release with hysteresis`() {
        val r = DepthRule()
        val on = r.update(s(c = 0.4, l = 1.0, r = 0.99), 1_000L, 1_000L)
        assertTrue(on.left)
        assertFalse(on.right)
        assertFalse(on.centre)
        assertTrue(r.update(s(c = 0.4, l = 0.91, r = 0.3), 1_250L, 1_250L).left)
        assertFalse(r.update(s(c = 0.4, l = 0.89, r = 0.3), 1_500L, 1_500L).left)
    }

    @Test
    fun `threshold is the setting`() {
        val low = DepthRule(DepthParams(threshold = 0.85))
        assertTrue(low.feed(List(5) { s(0.9) }).last().centre)
        val high = DepthRule(DepthParams(threshold = 1.3))
        assertFalse(high.feed(List(5) { s(1.2) }).last().centre)
    }
}
