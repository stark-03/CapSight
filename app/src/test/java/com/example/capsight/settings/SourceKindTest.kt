package com.example.capsight.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored frame-source value (DataStore string) round-trips, and bad values fall back safely. */
class SourceKindTest {

    @Test
    fun `every source round-trips through its stored name`() {
        for (kind in SourceKind.entries) assertEquals(kind, SourceKind.fromStored(kind.storedName))
    }

    @Test
    fun `no camera is stored as NONE`() {
        assertEquals("NONE", SourceKind.NONE.storedName)
        assertEquals(SourceKind.NONE, SourceKind.fromStored("NONE"))
    }

    @Test
    fun `unknown or missing stored value falls back to the default`() {
        val default = CameraSettings().source
        assertEquals(SourceKind.PHONE_CAMERA, default)
        assertEquals(default, SourceKind.fromStored(null))
        assertEquals(default, SourceKind.fromStored("CEILING_CAM"))
        assertEquals(default, SourceKind.fromStored(""))
        assertEquals(default, SourceKind.fromStored("none"))
        assertEquals(SourceKind.ESP32, SourceKind.fromStored("bogus", SourceKind.ESP32))
    }

    @Test
    fun `only NONE has no camera`() {
        assertFalse(SourceKind.NONE.hasCamera)
        SourceKind.entries.filter { it != SourceKind.NONE }.forEach { assertTrue(it.name, it.hasCamera) }
    }
}
