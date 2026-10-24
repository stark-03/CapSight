package com.example.capsight.perception

import com.example.capsight.detect.Detection

/** Tuning for [DistanceTracker] (FR-DIST-04). Fixed values, not user settings. */
data class TrackParams(
    /** Same label and at least this box overlap = the same object as in the last frame. */
    val minIou: Float = 0.3f,
    /** Weight of the new measurement in the moving average (0.5 halves the jitter of one frame). */
    val alpha: Double = 0.5,
    /** A track not matched for longer than this is forgotten. */
    val maxUnseenMs: Long = 1_000L,
    /**
     * Safety rule: a new distance this much (fraction) below the smoothed one is taken as is.
     * An approaching hazard must never be reported late because of smoothing.
     */
    val snapCloserFraction: Double = 0.30,
)

/**
 * Smooths each object's distance over time (FR-DIST-04). Size-based distances jump from frame to
 * frame with box jitter; following each object and averaging its distance makes warnings steadier.
 *
 * Matching is greedy: all (detection, track) pairs with the same label and IoU >= [TrackParams.minIou],
 * highest IoU first, each used once. A matched track keeps an exponential moving average, except
 * that a measurement more than [TrackParams.snapCloserFraction] NEARER than the average replaces it
 * at once. Getting further is always smoothed - the conservative direction.
 *
 * Pure Kotlin: the time is passed in, one [update] per detector result.
 */
class DistanceTracker(private val p: TrackParams = TrackParams()) {

    /** One measured object of the current frame. */
    data class Observation(val detection: Detection, val rawM: Double)

    /** [distanceM] is the smoothed distance; [trackId] stays the same while the object is followed. */
    data class Smoothed(val trackId: Int, val distanceM: Double)

    private class Track(val id: Int, var box: Detection, var smoothedM: Double, var lastSeenMs: Long)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    /** Tracks currently followed (for tests and the debug view). */
    val size: Int get() = tracks.size

    /** Smoothed distances for [obs], in the same order. */
    fun update(obs: List<Observation>, nowMs: Long): List<Smoothed> {
        tracks.removeAll { nowMs - it.lastSeenMs > p.maxUnseenMs }

        data class Candidate(val obs: Int, val track: Track, val iou: Float)
        val pairs = buildList {
            obs.forEachIndexed { i, o ->
                for (t in tracks) {
                    if (t.box.label != o.detection.label) continue
                    val iou = t.box.iou(o.detection)
                    if (iou >= p.minIou) add(Candidate(i, t, iou))
                }
            }
        }.sortedByDescending { it.iou }

        val matched = arrayOfNulls<Track>(obs.size)
        val usedTracks = HashSet<Int>()
        for (pair in pairs) {
            if (matched[pair.obs] != null || pair.track.id in usedTracks) continue
            matched[pair.obs] = pair.track
            usedTracks += pair.track.id
        }

        return obs.mapIndexed { i, o ->
            val t = matched[i]
            if (t == null) {
                val created = Track(nextId++, o.detection, o.rawM, nowMs)
                tracks += created
                Smoothed(created.id, o.rawM)
            } else {
                t.smoothedM = smooth(t.smoothedM, o.rawM)
                t.box = o.detection
                t.lastSeenMs = nowMs
                Smoothed(t.id, t.smoothedM)
            }
        }
    }

    fun clear() {
        tracks.clear()
    }

    private fun smooth(previous: Double, raw: Double): Double =
        if (raw < previous * (1.0 - p.snapCloserFraction)) {
            raw
        } else {
            p.alpha * raw + (1.0 - p.alpha) * previous
        }
}
