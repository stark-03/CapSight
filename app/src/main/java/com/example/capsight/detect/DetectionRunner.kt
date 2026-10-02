package com.example.capsight.detect

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import com.example.capsight.camera.FpsMeter
import com.example.capsight.camera.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Latest detections and how fast they come, for the debug overlay (FR-APP-05). */
data class DetectionState(
    val running: Boolean = false,
    val backend: String = "",
    val error: String? = null,
    /** Frame the detections belong to; boxes are in its pixel space. */
    val frameSeq: Long = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val detections: List<Detection> = emptyList(),
    val inferenceMs: Long = 0,
    val totalMs: Long = 0,
    val fps: Float = 0f,
)

/**
 * Runs [YoloDetector] on the newest frame, one at a time, on a single dedicated thread
 * (CLAUDE.md §5: the interpreter is not thread-safe). Frames arriving during an inference
 * are skipped because the source is a StateFlow - never queued.
 */
class DetectionRunner(private val context: Context) {

    private val _state = MutableStateFlow(DetectionState())
    val state: StateFlow<DetectionState> = _state.asStateFlow()

    /** Detects on [frames] until cancelled. */
    suspend fun run(frames: StateFlow<Frame?>, minScore: Float, useGpu: Boolean, classes: Set<Int>?) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "CapSight-detect") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                val detector = try {
                    YoloDetector(context, preferGpu = useGpu)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "detector failed to load", e)
                    _state.value = DetectionState(error = "Model failed to load: ${e.message}")
                    return@withContext
                }
                _state.value = DetectionState(running = true, backend = detector.backend)
                val fps = FpsMeter()
                try {
                    frames.filterNotNull().collect { frame -> detectOne(detector, frame, minScore, classes, fps) }
                } finally {
                    detector.close()
                }
            }
        } finally {
            dispatcher.close()
            _state.value = DetectionState()
        }
    }

    private fun detectOne(detector: YoloDetector, frame: Frame, minScore: Float, classes: Set<Int>?, fps: FpsMeter) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val out = detector.detect(frame.bitmap, minScore, classes)
            val now = SystemClock.elapsedRealtime()
            fps.onFrame(now)
            _state.value = DetectionState(
                running = true,
                backend = detector.backend,
                frameSeq = frame.seq,
                frameWidth = frame.width,
                frameHeight = frame.height,
                detections = out.detections,
                inferenceMs = out.inferenceMs,
                totalMs = now - t0,
                fps = fps.fps(now),
            )
        } catch (e: Exception) {
            // One bad frame must not stop detection; the guidance loop (step 6) fails safe.
            Log.w(TAG, "detection failed on frame ${frame.seq}", e)
            _state.value = _state.value.copy(error = "Detection error: ${e.message}")
        }
    }
}
