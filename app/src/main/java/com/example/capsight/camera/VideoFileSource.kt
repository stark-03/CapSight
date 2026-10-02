package com.example.capsight.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * A recorded walk, for repeatable tests (FR-RX-05). Plays in real time and loops,
 * like a live camera: the position follows the wall clock, so slow decoding skips
 * frames instead of slowing the video down (newest-frame-only).
 *
 * Frames come from MediaMetadataRetriever, which seeks to each position. That is
 * fast for videos with frequent keyframes; re-encode long-GOP phone videos with
 *   ffmpeg -i walk.mp4 -vf scale=640:-2 -g 5 -an walk_640.mp4
 * for a smooth 10-15 fps.
 */
class VideoFileSource(private val context: Context, private val uri: Uri) : FrameSource {

    override val label = "Video file"

    override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            onStatus("Opening video")
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?: throw IOException("not a playable video: $uri")
            onStatus("Playing video (${durationMs / MS_PER_S} s, looping)")
            val start = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val t0 = SystemClock.elapsedRealtime()
                val positionUs = ((t0 - start) % durationMs) * US_PER_MS
                frameAt(retriever, positionUs)?.let(onFrame)
                delay((FRAME_INTERVAL_MS - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(0))
            }
        } finally {
            try {
                retriever.release()
            } catch (e: IOException) {
                Log.w(TAG, "retriever release failed", e)
            }
        }
    }

    /** Frame at the position, no larger than MAX_EDGE_PX on its long side. */
    private fun frameAt(retriever: MediaMetadataRetriever, positionUs: Long): Bitmap? {
        val option = MediaMetadataRetriever.OPTION_CLOSEST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(positionUs, option, MAX_EDGE_PX, MAX_EDGE_PX)
        }
        val full = retriever.getFrameAtTime(positionUs, option) ?: return null
        val scale = MAX_EDGE_PX.toFloat() / maxOf(full.width, full.height)
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(
            full, (full.width * scale).toInt(), (full.height * scale).toInt(), true,
        )
    }

    companion object {
        /** Matches the ESP32-CAM's 640-pixel-wide VGA frames. */
        private const val MAX_EDGE_PX = 640

        /** ~15 fps, inside the device's 10-20 fps range (contract §4.2). */
        private const val FRAME_INTERVAL_MS = 66L
        private const val US_PER_MS = 1_000L
        private const val MS_PER_S = 1_000L
    }
}
