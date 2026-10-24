package com.example.capsight.control

/** The two hardware keys the gestures use (FR-APP-06, CLAUDE.md §8.2). */
enum class VolumeKey { UP, DOWN }

/** A recognised volume-key gesture: one key pressed exactly two or three times (CLAUDE.md §8.2). */
enum class Gesture(val key: VolumeKey, val presses: Int, val label: String) {
    /** Start navigation without the camera (landmarks only). */
    UP_TWICE(VolumeKey.UP, 2, "volume up x2"),

    /** Start navigation with the camera (or switch to it). */
    UP_THREE(VolumeKey.UP, 3, "volume up x3"),

    /** Stop navigation. */
    DOWN_TWICE(VolumeKey.DOWN, 2, "volume down x2"),

    /** Status: "where am I". */
    DOWN_THREE(VolumeKey.DOWN, 3, "volume down x3"),
    ;

    companion object {
        /** The gesture for [presses] of [key]; null for 1 or 4+ presses (just volume changes). */
        fun of(key: VolumeKey, presses: Int): Gesture? = entries.firstOrNull { it.key == key && it.presses == presses }
    }
}

/**
 * Recognises a deliberate burst of two or three presses of one volume key (FR-APP-06). Pure
 * Kotlin: the caller passes the time in and calls [poll] at [pollAtMs].
 *
 * Rules (CLAUDE.md §8.2):
 * - Presses of the same key form a burst while each follows the previous one within [windowMs]
 *   AND the burst is still open. A burst is decided [settleMs] after its last press, so with the
 *   defaults (window 600 ms, settle 500 ms) the effective largest gap is just under 500 ms: a press
 *   500 ms or more after the previous one starts a new burst.
 * - Exactly 2 or 3 presses = a [Gesture]; 1 or 4+ presses = no gesture (only volume changes).
 * - A press of the other key cancels the open burst (no gesture) and starts a new burst of its own.
 * - Holding a key is not a gesture: an auto-repeat ([press] with repeat = true, known in the
 *   Activity) or two presses closer than [minGapMs] (auto-repeats arrive every ~50 ms; the
 *   MediaSession path cannot tell repeats apart) mark the whole burst as held, and it is ignored.
 *
 * Single presses are never consumed here: the caller still changes the volume for every press and,
 * when [poll] reports a gesture, restores the volume from before the burst's first press ([press]
 * returned true).
 *
 * Call [poll] with the current time before every [press], so a burst that is already over is
 * decided before the next press starts a new one. Not thread-safe; one caller thread.
 */
class MultiPressDetector(
    private val windowMs: Long = WINDOW_MS,
    private val settleMs: Long = SETTLE_MS,
    private val minGapMs: Long = MIN_GAP_MS,
) {
    init {
        require(windowMs > 0 && settleMs in 1..windowMs && minGapMs in 0 until settleMs)
    }

    /** Key of the open burst; null = idle. */
    private var key: VolumeKey? = null
    private var count = 0
    private var held = false
    private var lastAtMs = 0L

    /** Presses in the open burst (0 = idle), for tests and logs. */
    val presses: Int get() = if (key == null) 0 else count

    /**
     * Registers one key press at [nowMs]; [repeat] = an auto-repeat of a held key. True if it opens
     * a new burst (save the volume now, to restore it if the burst becomes a gesture).
     */
    fun press(key: VolumeKey, nowMs: Long, repeat: Boolean = false): Boolean {
        val gap = nowMs - lastAtMs
        val joins = this.key == key && gap in 0..windowMs && gap < settleMs
        if (joins) {
            count++
            if (repeat || gap < minGapMs) held = true
        } else {
            this.key = key
            count = 1
            held = repeat
        }
        lastAtMs = nowMs
        return !joins
    }

    /** When [poll] should next be called, or null when no burst is open. */
    val pollAtMs: Long?
        get() = if (key == null) null else lastAtMs + settleMs

    /**
     * Decides the open burst once [settleMs] have passed since its last press: returns the gesture
     * exactly once, or null (too early, nothing open, 1 or 4+ presses, or held). The burst is closed
     * whenever it is decided, gesture or not.
     */
    fun poll(nowMs: Long): Gesture? {
        val k = key ?: return null
        if (nowMs - lastAtMs < settleMs) return null
        val gesture = if (held) null else Gesture.of(k, count)
        key = null
        count = 0
        held = false
        return gesture
    }

    companion object {
        /** Presses of one key further apart than this never form a burst (FR-APP-06: "within ~600 ms"). */
        const val WINDOW_MS = 600L

        /** A burst is decided this long after its last press, so two and three presses can be told apart. */
        const val SETTLE_MS = 500L

        /** Closer presses than this are a held key's auto-repeat (Android repeats every ~50 ms), not taps. */
        const val MIN_GAP_MS = 90L
    }
}
