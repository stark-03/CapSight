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
        assertEquals("1.2", ok.stopDistanceM)
        assertEquals("2.5", ok.warnDistanceM)
        assertEquals("3", ok.phraseCooldownS)
        assertEquals("6", ok.clearRepeatS)
        assertTrue(ok.validate().isEmpty())
    }

    @Test
    fun `out-of-range and non-numeric values are rejected per field`() {
        val e = ok.copy(capFovDeg = "540", userHeightCm = "abc", stopDistanceM = "0.1").validate()
        assertEquals(setOf(HelperSettingsDraft.FOV, HelperSettingsDraft.HEIGHT, HelperSettingsDraft.STOP), e.keys)
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
}
