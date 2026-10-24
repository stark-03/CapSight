package com.example.capsight.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import com.example.capsight.TAG
import com.example.capsight.camera.FpsMeter
import com.example.capsight.camera.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The depth model's latest result and health, for the guidance loop and the debug view. */
data class DepthState(
    val running: Boolean = false,
    /** "GPU" or "CPU x4" while running. */
    val backend: String = "",
    /** Why depth is off ("switched off in Settings", "model not installed", ...); empty while running. */
    val off: String = "",
    /** Last per-frame problem, if the newest frame failed. */
    val error: String? = null,
    /** Zone scores of the newest result; null without one (or without a usable floor reference). */
    val scores: DepthScores? = null,
    val hz: Float = 0f,
    val inferenceMs: Long = 0,
    /** SystemClock.elapsedRealtime() of the newest result; 0 = none yet. */
    val updatedAtMs: Long = 0,
    /** Tiny greyscale depth map (white = near) for the debug view. */
    val thumbnail: Bitmap? = null,
)

/**
 * Runs [MidasDepth] on the newest frame at about [PERIOD_MS] (4 Hz), on its own single slightly
 * de-prioritised thread, so it never blocks or queues behind the YOLO detector (CLAUDE.md §5: newest
 * frame only, one interpreter per thread). Fail safe: a missing or failing model only turns depth
 * off, with the reason in [state]; guidance carries on without it.
 */
class DepthRunner(private val context: Context) {

    private val _state = MutableStateFlow(DepthState(off = "not running"))
    val state: StateFlow<DepthState> = _state.asStateFlow()

    /** Depth is not wanted (setting off): say why. */
    fun off(reason: String) {
        _state.value = DepthState(off = reason)
    }

    /** Estimates depth on [frames] until cancelled. */
    suspend fun run(frames: StateFlow<Frame?>, useGpu: Boolean) {
        if (!modelPresent()) {
            Log.w(TAG, "depth model ${MidasDepth.MODEL_ASSET} not in assets - depth off")
            off("model not installed")
            return
        }
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread({
                // Below the detector thread: when the CPU is short, YOLO wins.
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
                r.run()
            }, "CapSight-depth")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        var stopReason = "not running"
        try {
            withContext(dispatcher) {
                val model = try {
                    MidasDepth(context, preferGpu = useGpu)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "depth model failed to load - depth off", e)
                    stopReason = "model failed to load: ${e.message}"
                    return@withContext
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "depth model native library missing - depth off", e)
                    stopReason = "model failed to load: ${e.message}"
                    return@withContext
                }
                _state.value = DepthState(running = true, backend = model.backend)
                failures = 0
                val fps = FpsMeter(RATE_WINDOW_MS)
                var lastSeq = -1L
                try {
                    while (true) {
                        val start = SystemClock.elapsedRealtime()
                        val frame = frames.value
                        if (frame != null && frame.seq != lastSeq) {
                            lastSeq = frame.seq
                            estimateOne(model, frame, fps)
                        }
                        delay((PERIOD_MS - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(MIN_GAP_MS))
                    }
                } finally {
                    model.close()
                }
            }
        } finally {
            dispatcher.close()
            _state.value = DepthState(off = stopReason)
        }
    }

    private var failures = 0

    private fun estimateOne(model: MidasDepth, frame: Frame, fps: FpsMeter) {
        try {
            val out = model.estimate(frame.bitmap)
            val scores = DepthScorer.score(out.map, out.width, out.height)
            val now = SystemClock.elapsedRealtime()
            fps.onFrame(now)
            failures = 0
            _state.value = DepthState(
                running = true,
                backend = model.backend,
                error = if (scores == null) "no floor reference in this frame" else null,
                scores = scores,
                hz = fps.fps(now),
                inferenceMs = out.inferenceMs,
                updatedAtMs = now,
                thumbnail = thumbnail(out),
            )
        } catch (e: Exception) {
            // One bad frame only drops depth for that frame; never reaches the guidance loop.
            if (failures++ == 0) Log.w(TAG, "depth failed on frame ${frame.seq}", e)
            _state.value = _state.value.copy(scores = null, error = "Depth error: ${e.message}")
        }
    }

    private fun modelPresent(): Boolean = try {
        context.assets.list("")?.contains(MidasDepth.MODEL_ASSET) == true
    } catch (e: java.io.IOException) {
        false
    }

    /** Every [THUMB_STEP]th pixel, min-max stretched to grey (white = near). */
    private fun thumbnail(out: DepthOutput): Bitmap {
        val w = out.width / THUMB_STEP
        val h = out.height / THUMB_STEP
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in out.map) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = (hi - lo).takeIf { it > 0f && it.isFinite() } ?: 1f
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = out.map[(y * THUMB_STEP) * out.width + x * THUMB_STEP]
                val g = (((v - lo) / span) * MAX_GREY).toInt().coerceIn(0, MAX_GREY)
                px[y * w + x] = Color.rgb(g, g, g)
            }
        }
        return createBitmap(w, h).also { it.setPixels(px, 0, w, 0, 0, w, h) }
    }

    private companion object {
        /** ~4 Hz: enough for a 1 s sustain rule, light enough to leave YOLO its speed. */
        const val PERIOD_MS = 250L
        const val MIN_GAP_MS = 20L
        const val RATE_WINDOW_MS = 2_000L
        const val THUMB_STEP = 4
        const val MAX_GREY = 255
    }
}
