package com.example.capsight.camera

/**
 * Frames per second over a sliding window. Drops to zero by itself when frames
 * stop, so a frozen stream never shows a healthy rate. Pure, not thread-safe.
 */
class FpsMeter(private val windowMs: Long = DEFAULT_WINDOW_MS) {
    private val stamps = ArrayDeque<Long>()

    fun onFrame(nowMs: Long) {
        stamps.addLast(nowMs)
        trim(nowMs)
    }

    fun fps(nowMs: Long): Float {
        trim(nowMs)
        return stamps.size * MS_PER_S / windowMs
    }

    private fun trim(nowMs: Long) {
        while (stamps.isNotEmpty() && nowMs - stamps.first() >= windowMs) stamps.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1_000L
        private const val MS_PER_S = 1_000f
    }
}
