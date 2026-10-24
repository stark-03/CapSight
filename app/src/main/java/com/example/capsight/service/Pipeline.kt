package com.example.capsight.service

import android.annotation.SuppressLint
import android.os.SystemClock
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.net.toUri
import com.example.capsight.TAG
import com.example.capsight.ble.LandmarkCue
import com.example.capsight.ble.LandmarkScanner
import com.example.capsight.camera.FrameReceiver
import com.example.capsight.camera.FrameSource
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.MjpegFrameSource
import com.example.capsight.camera.PhoneCameraSource
import com.example.capsight.camera.PhoneTiltSensor
import com.example.capsight.camera.VideoFileSource
import com.example.capsight.depth.DepthParams
import com.example.capsight.depth.DepthRunner
import com.example.capsight.detect.CocoLabels
import com.example.capsight.detect.DetectionRunner
import com.example.capsight.detect.DetectionState
import com.example.capsight.nav.DecisionConfig
import com.example.capsight.nav.GuidanceConfig
import com.example.capsight.path.HysteresisParams
import com.example.capsight.path.ZoneAnalyzer
import com.example.capsight.path.ZoneParams
import com.example.capsight.perception.SmoothedPerception
import com.example.capsight.perception.CameraTilt
import com.example.capsight.perception.GroundParams
import com.example.capsight.perception.GroundPlane
import com.example.capsight.perception.PerceptionParams
import com.example.capsight.perception.Side
import com.example.capsight.record.DecisionCsv
import com.example.capsight.record.DecisionEntry
import com.example.capsight.record.DecisionLogger
import com.example.capsight.record.EntryKind
import com.example.capsight.settings.AppSettings
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.ControlSettings
import com.example.capsight.settings.DetectionSettings
import com.example.capsight.settings.LandmarkSettings
import com.example.capsight.settings.NavigationSettings
import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.SpeechSettings
import com.example.capsight.settings.StartWish
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * What the running session uses (CLAUDE.md §5.1): the effective frame source ([SourceKind.NONE] =
 * landmarks only, camera off) and whether that is a fallback because the camera permission was refused.
 */
data class SessionMode(val source: SourceKind, val cameraFallback: Boolean = false) {
    val landmarksOnly: Boolean get() = !source.hasCamera
}

/**
 * How one session starts (built by NavigationService from its start intent).
 * - [landmarksOnly]: force [SourceKind.NONE] whatever the saved source (CLAUDE.md §5.1).
 * - [cameraFallback]: only labels why there is no camera (permission refused / camera unavailable).
 * - [cameraSource]: the camera to use when the saved source is NONE (volume up x3, CLAUDE.md §8.2).
 * - [announce]: the start phrase; null = the default ([Phrases.navStarted]).
 * - [trigger]: the volume-key gesture that started it ("volume up x3"), logged as a `control` row.
 * - [verifyCamera]: started while CapSight was not on screen (e.g. from the pocket): if the phone
 *   camera cannot open (Android refuses background camera access), continue landmarks only and say
 *   [Phrases.CAMERA_UNAVAILABLE] instead of repeating "Camera disconnected".
 */
