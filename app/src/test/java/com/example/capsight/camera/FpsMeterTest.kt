package com.example.capsight.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsMeterTest {

    @Test
    fun `steady 15 fps reads 15`() {
        val m = FpsMeter()
        var t = 0L
        repeat(60) { m.onFrame(t); t += 66 }
        assertEquals(15f, m.fps(t), 1f)
    }

    @Test
    fun `rate falls to zero when frames stop`() {
        val m = FpsMeter()
        for (t in 0L until 1_000L step 100) m.onFrame(t)
        assertEquals(10f, m.fps(999), 0.01f)
        assertEquals(0f, m.fps(2_000), 0.01f)
    }

    @Test
    fun `no frames is zero`() {
        assertEquals(0f, FpsMeter().fps(12_345), 0f)
    }
}
