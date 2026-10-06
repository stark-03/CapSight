package com.example.capsight.ui.debug

import com.example.capsight.perception.Side
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.capsight.TAG
import com.example.capsight.camera.FrameReceiver
import com.example.capsight.camera.FrameSource
import com.example.capsight.camera.MjpegFrameSource
import com.example.capsight.camera.PhoneCameraSource
import com.example.capsight.camera.VideoFileSource
import com.example.capsight.settings.AppSettings
import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.DetectionRunner
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.DetectionSettings
import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.SpeechSettings
import com.example.capsight.settings.NavigationSettings
import com.example.capsight.nav.DecisionConfig
import com.example.capsight.nav.GuidanceConfig
import com.example.capsight.path.HysteresisParams
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.service.GuidanceLoop
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.Perception
import com.example.capsight.perception.PerceptionParams
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import com.example.capsight.voice.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts the whole pipeline for the debug screen until step 7 (FR-APP-05, steps 1-6): frame
 * receiver, detector, perception, 10 Hz guidance loop and speaker.
 *
 * TEMPORARY OWNER: from build step 7 NavigationService owns the receiver so it
 * survives the screen turning off, and this ViewModel only observes it. Until then
 * the receiver runs while the user has pressed Start AND the screen is visible.
 */
class DebugViewModel(app: Application) : AndroidViewModel(app) {

    private val appSettings = AppSettings(app)
    private val receiver = FrameReceiver()
    private val detector = DetectionRunner(app)
    private val speaker = Speaker(app)
    private val guidanceLoop = GuidanceLoop(speaker)

