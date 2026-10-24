package com.example.capsight.ble

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Rough distance to a BLE beacon from its smoothed RSSI (CLAUDE.md §4.6). Pure Kotlin.
 *
 * Log-distance path loss: d = 10^((rssiAt1m - rssi) / (10 * n)). [rssiAt1m] is the signal one metre
 * from the beacon (calibrated in the debug view), n the path-loss exponent (2 = open space, 3+ =
 * walls and people in the way). Approximate: about +/-1 m at 1-3 m, much worse farther or through
 * a body or wall, and no direction at all - which is why it is only ever spoken as rounded steps.
 */
object BeaconDistance {
    const val MIN_M = 0.3
    const val MAX_M = 30.0

    /** The distances ever spoken, nearest first: "about 1 metre" ... "about 20 metres". */
    val STEPS = listOf(1, 2, 3, 5, 8, 10, 15, 20)

    /**
     * Upper bound (exclusive) of each step but the last, in metres: below 1.5 -> 1, below 2.5 -> 2,
     * below 4 -> 3, below 6.5 -> 5, below 9 -> 8, below 12.5 -> 10, below 17.5 -> 15, else 20.
     */
    private val STEP_LIMITS = listOf(1.5, 2.5, 4.0, 6.5, 9.0, 12.5, 17.5)

    /** Estimated metres, clamped to [MIN_M, MAX_M]. A non-positive exponent or NaN gives [MAX_M] (safe: far). */
    fun metres(rssiDbm: Double, rssiAt1mDbm: Double, pathLossExponent: Double): Double {
        if (pathLossExponent <= 0.0) return MAX_M
        val d = 10.0.pow((rssiAt1mDbm - rssiDbm) / (10.0 * pathLossExponent))
        return if (d.isNaN()) MAX_M else d.coerceIn(MIN_M, MAX_M)
    }

    /** The spoken step for [metres] (one of [STEPS]). */
    fun step(metres: Double): Int {
        val i = STEP_LIMITS.indexOfFirst { metres < it }
        return if (i < 0) STEPS.last() else STEPS[i]
    }
}

/**
 * Hysteresis for the spoken distance step: the step changes only after the raw step has stayed on
 * the same side (nearer, or farther) of it for [holdMs]; it then takes the newest raw step. So a
 * reading jittering around a boundary never flips the step back and forth, while walking steadily
 * towards the beacon still moves it down. The first reading sets it at once. Pure: time passed in.
 */
class StepFilter(private val holdMs: Long) {
    /** The current (stable) step, or null before the first reading. */
    var step: Int? = null
        private set
    private var direction = 0
    private var sinceMs = 0L

    fun update(rawStep: Int, nowMs: Long): Int {
        val current = step ?: return rawStep.also { step = it }
        val d = (rawStep - current).sign
        if (d == 0) {
            direction = 0
            return current
        }
        if (d != direction) {
            direction = d
            sinceMs = nowMs
        }
        if (nowMs - sinceMs >= holdMs) {
            step = rawStep
            direction = 0
        }
        return step ?: rawStep
    }

    /** Sets the step outright (it was just spoken from the current reading). */
    fun force(newStep: Int) {
        step = newStep
        direction = 0
    }

    fun reset() {
        step = null
        direction = 0
    }
}

/**
 * The debug view's "Calibrate at 1 m": the phone is held 1 m from a beacon, so the strongest beacon
 * heard right now gives "Beacon signal at 1 m". Pure, for tests.
 */
sealed class Calibration {
    abstract val message: String

    data object NoBeacon : Calibration() {
        override val message = "No beacon heard - hold the phone 1 m from a beacon and try again"
    }

    data class OutOfRange(val dbm: Int) : Calibration() {
        override val message = "Signal $dbm dBm is outside $MIN_DBM to $MAX_DBM - not saved"
    }

    data class Ok(val dbm: Int, val landmark: Landmark) : Calibration() {
        override val message = "Calibrated: $dbm dBm at 1 m"
    }

    companion object {
        /** Valid "Beacon signal at 1 m" (also the Settings bounds). */
        const val MIN_DBM = -90
        const val MAX_DBM = -20

        /** [beacons] as the scanner lists them; the strongest smoothed RSSI wins, rounded to whole dBm. */
        fun from(beacons: List<BeaconView>): Calibration {
            val best = beacons.maxByOrNull { it.smoothedDbm } ?: return NoBeacon
            val dbm = best.smoothedDbm.roundToInt()
            return if (dbm in MIN_DBM..MAX_DBM) Ok(dbm, best.landmark) else OutOfRange(dbm)
        }
    }
}
