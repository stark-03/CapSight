package com.example.capsight.nav

import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import com.example.capsight.perception.UserFrame
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import java.util.Locale

/** Everything the policy looks at for one tick. The user is at the origin facing forward. */
data class GuidanceInput(
    /** Every obstacle with a measured position this tick (not only those in the corridor). */
    val obstacles: List<RelativeObstacle> = emptyList(),
    /** Needs the fine-tuned stairs model (FR-DET-04); empty until then. */
    val stairs: List<RelativeObstacle> = emptyList(),
    /** No frame for 2 s (FR-RX-03). */
    val cameraLost: Boolean = false,
    /** Detector failed to load or stopped producing results (NFR-REL-01, FR-NAV-05). */
    val systemFault: Boolean = false,
    /** False until the detector has fresh results: no basis for "Path clear." yet. */
    val perceptionReady: Boolean = true,
    /** Side chosen with hysteresis (SideChooser); null lets the policy pick from the zones. */
    val steerSide: Side? = null,
    /**
     * Landmarks-only mode (no camera by choice, CLAUDE.md §5.1): there is no vision to decide from,
     * so the policy stays silent - no "Camera disconnected", no obstacles, no "Path clear.".
     * BLE landmarks are spoken outside the policy and are not affected.
     */
    val cameraOff: Boolean = false,
)

/** Policy thresholds; defaults match CLAUDE.md §8 and live in settings (FR-NAV-03). */
data class DecisionConfig(
    val stopDistanceM: Double = 1.2,
    val warnDistanceM: Double = 2.5,
    val stairsWarnM: Double = 4.0,
    val corridorHalfWidthM: Double = 0.55,
    val phraseCooldownMs: Long = 3_000L,
    val stopCooldownMs: Long = 2_000L,
    val stairsCooldownMs: Long = 4_000L,
    val clearRepeatMs: Long = 6_000L,
)

/** One instruction and how soon it may repeat. Empty message = stay silent. */
data class Decision(val priority: Int, val message: String, val reason: String, val cooldownMs: Long) {
    val speak: Boolean get() = message.isNotEmpty()
}

/**
 * The navigation policy - a pure function, ported from reference/blenav/guidance.py decide()
 * (FR-NAV-01). Same inputs, same output: no threads, no clock, no hardware.
 *
 * First-person mode only: the BLE gating, calibration, heading and path-deviation rules of the
 * ceiling camera are not ported (CLAUDE.md §8). Objects are always named ("Chair ahead.").
 */
object Policy {

    fun decide(inp: GuidanceInput, cfg: DecisionConfig): Decision {
        // ---- No camera by choice: nothing to say (the camera-mode fail-safes below do not apply) ----
        if (inp.cameraOff) {
            return Decision(Priority.IDLE, "", CAMERA_OFF_REASON, cfg.phraseCooldownMs)
        }
        // ---- P0: the camera is gone - the user is walking blind ----
        if (inp.cameraLost) {
            return Decision(Priority.STOP, Phrases.CAMERA_LOST, "no frame for 2 s", cfg.stopCooldownMs)
        }
        // ---- P0: fail safe rather than go silent (FR-NAV-05) ----
        if (inp.systemFault) {
            return Decision(Priority.STOP, Phrases.FAIL_SAFE, "detector not working", cfg.stopCooldownMs)
        }
        // Nothing seen yet is not the same as nothing there.
        if (!inp.perceptionReady) {
            return Decision(Priority.IDLE, "", "waiting for the detector", cfg.phraseCooldownMs)
        }

        val half = cfg.corridorHalfWidthM
        val rels = inp.obstacles.filter { UserFrame.inCorridor(it, half, cfg.warnDistanceM) }
        val stairRels = inp.stairs.filter { UserFrame.inCorridor(it, half * 2, cfg.stairsWarnM) }

        // ---- P0: close enough that walking on means collision ----
        val imminent = rels.filter { it.forwardM <= cfg.stopDistanceM }
        imminent.minByOrNull { it.forwardM }?.let { n ->
            return Decision(Priority.STOP, Phrases.stop(n.label), "${n.label} at ${m(n.forwardM)}", cfg.stopCooldownMs)
        }

        // ---- P1: stairs are a fall risk, so they outrank ordinary obstacles ----
        stairRels.minByOrNull { it.forwardM }?.let { n ->
            return Decision(Priority.STAIRS, Phrases.STAIRS, "stairs at ${m(n.forwardM)}", cfg.stairsCooldownMs)
        }

        // ---- P2 / P3: obstacle ahead - steer around it if a side is open ----
        val nearest = rels.minByOrNull { it.forwardM }
        if (nearest != null) {
            val zones = ZoneAnalyzer.analyze(inp.obstacles, ZoneParams(half, cfg.warnDistanceM))
            val side = if (zones.left.blocked && zones.right.blocked) {
                null
            } else {
                inp.steerSide ?: ZoneAnalyzer.bestSide(zones, rels, half)
            }
            if (side == null) {
                return Decision(
                    Priority.BLOCKED, Phrases.PATH_BLOCKED,
                    "${nearest.label} at ${m(nearest.forwardM)}, both sides blocked", cfg.phraseCooldownMs,
                )
            }
            return Decision(
                Priority.AVOID, Phrases.avoid(nearest.label, side),
                "${nearest.label} at ${m(nearest.forwardM)}, steer ${side.name.lowercase()}", cfg.phraseCooldownMs,
            )
        }

        // ---- P4: all clear ----
        return Decision(Priority.CLEAR, Phrases.PATH_CLEAR, "path clear", cfg.clearRepeatMs)
    }

    const val CAMERA_OFF_REASON = "camera off - landmarks only"

    private fun m(v: Double) = String.format(Locale.US, "%.1f m", v)
}
