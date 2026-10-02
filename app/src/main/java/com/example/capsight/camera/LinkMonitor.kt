package com.example.capsight.camera

enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Decides whether the camera link is alive from frame arrival times alone
 * (FR-RX-03), whatever the source. No frame for [lostAfterMs] - including never
 * having had one since [startMs] - means DISCONNECTED. The next frame means
 * CONNECTED again (FR-RX-04).
 *
 * Pure logic: times are passed in (SystemClock.elapsedRealtime on Android).
 * Not thread-safe; the caller serialises access.
 */
class LinkMonitor(private val lostAfterMs: Long, private val startMs: Long) {
    var state: LinkState = LinkState.CONNECTING
        private set

    /** Number of CONNECTED -> DISCONNECTED transitions. */
    var disconnects: Int = 0
        private set

    private var lastFrameMs: Long? = null

    fun onFrame(nowMs: Long): LinkState {
        lastFrameMs = nowMs
        return update(nowMs)
    }

    fun update(nowMs: Long): LinkState {
        val since = lastFrameMs ?: startMs
        val next = when {
            nowMs - since > lostAfterMs -> LinkState.DISCONNECTED
            lastFrameMs == null -> LinkState.CONNECTING
            else -> LinkState.CONNECTED
        }
        if (state == LinkState.CONNECTED && next == LinkState.DISCONNECTED) disconnects++
        state = next
        return next
    }

    /** Milliseconds since the last frame, or null if none has arrived yet. */
    fun frameAgeMs(nowMs: Long): Long? = lastFrameMs?.let { nowMs - it }
}
