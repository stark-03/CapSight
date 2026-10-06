package com.example.capsight.perception

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.atan

class CameraFovTest {

    private fun fov(sideMm: Double, focalMm: Double) = Math.toDegrees(2 * atan(sideMm / (2 * focalMm)))

    // A typical 4:3 phone sensor: 6.4 x 4.8 mm active area, 4.8 mm lens.
    private val f = 4.8

    @Test
    fun `landscape 4-3 frame uses the full sensor width`() {
        assertEquals(fov(6.4, f), CameraFov.horizontalFovDeg(f, 6.4, 4.8, 640, 480, 0), 1e-9)
    }

    @Test
    fun `portrait frame (rotated 90) sees the sensor's short side across`() {
        assertEquals(fov(4.8, f), CameraFov.horizontalFovDeg(f, 6.4, 4.8, 640, 480, 90), 1e-9)
        assertEquals(fov(4.8, f), CameraFov.horizontalFovDeg(f, 6.4, 4.8, 640, 480, 270), 1e-9)
    }

    @Test
    fun `16-9 output from a 4-3 sensor keeps the width, crops the height`() {
        assertEquals(fov(6.4, f), CameraFov.horizontalFovDeg(f, 6.4, 4.8, 1280, 720, 0), 1e-9)
        assertEquals(fov(3.6, f), CameraFov.horizontalFovDeg(f, 6.4, 4.8, 1280, 720, 90), 1e-9)
    }

    @Test
    fun `4-3 output from a 16-9 sensor crops the width`() {
        // 6.4 x 3.6 sensor, 4:3 crop keeps 4.8 x 3.6.
        assertEquals(fov(4.8, f), CameraFov.horizontalFovDeg(f, 6.4, 3.6, 640, 480, 0), 1e-9)
    }

    @Test
    fun `typical phone main camera lands in the expected range`() {
        val portrait = CameraFov.horizontalFovDeg(f, 6.4, 4.8, 640, 480, 90)
        assert(portrait in 45.0..60.0) { "got $portrait" }
    }
}
