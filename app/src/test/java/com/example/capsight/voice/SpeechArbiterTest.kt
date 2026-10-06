package com.example.capsight.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-VOICE-03/04, FR-NAV-04: single pending slot, priority and cooldown (port of voice.py). */
class SpeechArbiterTest {

    private val cooldown = 3_000L

    @Test
    fun `accepted phrase waits in the slot until taken`() {
        val a = SpeechArbiter()
        assertTrue(a.offer("Path clear.", Priority.CLEAR, cooldown, 0))
        assertEquals("Path clear.", a.takeNext(0)?.text)
        assertNull(a.takeNext(0))
    }

    @Test
    fun `FR-NAV-04 identical phrase is suppressed within its cooldown`() {
        val a = SpeechArbiter()
        a.offer("Chair ahead. Move left.", Priority.AVOID, cooldown, 0)
        a.takeNext(1_000)
        assertFalse(a.offer("Chair ahead. Move left.", Priority.AVOID, cooldown, 3_999))
        assertTrue(a.offer("Chair ahead. Move left.", Priority.AVOID, cooldown, 4_000))
    }

    @Test
    fun `cooldown counts from when the phrase was spoken, not offered`() {
        val a = SpeechArbiter()
        a.offer("Path clear.", Priority.CLEAR, cooldown, 0)
        a.takeNext(2_000)
        assertFalse(a.offer("Path clear.", Priority.CLEAR, cooldown, 4_500))
    }

    @Test
    fun `a different phrase is never blocked by another phrase's cooldown`() {
        val a = SpeechArbiter()
        a.offer("Chair ahead. Move left.", Priority.AVOID, cooldown, 0)
        a.takeNext(0)
        assertTrue(a.offer("Chair ahead. Move right.", Priority.AVOID, cooldown, 100))
    }

    @Test
    fun `newer phrase of equal priority replaces the pending one - no queue`() {
        val a = SpeechArbiter()
        a.offer("Chair ahead. Move left.", Priority.AVOID, cooldown, 0)
        a.offer("Person ahead. Move right.", Priority.AVOID, cooldown, 10)
        assertEquals("Person ahead. Move right.", a.takeNext(20)?.text)
        assertNull(a.takeNext(20))
    }

    @Test
    fun `less urgent phrase cannot displace a more urgent pending one`() {
        val a = SpeechArbiter()
        a.offer("Stop. Chair ahead.", Priority.STOP, 2_000, 0)
        assertFalse(a.offer("Path clear.", Priority.CLEAR, 6_000, 10))
        assertEquals("Stop. Chair ahead.", a.pending?.text)
    }

    @Test
    fun `more urgent phrase replaces a less urgent pending one`() {
        val a = SpeechArbiter()
        a.offer("Path blocked. Stop and turn.", Priority.BLOCKED, cooldown, 0)
        a.offer("Person ahead. Move right.", Priority.AVOID, cooldown, 10)
        assertEquals(Priority.AVOID, a.pending?.priority)
    }

    @Test
    fun `burst of instructions leaves only the most urgent newest one`() {
        val a = SpeechArbiter()
        a.offer("Path clear.", Priority.CLEAR, 6_000, 0)
        a.offer("Path blocked. Stop and turn.", Priority.BLOCKED, cooldown, 1)
        a.offer("Person ahead. Move right.", Priority.AVOID, cooldown, 2)
        a.offer("Path clear.", Priority.CLEAR, 6_000, 3)
        assertEquals("Person ahead. Move right.", a.takeNext(4)?.text)
    }

    @Test
    fun `FR-VOICE-03 only P0 interrupts speech in progress`() {
        assertTrue(Utterance("Stop. Chair ahead.", Priority.STOP).interrupts)
        assertFalse(Utterance("Caution. Stairs ahead.", Priority.STAIRS).interrupts)
        assertFalse(Utterance("Chair ahead. Move left.", Priority.AVOID).interrupts)
    }
}
