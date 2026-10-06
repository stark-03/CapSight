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
    /** Hazard model (doors, stairs, poles, signs, potholes): its backend, or why it is absent. */
    val hazards: String = "",
    val error: String? = null,
    /** Frame the detections belong to; boxes are in its pixel space. */
    val frameSeq: Long = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Field of view the source reported for that frame, or null. */
    val frameFovDeg: Float? = null,
    val detections: List<Detection> = emptyList(),
    val inferenceMs: Long = 0,
    val totalMs: Long = 0,
    val fps: Float = 0f,
    /** SystemClock.elapsedRealtime() of the last result; 0 = none yet. Lets the loop spot a stalled detector. */
    val updatedAtMs: Long = 0,
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
                val hazards = loadHazards(useGpu)
                val hazardsText = hazards?.backend ?: hazardsMissing
                _state.value = DetectionState(running = true, backend = detector.backend, hazards = hazardsText)
                val fps = FpsMeter()
                try {
                    frames.filterNotNull().collect { frame ->
                        detectOne(detector, hazards, hazardsText, frame, minScore, classes, fps)
                    }
                } finally {
                    detector.close()
                    hazards?.close()
                }
            }
        } finally {
            dispatcher.close()
            _state.value = DetectionState()
        }
    }

    private var hazardsMissing = ""

    /** The optional hazard detector; the app works without it (CLAUDE.md §7, ml/hazards/). */
    private fun loadHazards(useGpu: Boolean): YoloDetector? {
        val present = try {
            context.assets.list("")?.contains(HazardLabels.MODEL_ASSET) == true
        } catch (e: java.io.IOException) {
            false
        }
        if (!present) {
            hazardsMissing = "not installed"
            return null
        }
        return try {
            YoloDetector(context, useGpu, HazardLabels.MODEL_ASSET, HazardLabels.NAMES, HazardLabels.ID_OFFSET)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Losing the extra model must not stop the main detector.
            Log.e(TAG, "hazard model failed to load", e)
            hazardsMissing = "failed: ${e.message}"
            null
        }
    }

    private fun detectOne(
        detector: YoloDetector,
        hazards: YoloDetector?,
        hazardsText: String,
        frame: Frame,
        minScore: Float,
        classes: Set<Int>?,
        fps: FpsMeter,
    ) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val main = detector.detect(frame.bitmap, minScore, classes)
            val extra = hazards?.detect(frame.bitmap, minScore, null)
            val out = if (extra == null) {
                main
            } else {
                main.copy(detections = main.detections + extra.detections, inferenceMs = main.inferenceMs + extra.inferenceMs)
            }
            val now = SystemClock.elapsedRealtime()
            fps.onFrame(now)
            _state.value = DetectionState(
                running = true,
                backend = detector.backend,
                hazards = hazardsText,
                frameSeq = frame.seq,
                frameWidth = frame.width,
                frameHeight = frame.height,
                frameFovDeg = frame.fovDeg,
                detections = out.detections,
                inferenceMs = out.inferenceMs,
                totalMs = now - t0,
                fps = fps.fps(now),
                updatedAtMs = now,
            )
        } catch (e: Exception) {
            // One bad frame must not stop detection; the guidance loop (step 6) fails safe.
            Log.w(TAG, "detection failed on frame ${frame.seq}", e)
            _state.value = _state.value.copy(error = "Detection error: ${e.message}")
        }
    }
}
