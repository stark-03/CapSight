package com.example.capsight.perception

import com.example.capsight.detect.Detection

/** How a detection matters for navigation right now. */
enum class Threat { NONE, WARN, STOP }

/** A detection with what perception made of it, for the debug view and the guidance loop. */
data class PerceivedObject(
    val detection: Detection,
    /** Null when it has no distance: unknown class, flat desk item, or below confidence. */
    val relative: RelativeObstacle?,
    val inCorridor: Boolean,
    val threat: Threat,
    /** Forward distance of this frame alone, before smoothing (FR-DIST-04); null without a distance. */
    val rawForwardM: Double? = relative?.forwardM,
    /** Same number while the object is followed from frame to frame; null when not tracked. */
    val trackId: Int? = null,
    /** Size-based forward distance (FR-DIST-01). */
    val sizeM: Double? = null,
    /** Ground-plane distance (FR-DIST-02), when the geometry applied. */
    val floorM: Double? = null,
    /** Which estimate the distance came from (FR-DIST-03); null without a distance. */
    val distanceSource: DistanceSource? = null,
)

/** Thresholds perception needs (CLAUDE.md §8 defaults live in settings). */
data class PerceptionParams(
    val fovDeg: Double,
    val minConfidence: Float,
    val skipUnreliable: Boolean,
    val corridorHalfWidthM: Double,
    val warnDistanceM: Double,
    val stopDistanceM: Double,
    /** Camera height and tilt for ground-plane distance; null = size-based only (no tilt sensor). */
    val ground: GroundParams? = null,
)

/** Detections of one frame -> obstacles with distance and corridor status (M4, M5). Pure Kotlin. */
object Perception {
    /**
     * Distance per object: size-based (FR-DIST-01); when [PerceptionParams.ground] is given and
     * applies to the box, also ground-plane (FR-DIST-02), and then the NEARER of the two is used
     * (FR-DIST-03: conservative - a wrong "far" is the dangerous error).
     *
     * With a [tracker], each object's distance is smoothed over time (FR-DIST-04) before the
     * corridor and threat are decided; [nowMs] is the time of this detector result. The sideways
     * offset is scaled with the distance, so the object stays on the same ray from the camera.
     */
    fun perceive(
        dets: List<Detection>,
        frameWidth: Int,
        frameHeight: Int,
        p: PerceptionParams,
        tracker: DistanceTracker? = null,
        nowMs: Long = 0L,
    ): List<PerceivedObject> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        val focal = Egocentric.focalFromFov(p.fovDeg, frameWidth)
        val measured = dets.map { d -> measure(d, frameWidth, frameHeight, focal, p) }
        val raw = measured.map { it?.rel }
        val smoothed = arrayOfNulls<DistanceTracker.Smoothed>(dets.size)
        if (tracker != null) {
            val measured = raw.indices.filter { raw[it] != null }
            val out = tracker.update(measured.map { DistanceTracker.Observation(dets[it], raw[it]!!.forwardM) }, nowMs)
            measured.forEachIndexed { k, i -> smoothed[i] = out[k] }
        }
        return dets.mapIndexed { i, d ->
            val r = raw[i]
            val s = smoothed[i]
            val rel = if (r != null && s != null && r.forwardM > 0.0) scaled(r, s.distanceM / r.forwardM) else r
            val inCorridor = rel != null && UserFrame.inCorridor(rel, p.corridorHalfWidthM, p.warnDistanceM)
            val threat = when {
                !inCorridor -> Threat.NONE
                rel!!.forwardM <= p.stopDistanceM -> Threat.STOP
                else -> Threat.WARN
            }
            val m = measured[i]
            PerceivedObject(
                d, rel, inCorridor, threat,
                rawForwardM = r?.forwardM,
                trackId = s?.trackId,
                sizeM = m?.sizeM,
                floorM = m?.floorM,
                distanceSource = m?.source,
            )
        }
    }

    private class Measured(val rel: RelativeObstacle, val sizeM: Double, val floorM: Double?, val source: DistanceSource)

    /** Size-based distance, fused with the ground-plane one when that applies (FR-DIST-03). */
    private fun measure(d: Detection, w: Int, h: Int, focal: Double, p: PerceptionParams): Measured? {
        val size = Egocentric.obstacles(listOf(d), w, focal, p.minConfidence, p.skipUnreliable, h).firstOrNull()
            ?: return null
        val floor = GroundPlane.distance(d, w, h, p.fovDeg, p.ground)
        return when {
            floor == null || size.forwardM <= 0.0 -> Measured(size, size.forwardM, null, DistanceSource.SIZE)
            floor < size.forwardM -> Measured(scaled(size, floor / size.forwardM), size.forwardM, floor, DistanceSource.FUSED_FLOOR)
            else -> Measured(size, size.forwardM, floor, DistanceSource.FUSED_SIZE)
        }
    }

    private fun scaled(r: RelativeObstacle, f: Double) =
        RelativeObstacle(r.label, r.forwardM * f, r.lateralM * f, r.distanceM * f)
}

/**
 * Perception with distance smoothing, run once per detector result (FR-DIST-04): the 10 Hz loop
 * reads the same result several times, and feeding it to the tracker again would pull the average
 * towards one frame. [key] identifies the result (DetectionState.updatedAtMs). Pure Kotlin.
 */
class SmoothedPerception(params: TrackParams = TrackParams()) {
    private val tracker = DistanceTracker(params)
    private var lastKey: Long? = null
    private var last: List<PerceivedObject> = emptyList()

    fun perceive(
        key: Long,
        dets: List<Detection>,
        frameWidth: Int,
        frameHeight: Int,
        p: PerceptionParams,
    ): List<PerceivedObject> {
        if (key == lastKey) return last
        last = Perception.perceive(dets, frameWidth, frameHeight, p, tracker, nowMs = key)
        lastKey = key
        return last
    }
}
