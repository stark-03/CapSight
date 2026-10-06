package com.example.capsight.nav

import com.example.capsight.path.HysteresisParams
import com.example.capsight.path.SideChooser
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.UserFrame

/** Everything one guidance tick needs besides the obstacles (CLAUDE.md §8 defaults in settings). */
data class GuidanceConfig(
    val decision: DecisionConfig = DecisionConfig(),
    /** Keep reporting a vanished obstacle for this many ticks (0.6 s at 10 Hz). */
    val holdTicks: Int = 6,
    val hysteresis: HysteresisParams = HysteresisParams(minHoldMs = 2_000L, switchMarginM = 0.5),
)

/**
 * The stateful part of one guidance tick, ported from GuidanceLoop._tick_first_person()
 * (FR-OBS-02, FR-PATH-04). Pure: no threads, the time is passed in.
 *
 * Asymmetric hold: react to a hazard the instant it appears, but do not stand down the instant it
 * vanishes. The detector misses objects to motion blur while walking, and one missed frame would
 * otherwise produce a false all-clear at the moment the user is closest. The hold keeps the last
 * MEASUREMENT, not a stale pixel box.
 */
class Guidance(private val cfg: GuidanceConfig) {
    private var lastObstacles: List<RelativeObstacle> = emptyList()
    private var lastStairs: List<RelativeObstacle> = emptyList()
    private var holdLeft = 0
    private val sides = SideChooser(cfg.hysteresis)

    /** Obstacles after the hold, for display. */
    var heldObstacles: List<RelativeObstacle> = emptyList()
        private set
    var heldStairs: List<RelativeObstacle> = emptyList()
        private set

    fun tick(
        fresh: List<RelativeObstacle>,
        nowMs: Long,
        freshStairs: List<RelativeObstacle> = emptyList(),
        cameraLost: Boolean = false,
        systemFault: Boolean = false,
        perceptionReady: Boolean = true,
    ): Decision {
        // Obstacles and stairs are held together: losing stairs for one frame must not mean clear.
        var obstacles = fresh
        var stairs = freshStairs
        if (fresh.isNotEmpty() || freshStairs.isNotEmpty()) {
            lastObstacles = fresh
            lastStairs = freshStairs
            holdLeft = cfg.holdTicks
        } else if (holdLeft > 0) {
            holdLeft--
            obstacles = lastObstacles
            stairs = lastStairs
        } else {
            lastObstacles = emptyList()
            lastStairs = emptyList()
        }
        heldObstacles = obstacles
        heldStairs = stairs

        val d = cfg.decision
        val zones = ZoneAnalyzer.analyze(obstacles, ZoneParams(d.corridorHalfWidthM, d.warnDistanceM))
        val corridor = obstacles.filter { UserFrame.inCorridor(it, d.corridorHalfWidthM, d.warnDistanceM) }
        val side = sides.choose(zones, corridor, d.corridorHalfWidthM, nowMs)
        return Policy.decide(
            GuidanceInput(
                obstacles = obstacles,
                stairs = stairs,
                cameraLost = cameraLost,
                systemFault = systemFault,
                perceptionReady = perceptionReady,
                steerSide = side,
            ),
            d,
        )
    }
}
