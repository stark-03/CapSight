package com.example.capsight.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-APP-04 / FR-DIST-05: the helper's settings are validated before they are saved. */
class HelperSettingsTest {

    private val ok = HelperSettingsDraft.DEFAULTS

    @Test
    fun `defaults come from CLAUDE_md section 8 and are valid`() {
        assertEquals("http://192.168.4.1", ok.cameraBaseUrl)
        assertEquals("54", ok.capFovDeg)
        assertEquals("165", ok.userHeightCm)
        assertEquals("1.3", ok.cameraHeightM)
        assertEquals("1.2", ok.stopDistanceM)
        assertEquals("2.5", ok.warnDistanceM)
        assertEquals("3", ok.phraseCooldownS)
        assertEquals("6", ok.clearRepeatS)
        assertTrue(ok.landmarksEnabled)
        assertEquals("-70", ok.landmarkAnnounceDbm)
        assertEquals("-80", ok.landmarkRearmDbm)
        assertEquals("60", ok.landmarkRepeatS)
        assertTrue("FR-APP-06 volume-button control is on by default", ok.volumeButtonControl)
        assertTrue("FR-OBS-04 depth obstacle detection is on by default", ok.depthEnabled)
        assertEquals("1.00", ok.depthThreshold)
        assertTrue(ok.validate().isEmpty())
    }

    @Test
    fun `out-of-range and non-numeric values are rejected per field`() {
        val e = ok.copy(capFovDeg = "540", userHeightCm = "abc", stopDistanceM = "0.1").validate()
        assertEquals(setOf(HelperSettingsDraft.FOV, HelperSettingsDraft.HEIGHT, HelperSettingsDraft.STOP), e.keys)
    }

    @Test
    fun `FR-DIST-02 camera height must be 0_5 to 2_2 m`() {
        assertTrue(ok.copy(cameraHeightM = "0.5").validate().isEmpty())
        assertTrue(ok.copy(cameraHeightM = "2,2").validate().isEmpty())
        assertEquals(setOf(HelperSettingsDraft.CAMERA_HEIGHT), ok.copy(cameraHeightM = "0.4").validate().keys)
        assertEquals(setOf(HelperSettingsDraft.CAMERA_HEIGHT), ok.copy(cameraHeightM = "165").validate().keys)
    }

    @Test
    fun `warning distance must be beyond the stop distance`() {
        val e = ok.copy(stopDistanceM = "2.0", warnDistanceM = "1.5").validate()
        assertEquals(setOf(HelperSettingsDraft.WARN), e.keys)
    }

    @Test
    fun `decimal comma is accepted, bad camera address is not`() {
        assertTrue(ok.copy(stopDistanceM = "1,3").validate().isEmpty())
        assertEquals(setOf(HelperSettingsDraft.CAMERA), ok.copy(cameraBaseUrl = "http://x/stream").validate().keys)
    }

    @Test
    fun `BLE landmark re-arm level must be below the announce level`() {
        assertEquals(setOf(HelperSettingsDraft.REARM), ok.copy(landmarkAnnounceDbm = "-80", landmarkRearmDbm = "-75").validate().keys)
        assertEquals(
            setOf(HelperSettingsDraft.ANNOUNCE, HelperSettingsDraft.LANDMARK_REPEAT),
            ok.copy(landmarkAnnounceDbm = "70", landmarkRepeatS = "1").validate().keys,
        )
    }

    @Test
    fun `beacon distance defaults - on, -52 dBm at 1 m, exponent 2_5`() {
        assertTrue(ok.landmarkSayDistance)
        assertEquals("-52", ok.landmarkRssiAt1mDbm)
        assertEquals("2.5", ok.landmarkPathLoss)
        val cfg = LandmarkSettings().config
        assertTrue(cfg.sayDistance)
        assertEquals(-52, cfg.rssiAt1mDbm)
        assertEquals(2.5, cfg.pathLossExponent, 0.0)
        val off = LandmarkSettings(sayDistance = false, rssiAt1mDbm = -60, pathLossExponent = 3.0).config
        assertEquals(false, off.sayDistance)
        assertEquals(-60, off.rssiAt1mDbm)
        assertEquals(3.0, off.pathLossExponent, 0.0)
    }

    @Test
    fun `beacon signal at 1 m must be -90 to -20 dBm`() {
        assertTrue(ok.copy(landmarkRssiAt1mDbm = "-90").validate().isEmpty())
        assertTrue(ok.copy(landmarkRssiAt1mDbm = "-20").validate().isEmpty())
        for (bad in listOf("-91", "-19", "52", "", "loud")) {
            assertEquals(bad, setOf(HelperSettingsDraft.RSSI_1M), ok.copy(landmarkRssiAt1mDbm = bad).validate().keys)
        }
    }

    @Test
    fun `path-loss exponent must be 1_5 to 4_0`() {
        assertTrue(ok.copy(landmarkPathLoss = "1.5").validate().isEmpty())
        assertTrue(ok.copy(landmarkPathLoss = "4,0").validate().isEmpty())
        assertTrue(ok.copy(landmarkPathLoss = "3", landmarkSayDistance = false).validate().isEmpty())
        for (bad in listOf("1.4", "4.1", "0", "")) {
            assertEquals(bad, setOf(HelperSettingsDraft.PATH_LOSS), ok.copy(landmarkPathLoss = bad).validate().keys)
        }
    }

    @Test
    fun `path-loss exponent round-trips through the stored float`() {
        val lm = LandmarkSettings(pathLossExponent = 2.7f.toDouble(), rssiAt1mDbm = -48, sayDistance = false)
        val d = HelperSettingsDraft.from(CameraSettings(), NavigationSettings(), SpeechSettings(), lm, ControlSettings())
        assertEquals("2.7", d.landmarkPathLoss)
        assertEquals("-48", d.landmarkRssiAt1mDbm)
        assertEquals(false, d.landmarkSayDistance)
    }

    @Test
    fun `FR-OBS-04 depth threshold must be 0_6 to 1_5`() {
        assertTrue(ok.copy(depthThreshold = "0.6").validate().isEmpty())
        assertTrue(ok.copy(depthThreshold = "1,5").validate().isEmpty())
        assertTrue(ok.copy(depthThreshold = "0.85", depthEnabled = false).validate().isEmpty())
        for (bad in listOf("0.59", "1.51", "", "near")) {
            assertEquals(bad, setOf(HelperSettingsDraft.DEPTH_THRESHOLD), ok.copy(depthThreshold = bad).validate().keys)
        }
    }

    @Test
    fun `depth threshold round-trips through the stored float`() {
        val nav = NavigationSettings(depthThreshold = 0.85f.toDouble(), depthEnabled = false)
        val d = HelperSettingsDraft.from(CameraSettings(), nav, SpeechSettings(), LandmarkSettings(), ControlSettings())
        assertEquals("0.85", d.depthThreshold)
        assertEquals(false, d.depthEnabled)
    }
}
