package com.example.capsight.ble

/** Proximity thresholds (CLAUDE.md §4.6); most come from settings (§8). */
data class LandmarkConfig(
    /** Announce when the smoothed RSSI reaches this (about 2-4 m; tune on site). */
    val announceDbm: Int = -70,
    /** Re-arm only after the smoothed RSSI falls below this (hysteresis). */
    val rearmDbm: Int = -80,
    /** Never announce the same beacon twice within this. */
    val repeatMs: Long = 60_000L,
    /** RSSI is the median of the readings in this window. */
    val windowMs: Long = 3_000L,
    /** A beacon unseen this long is re-armed, as if the user had walked away. */
    val unseenRearmMs: Long = 10_000L,
    /**
     * Say the distance ("..., about 4 metres.") and speak approach updates / arrival. Off here, so a
     * bare tracker behaves exactly as the original §4.6 rules; the app's default (LandmarkSettings) is on.
     */
    val sayDistance: Boolean = false,
    /** Smoothed RSSI one metre from the beacon (path-loss model, [BeaconDistance]). */
    val rssiAt1mDbm: Int = -52,
    /** Path-loss exponent: 2 = open space, 3+ = walls / people in the way. */
    val pathLossExponent: Double = 2.5,
    /** A new distance step must persist this long before it counts ([StepFilter]). */
    val stepHoldMs: Long = 1_500L,
    /** At most one distance update per beacon within this. */
    val updateGapMs: Long = 4_000L,
)

/** One beacon as the debug view shows it. */
data class BeaconView(
    val key: String,
    val landmark: Landmark,
    /** Median RSSI over the window, dBm. */
    val smoothedDbm: Double,
    /** Will be announced when it comes close enough. */
    val armed: Boolean,
    /** Spoken within the repeat interval. */
    val recentlyAnnounced: Boolean,
    /** Estimated metres from [smoothedDbm] (approximate, §4.6). */
    val distanceM: Double = BeaconDistance.MAX_M,
    /** The (hysteresis-filtered) distance step, metres. */
    val step: Int = BeaconDistance.STEPS.last(),
    /** "You are here" was spoken since the last re-arm. */
    val arrived: Boolean = false,
)

/** What a due landmark phrase is. */
enum class CueKind {
    /** The once-per-approach announcement: "Room 304 door nearby[, about 4 metres]." */
    FIRST,

    /** The distance step dropped: "Room 304 door, about 2 metres." */
    CLOSER,

    /** The step reached 1 m: "Room 304 door, you are here." */
    ARRIVED,
}

/** A landmark phrase that is due now, with the numbers behind it (for the decision log). */
data class LandmarkCue(
    val landmark: Landmark,
    val kind: CueKind,
    val phrase: String,
    /** Smoothed RSSI, dBm. */
    val rssiDbm: Double,
    /** Estimated metres (unrounded). */
    val distanceM: Double,
    /** The distance step spoken (or that would be spoken), metres. */
    val step: Int,
) {
    /** Same priority as the landmark's first announcement: STAIRS P1, the rest INFO. */
    val priority: Int get() = landmark.priority
}

/**
 * Decides when to speak a BLE landmark (CLAUDE.md §4.6). Pure Kotlin: times are passed in
 * (SystemClock.elapsedRealtime on Android). Not thread-safe; the caller serialises access.
 *
 * RSSI jumps by 10 dB from one packet to the next as the body blocks the signal, so each beacon's
 * readings are smoothed with the median of the last [LandmarkConfig.windowMs]. A beacon is
 * announced once when the smoothed RSSI reaches [LandmarkConfig.announceDbm], then stays quiet
 * until it has fallen below [LandmarkConfig.rearmDbm] or been unseen for
 * [LandmarkConfig.unseenRearmMs] - so standing at the edge of the range does not repeat it - and
 * never twice within [LandmarkConfig.repeatMs].
 *
 * With [LandmarkConfig.sayDistance] the first announcement also says the rounded distance, and
 * until the next re-arm the beacon speaks again only when its distance step DROPS below the last
 * one spoken (at most once per [LandmarkConfig.updateGapMs]); reaching 1 m says "you are here"
 * once and ends the updates. Moving away never speaks.
 *
 * Announcing is two steps: [onReading] / [onAdvert] return what is due, and the caller calls
 * [announced] once the speaker accepted the phrase. A phrase the speaker refused (a more urgent
 * one waiting) is simply offered again with the next reading.
 */
class LandmarkTracker(private val cfg: LandmarkConfig = LandmarkConfig()) {

    private class Beacon(var landmark: Landmark, stepHoldMs: Long) {
        val times = ArrayDeque<Long>()
        val rssi = ArrayDeque<Int>()
        var lastSeenMs: Long? = null
        var armed = true
        var announcedMs: Long? = null
        val steps = StepFilter(stepHoldMs)
        var distanceM = BeaconDistance.MAX_M

        /** First announcement made since the last re-arm: distance updates may follow. */
        var approaching = false
        var spokenStep: Int? = null
        var spokenMs: Long? = null
        var arrived = false

        /** What [onAdvert] last offered; applied by [announced]. */
        var pending: LandmarkCue? = null

