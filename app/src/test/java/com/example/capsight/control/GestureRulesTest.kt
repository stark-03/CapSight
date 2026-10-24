package com.example.capsight.control

import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.StartWish
import com.example.capsight.voice.Phrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-APP-06 (CLAUDE.md §8.2): what each volume-key gesture does in each state. */
class GestureRulesTest {

    /** Everything granted, landmarks on, phone camera selected, CapSight not on screen (pocket). */
    private fun inputs(
        state: RunState,
        selected: SourceKind = SourceKind.PHONE_CAMERA,
        landmarks: Boolean = true,
        camera: Boolean = true,
        ble: Boolean = true,
        notifications: Boolean = true,
        canAsk: Boolean = false,
    ) = ControlInputs(state, selected, landmarks, camera, ble, notifications, canAsk)

    private fun decide(g: Gesture, i: ControlInputs) = GestureRules.decide(g, i)

    private fun start(a: ControlAction): ControlAction.Start {
        assertTrue("expected a start, got $a", a is ControlAction.Start)
        return a as ControlAction.Start
    }

    // ---------------------------------------------------------------- stopped

    @Test
    fun `stopped - up x2 starts without camera`() {
        val s = start(decide(Gesture.UP_TWICE, inputs(RunState.STOPPED)))
        assertEquals(SourceKind.NONE, s.plan.source)
        assertTrue(s.plan.landmarksOnly)
        assertFalse(s.plan.cameraFallback)
        assertEquals(Phrases.NAV_STARTED_NO_CAMERA, s.announce)
    }

