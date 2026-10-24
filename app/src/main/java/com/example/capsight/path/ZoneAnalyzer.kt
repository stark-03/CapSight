package com.example.capsight.path

import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import com.example.capsight.perception.UserFrame
import kotlin.math.abs

/** One lane ahead of the user. */
data class Zone(
    /** Forward distance of the nearest obstacle in the lane, null if the lane is empty. */
    val nearestM: Double?,
    val nearestLabel: String?,
    /** Something within the warning distance. */
    val blocked: Boolean,
) {
    companion object {
        val EMPTY = Zone(null, null, false)
    }
}

data class Zones(val left: Zone, val centre: Zone, val right: Zone) {
    /** FR-PATH-02: centre and both sides blocked - no clear path (P3). */
    val allBlocked: Boolean get() = left.blocked && centre.blocked && right.blocked
}

/** Zone geometry and the warning distance (CLAUDE.md §8 defaults live in settings). */
data class ZoneParams(val corridorHalfWidthM: Double, val warnDistanceM: Double)

/**
 * Left / centre / right clearance (FR-PATH-01/02). Lanes are metric, not image thirds: the centre
 * lane is the walking corridor (|lateral| <= half-width) and each side lane is one corridor width
 * beside it - where a side-step would take the user. Pure Kotlin.
 *
 * LIMITATION: the camera cannot see the side lanes close up (at 1 m a 54-degree lens sees only
 * +-0.5 m), so an empty side lane near the user means "not seen", not "known clear". The white cane
 * covers that range (NFR-SAFE-01).
 */
object ZoneAnalyzer {

    fun analyze(obstacles: List<RelativeObstacle>, p: ZoneParams): Zones {
        val half = p.corridorHalfWidthM
        val sideOuter = half * SIDE_LANE_WIDTHS
        val ahead = obstacles.filter { it.forwardM > 0.0 }
        fun zone(members: List<RelativeObstacle>): Zone {
            val nearest = members.minByOrNull { it.forwardM } ?: return Zone.EMPTY
            return Zone(nearest.forwardM, nearest.label, nearest.forwardM <= p.warnDistanceM)
        }
        return Zones(
            left = zone(ahead.filter { it.lateralM > half && it.lateralM <= sideOuter }),
            centre = zone(ahead.filter { abs(it.lateralM) <= half }),
            right = zone(ahead.filter { it.lateralM < -half && it.lateralM >= -sideOuter }),
        )
    }

    /**
     * Which way to steer when the centre is blocked, ignoring history: null if both sides are
     * blocked; otherwise the side with more clearance; on a tie (usually both empty), the ported
     * world.free_side over the corridor obstacles - away from the nearest one, ties go right.
     */
    fun bestSide(zones: Zones, corridorObstacles: List<RelativeObstacle>, halfWidthM: Double): Side? {
        if (zones.left.blocked && zones.right.blocked) return null
        val l = zones.left.clearance()
        val r = zones.right.clearance()
        return when {
            l > r -> Side.LEFT
            r > l -> Side.RIGHT
            else -> UserFrame.freeSide(corridorObstacles, halfWidthM)
        }
    }

    /**
     * Lateral offset of the middle of a side lane, + = left. Where an obstacle known only by its
     * side (a near wall seen by the depth model, FR-PATH-03) is placed so it blocks that lane.
     */
    fun sideLaneCentreM(halfWidthM: Double, side: Side): Double {
        val m = halfWidthM * (1.0 + SIDE_LANE_WIDTHS) / 2.0
        return if (side == Side.LEFT) m else -m
    }

    /** Clearance of a lane; an empty lane is clear as far as we can tell. */
    fun Zone.clearance(): Double = nearestM ?: Double.POSITIVE_INFINITY

    /** Side lanes reach this many corridor half-widths from the centre line (one corridor width beside it). */
    private const val SIDE_LANE_WIDTHS = 3.0
}
