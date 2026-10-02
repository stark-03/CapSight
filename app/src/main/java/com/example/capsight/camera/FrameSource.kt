package com.example.capsight.camera

import android.graphics.Bitmap

/** One decoded frame, stamped by [FrameReceiver] with SystemClock.elapsedRealtime(). */
class Frame(val bitmap: Bitmap, val seq: Long, val timestampMs: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Anything that produces camera frames: the ESP32-CAM stream, the phone's own
 * camera, or a video file (FR-RX-05). The receiver decides link health from frame
 * timing, so a source only has to deliver frames and describe what it is doing.
 */
interface FrameSource {
    /** Short human-readable name for the debug screen and logs. */
    val label: String

    /**
     * Runs until the calling coroutine is cancelled. Delivers each upright frame to
     * [onFrame] (from any thread) and progress or problems to [onStatus]. A source
     * should retry on its own where that makes sense; if it throws, [FrameReceiver]
     * restarts it after a short pause.
     */
    suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit)
}
