package com.example.capsight.path

import com.example.capsight.path.ZoneAnalyzer.clearance
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side

/** Hysteresis settings (FR-PATH-04). Defaults in NavigationSettings. */
data class HysteresisParams(
    /** Keep a chosen side at least this long. */
    val minHoldMs: Long,
    /** Within the hold, switch only if the other side is clearer by more than this. */
    val switchMarginM: Double,
)

/**
 * Picks the steering side with hysteresis so the user does not hear "left, right, left"
 * (FR-PATH-04). The previous side is kept unless it becomes blocked, or the other side is clearer
 * by [HysteresisParams.switchMarginM], or [HysteresisParams.minHoldMs] has passed and the other
 * side is now better. Forgets its side once the centre is clear.
 *
 * Pure Kotlin, time passed in. Not thread-safe; the guidance loop owns one.
 */
class SideChooser(private val params: HysteresisParams) {
    var current: Side? = null
        private set
    private var chosenAtMs = 0L

    /** The side to steer to, or null when no side is open (both blocked) or the centre is clear. */
    fun choose(zones: Zones, corridorObstacles: List<RelativeObstacle>, halfWidthM: Double, nowMs: Long): Side? {
        if (!zones.centre.blocked) {
            current = null
            return null
        }
        val best = ZoneAnalyzer.bestSide(zones, corridorObstacles, halfWidthM)
        val prev = current
        if (best == null) {
            current = null
            return null
        }
        if (prev == null || prev == best) {
            if (prev == null) chosenAtMs = nowMs
            current = best
            return best
        }
        val prevZone = if (prev == Side.LEFT) zones.left else zones.right
        val bestZone = if (best == Side.LEFT) zones.left else zones.right
        val held = nowMs - chosenAtMs < params.minHoldMs
        val muchBetter = bestZone.clearance() - prevZone.clearance() > params.switchMarginM
        val switch = prevZone.blocked || !held || muchBetter
        if (switch) {
            current = best
            chosenAtMs = nowMs
        }
        return current
    }

    fun reset() {
        current = null
    }
}
