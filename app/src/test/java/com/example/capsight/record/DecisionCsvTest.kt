package com.example.capsight.record

import com.example.capsight.perception.RelativeObstacle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-APP-07: the local decision log's CSV lines, timestamps and file housekeeping. */
class DecisionCsvTest {

    private val ist = (5 * 60 + 30) * 60_000

    @Test
    fun `plain fields are left alone, commas quotes and line breaks are quoted`() {
        assertEquals("Path clear.", DecisionCsv.escape("Path clear."))
        assertEquals("\"Stop. Chair ahead, close\"", DecisionCsv.escape("Stop. Chair ahead, close"))
        assertEquals("\"say \"\"hi\"\"\"", DecisionCsv.escape("say \"hi\""))
        assertEquals("\"a\nb\"", DecisionCsv.escape("a\nb"))
        assertEquals("", DecisionCsv.escape(""))
    }

    @Test
    fun `ISO time with offset, leap day and before the epoch`() {
        assertEquals("2023-11-15T03:43:20.000+05:30", DecisionCsv.isoTime(1_700_000_000_000, ist))
        assertEquals("2026-10-22T19:33:05.123+05:30", DecisionCsv.isoTime(1_792_677_785_123, ist))
        assertEquals("2000-02-29T00:00:00.000+00:00", DecisionCsv.isoTime(951_782_400_000, 0))
        assertEquals("1969-12-31T23:59:59.999+00:00", DecisionCsv.isoTime(-1, 0))
        assertEquals("1970-01-01T00:00:00.000-03:30", DecisionCsv.isoTime(12_600_000, -12_600_000))
    }

    @Test
    fun `row has every column in header order`() {
        val e = DecisionEntry(
            elapsedMs = 123_456,
            kind = EntryKind.DECISION,
            priority = 2,
            phrase = "Chair ahead. Move left.",
            reason = "chair 2.10 m in corridor, free side left",
            source = "Phone camera",
            cameraFps = 29.97f,
            detectorFps = 8.04f,
            nearest = RelativeObstacle("chair", 2.104, 0.1, 2.11),
        )
        val row = DecisionCsv.row(e, 1_700_000_000_000, ist)
        assertEquals(
            "2023-11-15T03:43:20.000+05:30,123456,decision,2,Chair ahead. Move left.," +
                "\"chair 2.10 m in corridor, free side left\",Phone camera,30.0,8.0,chair,2.10",
            row,
        )
        assertEquals(DecisionCsv.HEADER.split(',').size, row.replace("\"chair 2.10 m in corridor, free side left\"", "x").split(',').size)
    }

    @Test
    fun `no obstacle leaves the nearest columns empty`() {
        val e = DecisionEntry(5, EntryKind.LANDMARK, 5, "Room 304 door nearby.", "BLE beacon", "ESP32-CAM", 0f, 0f, null)
        assertTrue(DecisionCsv.row(e, 0, 0).endsWith(",landmark,5,Room 304 door nearby.,BLE beacon,ESP32-CAM,0.0,0.0,,"))
    }

    @Test
    fun `volume-key gestures are logged as control rows`() {
        val e = DecisionEntry(9, EntryKind.CONTROL, 5, "Navigation stopped. Press volume up twice to start.", "volume down x2: stop", "No camera (landmarks only)", 0f, 0f, null)
        assertTrue(DecisionCsv.row(e, 0, 0).contains(",9,control,5,Navigation stopped. Press volume up twice to start.,volume down x2: stop,"))
    }

    @Test
    fun `nearest is the obstacle with the smallest forward distance`() {
        val far = RelativeObstacle("person", 3.0, 0.0, 3.0)
        val near = RelativeObstacle("chair", 1.5, 1.0, 1.8)
        assertEquals(near, DecisionCsv.nearest(listOf(far, near)))
        assertNull(DecisionCsv.nearest(emptyList()))
    }

    @Test
    fun `file names sort in time order`() {
        val a = DecisionCsv.fileName(1_700_000_000_000, ist)
        val b = DecisionCsv.fileName(1_700_000_000_001, ist)
        assertEquals("decisions_2023-11-15_03-43-20-000.csv", a)
        assertTrue(a < b)
        assertTrue(DecisionCsv.isLogFile(a))
    }

    @Test
    fun `pruning keeps the newest files and never touches other files`() {
        val logs = (1..25).map { DecisionCsv.fileName(1_700_000_000_000L + it * 60_000L, 0) }
        val names = logs.shuffled(java.util.Random(1)) + listOf("settings.preferences_pb", "notes.csv")
        val delete = DecisionCsv.filesToDelete(names, keep = 20)
        assertEquals(logs.take(5).toSet(), delete.toSet())
        assertEquals(logs.last(), DecisionCsv.newest(names))
        assertTrue(DecisionCsv.filesToDelete(logs.take(3), keep = 20).isEmpty())
        assertNull(DecisionCsv.newest(listOf("notes.csv")))
    }
}
