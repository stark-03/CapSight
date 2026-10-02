package com.example.capsight.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterboxTest {

    @Test
    fun `VGA camera frame is padded top and bottom`() {
        val lb = Letterbox.compute(640, 480, 320)
        assertEquals(0.5f, lb.scale, 1e-6f)
        assertEquals(320, lb.scaledWidth)
        assertEquals(240, lb.scaledHeight)
        assertEquals(0, lb.padX)
        assertEquals(40, lb.padY)
    }

    @Test
    fun `portrait frame is padded left and right (matches the Python export check)`() {
        val lb = Letterbox.compute(810, 1080, 320)
        assertEquals(0.296296f, lb.scale, 1e-5f)
        assertEquals(240, lb.scaledWidth)
        assertEquals(40, lb.padX)
        assertEquals(0, lb.padY)
    }

    @Test
    fun `model coordinates map back to frame pixels`() {
        val lb = Letterbox.compute(640, 480, 320)
        assertEquals(0f, lb.toFrameX(0f), 1e-4f)
        assertEquals(640f, lb.toFrameX(320f), 1e-4f)
        assertEquals(0f, lb.toFrameY(40f), 1e-4f)
        assertEquals(480f, lb.toFrameY(280f), 1e-4f)
        assertEquals(200f, lb.toFrameY(140f), 1e-4f)
    }

    @Test
    fun `points in the padding clamp to the frame edge`() {
        val lb = Letterbox.compute(640, 480, 320)
        assertEquals(0f, lb.toFrameY(10f), 0f)
        assertEquals(480f, lb.toFrameY(310f), 0f)
    }

    @Test
    fun `QVGA no-PSRAM frame uses the same mapping`() {
        val lb = Letterbox.compute(320, 240, 320)
        assertEquals(1f, lb.scale, 0f)
        assertEquals(40, lb.padY)
    }
}
