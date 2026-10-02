package com.example.capsight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-RX-03 / FR-RX-04: "Camera disconnected" after 2 s without a frame. */
class LinkMonitorTest {

    private val lostAfter = 2_000L

    @Test
    fun `FR-RX-03 starts connecting and is disconnected if no frame ever arrives`() {
        val m = LinkMonitor(lostAfter, startMs = 1_000)
        assertEquals(LinkState.CONNECTING, m.update(1_000))
        assertEquals(LinkState.CONNECTING, m.update(3_000))
        assertEquals(LinkState.DISCONNECTED, m.update(3_001))
        assertEquals(0, m.disconnects)
    }

    @Test
    fun `FR-RX-03 frames keep the link connected`() {
        val m = LinkMonitor(lostAfter, startMs = 0)
        for (t in 100L..10_000L step 100) {
            assertEquals(LinkState.CONNECTED, m.onFrame(t))
        }
        assertEquals(LinkState.CONNECTED, m.update(12_000))
    }

    @Test
    fun `FR-RX-03 disconnected strictly after 2 s without a frame`() {
        val m = LinkMonitor(lostAfter, startMs = 0)
        m.onFrame(500)
        assertEquals(LinkState.CONNECTED, m.update(2_500))
        assertEquals(LinkState.DISCONNECTED, m.update(2_501))
        assertEquals(1, m.disconnects)
        assertEquals(2_001L, m.frameAgeMs(2_501))
    }

    @Test
    fun `FR-RX-04 next frame reconnects and each drop is counted once`() {
        val m = LinkMonitor(lostAfter, startMs = 0)
        m.onFrame(0)
        m.update(3_000)
        m.update(4_000)
        assertEquals(1, m.disconnects)
        assertEquals(LinkState.CONNECTED, m.onFrame(4_100))
        m.update(7_000)
        assertEquals(2, m.disconnects)
    }

    @Test
    fun `frame age is unknown before the first frame`() {
        assertNull(LinkMonitor(lostAfter, startMs = 0).frameAgeMs(5_000))
    }
}
