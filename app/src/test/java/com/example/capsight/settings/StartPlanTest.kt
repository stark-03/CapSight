package com.example.capsight.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md §5.1: selected source x camera permission x landmarks on -> the session's source. */
class StartPlanTest {

    private fun plan(src: SourceKind, camera: Boolean, landmarks: Boolean) = StartPlan.decide(src, camera, landmarks)

    @Test
    fun `phone camera with permission uses the phone camera`() {
        for (lm in listOf(true, false)) {
            val p = plan(SourceKind.PHONE_CAMERA, camera = true, landmarks = lm)
            assertEquals(SourceKind.PHONE_CAMERA, p.source)
            assertTrue(p.usesPhoneCamera)
            assertFalse(p.cameraFallback)
            assertFalse(p.landmarksOnly)
        }
    }

    @Test
    fun `phone camera refused falls back to landmarks only for the session`() {
        val p = plan(SourceKind.PHONE_CAMERA, camera = false, landmarks = true)
        assertEquals(SourceKind.NONE, p.source)
        assertTrue(p.canStart)
        assertTrue(p.landmarksOnly)
        assertTrue(p.cameraFallback)
        assertFalse("no camera service type", p.usesPhoneCamera)
    }

    @Test
    fun `phone camera refused with landmarks off cannot start`() {
        val p = plan(SourceKind.PHONE_CAMERA, camera = false, landmarks = false)
        assertNull(p.source)
        assertFalse(p.canStart)
    }

    @Test
    fun `esp32 and video need no camera permission`() {
        for (src in listOf(SourceKind.ESP32, SourceKind.VIDEO_FILE)) {
            for (cam in listOf(true, false)) {
                for (lm in listOf(true, false)) {
                    val p = plan(src, cam, lm)
                    assertEquals("$src cam=$cam lm=$lm", src, p.source)
                    assertFalse(p.cameraFallback)
                    assertFalse(p.usesPhoneCamera)
                }
            }
        }
    }

    @Test
    fun `no camera chosen runs landmarks only, never a fallback`() {
        for (cam in listOf(true, false)) {
            val p = plan(SourceKind.NONE, cam, landmarks = true)
            assertEquals(SourceKind.NONE, p.source)
            assertTrue(p.landmarksOnly)
            assertFalse(p.cameraFallback)
        }
    }

    @Test
    fun `no camera chosen with landmarks off cannot start`() {
        for (cam in listOf(true, false)) assertFalse(plan(SourceKind.NONE, cam, landmarks = false).canStart)
    }

    // ---- volume-key wishes (CLAUDE.md §8.2) ----

    @Test
    fun `saved wish is the plain decision`() {
        for (src in SourceKind.entries) for (cam in listOf(true, false)) for (lm in listOf(true, false)) {
            assertEquals(StartPlan.decide(src, cam, lm), StartPlan.decide(StartWish.SAVED, src, cam, lm))
        }
    }

    @Test
    fun `no-camera wish is landmarks only whatever is selected`() {
        for (src in SourceKind.entries) {
            val p = StartPlan.decide(StartWish.NO_CAMERA, src, cameraPermission = true, landmarksEnabled = true)
            assertEquals(SourceKind.NONE, p.source)
            assertFalse(p.cameraFallback)
            assertFalse(StartPlan.decide(StartWish.NO_CAMERA, src, cameraPermission = true, landmarksEnabled = false).canStart)
        }
    }

    @Test
    fun `camera wish uses the selected camera, or the phone camera when none is selected`() {
        assertEquals(SourceKind.ESP32, StartPlan.decide(StartWish.CAMERA, SourceKind.ESP32, false, true).source)
        assertEquals(SourceKind.VIDEO_FILE, StartPlan.decide(StartWish.CAMERA, SourceKind.VIDEO_FILE, false, true).source)
        assertEquals(SourceKind.PHONE_CAMERA, StartPlan.decide(StartWish.CAMERA, SourceKind.NONE, true, true).source)
        assertEquals(SourceKind.PHONE_CAMERA, StartPlan.cameraFor(SourceKind.NONE))
    }

    @Test
    fun `camera wish without the permission falls back to landmarks only`() {
        val p = StartPlan.decide(StartWish.CAMERA, SourceKind.NONE, cameraPermission = false, landmarksEnabled = true)
        assertEquals(SourceKind.NONE, p.source)
        assertTrue(p.cameraFallback)
        assertFalse(StartPlan.decide(StartWish.CAMERA, SourceKind.PHONE_CAMERA, false, false).canStart)
    }
}
