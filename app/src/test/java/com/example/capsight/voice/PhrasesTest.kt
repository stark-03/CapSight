package com.example.capsight.voice

import com.example.capsight.ble.Landmark
import com.example.capsight.ble.LandmarkType
import com.example.capsight.perception.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-VOICE-04: SRS §4.7 wording, at most five words where possible. */
class PhrasesTest {

    @Test
    fun `stop and avoid follow the SRS wording`() {
        assertEquals("Stop. Chair ahead.", Phrases.stop("chair"))
        assertEquals("Person ahead. Move left.", Phrases.avoid("person", Side.LEFT))
        assertEquals("Dining table ahead. Move right.", Phrases.avoid("dining table", Side.RIGHT))
    }

    @Test
    fun `FR-VOICE-04 fixed phrases are at most five words`() {
        listOf(
            Phrases.CAMERA_LOST, Phrases.CAMERA_CONNECTED, Phrases.STAIRS,
            Phrases.PATH_BLOCKED, Phrases.PATH_CLEAR, Phrases.stop("potted plant"),
        ).forEach { assertTrue(it, it.split(" ").size <= 5) }
    }

    // ---- volume-key control (FR-APP-06, CLAUDE.md §8.2) ----

    private val door = Landmark(LandmarkType.DOOR, "Room 304")

    @Test
    fun `status while stopped gives only the start hint`() {
        assertEquals("Navigation is stopped. Press volume up twice to start.", Phrases.status(null, door to 3, true, true))
    }

    @Test
    fun `status names the mode and the nearest landmark with its distance step`() {
        assertEquals(
            "Navigating without camera. Room 304 door, about 3 metres.",
            Phrases.status(false, door to 3, sayDistance = true, landmarksListening = true),
        )
        assertEquals(
            "Navigating with camera. Caution. Stairs, about 1 metre.",
            Phrases.status(true, Landmark(LandmarkType.STAIRS, "") to 1, sayDistance = true, landmarksListening = true),
        )
    }

    @Test
    fun `status without distance uses the plain landmark phrase`() {
        assertEquals("Navigating with camera. Room 304 door nearby.", Phrases.status(true, door to 3, sayDistance = false, landmarksListening = true))
    }

    @Test
    fun `status without a landmark says none or that landmarks are not available`() {
        assertEquals("Navigating with camera. No landmark nearby.", Phrases.status(true, null, true, landmarksListening = true))
        assertEquals("Navigating without camera. Landmarks are not available.", Phrases.status(false, null, true, landmarksListening = false))
    }

    @Test
    fun `start and stop phrases carry the gesture hints only while volume-button control is on`() {
        assertEquals("Navigation started with camera. Press volume down twice to stop.", Phrases.navStarted(camera = true, volumeHints = true))
        assertEquals(
            "Navigation started without camera. To use the camera, press volume up three times.",
            Phrases.navStarted(camera = false, volumeHints = true),
        )
        assertEquals(Phrases.NAV_STARTED, Phrases.navStarted(camera = true, volumeHints = false))
        assertEquals(Phrases.NAV_STARTED_LANDMARKS_ONLY, Phrases.navStarted(camera = false, volumeHints = false))
        assertEquals("Navigation stopped. Press volume up twice to start.", Phrases.navStopped(true))
        assertEquals(Phrases.NAV_STOPPED, Phrases.navStopped(false))
    }

    @Test
    fun `camera unavailable wording`() {
        assertEquals(
            "Camera is not available. Navigating without camera. Unlock the phone and press volume up three times, " +
                "or ask a helper to allow the camera in Helper settings, Phone setup.",
            Phrases.CAMERA_UNAVAILABLE,
        )
        assertEquals("Navigation is already stopped. Press volume up twice to start.", Phrases.ALREADY_STOPPED)
    }

    // ---- welcome / help ----

    @Test
    fun `help teaches the four volume gestures and that the camera is optional`() {
        val text = Phrases.help(volumeButtons = true).joinToString(" ")
        listOf(
            "Volume up twice: start without camera.",
            "Volume up three times: start with the camera",
            "Volume down twice: stop.",
            "Volume down three times: hear the status.",
            "The camera is optional.",
            "Keep using your cane.",
        ).forEach { assertTrue(it, text.contains(it)) }
    }

    @Test
    fun `help without volume buttons does not teach gestures that do nothing`() {
        val text = Phrases.help(volumeButtons = false).joinToString(" ")
        assertTrue(text.contains("volume buttons are switched off"))
        assertTrue(!text.contains("Volume up twice"))
        assertTrue(text.contains("The camera is optional."))
    }

    @Test
    fun `help lines are short enough to let warnings through between them`() {
        (Phrases.help(true) + Phrases.help(false) + Phrases.SAFETY_INTRO + Phrases.HELP_CLOSE).forEach {
            assertTrue(it, it.split(" ").size <= 25)
        }
    }
}

