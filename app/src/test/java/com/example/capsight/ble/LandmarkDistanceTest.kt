package com.example.capsight.ble

import com.example.capsight.voice.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md §4.6 distance announcements. With the defaults (-52 dBm at 1 m, n 2.5):
 * -70 dBm ~5.2 m (step 5), -65 ~3.3 m (3), -61 ~2.3 m (2), -50 ~0.8 m (1), -75 ~8.3 m (8).
 */
class LandmarkDistanceTest {

    private val door = "CS:DOOR:Room 304"
    private val key = "AA:BB:CC:DD:EE:01"
    private val on = LandmarkConfig(sayDistance = true)

    private class Said(val t: Long, val cue: LandmarkCue)

    /** Feeds a constant [rssi] every 100 ms over [fromMs, toMs), accepting every due phrase. */
    private fun LandmarkTracker.hold(rssi: Int, fromMs: Long, toMs: Long, out: MutableList<Said>, name: String = door) {
        var t = fromMs
        while (t < toMs) {
            onAdvert(key, name, rssi, t)?.let { announced(key, t); out += Said(t, it) }
            t += 100
        }
    }

    @Test
    fun `first announcement includes the distance for every type`() {
        assertEquals("Room 304 door nearby, about 4 metres.", Landmark(LandmarkType.DOOR, "Room 304").phraseAt(4))
        assertEquals("Door nearby, about 1 metre.", Landmark(LandmarkType.DOOR, "").phraseAt(1))
        assertEquals("Caution. Stairs nearby, about 3 metres.", Landmark(LandmarkType.STAIRS, "").phraseAt(3))
        assertEquals("Lift nearby, about 2 metres.", Landmark(LandmarkType.LIFT, "").phraseAt(2))
        assertEquals("Lift nearby, Block A, about 2 metres.", Landmark(LandmarkType.LIFT, "Block A").phraseAt(2))
        assertEquals("Washroom nearby, about 5 metres.", Landmark(LandmarkType.WASHROOM, "").phraseAt(5))
        assertEquals("Exit nearby, about 8 metres.", Landmark(LandmarkType.EXIT, "").phraseAt(8))
        assertEquals("Library nearby, about 10 metres.", Landmark(LandmarkType.ROOM, "Library").phraseAt(10))
        assertEquals("Canteen nearby, about 20 metres.", Landmark(LandmarkType.OTHER, "Canteen").phraseAt(20))
    }

    @Test
    fun `update and arrival phrases`() {
        val d = Landmark(LandmarkType.DOOR, "Room 304")
        assertEquals("Room 304 door, about 2 metres.", d.closerPhrase(2))
        assertEquals("Room 304 door, you are here.", d.herePhrase)
        val s = Landmark(LandmarkType.STAIRS, "")
        assertEquals("Caution. Stairs, about 2 metres.", s.closerPhrase(2))
        assertEquals("Caution. Stairs right in front of you.", s.herePhrase)
        assertEquals("Library, you are here.", Landmark(LandmarkType.ROOM, "Library").herePhrase)
        assertEquals("Lift, about 3 metres.", Landmark(LandmarkType.LIFT, "").closerPhrase(3))
    }

    @Test
    fun `tracker speaks the first announcement with the distance`() {
        val t = LandmarkTracker(on)
        val cue = t.onAdvert(key, door, -65, 0)
        assertEquals("Room 304 door nearby, about 3 metres.", cue?.phrase)
        assertEquals(CueKind.FIRST, cue?.kind)
        assertEquals(3, cue?.step)
        assertEquals(-65.0, cue!!.rssiDbm, 0.0)
        assertEquals(3.31, cue.distanceM, 0.01)
        val stairs = t.onAdvert("02", "CS:STAIRS:", -61, 0)
        assertEquals("Caution. Stairs nearby, about 2 metres.", stairs?.phrase)
        assertEquals(Priority.STAIRS, stairs?.priority)
    }

