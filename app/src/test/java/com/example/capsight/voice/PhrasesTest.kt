package com.example.capsight.voice

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
}
