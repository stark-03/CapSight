package com.example.capsight.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-RX-01 / FR-APP-04: configurable camera address and the §4.1 health probe. */
class CameraContractTest {

    @Test
    fun `default base URL maps to the real device endpoints`() {
        val e = CameraContract.endpoints(CameraContract.DEFAULT_BASE_URL)!!
        assertEquals("http://192.168.4.1/", e.probeUrl)
        assertEquals("http://192.168.4.1:81/stream", e.streamUrl)
    }

    @Test
    fun `scheme, trailing slash and whitespace are optional`() {
        val expected = CameraContract.endpoints("http://192.168.4.1")
        assertEquals(expected, CameraContract.endpoints("192.168.4.1"))
        assertEquals(expected, CameraContract.endpoints(" http://192.168.4.1/ "))
        assertEquals(expected, CameraContract.endpoints("http://192.168.4.1:80"))
    }

    @Test
    fun `fake camera on a laptop address`() {
        val e = CameraContract.endpoints("http://10.42.0.1")!!
        assertEquals("http://10.42.0.1/", e.probeUrl)
        assertEquals("http://10.42.0.1:81/stream", e.streamUrl)
    }

    @Test
    fun `non-standard port puts the stream on port plus one`() {
        val e = CameraContract.endpoints("http://192.168.1.20:8080")!!
        assertEquals("http://192.168.1.20:8080/", e.probeUrl)
        assertEquals("http://192.168.1.20:8081/stream", e.streamUrl)
    }

    @Test
    fun `rejects addresses that are not a plain http host`() {
        assertNull(CameraContract.endpoints(""))
        assertNull(CameraContract.endpoints("https://192.168.4.1"))
        assertNull(CameraContract.endpoints("http://192.168.4.1:81/stream"))
        assertNull(CameraContract.endpoints("http://192.168.4.1/?x=1"))
        assertNull(CameraContract.endpoints("http://"))
        assertNull(CameraContract.endpoints("not a url"))
        assertNull(CameraContract.endpoints("http://192.168.4.1:65535"))
    }

    @Test
    fun `health probe needs HTTP 200 and the BLENAV_CAM marker`() {
        val page = "<html><head><title>BLENAV_CAM</title></head></html>"
        assertTrue(CameraContract.isCameraLandingPage(200, page))
        assertFalse(CameraContract.isCameraLandingPage(404, page))
        assertFalse(CameraContract.isCameraLandingPage(200, "<title>Router login</title>"))
    }
}
