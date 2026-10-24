package com.example.capsight.service

import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.ReceiverStatus
import com.example.capsight.depth.DepthCue
import com.example.capsight.depth.DepthRule
import com.example.capsight.depth.DepthState
import com.example.capsight.detect.DetectionState
import com.example.capsight.detect.HazardLabels
import com.example.capsight.nav.Decision
import com.example.capsight.nav.Guidance
import com.example.capsight.nav.GuidanceConfig
import com.example.capsight.perception.SmoothedPerception
import com.example.capsight.perception.PerceptionParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.record.DecisionCsv
import com.example.capsight.record.DecisionEntry
import com.example.capsight.record.EntryKind
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
    /** FR-OBS-04 / FR-PATH-03: what the depth rule says this tick. */
    val depthCue: DepthCue = DepthCue.NONE,
    /** The depth rule's "Stop. Obstacle ahead." is in play (fresh or held). */
    val depthStop: Boolean = false,
    /** The depth rule saw a near centre that a YOLO obstacle already explains. */
    val depthExplained: Boolean = false,
)

/**
 * The 10 Hz decision ticker (FR-NAV-02): runs at a fixed rate whatever the detector speed,
 * reading the newest camera and detector state, and speaks the decision.
 *
 * Fail safe (FR-NAV-05, NFR-REL-01): a tick that throws says "Stop."; a detector that failed or
 * stopped producing results for as long as the camera-lost timeout is a P0 too; and nothing is
 * said to be clear before the detector has fresh results.
 *
 * Landmarks-only mode ([run] with cameraOff, CLAUDE.md §5.1): there are no frames by design, so
 * the loop ticks silently - no "Camera disconnected", no obstacles, no "Path clear." and no
 * fail-safe for missing frames. BLE landmarks are spoken by the pipeline, not here.
 *
 * Every phrase the speaker accepts is also handed to [onSpoken] (the FR-APP-07 decision log); a
 * failure there is logged and ignored, guidance carries on.
 */
class GuidanceLoop(
    private val speaker: Speaker,
    private val onSpoken: (DecisionEntry) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    /**
     * Ticks until cancelled. [speak] false still decides (for the screen) but stays quiet.
     * [cameraOff] = landmarks-only mode: link, detection and depth are ignored, every decision is silent.
     */
    suspend fun run(
        link: StateFlow<ReceiverStatus>,
        detection: StateFlow<DetectionState>,
        depth: StateFlow<DepthState>,
        paramsFor: (DetectionState) -> PerceptionParams,
        config: GuidanceConfig,
        cameraLostMs: Long,
        periodMs: Long,
        speak: Boolean,
        cameraOff: Boolean = false,
    ) {
        val guidance = Guidance(config)
        // FR-DIST-04: per-object distance smoothing, fed once per detector result.
        val perception = SmoothedPerception()
        // FR-OBS-04: the sustained near-centre rule, fed once per depth result.
        val depthRule = DepthRule(config.depth)
        var cue = DepthCue.NONE
        var prevLink: LinkState? = null
        var ticks = 0L
        val started = clock()
        var next = started
        Log.i(TAG, "guidance loop start, every $periodMs ms${if (cameraOff) " (camera off, landmarks only)" else ""}")
        try {
            while (true) {
                val now = clock()
                cue = if (cameraOff) DepthCue.NONE else depthCue(depthRule, depth.value, now)
                val decision = try {
                    if (cameraOff) {
                        guidance.tick(fresh = emptyList(), nowMs = now, cameraOff = true)
                    } else {
                        tickOnce(guidance, perception, link.value, detection.value, cue, paramsFor, cameraLostMs, now)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "guidance tick failed - failing safe", e)
                    Decision(Priority.STOP, Phrases.FAIL_SAFE, "tick error: ${e.message}", config.decision.stopCooldownMs)
                }
                val st = link.value
                if (speak) {
                    if (decision.speak && speaker.say(decision.message, decision.priority, decision.cooldownMs)) {
                        record(EntryKind.DECISION, decision.priority, decision.message, decision.reason, st, detection.value, guidance, now)
                    }
                    // FR-RX-04. Ranked above "Path clear." so it is heard first, below any obstacle warning.
                    if (!cameraOff && prevLink == LinkState.DISCONNECTED && st.link == LinkState.CONNECTED &&
                        speaker.say(Phrases.CAMERA_CONNECTED, Priority.BLOCKED, config.decision.phraseCooldownMs)
                    ) {
                        record(EntryKind.STATUS, Priority.BLOCKED, Phrases.CAMERA_CONNECTED, "camera link back", st, detection.value, guidance, now)
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
                    depthCue = cue,
                    depthStop = guidance.depthStopActive,
                    depthExplained = guidance.depthExplained,
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

    /** FR-APP-07: one decision-log line. Never throws into the loop. */
    private fun record(
        kind: EntryKind,
        priority: Int,
        phrase: String,
        reason: String,
        st: ReceiverStatus,
        det: DetectionState,
        guidance: Guidance,
        now: Long,
    ) {
        try {
            onSpoken(
                DecisionEntry(
                    elapsedMs = now,
                    kind = kind,
                    priority = priority,
                    phrase = phrase,
                    reason = reason,
                    source = st.sourceLabel,
                    cameraFps = st.fps,
                    detectorFps = det.fps,
                    nearest = DecisionCsv.nearest(guidance.heldObstacles),
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "decision log entry dropped", e)
        }
    }

    /**
     * The depth rule's cue for this tick. Depth is an extra, never a dependency: any failure here
     * means no cue (guidance carries on exactly as without depth), never a failed tick.
     */
    private fun depthCue(rule: DepthRule, ds: DepthState, now: Long): DepthCue = try {
        rule.update(ds.scores, ds.updatedAtMs, now)
    } catch (e: Exception) {
        Log.w(TAG, "depth rule failed - ignoring depth this tick", e)
        rule.reset()
        DepthCue.NONE
    }

    private fun tickOnce(
        guidance: Guidance,
        perception: SmoothedPerception,
        st: ReceiverStatus,
        det: DetectionState,
        depth: DepthCue,
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
            perception.perceive(det.updatedAtMs, det.detections, det.frameWidth, det.frameHeight, paramsFor(det))
                .mapNotNull { it.relative }
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
            depth = depth,
        )
    }

    private companion object {
        const val MS_PER_S = 1_000f
    }
}
