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
import com.example.capsight.camera.LinkState
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
 * Hosts the frame receiver, detector and speaker for the debug screen (FR-APP-05, steps 1-3).
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
        // FR-RX-03/04 spoken. Step 6's guidance loop takes this over as its P0 camera-lost rule.
        viewModelScope.launch {
            var previous: LinkState? = null
            combine(status, speechSettings.filterNotNull()) { st, sp -> st to sp }.collect { (st, sp) ->
                if (!st.running || !sp.announceCameraLink) {
                    previous = null
                    return@collect
                }
                when (st.link) {
                    // Offered on every status update; the 2 s P0 cooldown sets the repeat rate.
                    LinkState.DISCONNECTED -> speaker.say(Phrases.CAMERA_LOST, Priority.STOP, sp.stopCooldownMs)
                    LinkState.CONNECTED -> if (previous == LinkState.DISCONNECTED) {
                        speaker.say(Phrases.CAMERA_CONNECTED, Priority.INFO, sp.phraseCooldownMs)
                    }
                    LinkState.CONNECTING -> Unit
                }
                previous = st.link
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

    fun setAnnounceCameraLink(on: Boolean) = viewModelScope.launch { appSettings.setAnnounceCameraLink(on) }

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

    private companion object {
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
