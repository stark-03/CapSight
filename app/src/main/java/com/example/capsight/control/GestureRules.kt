package com.example.capsight.control

import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.StartPlan
import com.example.capsight.settings.StartWish
import com.example.capsight.voice.Phrases

/** Whether navigation runs, and with or without a camera (CLAUDE.md §5.1). */
enum class RunState { STOPPED, NO_CAMERA, CAMERA }

/** Everything a gesture's meaning depends on. Permission and screen state are passed in (pure). */
data class ControlInputs(
    val state: RunState,
    /** The frame source saved in Settings. */
    val selected: SourceKind,
    /** "BLE landmarks" switched on in Settings. */
    val landmarksEnabled: Boolean,
    val cameraPermission: Boolean,
    /** BLE scan permissions granted (fine location, plus BLUETOOTH_SCAN on API 31+). */
    val blePermission: Boolean,
    /** POST_NOTIFICATIONS granted (always true below API 33). */
    val notificationPermission: Boolean,
    /** CapSight is on screen (resumed), so the normal start flow may show permission dialogs. */
    val canAsk: Boolean,
)

/** What to do for a recognised gesture. */
sealed interface ControlAction {
    /**
     * Start as [plan] and say [announce] as the start phrase. While running without camera (volume
     * up x3) this restarts the session with the camera.
     */
    data class Start(val plan: StartPlan, val announce: String) : ControlAction

    /** Run the normal start flow for [wish], with its permission dialogs (only while CapSight is on screen). */
    data class Ask(val wish: StartWish) : ControlAction

    data object Stop : ControlAction

    /** Say the status sentence ([Phrases.status]), built from the live state. */
    data object Status : ControlAction

    /** Only say [phrase]; nothing changes. */
    data class Say(val phrase: String) : ControlAction
}

/**
 * The volume-key gestures' meaning in each state (FR-APP-06, CLAUDE.md §8.2). Pure Kotlin.
 *
 * | Gesture | stopped | running without camera | running with camera |
 * |---|---|---|---|
 * | up x2 | start without camera | say "already running without camera" | say "already running with camera" |
 * | up x3 | start with camera | switch to the camera (restart) | say "already running with camera" |
 * | down x2 | say "already stopped" | stop | stop |
 * | down x3 | status | status | status |
 *
 * Up x2 never turns a running camera off: raising the volume quickly by two steps must not end
 * obstacle warnings. Without the camera permission, up x3 starts (or stays) without camera and
 * says [Phrases.CAMERA_UNAVAILABLE]; while CapSight is on screen it opens the permission flow instead.
 * Off screen no dialog is ever opened: a missing BLE permission is spoken after the start phrase.
 */
object GestureRules {

    fun decide(gesture: Gesture, inputs: ControlInputs): ControlAction = when (gesture) {
        Gesture.UP_TWICE -> when (inputs.state) {
            RunState.STOPPED -> start(StartWish.NO_CAMERA, inputs)
            RunState.NO_CAMERA -> ControlAction.Say(Phrases.ALREADY_NO_CAMERA)
            RunState.CAMERA -> ControlAction.Say(Phrases.ALREADY_CAMERA)
        }
        Gesture.UP_THREE -> when (inputs.state) {
            RunState.STOPPED, RunState.NO_CAMERA -> start(StartWish.CAMERA, inputs)
            RunState.CAMERA -> ControlAction.Say(Phrases.ALREADY_CAMERA)
        }
        Gesture.DOWN_TWICE -> if (inputs.state == RunState.STOPPED) ControlAction.Say(Phrases.ALREADY_STOPPED) else ControlAction.Stop
        Gesture.DOWN_THREE -> ControlAction.Status
    }

    private fun start(wish: StartWish, i: ControlInputs): ControlAction {
        val plan = StartPlan.decide(wish, i.selected, i.cameraPermission, i.landmarksEnabled)
        val running = i.state != RunState.STOPPED
        val cameraMissing = wish == StartWish.CAMERA &&
            StartPlan.cameraFor(i.selected) == SourceKind.PHONE_CAMERA && !i.cameraPermission
        val othersMissing = !running && (!i.notificationPermission || (i.landmarksEnabled && !i.blePermission))
        if (i.canAsk && (cameraMissing || othersMissing)) return ControlAction.Ask(wish)
        if (running) {
            // Only up x3 while running without camera gets here: switch if the camera can be used.
            return if (plan.source?.hasCamera == true) {
                ControlAction.Start(plan, Phrases.NAV_STARTED_CAMERA)
            } else {
                ControlAction.Say(Phrases.CAMERA_UNAVAILABLE)
            }
        }
        if (!plan.canStart) {
            return ControlAction.Say(if (wish == StartWish.CAMERA) Phrases.CAMERA_UNAVAILABLE_NOTHING else Phrases.LANDMARKS_SWITCHED_OFF)
        }
        val base = when {
            plan.cameraFallback -> Phrases.CAMERA_UNAVAILABLE
            plan.landmarksOnly -> Phrases.NAV_STARTED_NO_CAMERA
            else -> Phrases.NAV_STARTED_CAMERA
        }
        // Landmarks are all a no-camera session has: say when they cannot work yet.
        val announce = if (plan.landmarksOnly && !i.blePermission) "$base ${Phrases.LANDMARKS_NOT_ALLOWED}" else base
        return ControlAction.Start(plan, announce)
    }
}