data class SessionStart(
    val landmarksOnly: Boolean = false,
    val cameraFallback: Boolean = false,
    val cameraSource: SourceKind? = null,
    val announce: String? = null,
    val trigger: String? = null,
    val verifyCamera: Boolean = false,
)

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
    private val depthRunner = DepthRunner(app)
    val speaker: Speaker by lazy { Speaker(app) }
    private val decisionLog = DecisionLogger(app)
    private val guidanceLoop by lazy { GuidanceLoop(speaker, onSpoken = decisionLog::record) }
    private val landmarkScanner = LandmarkScanner(app)
    private val tiltSensor = PhoneTiltSensor(app)

    val camera: StateFlow<CameraSettings?> = settings.camera.stateIn(scope, SharingStarted.Eagerly, null)
    val detectionSettings: StateFlow<DetectionSettings?> = settings.detection.stateIn(scope, SharingStarted.Eagerly, null)
    val speechSettings: StateFlow<SpeechSettings?> = settings.speech.stateIn(scope, SharingStarted.Eagerly, null)
    val navigationSettings: StateFlow<NavigationSettings?> = settings.navigation.stateIn(scope, SharingStarted.Eagerly, null)
    val landmarkSettings: StateFlow<LandmarkSettings?> = settings.landmarks.stateIn(scope, SharingStarted.Eagerly, null)
    val controlSettings: StateFlow<ControlSettings?> = settings.controls.stateIn(scope, SharingStarted.Eagerly, null)

    val frame = receiver.frame
    val status = receiver.status
    val detection = detector.state

    /** FR-OBS-04: the depth model's newest zone scores, rate and backend (or why it is off). */
    val depth = depthRunner.state
    val speech get() = speaker.state
    val guidance get() = guidanceLoop.state

    /** BLE landmark scanner status and the beacons it hears (CLAUDE.md §4.6). */
    val landmarks = landmarkScanner.state

    /** FR-APP-07: the current session's decision log file and row count. */
    val decisionLogState = decisionLog.state

    /** FR-APP-07: the newest decision log, for the Share button. */
    fun newestDecisionLog() = decisionLog.newestFile()

    /** FR-APP-06: the volume-key session's state, written by NavigationService, for the debug view. */
    val volumeKeys = MutableStateFlow(VOLUME_KEYS_OFF)

    /**
     * FR-APP-06: NavigationService's MediaSession currently takes the volume keys (navigating, or
     * standby with the screen off / locked). While false, MainActivity watches the keys itself.
     */
    val volumeKeySessionActive = MutableStateFlow(false)

    /** NavigationService is in standby: stopped, but holding the volume keys for a start (CLAUDE.md §8.2). */
    val standby = MutableStateFlow(false)

    /** MainActivity is resumed (on screen): a gesture may open the permission dialogs. Written by MainActivity. */
    val appInFront = MutableStateFlow(false)

    /**
     * A gesture start that first needs permission dialogs (CapSight on screen only): the screen runs
     * the normal start flow for this wish. Collected by AppRoot.
     */
    val startRequests = MutableSharedFlow<StartWish>(extraBufferCapacity = 1)

    /** Set when the phone camera reports it cannot be opened (verified sessions, see [SessionStart]). */
    private val cameraBlocked = MutableStateFlow<String?>(null)

    private val _running = MutableStateFlow(false)

    /** True while NavigationService is running the pipeline. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _session = MutableStateFlow<SessionMode?>(null)

    /** The running session's effective source (landmarks only?); null while stopped. */
    val session: StateFlow<SessionMode?> = _session.asStateFlow()

    /** The debug view's own distance smoothing (FR-DIST-04), same inputs as the guidance loop's. */
    private val debugPerception = SmoothedPerception()

    /** Detections with distance, corridor status and zones, for the debug overlay. */
    val perceived: StateFlow<PerceivedView> = combine(
        detection,
        camera.filterNotNull(),
        detectionSettings.filterNotNull(),
        navigationSettings.filterNotNull(),
    ) { det, cam, ds, nav ->
        val (params, fovSource) = perceptionParams(cam, ds, nav, det)
        val objects = debugPerception.perceive(det.updatedAtMs, det.detections, det.frameWidth, det.frameHeight, params)
        val zones = ZoneAnalyzer.analyze(objects.mapNotNull { it.relative }, ZoneParams(nav.corridorHalfWidthM, nav.warnDistanceM))
        val corridor = objects.filter { it.inCorridor }.mapNotNull { it.relative }
        PerceivedView(
            objects = objects,
            frameWidth = det.frameWidth,
            frameHeight = det.frameHeight,
            fovDeg = params.fovDeg,
            fovSource = fovSource,
            tilt = groundFor(cam.source, nav, det.frameRotationDeg).second,
            zones = if (det.running) zones else null,
            bestSide = ZoneAnalyzer.bestSide(zones, corridor, nav.corridorHalfWidthM),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_TIMEOUT_MS), PerceivedView())

    init {
        scope.launch { speechSettings.filterNotNull().collect { speaker.configure(it.rate, it.volume) } }
    }

    /**
     * Runs camera, detector and the 10 Hz guidance loop until cancelled. Called by NavigationService.
     *
     * [SessionStart.landmarksOnly] forces [SourceKind.NONE] for this session whatever the saved source
     * (the camera permission was refused, or the user chose no camera; CLAUDE.md §5.1): then no frame
     * receiver, detector, depth, tilt sensor or camera-link announcements run - only BLE landmarks, the
     * speaker, the decision log and a silent guidance loop. Without it a saved NONE source does the
     * same (unless [SessionStart.cameraSource] replaces it), and can be changed while running.
     * A verified camera session that cannot open the phone camera switches to the same mode.
     */
    suspend fun run(start: SessionStart = SessionStart()) {
        _running.value = true
        cameraBlocked.value = null
        // Set once if a verified camera session falls back to landmarks only.
        val forcedNoCamera = MutableStateFlow(start.landmarksOnly)
        var fallback = start.cameraFallback
        val effective: Flow<CameraSettings> = combine(camera.filterNotNull(), forcedNoCamera) { c, forced ->
            when {
                forced -> c.copy(source = SourceKind.NONE)
                c.source == SourceKind.NONE && start.cameraSource != null -> c.copy(source = start.cameraSource)
                else -> c
            }
        }.distinctUntilChanged()
        val hasCamera: Flow<Boolean> = effective.map { it.source.hasCamera }.distinctUntilChanged()
        try {
            val first = effective.first()
            _session.value = SessionMode(first.source, fallback)
            Log.i(TAG, "pipeline start (source ${first.source}${if (fallback) ", camera fallback" else ""}${start.trigger?.let { ", by $it" } ?: ""})")
            val sp = speechSettings.filterNotNull().first()
            val hints = controlSettings.value?.volumeButtonControl ?: ControlSettings().volumeButtonControl
            // FR-APP-02; landmarks only says so, so the user knows obstacles are not watched.
            val announce = start.announce ?: Phrases.navStarted(first.source.hasCamera, hints)
            speaker.say(announce, Priority.INFO, 0)
            val opening = start.trigger?.let { listOf(controlEntry(announce, "${it}: start", first.source)) }.orEmpty()
            coroutineScope {
                launch { effective.collect { _session.value = SessionMode(it.source, fallback) } }
                // FR-APP-07: one log file per session, written on the IO dispatcher. Contains its own
                // failures: a full disk never stops guidance.
                launch { decisionLog.run(opening) }
                // Started from the pocket: Android may refuse the phone camera (background / locked).
                // Then carry on landmarks only and say so, instead of "Camera disconnected" for ever.
                if (start.verifyCamera && first.source == SourceKind.PHONE_CAMERA) {
                    launch {
                        val problem = cameraProblem()
                        if (problem != null && landmarkSettings.value?.enabled != false) {
                            Log.w(TAG, "phone camera unavailable ($problem) - continuing landmarks only")
                            fallback = true
                            forcedNoCamera.value = true
                            speaker.say(Phrases.CAMERA_UNAVAILABLE, Priority.BLOCKED, 0)
                            decisionLog.record(controlEntry(Phrases.CAMERA_UNAVAILABLE, "camera unavailable: $problem", SourceKind.NONE))
                        } else if (problem != null) {
                            // Landmarks off: nothing else to guide with, keep the camera-mode fail-safes.
                            Log.w(TAG, "phone camera unavailable ($problem), landmarks off - staying in camera mode")
                        }
                    }
                }
                launch {
                    // A settings change while running restarts the receiver with the new source.
                    // No camera (landmarks only): no receiver, so no link monitoring either.
                    effective.collectLatest { s -> if (s.source.hasCamera) receiver.run(sourceFor(s), s.cameraLostMs) }
                }
                // Separate job, so switching GPU/CPU or class filter does not reconnect the camera.
                launch {
                    combine(hasCamera, detectionSettings.filterNotNull()) { cam, d -> cam to d }
                        .distinctUntilChanged()
                        .collectLatest { (cam, d) ->
                            if (cam) {
                                val classes = if (d.showAllClasses) null else CocoLabels.NAVIGATION
                                detector.run(receiver.frame, d.minConfidence, d.useGpu, classes)
                            }
                        }
                }
                // FR-OBS-04 / FR-PATH-03: monocular depth at ~4 Hz on its own thread. Contains its own
                // failures (missing model = depth off); never slows or stops the detector.
                launch {
                    combine(
                        hasCamera,
                        navigationSettings.filterNotNull().map { it.depthEnabled },
                        detectionSettings.filterNotNull().map { it.useGpu },
                    ) { cam, on, gpu -> Triple(cam, on, gpu) }
                        .distinctUntilChanged()
                        .collectLatest { (cam, on, gpu) ->
                            when {
                                !cam -> depthRunner.off(DEPTH_OFF_NO_CAMERA)
                                on -> depthRunner.run(receiver.frame, gpu)
                                else -> depthRunner.off(DEPTH_OFF_SETTING)
                            }
                        }
                }
                // FR-DIST-02: the phone's tilt sensor, only while the phone's own camera is the source.
                launch {
                    effective.map { it.source }.distinctUntilChanged().collectLatest { src ->
                        if (src == SourceKind.PHONE_CAMERA) tiltSensor.run()
                    }
                }
                // FR-APP-02: "Connecting to camera" if the link is not up shortly after start.
                launch {
                    delay(CONNECTING_ANNOUNCE_MS)
                    if (_session.value?.landmarksOnly == false && status.value.link != LinkState.CONNECTED) {
                        speaker.say(Phrases.CONNECTING, Priority.INFO, sp.phraseCooldownMs)
                    }
                }
                // BLE landmarks (§4.6): spoken through the same speaker, so its single slot and
                // priorities still hold - STAIRS at P1, the rest at INFO below every instruction.
                // Never ends navigation: the scanner contains its own failures (FR-NAV-05).
                launch {
                    landmarkSettings.filterNotNull().distinctUntilChanged().collectLatest { lm ->
                        if (!lm.enabled) landmarkScanner.off() else landmarkScanner.run(lm.config, ::announceLandmark)
                    }
                }
                // FR-NAV-02: the 10 Hz guidance loop - camera lost, obstacles, sides, all spoken from here.
                // Landmarks only: it ticks silently (no camera-lost, obstacle or clear phrases).
                launch {
                    combine(
                        effective,
                        detectionSettings.filterNotNull(),
                        navigationSettings.filterNotNull(),
                        speechSettings.filterNotNull(),
                    ) { cam, ds, nav, s -> LoopConfig(cam, ds, nav, s) }
                        .distinctUntilChanged()
                        .collectLatest { c ->
                            guidanceLoop.run(
                                link = status,
                                detection = detection,
                                depth = depth,
                                paramsFor = { det -> perceptionParams(c.cam, c.ds, c.nav, det).first },
                                config = guidanceConfig(c.nav, c.sp),
                                cameraLostMs = c.cam.cameraLostMs,
                                periodMs = MS_PER_S / c.nav.decisionRateHz,
                                speak = c.sp.speakGuidance,
                                cameraOff = !c.cam.source.hasCamera,
                            )
                        }
                }
            }
        } finally {
            _running.value = false
            _session.value = null
            Log.i(TAG, "pipeline stop")
        }
    }

    /**
     * Waits for the phone camera's first frame. Null = frames arrive; otherwise why not (the camera
     * reported a critical error, or no frame within [CAMERA_VERIFY_MS]).
     */
    private suspend fun cameraProblem(): String? {
        val first = withTimeoutOrNull(CAMERA_VERIFY_MS) {
            merge(
                status.filter { it.framesReceived > 0 }.map { CAMERA_OK },
                cameraBlocked.filterNotNull(),
            ).first()
        }
        return when (first) {
            CAMERA_OK -> null
            null -> "no frame within ${CAMERA_VERIFY_MS / MS_PER_S} s"
            else -> first
        }
    }

    /** FR-APP-07: a `control` row (volume-key gesture) for the decision log. */
    private fun controlEntry(phrase: String, reason: String, source: SourceKind): DecisionEntry {
        val st = status.value
        return DecisionEntry(
            elapsedMs = SystemClock.elapsedRealtime(),
            kind = EntryKind.CONTROL,
            priority = Priority.INFO,
            phrase = phrase,
            reason = reason,
            source = if (source.hasCamera) st.sourceLabel.ifEmpty { source.name } else NoCamera.label,
            cameraFps = st.fps,
            detectorFps = detection.value.fps,
            nearest = DecisionCsv.nearest(guidance.value.obstacles),
        )
    }

    /**
     * FR-APP-07: logs a volume-key gesture's reply ([phrase]) with [reason] in the running session's
     * decision log. Does nothing while stopped (there is no session log then).
     */
    fun recordControl(phrase: String, reason: String) {
        if (!_running.value) return
        decisionLog.record(controlEntry(phrase, reason, _session.value?.source ?: SourceKind.NONE))
    }

    /**
     * A landmark phrase is due (first announcement, closer or arrived): offer it to the speaker at
     * the landmark's priority; true if accepted. Every accepted one is logged (FR-APP-07).
     */
    private fun announceLandmark(cue: LandmarkCue): Boolean {
        val landmark = cue.landmark
        val cooldown = (speechSettings.value ?: SpeechSettings()).phraseCooldownMs
        val accepted = speaker.say(cue.phrase, cue.priority, cooldown)
        if (accepted) {
            val st = status.value
            decisionLog.record(
                DecisionEntry(
                    elapsedMs = SystemClock.elapsedRealtime(),
                    kind = EntryKind.LANDMARK,
                    priority = cue.priority,
                    phrase = cue.phrase,
                    reason = String.format(
                        Locale.US,
                        "BLE beacon %s %s ~%.1f m (step %d) %.0f dBm",
                        landmark.type.name,
                        cue.kind.name.lowercase(),
                        cue.distanceM,
                        cue.step,
                        cue.rssiDbm,
                    ),
                    source = if (_session.value?.landmarksOnly == true) NoCamera.label else st.sourceLabel,
                    cameraFps = st.fps,
                    detectorFps = detection.value.fps,
                    nearest = DecisionCsv.nearest(guidance.value.obstacles),
                ),
            )
        }
        return accepted
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
        SourceKind.PHONE_CAMERA -> PhoneCameraSource(app) { reason -> cameraBlocked.value = reason }
        SourceKind.VIDEO_FILE -> s.videoUri?.let { VideoFileSource(app, it.toUri()) } ?: NoVideoChosen
        SourceKind.NONE -> NoCamera // never run: run() starts no receiver without a camera
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
        det: DetectionState,
    ): Pair<PerceptionParams, String> {
        val (fov, fovSource) = fovFor(cam.source, det.frameFovDeg, nav)
        return PerceptionParams(
            fovDeg = fov,
            minConfidence = ds.minConfidence,
            skipUnreliable = nav.skipUnreliable,
            corridorHalfWidthM = nav.corridorHalfWidthM,
            warnDistanceM = nav.warnDistanceM,
            stopDistanceM = nav.stopDistanceM,
            ground = groundFor(cam.source, nav, det.frameRotationDeg).first,
        ) to fovSource
    }

    /**
     * Camera pose for ground-plane distance (FR-DIST-02/03) and a line for the debug view. Only the
     * phone camera has a tilt sensor; the cap gets one with the planned /imu (contract v2, §4.4).
     * Never throws: any problem means size-based distance only.
     */
    private fun groundFor(source: SourceKind, nav: NavigationSettings, rotationDeg: Int?): Pair<GroundParams?, String> = try {
        val reading = tiltSensor.reading.value
        val tilt = reading?.let { CameraTilt.fromGravity(it.gravity, rotationDeg ?: 0) }
        when {
            source != SourceKind.PHONE_CAMERA -> null to "size only (no tilt sensor for this source)"
            reading == null || tilt == null -> null to "size only (tilt: ${tiltSensor.status.value})"
            SystemClock.elapsedRealtime() - reading.atMs > TILT_STALE_MS -> null to "size only (tilt reading stale)"
            else -> {
                val g = GroundParams(nav.cameraHeightM, tilt)
                val mode = if (GroundPlane.usable(g)) "floor+size, nearer wins" else "size only (roll over ${GroundPlane.MAX_ROLL_DEG.toInt()}°)"
                g to String.format(
                    Locale.US, "pitch %.1f° down  roll %.1f°  cam %.2f m  -> %s",
                    tilt.pitchDownDeg, tilt.rollDeg, nav.cameraHeightM, mode,
                )
            }
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "tilt unavailable", e)
        null to "size only (tilt error)"
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
        depth = DepthParams(enabled = nav.depthEnabled, threshold = nav.depthThreshold),
    )

    /** Which horizontal FOV turns pixels into metres for this source (CLAUDE.md §8, §8.1). */
    private fun fovFor(source: SourceKind, frameFov: Float?, nav: NavigationSettings): Pair<Double, String> =
        when (source) {
            SourceKind.ESP32 -> nav.capFovDeg to "cap setting"
            SourceKind.PHONE_CAMERA ->
                frameFov?.let { it.toDouble() to "phone camera" } ?: (nav.phoneFovDeg to "phone default")
            SourceKind.VIDEO_FILE -> nav.phoneFovDeg to "video default"
            SourceKind.NONE -> nav.phoneFovDeg to "no camera"
        }

    private object NoVideoChosen : FrameSource {
        override val label = "Video file"

        override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
            onStatus("Choose a video file first")
            awaitCancellation()
        }
    }

    /** Landmarks only (CLAUDE.md §5.1): no frames, by choice. */
    private object NoCamera : FrameSource {
        override val label = "No camera (landmarks only)"

        override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
            onStatus("Camera off - landmarks only")
            awaitCancellation()
        }
    }

    companion object {
        private const val UI_STOP_TIMEOUT_MS = 5_000L

        /** A tilt reading older than this is not trusted (sensor stopped). */
        private const val TILT_STALE_MS = 1_000L
        const val VOLUME_KEYS_OFF = "off"
        private const val DEPTH_OFF_SETTING = "switched off in Settings"
        private const val DEPTH_OFF_NO_CAMERA = "camera off - landmarks only"
        private const val MS_PER_S = 1_000L
        private const val CONNECTING_ANNOUNCE_MS = 1_500L

        /** A verified phone-camera session that has no frame by then continues landmarks only. */
        private const val CAMERA_VERIFY_MS = 5_000L
        private const val CAMERA_OK = ""
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