    @Test
    fun `approach - one update per smaller step, then arrival once`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-65, 0, 5_000, said)
        t.hold(-61, 5_000, 15_000, said)
        t.hold(-50, 15_000, 30_000, said)
        assertEquals(
            listOf("Room 304 door nearby, about 3 metres.", "Room 304 door, about 2 metres.", "Room 304 door, you are here."),
            said.map { it.cue.phrase },
        )
        assertEquals(listOf(CueKind.FIRST, CueKind.CLOSER, CueKind.ARRIVED), said.map { it.cue.kind })
        assertTrue(said.all { it.cue.priority == Priority.INFO })
        assertTrue(t.snapshot(30_000).single().arrived)
    }

    @Test
    fun `updates are at least 4 s apart per beacon`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-70, 0, 100, said) // first, step 5
        t.hold(-61, 100, 4_100, said) // step 2 settles at 1.6 s, but must wait for 4 s
        t.hold(-50, 4_100, 12_000, said) // step 1 settles at ~7.1 s, must wait for 8 s
        assertEquals(listOf(0L, 4_000L, 8_000L), said.map { it.t })
        assertEquals(listOf(5, 2, 1), said.map { it.cue.step })
        assertEquals("Room 304 door, about 2 metres.", said[1].cue.phrase)
        assertEquals("Room 304 door, you are here.", said[2].cue.phrase)
    }

    @Test
    fun `moving away never speaks, and coming back only below the last spoken step`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-61, 0, 5_000, said) // first, step 2
        t.hold(-75, 5_000, 15_000, said) // step 8, still above the -80 re-arm level
        assertEquals(8, t.snapshot(14_900).single().step)
        t.hold(-61, 15_000, 25_000, said) // back to 2: not smaller than the last spoken 2
        assertEquals(1, said.size)
        t.hold(-50, 25_000, 35_000, said)
        assertEquals("Room 304 door, you are here.", said.last().cue.phrase)
        assertEquals(2, said.size)
    }

    @Test
    fun `first announcement already at 1 m - no separate arrival`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-45, 0, 20_000, said)
        assertEquals(listOf("Room 304 door nearby, about 1 metre."), said.map { it.cue.phrase })
    }

    @Test
    fun `stairs updates keep P1 and the caution wording`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-65, 0, 5_000, said, name = "CS:STAIRS:")
        t.hold(-61, 5_000, 15_000, said, name = "CS:STAIRS:")
        t.hold(-50, 15_000, 25_000, said, name = "CS:STAIRS:")
        assertEquals(
            listOf(
                "Caution. Stairs nearby, about 3 metres.",
                "Caution. Stairs, about 2 metres.",
                "Caution. Stairs right in front of you.",
            ),
            said.map { it.cue.phrase },
        )
        assertTrue(said.all { it.cue.priority == Priority.STAIRS })
    }

    @Test
    fun `a refused update is offered again with the next reading`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-65, 0, 5_000, said)
        var time = 5_000L
        var cue: LandmarkCue? = null
        while (cue == null && time < 15_000) {
            cue = t.onAdvert(key, door, -61, time) // refused: not marked announced
            time += 100
        }
        assertEquals(CueKind.CLOSER, cue?.kind)
        assertEquals(cue, t.onAdvert(key, door, -61, time))
        t.announced(key, time)
        assertNull(t.onAdvert(key, door, -61, time + 100))
    }

    @Test
    fun `setting off - exactly the original behaviour, no distance, no updates`() {
        val off = LandmarkTracker(LandmarkConfig(sayDistance = false))
        val bare = LandmarkTracker()
        val a = mutableListOf<Said>()
        val b = mutableListOf<Said>()
        for (tr in listOf(off to a, bare to b)) {
            tr.first.hold(-65, 0, 5_000, tr.second)
            tr.first.hold(-61, 5_000, 15_000, tr.second)
            tr.first.hold(-50, 15_000, 30_000, tr.second)
        }
        assertEquals(listOf("Room 304 door nearby."), a.map { it.cue.phrase })
        assertEquals(a.map { it.t to it.cue.phrase }, b.map { it.t to it.cue.phrase })
    }

    @Test
    fun `re-arm resets arrival - the next approach speaks again`() {
        val t = LandmarkTracker(on.copy(repeatMs = 0))
        val said = mutableListOf<Said>()
        t.hold(-61, 0, 5_000, said)
        t.hold(-50, 5_000, 15_000, said)
        assertEquals("Room 304 door, you are here.", said.last().cue.phrase)
        t.hold(-90, 15_000, 20_000, said) // below -80: re-armed
        assertEquals(2, said.size)
        t.hold(-65, 20_000, 25_000, said)
        t.hold(-50, 25_000, 35_000, said)
        assertEquals(
            listOf("Room 304 door nearby, about 3 metres.", "Room 304 door, you are here."),
            said.drop(2).map { it.cue.phrase },
        )
    }

    @Test
    fun `unseen for 10 s also re-arms the arrival`() {
        val t = LandmarkTracker(on.copy(repeatMs = 0))
        val said = mutableListOf<Said>()
        t.hold(-50, 0, 3_000, said)
        assertEquals(1, said.size)
        assertNotNull(t.onAdvert(key, door, -50, 13_000))
    }

    @Test
    fun `snapshot shows distance and step`() {
        val t = LandmarkTracker(on)
        val said = mutableListOf<Said>()
        t.hold(-61, 0, 2_000, said)
        val v = t.snapshot(2_000).single()
        assertEquals(2.29, v.distanceM, 0.01)
        assertEquals(2, v.step)
    }
}
