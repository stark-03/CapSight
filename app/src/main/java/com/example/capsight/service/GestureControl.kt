package com.example.capsight.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.capsight.TAG
import com.example.capsight.ble.LandmarkScanner
import com.example.capsight.control.ControlAction
import com.example.capsight.control.ControlInputs
import com.example.capsight.control.Gesture
import com.example.capsight.control.GestureRules
import com.example.capsight.control.RunState
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.LandmarkSettings
import com.example.capsight.voice.Haptics
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority

/**
 * Carries out a recognised volume-key gesture (FR-APP-06, CLAUDE.md §8.2), whichever path saw it:
 * MainActivity (screen on, CapSight in front) or NavigationService's MediaSession (navigating, or
 * standby with the screen off / locked). [GestureRules] decides; this reads the live state, confirms
 * with a vibration, and starts / stops / speaks. Main thread. Never throws.
 */
object GestureControl {

    fun handle(context: Context, gesture: Gesture) {
        val app = context.applicationContext
        try {
            confirm(app, gesture)
            val pipeline = Pipeline.get(app)
            val inputs = inputs(app, pipeline, canAsk = pipeline.appInFront.value && pipeline.startRequests.subscriptionCount.value > 0)
            val action = GestureRules.decide(gesture, inputs)
            Log.i(TAG, "${gesture.label} in ${inputs.state}: $action")
            execute(app, pipeline, gesture, inputs, action)
        } catch (e: RuntimeException) {
            Log.e(TAG, "${gesture.label} failed", e)
            try {
                Pipeline.get(app).speaker.say(Phrases.START_FAILED, Priority.INFO, 0)
            } catch (e2: RuntimeException) {
                Log.e(TAG, "could not even say that", e2)
            }
        }
    }

    /**
     * The main screen's "Repeat status" button: the same reply and vibration as volume down x3, but
     * logged as a screen action. While stopped with volume-button control off, no gesture hint.
     */
    fun repeatStatus(context: Context) {
        val app = context.applicationContext
        try {
            Haptics(app).gestureStatus()
            val pipeline = Pipeline.get(app)
            val state = inputs(app, pipeline, canAsk = false).state
            val hints = pipeline.controlSettings.value?.volumeButtonControl ?: true
            val phrase = if (state == RunState.STOPPED && !hints) Phrases.STATUS_STOPPED_PLAIN else status(pipeline, state)
            say(pipeline, phrase, "screen button: status")
        } catch (e: RuntimeException) {
            Log.e(TAG, "repeat status failed", e)
        }
    }

    private fun execute(app: Context, pipeline: Pipeline, gesture: Gesture, inputs: ControlInputs, action: ControlAction) {
        when (action) {
            is ControlAction.Start ->
                if (!NavigationService.start(app, action.plan, action.announce, gesture.label)) {
                    pipeline.speaker.say(Phrases.START_FAILED, Priority.INFO, 0)
                }
            is ControlAction.Ask -> pipeline.startRequests.tryEmit(action.wish)
            ControlAction.Stop -> NavigationService.stop(app, gesture.label)
            ControlAction.Status -> say(pipeline, status(pipeline, inputs.state), "${gesture.label}: status")
            is ControlAction.Say -> say(pipeline, action.phrase, "${gesture.label}: ${inputs.state.name.lowercase()}")
        }
    }

    /** Status and replies are INFO: they never interrupt or replace a warning (only P0 interrupts). */
    private fun say(pipeline: Pipeline, phrase: String, reason: String) {
        if (pipeline.speaker.say(phrase, Priority.INFO, 0)) pipeline.recordControl(phrase, reason)
    }

    private fun status(pipeline: Pipeline, state: RunState): String {
        val withCamera = when (state) {
            RunState.STOPPED -> null
            RunState.NO_CAMERA -> false
            RunState.CAMERA -> true
        }
        val lm = pipeline.landmarks.value
        val nearest = lm.beacons.firstOrNull()?.let { it.landmark to it.step }
        val sayDistance = (pipeline.landmarkSettings.value ?: LandmarkSettings()).sayDistance
        return Phrases.status(withCamera, nearest, sayDistance, landmarksListening = lm.status.startsWith(SCANNING))
    }

    private fun inputs(app: Context, pipeline: Pipeline, canAsk: Boolean): ControlInputs {
        val session = pipeline.session.value
        val state = when {
            !pipeline.running.value -> RunState.STOPPED
            session == null || session.landmarksOnly -> RunState.NO_CAMERA
            else -> RunState.CAMERA
        }
        return ControlInputs(
            state = state,
            selected = pipeline.camera.value?.source ?: CameraSettings().source,
            landmarksEnabled = pipeline.landmarkSettings.value?.enabled ?: true,
            cameraPermission = granted(app, Manifest.permission.CAMERA),
            blePermission = LandmarkScanner.hasScanPermission(app),
            notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(app, Manifest.permission.POST_NOTIFICATIONS),
            canAsk = canAsk,
        )
    }

    /** FR-APP-06: feel which gesture was recognised, even before speech starts. */
    private fun confirm(app: Context, gesture: Gesture) {
        val haptics = Haptics(app)
        when (gesture) {
            Gesture.UP_TWICE, Gesture.UP_THREE -> haptics.gestureStart()
            Gesture.DOWN_TWICE -> haptics.gestureStop()
            Gesture.DOWN_THREE -> haptics.gestureStatus()
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** LandmarkScanner's status while it scans ("BLE: scanning, 2 beacons"). */
    private const val SCANNING = "BLE: scanning"
}
