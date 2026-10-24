package com.example.capsight.ble

import com.example.capsight.voice.Priority
import com.example.capsight.voice.SpeechArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md §4.6: median smoothing, announce threshold, hysteresis, re-arm and 60 s repeat. */
class LandmarkTrackerTest {

    private val door = "CS:DOOR:Room 304"
    private val key = "AA:BB:CC:DD:EE:01"

    /** Feeds one reading and, like the scanner, marks it announced when it is due. */
    private fun LandmarkTracker.feed(rssi: Int, now: Long, name: String = door, id: String = key): Landmark? =
        onReading(id, name, rssi, now)?.also { announced(id, now) }

    @Test
    fun `median of odd and even counts`() {
        assertEquals(-70.0, LandmarkTracker.median(listOf(-90, -70, -60)), 0.0)
        assertEquals(-65.0, LandmarkTracker.median(listOf(-60, -70)), 0.0)
        assertEquals(-75.0, LandmarkTracker.median(listOf(-80, -60, -90, -70)), 0.0)
    }

    @Test
    fun `non-landmark names are ignored and not listed`() {
        val t = LandmarkTracker()
        assertNull(t.onReading(key, "Galaxy Buds", -40, 0))
        assertNull(t.onReading(key, null, -40, 0))
        assertTrue(t.snapshot(0).isEmpty())
    }

    @Test
    fun `announces once when the smoothed RSSI reaches the threshold`() {
        val t = LandmarkTracker()
        assertNull(t.feed(-75, 0))
        assertNull(t.feed(-71, 100))
        // Median of -75, -71, -68 is -71: still too far.
        assertNull(t.feed(-68, 200))
        // Median of -75, -71, -68, -65 is -69.5.
        assertEquals("Room 304 door nearby.", t.feed(-65, 300)?.phrase)
        assertNull(t.feed(-60, 400))
    }

    @Test
    fun `a single strong packet does not trigger - the median ignores spikes`() {
        val t = LandmarkTracker()
        t.feed(-85, 0)
        t.feed(-84, 100)
        assertNull(t.feed(-50, 200))
    }

    @Test
    fun `smoothing uses only the last 3 s`() {
        val t = LandmarkTracker()
        repeat(10) { t.feed(-90, it * 100L) }
        assertNull(t.feed(-60, 2_000))
        // At 4 s the -90 readings (0..0.9 s) are older than 3 s; only the -60s remain.
        assertNotNull(t.feed(-60, 4_000))
    }

    @Test
    fun `no repeat while hovering between the thresholds, re-arm below -80`() {
        val t = LandmarkTracker(LandmarkConfig(repeatMs = 0))
        assertNotNull(t.feed(-65, 0))
        // Drifts to -75 and back: still disarmed (hysteresis).
        assertNull(t.feed(-75, 4_000))
        assertNull(t.feed(-65, 8_000))
        // Falls below -80 (fresh window): re-armed, so the next approach announces again.
        assertNull(t.feed(-85, 12_000))
        assertTrue(t.snapshot(12_000).single().armed)
        assertNotNull(t.feed(-65, 16_000))
    }

    @Test
    fun `unseen for 10 s re-arms`() {
        val t = LandmarkTracker(LandmarkConfig(repeatMs = 0))
        assertNotNull(t.feed(-65, 0))
        assertNull(t.feed(-65, 9_999))
        assertNotNull(t.feed(-65, 19_999))
    }

    @Test
    fun `never the same beacon twice within 60 s, even when re-armed`() {
        val t = LandmarkTracker()
        assertNotNull(t.feed(-65, 0))
        assertNull(t.feed(-85, 20_000)) // re-armed
        assertNull(t.feed(-65, 30_000)) // armed and close, but within 60 s
        assertNull(t.feed(-65, 59_999))
        assertNotNull(t.feed(-65, 60_000))
    }

    @Test
    fun `refused by the speaker - offered again with the next reading`() {
        val t = LandmarkTracker()
        assertNotNull(t.onReading(key, door, -60, 0)) // not marked announced
        assertNotNull(t.onReading(key, door, -60, 100))
        t.announced(key, 100)
        assertNull(t.onReading(key, door, -60, 200))
    }

    @Test
    fun `beacons are tracked separately`() {
        val t = LandmarkTracker()
        assertNotNull(t.feed(-60, 0))
        assertEquals("Caution. Stairs nearby.", t.feed(-60, 0, name = "CS:STAIRS:", id = "02")?.phrase)
        assertEquals(2, t.snapshot(0).size)
    }

    @Test
    fun `snapshot lists beacons heard in the window, strongest first, with their state`() {
        val t = LandmarkTracker()
        t.feed(-60, 0)
        t.feed(-78, 0, name = "CS:LIFT:", id = "02")
        val s = t.snapshot(1_000)
        assertEquals(listOf(key, "02"), s.map { it.key })
        assertEquals(-60.0, s[0].smoothedDbm, 0.0)
        assertTrue(s[0].recentlyAnnounced)
        assertFalse(s[0].armed)
        assertTrue(s[1].armed)
        assertFalse(s[1].recentlyAnnounced)
        assertTrue(t.snapshot(3_001).isEmpty())
    }

    @Test
    fun `info landmark never displaces pending obstacle speech in the single slot`() {
        val a = SpeechArbiter()
        val stopPhrase = "Stop. Chair ahead."
        a.offer(stopPhrase, Priority.STOP, 2_000, 0)
        val lm = Landmark.parse(door)!!
        assertFalse(a.offer(lm.phrase, lm.priority, 3_000, 10))
        assertEquals(stopPhrase, a.pending?.text)
        // Stairs (P1) displaces a pending P2 avoid, like camera-detected stairs.
        val b = SpeechArbiter()
        b.offer("Chair ahead. Move left.", Priority.AVOID, 3_000, 0)
        val stairs = Landmark.parse("CS:STAIRS:")!!
        assertTrue(b.offer(stairs.phrase, stairs.priority, 3_000, 10))
    }
}