    @Test
    fun `stopped - up x3 starts with the phone camera`() {
        val s = start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED)))
        assertEquals(SourceKind.PHONE_CAMERA, s.plan.source)
        assertTrue(s.plan.usesPhoneCamera)
        assertEquals(Phrases.NAV_STARTED_CAMERA, s.announce)
    }

    @Test
    fun `stopped - up x3 uses the cap when the cap is selected, and the phone camera when no camera is`() {
        assertEquals(SourceKind.ESP32, start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, selected = SourceKind.ESP32, camera = false))).plan.source)
        assertEquals(SourceKind.PHONE_CAMERA, start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, selected = SourceKind.NONE))).plan.source)
    }

    @Test
    fun `stopped - down x2 only says it is already stopped`() {
        assertEquals(ControlAction.Say(Phrases.ALREADY_STOPPED), decide(Gesture.DOWN_TWICE, inputs(RunState.STOPPED)))
    }

    @Test
    fun `down x3 is the status in every state`() {
        for (st in RunState.entries) assertEquals(ControlAction.Status, decide(Gesture.DOWN_THREE, inputs(st)))
    }

    // ---------------------------------------------------------------- running without camera

    @Test
    fun `without camera - up x2 says so and does not restart`() {
        assertEquals(ControlAction.Say(Phrases.ALREADY_NO_CAMERA), decide(Gesture.UP_TWICE, inputs(RunState.NO_CAMERA)))
    }

    @Test
    fun `without camera - up x3 switches to the camera`() {
        val s = start(decide(Gesture.UP_THREE, inputs(RunState.NO_CAMERA)))
        assertEquals(SourceKind.PHONE_CAMERA, s.plan.source)
        assertEquals(Phrases.NAV_STARTED_CAMERA, s.announce)
    }

    @Test
    fun `without camera - up x3 without the camera permission stays and says why`() {
        assertEquals(ControlAction.Say(Phrases.CAMERA_UNAVAILABLE), decide(Gesture.UP_THREE, inputs(RunState.NO_CAMERA, camera = false)))
    }

    @Test
    fun `running - down x2 stops`() {
        assertEquals(ControlAction.Stop, decide(Gesture.DOWN_TWICE, inputs(RunState.NO_CAMERA)))
        assertEquals(ControlAction.Stop, decide(Gesture.DOWN_TWICE, inputs(RunState.CAMERA)))
    }

    // ---------------------------------------------------------------- running with camera

    @Test
    fun `with camera - up x2 and up x3 never turn the camera off or restart`() {
        assertEquals(ControlAction.Say(Phrases.ALREADY_CAMERA), decide(Gesture.UP_TWICE, inputs(RunState.CAMERA)))
        assertEquals(ControlAction.Say(Phrases.ALREADY_CAMERA), decide(Gesture.UP_THREE, inputs(RunState.CAMERA)))
    }

    // ---------------------------------------------------------------- camera unavailable fallback

    @Test
    fun `up x3 without the camera permission in the pocket starts without camera and says why`() {
        val s = start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, camera = false)))
        assertEquals(SourceKind.NONE, s.plan.source)
        assertTrue(s.plan.cameraFallback)
        assertEquals(Phrases.CAMERA_UNAVAILABLE, s.announce)
    }

    @Test
    fun `up x3 without camera permission and landmarks off cannot start`() {
        assertEquals(
            ControlAction.Say(Phrases.CAMERA_UNAVAILABLE_NOTHING),
            decide(Gesture.UP_THREE, inputs(RunState.STOPPED, camera = false, landmarks = false)),
        )
    }

    @Test
    fun `up x3 with landmarks off still starts with the camera`() {
        assertEquals(SourceKind.PHONE_CAMERA, start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, landmarks = false))).plan.source)
    }

    @Test
    fun `up x2 with landmarks off says so and points to the camera`() {
        assertEquals(ControlAction.Say(Phrases.LANDMARKS_SWITCHED_OFF), decide(Gesture.UP_TWICE, inputs(RunState.STOPPED, landmarks = false)))
    }

    @Test
    fun `cap selected - up x3 needs no camera permission`() {
        val s = start(decide(Gesture.UP_THREE, inputs(RunState.NO_CAMERA, selected = SourceKind.ESP32, camera = false)))
        assertEquals(SourceKind.ESP32, s.plan.source)
    }

    // ---------------------------------------------------------------- permissions

    @Test
    fun `in the pocket a missing BLE permission is spoken, never a dialog`() {
        val s = start(decide(Gesture.UP_TWICE, inputs(RunState.STOPPED, ble = false, notifications = false)))
        assertEquals("${Phrases.NAV_STARTED_NO_CAMERA} ${Phrases.LANDMARKS_NOT_ALLOWED}", s.announce)
        val f = start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, ble = false, camera = false)))
        assertEquals("${Phrases.CAMERA_UNAVAILABLE} ${Phrases.LANDMARKS_NOT_ALLOWED}", f.announce)
    }

    @Test
    fun `camera start does not mention a missing BLE permission`() {
        assertEquals(Phrases.NAV_STARTED_CAMERA, start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, ble = false))).announce)
    }

    @Test
    fun `on screen missing permissions open the start flow for that wish`() {
        assertEquals(ControlAction.Ask(StartWish.NO_CAMERA), decide(Gesture.UP_TWICE, inputs(RunState.STOPPED, ble = false, canAsk = true)))
        assertEquals(ControlAction.Ask(StartWish.NO_CAMERA), decide(Gesture.UP_TWICE, inputs(RunState.STOPPED, notifications = false, canAsk = true)))
        assertEquals(ControlAction.Ask(StartWish.CAMERA), decide(Gesture.UP_THREE, inputs(RunState.STOPPED, camera = false, canAsk = true)))
        assertEquals(ControlAction.Ask(StartWish.CAMERA), decide(Gesture.UP_THREE, inputs(RunState.NO_CAMERA, camera = false, canAsk = true)))
    }

    @Test
    fun `on screen with everything granted starts at once`() {
        start(decide(Gesture.UP_TWICE, inputs(RunState.STOPPED, canAsk = true)))
        start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, canAsk = true)))
    }

    @Test
    fun `on screen a missing BLE permission does not block switching to the camera`() {
        start(decide(Gesture.UP_THREE, inputs(RunState.NO_CAMERA, ble = false, canAsk = true)))
    }

    @Test
    fun `on screen BLE is not asked for when landmarks are off`() {
        start(decide(Gesture.UP_THREE, inputs(RunState.STOPPED, landmarks = false, ble = false, canAsk = true)))
    }
}
