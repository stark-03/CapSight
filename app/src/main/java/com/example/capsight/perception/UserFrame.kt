package com.example.capsight.perception

import kotlin.math.hypot

/** Which way to steer. */
enum class Side { LEFT, RIGHT }

/** A point on the floor, metres. */
data class Vec2(val x: Double, val y: Double)

/** An obstacle in the user's own frame (port of world.py RelativeObstacle). */
data class RelativeObstacle(
    val label: String,
    /** Distance ahead along the user's heading. */
    val forwardM: Double,
    /** Positive = to the user's LEFT. */
    val lateralM: Double,
    val distanceM: Double,
) {
    val side: Side get() = if (lateralM > 0) Side.LEFT else Side.RIGHT

    /** Floor position for a user at the origin facing +Y (what egocentric_obstacles returned). */
    fun worldPosition(): Vec2 = Vec2(-lateralM, forwardM)
}

/** Obstacle geometry helpers ported from reference/blenav/world.py (FR-OBS-01, FR-PATH-02). */
object UserFrame {

    /** Express an obstacle in the user's forward/lateral frame. */
    fun relativeToUser(userPos: Vec2?, heading: Vec2?, obstaclePos: Vec2, label: String = "obstacle"): RelativeObstacle? {
        if (userPos == null || heading == null) return null
        val vx = obstaclePos.x - userPos.x
        val vy = obstaclePos.y - userPos.y
        val forward = vx * heading.x + vy * heading.y // dot
        val lateral = heading.x * vy - heading.y * vx // cross z: + is left of heading
        return RelativeObstacle(label, forward, lateral, hypot(vx, vy))
    }

    /** Is the obstacle in the walking corridor? Filters out the chair 3 m off to the side. */
    fun inCorridor(rel: RelativeObstacle, halfWidthM: Double, maxForwardM: Double): Boolean =
        rel.forwardM > 0.0 && rel.forwardM <= maxForwardM && kotlin.math.abs(rel.lateralM) <= halfWidthM

    /**
     * The side with more clearance. Equal counts: away from the nearest obstacle. No obstacles
     * or a dead-centre tie: right (keep-right convention).
     */
    @Suppress("UNUSED_PARAMETER") // kept for parity with world.free_side(rels, half_width_m)
    fun freeSide(rels: List<RelativeObstacle>, halfWidthM: Double): Side {
        val leftBlock = rels.count { it.lateralM >= 0 }
        val rightBlock = rels.count { it.lateralM < 0 }
        if (leftBlock < rightBlock) return Side.LEFT
        if (rightBlock < leftBlock) return Side.RIGHT
        val nearest = rels.minByOrNull { it.forwardM } ?: return Side.RIGHT
        return if (nearest.lateralM >= 0) Side.RIGHT else Side.LEFT
    }
}