    val settings: StateFlow<CameraSettings?> =
        appSettings.camera.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val detectionSettings: StateFlow<DetectionSettings?> =
        appSettings.detection.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val frame = receiver.frame
    val status = receiver.status
    val detection = detector.state
    val speechSettings: StateFlow<SpeechSettings?> =
        appSettings.speech.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val speech = speaker.state
    val guidance = guidanceLoop.state
    val navigationSettings: StateFlow<NavigationSettings?> =
        appSettings.navigation.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Detections with distance and corridor status, for the overlay (build step 4). */
    val perceived: StateFlow<PerceivedView> = combine(
        detection,
        settings.filterNotNull(),
        detectionSettings.filterNotNull(),
        navigationSettings.filterNotNull(),
    ) { det, cam, ds, nav ->
        val (params, fovSource) = perceptionParams(cam, ds, nav, det.frameFovDeg)
        val fov = params.fovDeg
        val objects = Perception.perceive(det.detections, det.frameWidth, det.frameHeight, params)
        val measured = objects.mapNotNull { it.relative }
        val zones = ZoneAnalyzer.analyze(measured, ZoneParams(nav.corridorHalfWidthM, nav.warnDistanceM))
        val corridor = objects.filter { it.inCorridor }.mapNotNull { it.relative }
        PerceivedView(
            objects = objects,
            frameWidth = det.frameWidth,
            frameHeight = det.frameHeight,
            fovDeg = fov,
            fovSource = fovSource,
            zones = if (det.running) zones else null,
            bestSide = ZoneAnalyzer.bestSide(zones, corridor, nav.corridorHalfWidthM),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PerceivedView())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val visible = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(_running, visible, settings.filterNotNull()) { run, shown, s ->
                if (run && shown) s else null
            }
                .distinctUntilChanged()
                // A settings change while running restarts the receiver with the new source.
                .collectLatest { s -> if (s != null) receiver.run(sourceFor(s), s.cameraLostMs) }
        }
        // Separate job, so switching GPU/CPU or class filter does not reconnect the camera.
        viewModelScope.launch {
            combine(_running, visible, detectionSettings.filterNotNull()) { run, shown, d ->
                if (run && shown) d else null
            }
                .distinctUntilChanged()
                .collectLatest { d ->
                    if (d != null) {
                        val classes = if (d.showAllClasses) null else CocoLabels.NAVIGATION
                        detector.run(receiver.frame, d.minConfidence, d.useGpu, classes)
                    }
                }
        }
        viewModelScope.launch {
            speechSettings.filterNotNull().collect { speaker.configure(it.rate, it.volume) }
        }
        // FR-NAV-02: the 10 Hz guidance loop - camera lost, obstacles, sides, all spoken from here.
        viewModelScope.launch {
            combine(
                combine(_running, visible) { run, shown -> run && shown },
                settings.filterNotNull(),
                detectionSettings.filterNotNull(),
                navigationSettings.filterNotNull(),
                speechSettings.filterNotNull(),
            ) { on, cam, ds, nav, sp -> if (on) LoopConfig(cam, ds, nav, sp) else null }
                .distinctUntilChanged()
                .collectLatest { c ->
                    if (c == null) return@collectLatest
                    guidanceLoop.run(
                        link = status,
                        detection = detection,
                        paramsFor = { det -> perceptionParams(c.cam, c.ds, c.nav, det.frameFovDeg).first },
                        config = guidanceConfig(c.nav, c.sp),
                        cameraLostMs = c.cam.cameraLostMs,
                        periodMs = MS_PER_S / c.nav.decisionRateHz,
                        speak = c.sp.speakGuidance,
                    )
                }
        }
    }

    /** Debug buttons that exercise the speaker's rules (build step 3). */
    enum class SpeechTest { CLEAR, AVOID, STOP, BURST, INTERRUPT }

    fun testSpeech(test: SpeechTest) {
        val sp = speechSettings.value ?: SpeechSettings()
        when (test) {
            SpeechTest.CLEAR -> speaker.say(Phrases.PATH_CLEAR, Priority.CLEAR, sp.clearRepeatMs)
            SpeechTest.AVOID -> speaker.say(
                Phrases.avoid("chair", Side.LEFT), Priority.AVOID, sp.phraseCooldownMs,
            )
            SpeechTest.STOP -> speaker.say(Phrases.stop("chair"), Priority.STOP, sp.stopCooldownMs)
            // Single slot: the first phrase plays, "Path blocked" is replaced by the more urgent
            // "Person ahead", and the last "Path clear." cannot displace it. You hear two phrases.
            SpeechTest.BURST -> {
                speaker.say(Phrases.PATH_CLEAR, Priority.CLEAR, 0)
                speaker.say(Phrases.PATH_BLOCKED, Priority.BLOCKED, 0)
                speaker.say(Phrases.avoid("person", Side.RIGHT), Priority.AVOID, 0)
                speaker.say(Phrases.PATH_CLEAR, Priority.CLEAR, 0)
            }
            // FR-VOICE-03: a P0 cuts off a long sentence in the middle.
            SpeechTest.INTERRUPT -> viewModelScope.launch {
                speaker.say(INTERRUPT_SAMPLE, Priority.INFO, 0)
                delay(INTERRUPT_AFTER_MS)
                speaker.say(Phrases.stop("person"), Priority.STOP, 0)
            }
        }
    }

    fun setSpeechRate(rate: Float) = viewModelScope.launch { appSettings.setSpeechRate(rate) }

    fun setSpeakGuidance(on: Boolean) = viewModelScope.launch { appSettings.setSpeakGuidance(on) }

    override fun onCleared() {
        speaker.shutdown()
    }

    fun start() {
        _running.value = true
    }

    fun stop() {
        _running.value = false
    }

    fun setVisible(shown: Boolean) {
        visible.value = shown
    }

    fun setSource(kind: SourceKind) = viewModelScope.launch { appSettings.setSource(kind) }

    fun setCameraBaseUrl(url: String) = viewModelScope.launch { appSettings.setCameraBaseUrl(url) }

    fun setBindToWifi(bind: Boolean) = viewModelScope.launch { appSettings.setBindToWifi(bind) }

    fun setUseGpu(use: Boolean) = viewModelScope.launch { appSettings.setUseGpu(use) }

    fun setShowAllClasses(show: Boolean) = viewModelScope.launch { appSettings.setShowAllClasses(show) }

    fun setVideo(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable permission for $uri; it works until reboot", e)
        }
        viewModelScope.launch { appSettings.setVideoUri(uri.toString()) }
    }

    private fun sourceFor(s: CameraSettings): FrameSource {
        val app = getApplication<Application>()
        return when (s.source) {
            SourceKind.ESP32 -> MjpegFrameSource(app, s.cameraBaseUrl, s.bindToWifi)
            SourceKind.PHONE_CAMERA -> PhoneCameraSource(app)
            SourceKind.VIDEO_FILE -> s.videoUri?.let { VideoFileSource(app, it.toUri()) } ?: NoVideoChosen
        }
    }

    private data class LoopConfig(
        val cam: CameraSettings,
        val ds: DetectionSettings,
        val nav: NavigationSettings,
        val sp: SpeechSettings,
    )

    private fun perceptionParams(
        cam: CameraSettings,
        ds: DetectionSettings,
        nav: NavigationSettings,
        frameFov: Float?,
    ): Pair<PerceptionParams, String> {
        val (fov, fovSource) = fovFor(cam.source, frameFov, nav)
        return PerceptionParams(
            fovDeg = fov,
            minConfidence = ds.minConfidence,
            skipUnreliable = nav.skipUnreliable,
            corridorHalfWidthM = nav.corridorHalfWidthM,
            warnDistanceM = nav.warnDistanceM,
            stopDistanceM = nav.stopDistanceM,
        ) to fovSource
    }

    private fun guidanceConfig(nav: NavigationSettings, sp: SpeechSettings) = GuidanceConfig(
        decision = DecisionConfig(
            stopDistanceM = nav.stopDistanceM,
            warnDistanceM = nav.warnDistanceM,
            stairsWarnM = nav.stairsWarnM,
            corridorHalfWidthM = nav.corridorHalfWidthM,
            phraseCooldownMs = sp.phraseCooldownMs,
            stopCooldownMs = sp.stopCooldownMs,
            stairsCooldownMs = sp.stairsCooldownMs,
            clearRepeatMs = sp.clearRepeatMs,
        ),
        holdTicks = nav.holdTicks,
        hysteresis = HysteresisParams(nav.sideHoldMs, nav.sideSwitchMarginM),
    )

    /** Which horizontal FOV turns pixels into metres for this source (CLAUDE.md §8, §8.1). */
    private fun fovFor(source: SourceKind, frameFov: Float?, nav: NavigationSettings): Pair<Double, String> =
        when (source) {
            SourceKind.ESP32 -> nav.capFovDeg to "cap setting"
            SourceKind.PHONE_CAMERA ->
                frameFov?.let { it.toDouble() to "phone camera" } ?: (nav.phoneFovDeg to "phone default")
            SourceKind.VIDEO_FILE -> nav.phoneFovDeg to "video default"
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MS_PER_S = 1_000L
        const val INTERRUPT_SAMPLE =
            "This is a long test sentence that keeps going so that you can hear it being cut off."
        const val INTERRUPT_AFTER_MS = 1_200L
    }

    private object NoVideoChosen : FrameSource {
        override val label = "Video file"

        override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
            onStatus("Choose a video file first")
            awaitCancellation()
        }
    }
}
