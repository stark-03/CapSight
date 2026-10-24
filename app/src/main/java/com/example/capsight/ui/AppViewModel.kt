package com.example.capsight.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.capsight.TAG
import com.example.capsight.ble.Calibration
import com.example.capsight.control.Gesture
import com.example.capsight.service.GestureControl
import com.example.capsight.service.NavigationService
import com.example.capsight.service.Pipeline
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.HelperSettingsDraft
import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.StartPlan
import com.example.capsight.settings.StartWish
import com.example.capsight.voice.Haptics
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What every screen observes. The pipeline itself runs in NavigationService (FR-APP-03); this only
 * exposes its StateFlows, starts and stops the service, and edits settings (CLAUDE.md §5).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val pipeline = Pipeline.get(app)
    private val store = pipeline.settings

    val camera = pipeline.camera
    val detectionSettings = pipeline.detectionSettings
    val speechSettings = pipeline.speechSettings
    val navigationSettings = pipeline.navigationSettings
    val landmarkSettings = pipeline.landmarkSettings
    val controlSettings = pipeline.controlSettings
    val frame = pipeline.frame
    val status = pipeline.status
    val detection = pipeline.detection
    val depth = pipeline.depth
    val perceived = pipeline.perceived
    val speech = pipeline.speech
    val guidance = pipeline.guidance
    val running = pipeline.running

    /** The running session's effective source: landmarks only (camera off)? Null while stopped. */
    val session = pipeline.session
    val landmarks = pipeline.landmarks
    val decisionLog = pipeline.decisionLogState
    val volumeKeys = pipeline.volumeKeys

    /** FR-APP-07: the newest decision log file, or null. */
    fun newestDecisionLog() = pipeline.newestDecisionLog()

    /** Null until loaded; false shows the first-run safety statement (NFR-SAFE-01). */
    val safetyAccepted: StateFlow<Boolean?> = store.safetyAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until loaded; false shows the spoken welcome once after the safety statement. */
    val welcomeShown: StateFlow<Boolean?> = store.welcomeShown.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val haptics by lazy { Haptics(app) }

    /** The saved frame source (Settings / debug view). */
    private val selectedSource: SourceKind get() = camera.value?.source ?: CameraSettings().source

    /** A start for [wish] would use the phone camera: the start flow asks for the camera permission. */
    fun wantsPhoneCamera(wish: StartWish): Boolean = when (wish) {
        StartWish.SAVED -> selectedSource == SourceKind.PHONE_CAMERA
        StartWish.NO_CAMERA -> false
        StartWish.CAMERA -> StartPlan.cameraFor(selectedSource) == SourceKind.PHONE_CAMERA
    }

    /** BLE landmarks switched on (the default): ask for the scan permission at start (§6.1). */
    val usesLandmarks: Boolean get() = landmarkSettings.value?.enabled ?: true

    /** What a start for [wish] would run with, given the camera permission (CLAUDE.md §5.1, §8.2). */
    fun startPlan(wish: StartWish, cameraPermission: Boolean) =
        StartPlan.decide(wish, selectedSource, cameraPermission, usesLandmarks)

    /**
     * Starts [plan] with the default start phrase (with the volume-key hint while that control is on,
     * so a screen start sounds exactly like the gesture). A volume-key [wish] (after its permission
     * dialogs, [byVolumeKeys]) or a main-screen start button gets a `control` row in the decision log.
     */
    fun startNavigation(plan: StartPlan, wish: StartWish = StartWish.SAVED, byVolumeKeys: Boolean = false) {
        if (!plan.canStart) return
        val trigger = when {
            byVolumeKeys -> "volume keys (${wish.name.lowercase()}) after permissions"
            wish == StartWish.SAVED -> null
            else -> "screen button (${wish.name.lowercase()})"
        }
        val ok = NavigationService.start(getApplication(), plan, trigger = trigger)
        if (!ok) {
            pipeline.speaker.say(Phrases.START_FAILED, Priority.INFO, 0)
        } else if (wish == StartWish.CAMERA && plan.cameraFallback && running.value) {
            // Volume up x3 while running without camera, camera refused in the dialog: it stays as it is.
            pipeline.speaker.say(Phrases.CAMERA_UNAVAILABLE, Priority.INFO, 0)
        }
    }

    /** Start refused: no camera and landmarks off. Spoken, as the user may not see the screen. */
    fun sayNothingToGuide() {
        pipeline.speaker.say(Phrases.NOTHING_TO_GUIDE, Priority.INFO, 0)
    }

    /** Volume-key start that needs permission dialogs first: the screen runs the normal start flow. */
    val startRequests: SharedFlow<StartWish> = pipeline.startRequests.asSharedFlow()

    /** Volume-button control is on and the first-run safety statement accepted (FR-APP-06). */
    val volumeGesturesEnabled: Boolean
        get() = safetyAccepted.value == true && controlSettings.value?.volumeButtonControl ?: true

    /**
     * MainActivity watches the volume keys itself: gestures on, and NavigationService's MediaSession
     * is not taking them (standby with the screen on and unlocked, or no service yet).
     */
    val activityWatchesVolumeKeys: Boolean
        get() = volumeGesturesEnabled && !pipeline.volumeKeySessionActive.value

    /** The service's MediaSession takes the volume keys now (navigating, or standby with the screen locked). */
    val volumeKeySessionActive: Boolean get() = pipeline.volumeKeySessionActive.value

    /** FR-APP-06: a gesture recognised by MainActivity. */
    fun onGesture(gesture: Gesture) = GestureControl.handle(getApplication(), gesture)

    /** MainActivity resumed / paused: while on screen, gestures may open the permission dialogs. */
    fun setInFront(inFront: Boolean) {
        pipeline.appInFront.value = inFront
    }

    fun stopNavigation() {
        NavigationService.stop(getApplication())
    }

    // ---- Main screen buttons: the same vibrations and phrases as the volume gestures (CLAUDE.md §8.2) ----

    /** A start button was pressed: two short pulses like volume up x2 / x3; the start flow follows. */
    fun confirmStartPressed() = haptics.gestureStart()

    /** "Stop navigation" button: one long pulse like volume down x2, then the usual stop phrase. */
    fun stopFromScreen() {
        haptics.gestureStop()
        NavigationService.stop(getApplication(), trigger = "screen button")
    }

    /** "Repeat status" button: the volume down x3 reply. */
    fun repeatStatus() = GestureControl.repeatStatus(getApplication())

    // ---- Welcome / Help ----

    private var helpJob: Job? = null

    /** The welcome / help lines (shown and spoken), matching the volume-button setting. */
    val helpLines: List<String> get() = Phrases.help(controlSettings.value?.volumeButtonControl ?: true) + Phrases.HELP_CLOSE

    /** Speaks the help / welcome from the top (see [speakLines]). */
    fun readHelp() = speakLines(helpLines)

    /** First run: the safety statement, spoken for a user who cannot read the screen. */
    fun readSafety() = speakLines(Phrases.SAFETY_INTRO)

    /**
     * Speaks [lines] one at a time, each only when the speaker is free, so warnings (P0-P4) still get
     * through between lines and a line never replaces a warning. Waits briefly for text-to-speech to
     * be ready (first app start), so the first line is not lost. Replaces any reading in progress.
     */
    private fun speakLines(lines: List<String>) {
        helpJob?.cancel()
        helpJob = viewModelScope.launch {
            withTimeoutOrNull(TTS_WAIT_MS) { pipeline.speech.first { it.ttsReady } }
            for (line in lines) {
                while (true) {
                    pipeline.speech.first { it.speaking == null && it.pending == null }
                    if (pipeline.speaker.say(line, Priority.INFO, 0)) break
                    delay(HELP_RETRY_MS)
                }
                delay(HELP_GAP_MS)
            }
        }
    }

    /** Stops after the line being spoken now. */
    fun stopHelp() {
        helpJob?.cancel()
        helpJob = null
    }

    /** "Got it" on the first-run welcome. */
    fun finishWelcome() {
        stopHelp()
        viewModelScope.launch { store.markWelcomeShown() }
    }

    fun testSpeech(test: Pipeline.SpeechTest) = pipeline.testSpeech(test)

    fun acceptSafety() = viewModelScope.launch { store.acceptSafety() }

    suspend fun saveHelperSettings(draft: HelperSettingsDraft): Map<String, String> = store.saveHelperSettings(draft)

    fun setSource(kind: SourceKind) = viewModelScope.launch { store.setSource(kind) }

    fun setCameraBaseUrl(url: String) = viewModelScope.launch { store.setCameraBaseUrl(url) }

    fun setBindToWifi(bind: Boolean) = viewModelScope.launch { store.setBindToWifi(bind) }

    fun setUseGpu(use: Boolean) = viewModelScope.launch { store.setUseGpu(use) }

    fun setShowAllClasses(show: Boolean) = viewModelScope.launch { store.setShowAllClasses(show) }

    fun setSpeechRate(rate: Float) = viewModelScope.launch { store.setSpeechRate(rate) }

    fun setSpeakGuidance(on: Boolean) = viewModelScope.launch { store.setSpeakGuidance(on) }

    /**
     * "Calibrate at 1 m" (CLAUDE.md §4.6): the strongest beacon heard now sets "Beacon signal at 1 m"
     * and is saved. Returns the message to show ("Calibrated: -51 dBm at 1 m", or why not).
     */
    fun calibrateBeaconAt1m(): String = calibrateBeacon().message

    /** As [calibrateBeaconAt1m], returning the result, so the Settings draft can show the saved value. */
    fun calibrateBeacon(): Calibration {
        val c = Calibration.from(landmarks.value.beacons)
        if (c is Calibration.Ok) {
            viewModelScope.launch { store.setLandmarkRssiAt1m(c.dbm) }
            Log.i(TAG, "beacon calibrated: ${c.dbm} dBm at 1 m (${c.landmark.name})")
        }
        return c
    }

    fun setVideo(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable permission for $uri; it works until reboot", e)
        }
        viewModelScope.launch { store.setVideoUri(uri.toString()) }
    }

    private companion object {
        const val HELP_RETRY_MS = 300L
        const val HELP_GAP_MS = 250L
        const val TTS_WAIT_MS = 4_000L
    }
}
