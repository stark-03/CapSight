package com.example.capsight.ui

import com.example.capsight.camera.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The main screen's status area and the first-run steps. */
class MainStatusTest {

    private fun build(
        running: Boolean = true,
        landmarksOnly: Boolean = false,
        fallback: Boolean = false,
        link: LinkState = LinkState.CONNECTED,
        ble: String = "BLE: scanning, 2 beacons",
        hints: Boolean = true,
    ) = MainStatus.build(running, landmarksOnly, fallback, link, ble, hints)

    @Test
    fun `stopped says so with the volume-button hint`() {
        val s = build(running = false, landmarksOnly = true)
        assertEquals(MainMode.STOPPED, s.mainMode)
        assertEquals("Stopped", s.mode)
        assertEquals(MainStatus.START_HINT, s.detail)
        assertEquals(MainStatus.START_HINT_NO_KEYS, build(running = false, hints = false).detail)
    }

    @Test
    fun `with camera names the camera link`() {
        assertEquals(MainStatus(MainMode.WITH_CAMERA, "Navigating with camera", "Camera connected"), build())
        assertEquals("Connecting to camera", build(link = LinkState.CONNECTING).detail)
        assertEquals("Camera disconnected - please stop", build(link = LinkState.DISCONNECTED).detail)
    }

    @Test
    fun `without camera explains the landmarks state and a refused camera`() {
        val s = build(landmarksOnly = true, link = LinkState.DISCONNECTED)
        assertEquals(MainMode.WITHOUT_CAMERA, s.mainMode)
        assertEquals("Navigating without camera - landmarks only", s.mode)
        assertEquals("Landmarks: scanning, 2 beacons.", s.detail)
        assertEquals(
            "Camera not available, so landmarks only. Landmarks: permission denied.",
            build(landmarksOnly = true, fallback = true, ble = "BLE: permission denied").detail,
        )
    }

    @Test
    fun `landmark line tolerates odd scanner text`() {
        assertEquals("Landmarks: not running.", MainStatus.landmarkLine("BLE:"))
        assertEquals("Landmarks: Bluetooth off.", MainStatus.landmarkLine("BLE: Bluetooth off."))
        assertEquals("Landmarks: something.", MainStatus.landmarkLine("something"))
    }

    @Test
    fun `first run shows the safety statement, then the welcome once, then the main screen`() {
        assertEquals(AppStage.LOADING, AppStage.of(null, null))
        assertEquals(AppStage.LOADING, AppStage.of(null, true))
        assertEquals(AppStage.SAFETY, AppStage.of(false, null))
        assertEquals(AppStage.SAFETY, AppStage.of(false, true))
        assertEquals(AppStage.LOADING, AppStage.of(true, null))
        assertEquals(AppStage.WELCOME, AppStage.of(true, false))
        assertEquals(AppStage.MAIN, AppStage.of(true, true))
    }
}