        fun rearm() {
            armed = true
            approaching = false
            arrived = false
            spokenStep = null
        }
    }

    private val beacons = HashMap<String, Beacon>()

    /**
     * One advertisement: [key] identifies the beacon (its Bluetooth address), [name] is the
     * advertised name. Returns the landmark if a phrase about it is due now, else null.
     */
    fun onReading(key: String, name: String?, rssiDbm: Int, nowMs: Long): Landmark? =
        onAdvert(key, name, rssiDbm, nowMs)?.landmark

    /** Like [onReading], but returns the due phrase itself (first announcement, closer or arrived). */
    fun onAdvert(key: String, name: String?, rssiDbm: Int, nowMs: Long): LandmarkCue? {
        val landmark = Landmark.parse(name) ?: return null
        val b = beacons.getOrPut(key) { Beacon(landmark, cfg.stepHoldMs) }
        b.landmark = landmark
        val lastSeen = b.lastSeenMs
        if (lastSeen != null && nowMs - lastSeen >= cfg.unseenRearmMs) {
            b.rearm()
            b.times.clear()
            b.rssi.clear()
            b.steps.reset()
        }
        b.lastSeenMs = nowMs
        b.times.addLast(nowMs)
        b.rssi.addLast(rssiDbm)
        trim(b, nowMs)
        val smoothed = median(b.rssi)
        if (smoothed < cfg.rearmDbm) b.rearm()
        b.distanceM = BeaconDistance.metres(smoothed, cfg.rssiAt1mDbm.toDouble(), cfg.pathLossExponent)
        val rawStep = BeaconDistance.step(b.distanceM)
        val last = b.announcedMs
        val firstDue = b.armed && smoothed >= cfg.announceDbm && (last == null || nowMs - last >= cfg.repeatMs)
        // The first announcement says the current (already median-smoothed) step: the filtered one may
        // still lag behind from far away. Updates use the filtered step, so jitter never speaks.
        val filtered = b.steps.update(rawStep, nowMs)
        val step = if (firstDue) rawStep else filtered

        fun cue(kind: CueKind, phrase: String) = LandmarkCue(landmark, kind, phrase, smoothed, b.distanceM, step)
        val spoken = b.spokenStep
        val spokenMs = b.spokenMs
        val closer = cfg.sayDistance && b.approaching && !b.arrived && spoken != null && step < spoken &&
            (spokenMs == null || nowMs - spokenMs >= cfg.updateGapMs)
        val due = when {
            firstDue -> cue(CueKind.FIRST, if (cfg.sayDistance) landmark.phraseAt(step) else landmark.phrase)
            closer && step == BeaconDistance.STEPS.first() -> cue(CueKind.ARRIVED, landmark.herePhrase)
            closer -> cue(CueKind.CLOSER, landmark.closerPhrase(step))
            else -> null
        }
        b.pending = due
        return due
    }

    /** The phrase last offered for [key] was accepted by the speaker: update its state. */
    fun announced(key: String, nowMs: Long) {
        val b = beacons[key] ?: return
        val cue = b.pending
        b.pending = null
        when (cue?.kind) {
            CueKind.CLOSER -> b.spokenStep = cue.step
            CueKind.ARRIVED -> {
                b.spokenStep = cue.step
                b.arrived = true
            }
            // FIRST (or a caller that never asked): disarm and start the repeat interval.
            CueKind.FIRST, null -> {
                b.armed = false
                b.announcedMs = nowMs
                b.approaching = true
                b.spokenStep = cue?.step
                cue?.let { b.steps.force(it.step) }
                // Already within 1 m: "about 1 metre" said it; no separate "you are here".
                b.arrived = cfg.sayDistance && cue?.step == BeaconDistance.STEPS.first()
            }
        }
        b.spokenMs = nowMs
    }

    /**
     * Beacons heard within the smoothing window, strongest first. Also forgets beacons unseen for
     * longer than the repeat interval (their "announced" time no longer matters).
     */
    fun snapshot(nowMs: Long): List<BeaconView> {
        beacons.values.removeAll { b -> b.lastSeenMs.let { it == null || nowMs - it > maxOf(cfg.repeatMs, cfg.unseenRearmMs) } }
        return beacons.mapNotNull { (key, b) ->
            trim(b, nowMs)
            if (b.rssi.isEmpty()) return@mapNotNull null
            BeaconView(
                key = key,
                landmark = b.landmark,
                smoothedDbm = median(b.rssi),
                armed = b.armed,
                recentlyAnnounced = b.announcedMs?.let { nowMs - it < cfg.repeatMs } ?: false,
                distanceM = b.distanceM,
                step = b.steps.step ?: BeaconDistance.step(b.distanceM),
                arrived = b.arrived,
            )
        }.sortedByDescending { it.smoothedDbm }
    }

    private fun trim(b: Beacon, nowMs: Long) {
        while (b.times.isNotEmpty() && nowMs - b.times.first() > cfg.windowMs) {
            b.times.removeFirst()
            b.rssi.removeFirst()
        }
    }

    companion object {
        /** Median; the mean of the middle two for an even count. Not called with an empty list. */
        fun median(values: Collection<Int>): Double {
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid].toDouble() else (s[mid - 1] + s[mid]) / 2.0
        }
    }
}
