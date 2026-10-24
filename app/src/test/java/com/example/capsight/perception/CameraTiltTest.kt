package com.example.capsight.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * FR-DIST-02: camera pitch and roll from the phone's gravity sensor, with concrete readings.
 * Android axes: x right, y top edge, z out of the screen; gravity reads +9.81 along "up".
 */
class CameraTiltTest {

    private val g = 9.81
    private fun rad(deg: Double) = Math.toRadians(deg)

    @Test
    fun `upright portrait looking straight ahead is 0 pitch, 0 roll`() {
        val t = CameraTilt.fromGravity(Gravity(0.0, g, 0.0), 0)!!
        assertEquals(0.0, t.pitchDownDeg, 1e-9)
        assertEquals(0.0, t.rollDeg, 1e-9)
    }

    @Test
    fun `portrait tilted 30 deg towards the floor is 30 deg pitch down`() {
        // Top edge tips away from the user: the screen faces partly up (+z), the back camera partly down.
        val t = CameraTilt.fromGravity(Gravity(0.0, g * cos(rad(30.0)), g * sin(rad(30.0))), 0)!!
        assertEquals(30.0, t.pitchDownDeg, 1e-9)
        assertEquals(0.0, t.rollDeg, 1e-9)
    }

    @Test
    fun `tilted back to look up is negative pitch, flat face up is 90 down`() {
        assertEquals(-20.0, CameraTilt.fromGravity(Gravity(0.0, g * cos(rad(20.0)), -g * sin(rad(20.0))), 0)!!.pitchDownDeg, 1e-9)
        assertEquals(90.0, CameraTilt.fromGravity(Gravity(0.0, 0.0, g), 0)!!.pitchDownDeg, 1e-9)
    }

    @Test
    fun `portrait turned 10 deg clockwise is plus 10 roll`() {
        // Top edge tips right: world-up leans towards the device's left (-x).
        val t = CameraTilt.fromGravity(Gravity(-g * sin(rad(10.0)), g * cos(rad(10.0)), 0.0), 0)!!
        assertEquals(10.0, t.rollDeg, 1e-9)
        assertEquals(0.0, t.pitchDownDeg, 1e-9)
    }

    @Test
    fun `landscape with matching frame rotation is level`() {
        // Turned 90 deg counter-clockwise (top edge left): up is the device's +x; frames rotated 90.
        val left = CameraTilt.fromGravity(Gravity(g, 0.0, 0.0), 90)!!
        assertEquals(0.0, left.rollDeg, 1e-9)
        assertEquals(0.0, left.pitchDownDeg, 1e-9)
        // Turned 90 deg clockwise (top edge right): up is -x; frames rotated 270.
        assertEquals(0.0, CameraTilt.fromGravity(Gravity(-g, 0.0, 0.0), 270)!!.rollDeg, 1e-9)
        // Same landscape hold but frames still portrait-upright: the frame is rolled a quarter turn.
        assertEquals(-90.0, CameraTilt.fromGravity(Gravity(g, 0.0, 0.0), 0)!!.rollDeg, 1e-9)
    }

    @Test
    fun `landscape tilted 25 deg down keeps the pitch whatever the frame rotation`() {
        val t = CameraTilt.fromGravity(Gravity(g * cos(rad(25.0)), 0.0, g * sin(rad(25.0))), 90)!!
        assertEquals(25.0, t.pitchDownDeg, 1e-9)
        assertEquals(0.0, t.rollDeg, 1e-9)
    }

    @Test
    fun `pitch does not depend on the magnitude, a zero reading is no tilt`() {
        assertEquals(30.0, CameraTilt.fromGravity(Gravity(0.0, 2 * g * cos(rad(30.0)), 2 * g * sin(rad(30.0))), 0)!!.pitchDownDeg, 1e-9)
        assertNull(CameraTilt.fromGravity(Gravity(0.0, 0.0, 0.0), 0))
    }

    @Test
    fun `low-pass moves part of the way to each sample`() {
        val first = CameraTilt.lowPass(null, Gravity(0.0, g, 0.0), 0.1)
        assertEquals(Gravity(0.0, g, 0.0), first)
        val next = CameraTilt.lowPass(first, Gravity(0.0, g + 10.0, 5.0), 0.1)
        assertEquals(g + 1.0, next.y, 1e-9)
        assertEquals(0.5, next.z, 1e-9)
    }
}
