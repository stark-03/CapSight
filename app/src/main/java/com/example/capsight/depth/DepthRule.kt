package com.example.capsight.depth

/**
 * The depth rule's settings (FR-OBS-04 / FR-PATH-03). [enabled] and [threshold] are settings
 * (CLAUDE.md §8); the timing values are fixed, like TrackParams.
 */
data class DepthParams(
    val enabled: Boolean = true,
    /** Zone score that counts as "near" (1.0 = as near as the floor just ahead). Valid 0.6-1.5. */
    val threshold: Double = DEFAULT_THRESHOLD,
    /** A near zone is released only once its score drops below threshold minus this. */
    val hysteresis: Double = 0.1,
    /** The centre must stay near over consecutive results covering this long before "Stop". */
    val sustainMs: Long = 1_000L,
    /** A depth result older than this, or a gap this long between results, breaks the evidence. */
    val staleMs: Long = 1_500L,
) {
    companion object {
        const val DEFAULT_THRESHOLD = 1.0
    }
}

/** What the depth rule says this tick. NONE whenever depth is off, stale or unsure. */
data class DepthCue(
    /** Sustained near unknown obstacle in the centre: becomes "Stop. Obstacle ahead." unless YOLO explains it. */
    val centre: Boolean = false,
    /** Near wall/object on that side: the side chooser treats that side as blocked (FR-PATH-03). */
    val left: Boolean = false,
    val right: Boolean = false,
) {
    val any: Boolean get() = centre || left || right

    companion object {
        val NONE = DepthCue()

        /** Spoken label of a depth obstacle: "Stop. Obstacle ahead." Not a COCO or hazard class. */
        const val LABEL = "obstacle"
    }
}

/**
 * Turns depth scores into a [DepthCue] - the conservative rule decided 22 Oct 2026 (ml/depth/README.md).
 *
 * Centre: near only when the score is >= threshold on consecutive results covering
 * [DepthParams.sustainMs] (one near frame is never enough: pillars and reflections flicker), then
 * released as soon as a result drops below threshold - hysteresis. Sides: near at >= threshold,
 * released below threshold - hysteresis, no sustain (blocking a side only withholds "Move left/right";
 * it never makes the app say something new on its own).
 *
 * Fed once per depth result (keyed on its time, like SmoothedPerception) and asked every guidance
 * tick. Pure Kotlin, time passed in, not thread-safe: the guidance loop owns one.
 */
class DepthRule(private val p: DepthParams = DepthParams()) {
    private var lastResultMs: Long? = null
    private var runStartMs: Long? = null
    private var cue = DepthCue.NONE

    /**
     * @param scores the newest result's scores, null if there is none or it had no floor reference
     * @param resultAtMs when that result was produced (0 = none yet)
     * @param nowMs this tick
     */
    fun update(scores: DepthScores?, resultAtMs: Long, nowMs: Long): DepthCue {
        if (!p.enabled || scores == null || resultAtMs <= 0L || nowMs - resultAtMs > p.staleMs) {
            reset()
            return cue
        }
        if (resultAtMs != lastResultMs) {
            onResult(scores, resultAtMs)
            lastResultMs = resultAtMs
        }
        return cue
    }

    fun reset() {
        lastResultMs = null
        runStartMs = null
        cue = DepthCue.NONE
    }

    private fun onResult(s: DepthScores, atMs: Long) {
        val last = lastResultMs
        if (last != null && atMs - last > p.staleMs) {
            // Not consecutive: the evidence so far is too old to sustain anything.
            runStartMs = null
            cue = DepthCue.NONE
        }
        val on = p.threshold
        val off = p.threshold - p.hysteresis
        var centre = cue.centre
        if (centre) {
            if (s.centre < off) {
                centre = false
                runStartMs = null
            }
        } else if (s.centre >= on) {
            val start = runStartMs ?: atMs.also { runStartMs = it }
            centre = atMs - start >= p.sustainMs
        } else {
            runStartMs = null
        }
        cue = DepthCue(
            centre = centre,
            left = if (cue.left) s.left >= off else s.left >= on,
            right = if (cue.right) s.right >= off else s.right >= on,
        )
    }
}
