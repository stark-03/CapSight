package com.example.capsight.nav

import com.example.capsight.depth.DepthCue
import com.example.capsight.depth.DepthParams
import com.example.capsight.path.HysteresisParams
import com.example.capsight.path.SideChooser
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.RelativeObstacle
import com.example.capsight.perception.Side
import com.example.capsight.perception.UserFrame
import kotlin.math.hypot

/** Everything one guidance tick needs besides the obstacles (CLAUDE.md §8 defaults in settings). */
data class GuidanceConfig(
    val decision: DecisionConfig = DecisionConfig(),
    /** Keep reporting a vanished obstacle for this many ticks (0.6 s at 10 Hz). */
    val holdTicks: Int = 6,
    val hysteresis: HysteresisParams = HysteresisParams(minHoldMs = 2_000L, switchMarginM = 0.5),
    /** FR-OBS-04 / FR-PATH-03: the monocular depth rule (threshold, sustain, hysteresis). */
    val depth: DepthParams = DepthParams(),
)

/**
 * The stateful part of one guidance tick, ported from GuidanceLoop._tick_first_person()
 * (FR-OBS-02, FR-PATH-04). Pure: no threads, the time is passed in.
 *
 * Asymmetric hold: react to a hazard the instant it appears, but do not stand down the instant it
 * vanishes. The detector misses objects to motion blur while walking, and one missed frame would
 * otherwise produce a false all-clear at the moment the user is closest. The hold keeps the last
 * MEASUREMENT, not a stale pixel box.
 *
 * Depth (FR-OBS-04 / FR-PATH-03): a [DepthCue] becomes unlabeled obstacles fed to the same policy -
 * a centre one at the stop distance ("Stop. Obstacle ahead.", P0) unless a YOLO obstacle or stairs
 * already held in the corridor within the stop distance explain it, and one in each near side lane
 * so "Move left/right" never points into a wall. Depth obstacles have their own hold of the same
 * length: they never refresh, or replace, the held YOLO obstacles. With [DepthCue.NONE] the tick is
 * exactly what it was without depth.
 */
class Guidance(private val cfg: GuidanceConfig) {
    private var lastObstacles: List<RelativeObstacle> = emptyList()
    private var lastStairs: List<RelativeObstacle> = emptyList()
    private var holdLeft = 0
    private var lastDepth: List<RelativeObstacle> = emptyList()
    private var depthHoldLeft = 0
    private val sides = SideChooser(cfg.hysteresis)

    /** Obstacles after the hold, for display. */
    var heldObstacles: List<RelativeObstacle> = emptyList()
        private set
    var heldStairs: List<RelativeObstacle> = emptyList()
        private set

    /** The depth rule's "Stop. Obstacle ahead." obstacle is in play this tick (fresh or held). */
    var depthStopActive: Boolean = false
        private set

    /** The depth rule saw a near centre, but a YOLO obstacle already explains it. */
    var depthExplained: Boolean = false
        private set

    fun tick(
        fresh: List<RelativeObstacle>,
        nowMs: Long,
        freshStairs: List<RelativeObstacle> = emptyList(),
        cameraLost: Boolean = false,
        systemFault: Boolean = false,
        perceptionReady: Boolean = true,
        depth: DepthCue = DepthCue.NONE,
        cameraOff: Boolean = false,
    ): Decision {
        // Landmarks-only mode (CLAUDE.md §5.1): no vision, nothing held, silent decision.
        if (cameraOff) {
            reset()
            return Policy.decide(GuidanceInput(cameraOff = true), cfg.decision)
        }
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
        val d = cfg.decision
        val depthHeld = holdDepth(depthObstacles(depth, obstacles + stairs, d))
        depthStopActive = depthHeld.any { it.lateralM == 0.0 }
        obstacles = obstacles + depthHeld
        heldObstacles = obstacles
        heldStairs = stairs

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

    /** Forgets every held obstacle (camera off: nothing seen means nothing to hold). */
    private fun reset() {
        lastObstacles = emptyList()
        lastStairs = emptyList()
        holdLeft = 0
        lastDepth = emptyList()
        depthHoldLeft = 0
        heldObstacles = emptyList()
        heldStairs = emptyList()
        depthStopActive = false
        depthExplained = false
    }

    /** Unlabeled obstacles for this tick's depth cue (see the class comment). */
    private fun depthObstacles(cue: DepthCue, held: List<RelativeObstacle>, d: DecisionConfig): List<RelativeObstacle> {
        val explained = held.any { UserFrame.inCorridor(it, d.corridorHalfWidthM, d.stopDistanceM) }
        depthExplained = cue.centre && explained
        if (!cue.any) return emptyList()
        val at = d.stopDistanceM
        fun ob(lateral: Double) = RelativeObstacle(DepthCue.LABEL, at, lateral, hypot(at, lateral))
        return buildList {
            if (cue.centre && !explained) add(ob(0.0))
            if (cue.left) add(ob(ZoneAnalyzer.sideLaneCentreM(d.corridorHalfWidthM, Side.LEFT)))
            if (cue.right) add(ob(ZoneAnalyzer.sideLaneCentreM(d.corridorHalfWidthM, Side.RIGHT)))
        }
    }

    /** The same asymmetric hold as for YOLO obstacles, kept apart so depth never masks a dropout. */
    private fun holdDepth(fresh: List<RelativeObstacle>): List<RelativeObstacle> {
        if (fresh.isNotEmpty()) {
            lastDepth = fresh
            depthHoldLeft = cfg.holdTicks
            return fresh
        }
        if (depthHoldLeft > 0) {
            depthHoldLeft--
            return lastDepth
        }
        lastDepth = emptyList()
        return emptyList()
    }
}
