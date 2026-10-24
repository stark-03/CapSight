package com.example.capsight.perception

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A gravity reading in Android sensor coordinates, m/s^2. */
data class Gravity(val x: Double, val y: Double, val z: Double)

/**
 * Orientation of the camera's optical axis.
 *
 * - [pitchDownDeg]: angle of the optical axis BELOW the horizon. 0 = looking straight ahead,
 *   +30 = tilted 30 deg towards the floor, 90 = looking straight down, negative = looking up.
 *   (Note: the planned ESP32 /imu reports the opposite sign, "negative pitch = looking down".)
 * - [rollDeg]: rotation of the upright frame about the optical axis. 0 = the frame's horizontal
 *   axis is level; positive = the frame (phone) is turned CLOCKWISE as seen by the user looking at
 *   the screen, i.e. the top edge of the frame tips to the right.
 */
data class Tilt(val pitchDownDeg: Double, val rollDeg: Double)

/**
 * The phone camera's tilt from the phone's own gravity sensor (FR-DIST-02). Pure Kotlin.
 *
 * Android sensor axes, fixed to the device in its natural (portrait) orientation: x to the right
 * of the screen, y to the top edge, z out of the screen towards the user. At rest the gravity
 * (and accelerometer) sensor reports the vector pointing UP: (0, 9.81, 0) upright in portrait,
 * (0, 0, 9.81) lying face up. The back camera looks along -z.
 */
object CameraTilt {

    /**
     * @param g gravity reading (TYPE_GRAVITY, or a low-passed accelerometer)
     * @param uprightRotationDeg how the upright frame sits on the device: 0 = upright in portrait;
     *   90 = upright with the device turned 90 deg counter-clockwise (landscape, top edge to the
     *   left; Surface.ROTATION_90); 180; 270 (landscape, top edge to the right). This is the
     *   display rotation CameraX used to make the frames upright.
     * @return null if the reading has no length (no sensor data yet)
     */
    fun fromGravity(g: Gravity, uprightRotationDeg: Int): Tilt? {
        val n = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
        if (n < MIN_GRAVITY) return null
        val ux = g.x / n
        val uy = g.y / n
        val uz = g.z / n
        // Optical axis c = (0, 0, -1); its elevation is asin(c . up) = asin(-uz). Pitch down is minus that.
        val pitchDown = Math.toDegrees(asin(uz.coerceIn(-1.0, 1.0)))
        // The upright frame's up and right axes in device coordinates (both in the screen plane).
        val r = Math.toRadians(uprightRotationDeg.toDouble())
        val upX = sin(r)
        val upY = cos(r)
        val rightX = cos(r)
        val rightY = -sin(r)
        val alongUp = ux * upX + uy * upY
        val alongRight = ux * rightX + uy * rightY
        // Turned clockwise, world-up leans towards the frame's LEFT (negative "right" component).
        val roll = Math.toDegrees(atan2(-alongRight, alongUp))
        return Tilt(pitchDown, roll)
    }

    /** Low-pass filter for raw accelerometer samples: keeps gravity, removes walking jolts. */
    fun lowPass(previous: Gravity?, sample: Gravity, k: Double): Gravity =
        if (previous == null) {
            sample
        } else {
            Gravity(
                previous.x + k * (sample.x - previous.x),
                previous.y + k * (sample.y - previous.y),
                previous.z + k * (sample.z - previous.z),
            )
        }

    /** Below this the reading is noise (free fall or no data). */
    private const val MIN_GRAVITY = 1.0
}
