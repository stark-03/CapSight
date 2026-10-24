package com.example.capsight.ble

import com.example.capsight.voice.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md §4.6: beacon names `CS:<TYPE>:<LABEL>`, the spoken phrases and their priority. */
class LandmarkTest {

    @Test
    fun `valid names decode to type and label`() {
        assertEquals(Landmark(LandmarkType.DOOR, "Room 304"), Landmark.parse("CS:DOOR:Room 304"))
        assertEquals(Landmark(LandmarkType.STAIRS, ""), Landmark.parse("CS:STAIRS:"))
        assertEquals(Landmark(LandmarkType.STAIRS, ""), Landmark.parse("CS:STAIRS"))
        assertEquals(Landmark(LandmarkType.LIFT, "Block A"), Landmark.parse("CS:lift: Block A "))
    }

    @Test
    fun `unknown type is OTHER and keeps its label`() {
        assertEquals(Landmark(LandmarkType.OTHER, "Canteen"), Landmark.parse("CS:FOOD:Canteen"))
    }

    @Test
    fun `ROOM and OTHER without a label are ignored - nothing to say`() {
        assertNull(Landmark.parse("CS:ROOM:"))
        assertNull(Landmark.parse("CS:OTHER:"))
        assertNull(Landmark.parse("CS:FOOD:"))
        assertEquals(Landmark(LandmarkType.DOOR, ""), Landmark.parse("CS:DOOR:"))
    }

    @Test
    fun `names without the CS prefix are ignored`() {
        assertNull(Landmark.parse(null))
        assertNull(Landmark.parse(""))
        assertNull(Landmark.parse("Galaxy Buds"))
        assertNull(Landmark.parse("cs:DOOR:Room 1"))
        assertNull(Landmark.parse("XCS:DOOR:Room 1"))
    }

    @Test
    fun `names longer than 26 characters are ignored`() {
        val max = "CS:ROOM:" + "a".repeat(18)
        assertEquals(26, max.length)
        assertEquals(LandmarkType.ROOM, Landmark.parse(max)?.type)
        assertNull(Landmark.parse(max + "a"))
    }

    @Test
    fun `phrases follow the section 4_6 table`() {
        assertEquals("Room 304 door nearby.", Landmark(LandmarkType.DOOR, "Room 304").phrase)
        assertEquals("Door nearby.", Landmark(LandmarkType.DOOR, "").phrase)
        assertEquals("Caution. Stairs nearby.", Landmark(LandmarkType.STAIRS, "Block B").phrase)
        assertEquals("Caution. Stairs nearby.", Landmark(LandmarkType.STAIRS, "").phrase)
        assertEquals("Lift nearby.", Landmark(LandmarkType.LIFT, "").phrase)
        assertEquals("Lift nearby, Block A.", Landmark(LandmarkType.LIFT, "Block A").phrase)
        assertEquals("Washroom nearby.", Landmark(LandmarkType.WASHROOM, "").phrase)
        assertEquals("Washroom nearby, Ladies.", Landmark(LandmarkType.WASHROOM, "Ladies").phrase)
        assertEquals("Exit nearby.", Landmark(LandmarkType.EXIT, "").phrase)
        assertEquals("Library nearby.", Landmark(LandmarkType.ROOM, "Library").phrase)
        assertEquals("Canteen nearby.", Landmark(LandmarkType.OTHER, "Canteen").phrase)
    }

    @Test
    fun `stairs are P1, everything else is info below every instruction`() {
        assertEquals(Priority.STAIRS, Landmark(LandmarkType.STAIRS, "").priority)
        LandmarkType.entries.filter { it != LandmarkType.STAIRS }.forEach { t ->
            val p = Landmark(t, "x").priority
            assertEquals(Priority.INFO, p)
            // Lower wins: info must lose to P0-P4 in the speaker's single slot.
            listOf(Priority.STOP, Priority.STAIRS, Priority.AVOID, Priority.BLOCKED, Priority.CLEAR)
                .forEach { assertTrue("$t vs P$it", p > it) }
        }
    }
}
