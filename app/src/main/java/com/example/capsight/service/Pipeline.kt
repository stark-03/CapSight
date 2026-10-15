package com.example.capsight.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.net.toUri
import com.example.capsight.TAG
import com.example.capsight.camera.FrameReceiver
import com.example.capsight.camera.FrameSource
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.MjpegFrameSource
import com.example.capsight.camera.PhoneCameraSource
import com.example.capsight.camera.VideoFileSource
import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.DetectionRunner
import com.example.capsight.nav.DecisionConfig
import com.example.capsight.nav.GuidanceConfig
import com.example.capsight.path.HysteresisParams
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.Perception
import com.example.capsight.perception.PerceptionParams
import com.example.capsight.perception.Side
import com.example.capsight.settings.AppSettings
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.DetectionSettings
import com.example.capsight.settings.NavigationSettings
import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.SpeechSettings
import com.example.capsight.ui.debug.PerceivedView
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import com.example.capsight.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The whole guidance pipeline (SRS §3): receive -> detect -> perceive -> decide -> speak.
 *
 * One per process. [NavigationService] runs it ([run]) so it keeps going with the screen off
 * (FR-APP-03); screens only observe the StateFlows here (CLAUDE.md §5).
 */
class Pipeline private constructor(private val app: Context) {

    val settings = AppSettings(app)

    /** App-lifetime scope for the shared settings and UI-facing flows. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val receiver = FrameReceiver()
    private val detector = DetectionRunner(app)
    val speaker: Speaker by lazy { Speaker(app) }
    private val guidanceLoop by lazy { GuidanceLoop(speaker) }

    val camera: StateFlow<CameraSettings?> = settings.camera.stateIn(scope, SharingStarted.Eagerly, null)
    val detectionSettings: StateFlow<DetectionSettings?> = settings.detection.stateIn(scope, SharingStarted.Eagerly, null)
    val speechSettings: StateFlow<SpeechSettings?> = settings.speech.stateIn(scope, SharingStarted.Eagerly, null)
    val navigationSettings: StateFlow<NavigationSettings?> = settings.navigation.stateIn(scope, SharingStarted.Eagerly, null)

    val frame = receiver.frame
    val status = receiver.status
    val detection = detector.state
    val speech get() = speaker.state
    val guidance get() = guidanceLoop.state

    private val _running = MutableStateFlow(false)

    /** True while NavigationService is running the pipeline. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Detections with distance, corridor status and zones, for the debug overlay. */
    val perceived: StateFlow<PerceivedView> = combine(
        detection,
        camera.filterNotNull(),
        detectionSettings.filterNotNull(),
        navigationSettings.filterNotNull(),
    ) { det, cam, ds, nav ->
        val (params, fovSource) = perceptionParams(cam, ds, nav, det.frameFovDeg)
        val objects = Perception.perceive(det.detections, det.frameWidth, det.frameHeight, params)
        val zones = ZoneAnalyzer.analyze(objects.mapNotNull { it.relative }, ZoneParams(nav.corridorHalfWidthM, nav.warnDistanceM))
        val corridor = objects.filter { it.inCorridor }.mapNotNull { it.relative }
        PerceivedView(
            objects = objects,
            frameWidth = det.frameWidth,
            frameHeight = det.frameHeight,
            fovDeg = params.fovDeg,
            fovSource = fovSource,
            zones = if (det.running) zones else null,
            bestSide = ZoneAnalyzer.bestSide(zones, corridor, nav.corridorHalfWidthM),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_TIMEOUT_MS), PerceivedView())

    init {
        scope.launch { speechSettings.filterNotNull().collect { speaker.configure(it.rate, it.volume) } }
    }

    /** Runs camera, detector and the 10 Hz guidance loop until cancelled. Called by NavigationService. */
    suspend fun run() {
        _running.value = true
        Log.i(TAG, "pipeline start")
        val sp = speechSettings.filterNotNull().first()
        speaker.say(Phrases.NAV_STARTED, Priority.INFO, 0) // FR-APP-02
        try {
            coroutineScope {
                launch {
                    camera.filterNotNull().distinctUntilChanged()
                        // A settings change while running restarts the receiver with the new source.
                        .collectLatest { s -> receiver.run(sourceFor(s), s.cameraLostMs) }
                }
                // Separate job, so switching GPU/CPU or class filter does not reconnect the camera.
                launch {
                    detectionSettings.filterNotNull().distinctUntilChanged().collectLatest { d ->
                        val classes = if (d.showAllClasses) null else CocoLabels.NAVIGATION
                        detector.run(receiver.frame, d.minConfidence, d.useGpu, classes)
                    }
                }
                // FR-APP-02: "Connecting to camera" if the link is not up shortly after start.
                launch {
                    delay(CONNECTING_ANNOUNCE_MS)
                    if (status.value.link != LinkState.CONNECTED) {
                        speaker.say(Phrases.CONNECTING, Priority.INFO, sp.phraseCooldownMs)
                    }
                }
                // FR-NAV-02: the 10 Hz guidance loop - camera lost, obstacles, sides, all spoken from here.
                launch {
                    combine(
                        camera.filterNotNull(),
                        detectionSettings.filterNotNull(),
                        navigationSettings.filterNotNull(),
                        speechSettings.filterNotNull(),
                    ) { cam, ds, nav, s -> LoopConfig(cam, ds, nav, s) }
                        .distinctUntilChanged()
                        .collectLatest { c ->
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
        } finally {
            _running.value = false
            Log.i(TAG, "pipeline stop")
        }
    }

    // ------------------------------------------------------------------ speech tests

    /** Debug buttons that exercise the speaker's rules (build step 3). */
    enum class SpeechTest { CLEAR, AVOID, STOP, BURST, INTERRUPT }

    fun testSpeech(test: SpeechTest) {
        val sp = speechSettings.value ?: SpeechSettings()
        when (test) {
            SpeechTest.CLEAR -> speaker.say(Phrases.PATH_CLEAR, Priority.CLEAR, sp.clearRepeatMs)
            SpeechTest.AVOID -> speaker.say(Phrases.avoid("chair", Side.LEFT), Priority.AVOID, sp.phraseCooldownMs)
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
            SpeechTest.INTERRUPT -> scope.launch {
                speaker.say(INTERRUPT_SAMPLE, Priority.INFO, 0)
                delay(INTERRUPT_AFTER_MS)
                speaker.say(Phrases.stop("person"), Priority.STOP, 0)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sourceFor(s: CameraSettings): FrameSource = when (s.source) {
        SourceKind.ESP32 -> MjpegFrameSource(app, s.cameraBaseUrl, s.bindToWifi)
        SourceKind.PHONE_CAMERA -> PhoneCameraSource(app)
        SourceKind.VIDEO_FILE -> s.videoUri?.let { VideoFileSource(app, it.toUri()) } ?: NoVideoChosen
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

    private object NoVideoChosen : FrameSource {
        override val label = "Video file"

        override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
            onStatus("Choose a video file first")
            awaitCancellation()
        }
    }

    companion object {
        private const val UI_STOP_TIMEOUT_MS = 5_000L
        private const val MS_PER_S = 1_000L
        private const val CONNECTING_ANNOUNCE_MS = 1_500L
        private const val INTERRUPT_SAMPLE =
            "This is a long test sentence that keeps going so that you can hear it being cut off."
        private const val INTERRUPT_AFTER_MS = 1_200L

        // Holds only the application context (see get()), never an Activity: no leak.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Pipeline? = null

        fun get(context: Context): Pipeline =
            instance ?: synchronized(this) {
                instance ?: Pipeline(context.applicationContext).also { instance = it }
            }
    }
}
