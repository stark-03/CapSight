package com.example.capsight.voice

/** Instruction priorities, lower wins (CLAUDE.md §8, SRS §4.7). */
object Priority {
    const val STOP = 0
    const val STAIRS = 1
    const val AVOID = 2
    const val BLOCKED = 3
    const val CLEAR = 4

    /** Status announcements ("Camera connected") - below every navigation instruction. */
    const val INFO = 5
}

/** One phrase handed to the speech engine. */
data class Utterance(val text: String, val priority: Int) {
    /** P0 cuts off whatever is being said (FR-VOICE-03). */
    val interrupts: Boolean get() = priority == Priority.STOP
}

/**
 * Which phrase to say next - a port of the rules in reference/blenav/voice.py (FR-VOICE-03/04,
 * FR-NAV-04).
 *
 * A queue is the wrong structure for navigation: if five instructions pile up, the user must
 * hear the newest, not four stale commands while walking into a chair. So there is ONE pending
 * slot. A new phrase replaces the pending one unless the pending one is more urgent, and an
 * identical phrase is suppressed while it is still within its cooldown since it was last spoken.
 *
 * Pure Kotlin: times are passed in. Not thread-safe; [Speaker] serialises access.
 */
class SpeechArbiter {
    var pending: Utterance? = null
        private set

    private val spokenAt = HashMap<String, Long>()

    /** Returns true if accepted into the slot, false if suppressed. */
    fun offer(text: String, priority: Int, cooldownMs: Long, nowMs: Long): Boolean {
        val last = spokenAt[text]
        if (last != null && nowMs - last < cooldownMs) return false
        val p = pending
        if (p != null && p.priority < priority) return false
        pending = Utterance(text, priority)
        return true
    }

    /** Empties the slot and starts the phrase's cooldown, as voice.py does at dispatch. */
    fun takeNext(nowMs: Long): Utterance? {
        val u = pending ?: return null
        pending = null
        spokenAt[u.text] = nowMs
        return u
    }
}
