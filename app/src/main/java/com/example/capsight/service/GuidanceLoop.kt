package com.example.capsight.service

import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.ReceiverStatus
import com.example.capsight.detect.DetectionState
import com.example.capsight.detect.HazardLabels
import com.example.capsight.nav.Decision
import com.example.capsight.nav.Guidance
import com.example.capsight.nav.GuidanceConfig
import com.example.capsight.perception.Perception
import com.example.capsight.perception.PerceptionParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import com.example.capsight.voice.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the guidance loop last decided, for the screen. */
data class GuidanceState(
    val running: Boolean = false,
    val decision: Decision? = null,
    /** Obstacles after the dropout hold. */
    val obstacles: List<RelativeObstacle> = emptyList(),
    val ticks: Long = 0,
    val tickHz: Float = 0f,
)

/**
 * The 10 Hz decision ticker (FR-NAV-02): runs at a fixed rate whatever the detector speed,
 * reading the newest camera and detector state, and speaks the decision.
 *
 * Fail safe (FR-NAV-05, NFR-REL-01): a tick that throws says "Stop."; a detector that failed or
 * stopped producing results for as long as the camera-lost timeout is a P0 too; and nothing is
 * said to be clear before the detector has fresh results.
 *
 * Owned by the debug ViewModel until NavigationService takes over in build step 7.
 */
class GuidanceLoop(private val speaker: Speaker, private val clock: () -> Long = SystemClock::elapsedRealtime) {

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /** Ticks until cancelled. [speak] false still decides (for the screen) but stays quiet. */
    suspend fun run(
        link: StateFlow<ReceiverStatus>,
        detection: StateFlow<DetectionState>,
        paramsFor: (DetectionState) -> PerceptionParams,
        config: GuidanceConfig,
        cameraLostMs: Long,
        periodMs: Long,
        speak: Boolean,
    ) {
        val guidance = Guidance(config)
        var prevLink: LinkState? = null
        var ticks = 0L
        val started = clock()
        var next = started
        Log.i(TAG, "guidance loop start, every $periodMs ms")
        try {
            while (true) {
                val now = clock()
                val decision = try {
                    tickOnce(guidance, link.value, detection.value, paramsFor, cameraLostMs, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "guidance tick failed - failing safe", e)
                    Decision(Priority.STOP, Phrases.FAIL_SAFE, "tick error: ${e.message}", config.decision.stopCooldownMs)
                }
                val st = link.value
                if (speak) {
                    if (decision.speak) speaker.say(decision.message, decision.priority, decision.cooldownMs)
                    // FR-RX-04. Ranked above "Path clear." so it is heard first, below any obstacle warning.
                    if (prevLink == LinkState.DISCONNECTED && st.link == LinkState.CONNECTED) {
                        speaker.say(Phrases.CAMERA_CONNECTED, Priority.BLOCKED, config.decision.phraseCooldownMs)
                    }
                }
                if (decision.priority != _state.value.decision?.priority || decision.message != _state.value.decision?.message) {
                    Log.i(TAG, "decision P${decision.priority} \"${decision.message}\" (${decision.reason})")
                }
                prevLink = st.link
                ticks++
                val elapsed = (clock() - started).coerceAtLeast(1)
                _state.value = GuidanceState(
                    running = true,
                    decision = decision,
                    obstacles = guidance.heldObstacles,
                    ticks = ticks,
                    tickHz = ticks * MS_PER_S / elapsed,
                )
                next += periodMs
                val wait = next - clock()
                if (wait > 0) delay(wait) else next = clock() // fell behind: do not burst to catch up
            }
        } finally {
            Log.i(TAG, "guidance loop stop after $ticks ticks")
            _state.value = GuidanceState()
        }
    }

    private fun tickOnce(
        guidance: Guidance,
        st: ReceiverStatus,
        det: DetectionState,
        paramsFor: (DetectionState) -> PerceptionParams,
        cameraLostMs: Long,
        now: Long,
    ): Decision {
        val cameraLost = st.running && st.link == LinkState.DISCONNECTED
        val connected = st.link == LinkState.CONNECTED
        val detectorFailed = det.error != null && !det.running
        val stalled = connected && det.running && det.updatedAtMs > 0 && now - det.updatedAtMs > cameraLostMs
        val ready = connected && det.running && det.updatedAtMs > 0 && !stalled
        val measured = if (ready) {
            Perception.perceive(det.detections, det.frameWidth, det.frameHeight, paramsFor(det)).mapNotNull { it.relative }
        } else {
            emptyList()
        }
        // Stairs (hazard model) feed the P1 rule; everything else is an obstacle.
        val (stairs, fresh) = measured.partition { it.label == HazardLabels.STAIRS }
        return guidance.tick(
            fresh = fresh,
            nowMs = now,
            freshStairs = stairs,
            cameraLost = cameraLost,
            systemFault = detectorFailed || stalled,
            perceptionReady = ready,
        )
    }

    private companion object {
        const val MS_PER_S = 1_000f
    }
}
