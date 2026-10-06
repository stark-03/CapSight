package com.example.capsight.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What the screen shows as captions and speech status (CLAUDE.md §8: keep showing captions). */
data class SpeakerState(
    val ttsReady: Boolean = false,
    val engine: String = "",
    val error: String? = null,
    val speaking: String? = null,
    val pending: String? = null,
    val lastSpoken: String = "",
    val lastSuppressed: String? = null,
    val output: String = "",
    val recoveries: Int = 0,
)

/**
 * Speaks navigation instructions with Android TextToSpeech, offline (FR-VOICE-01..06, §8).
 *
 * Exactly one utterance is handed to the engine at a time; everything else waits in the
 * [SpeechArbiter]'s single slot, so a stale instruction is replaced rather than queued. A P0
 * phrase is spoken at once with QUEUE_FLUSH, cutting off what is being said, and vibrates.
 *
 * Audio uses USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, so Android routes it to Bluetooth earphones
 * when connected and to the phone speaker otherwise, switching mid-run (FR-VOICE-02, AT-07).
 *
 * Speech engines die quietly. If an utterance has not started within [START_TIMEOUT_MS] the
 * engine is treated as silent: the instruction falls back to vibration and caption, and the
 * engine is rebuilt. While speech is unavailable, P0 and P2 vibrate.
 *
 * Thread-safe; call [shutdown] when done.
 */
class Speaker(context: Context, private val clock: () -> Long = SystemClock::elapsedRealtime) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val haptics = Haptics(app)
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val arbiter = SpeechArbiter()
    private val lock = Any()

    private val _state = MutableStateFlow(SpeakerState())
    val state: StateFlow<SpeakerState> = _state.asStateFlow()

    // All guarded by lock.
    private var tts: TextToSpeech? = null
    private var ready = false
    private var engine = ""
    private var error: String? = null
    private var speakingId: String? = null
    private var speakingText: String? = null
    private var started = false
    private var seq = 0
    private var lastSpoken = ""
    private var lastSuppressed: String? = null
    private var recoveries = 0
    private var lastRebuildMs = Long.MIN_VALUE / 2
    private var rate = 1f
    private var volume = 1f
    private var focus: AudioFocusRequest? = null
    private var shutDown = false

    init {
        main.post { createEngine() }
    }

    /** FR-VOICE-05: speech rate (1.0 = normal) and volume (0..1). */
    fun configure(rate: Float, volume: Float) {
        synchronized(lock) {
            this.rate = rate
            this.volume = volume.coerceIn(0f, 1f)
            tts?.setSpeechRate(rate)
        }
    }

    /**
     * Requests speech. Returns false if suppressed (same phrase within [cooldownMs], or a more
     * urgent phrase is waiting).
     */
    fun say(text: String, priority: Int, cooldownMs: Long): Boolean {
        val accepted = synchronized(lock) {
            val ok = arbiter.offer(text, priority, cooldownMs, clock())
            if (!ok) lastSuppressed = text
            ok
        }
        if (accepted) pump()
        publish()
        return accepted
    }

    fun shutdown() {
        synchronized(lock) {
            shutDown = true
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
            abandonFocus()
        }
        main.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------ engine

    private fun createEngine() {
        synchronized(lock) {
            if (shutDown) return
            ready = false
            var created: TextToSpeech? = null
            created = TextToSpeech(app) { status -> main.post { onInit(created!!, status) } }
            tts = created
        }
        publish()
    }

    private fun onInit(engineInstance: TextToSpeech, status: Int) {
        synchronized(lock) {
            if (engineInstance !== tts || shutDown) return
            if (status != TextToSpeech.SUCCESS) {
                fail("Text-to-speech failed to start (status $status)")
                return
            }
            val lang = listOf(Locale.US, Locale.UK, Locale.ENGLISH).firstOrNull {
                engineInstance.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE
            }
            if (lang == null) {
                fail("No English voice installed - install one in phone Settings > Text-to-speech")
                return
            }
            chooseOfflineVoice(engineInstance, lang)
            engineInstance.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engineInstance.setSpeechRate(rate)
            engineInstance.setOnUtteranceProgressListener(progress)
            engine = engineInstance.defaultEngine.orEmpty()
            ready = true
            error = null
            Log.i(TAG, "TTS ready: engine $engine, voice ${engineInstance.voice?.name}")
        }
        pump()
        publish()
    }

    /** FR-VOICE-01: prefer a voice that works with no internet. */
    private fun chooseOfflineVoice(t: TextToSpeech, lang: Locale) {
        try {
            val offline = t.voices.orEmpty().filter {
                it.locale.language == lang.language &&
                    !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
            }
            val best = offline.firstOrNull { it.locale == lang } ?: offline.firstOrNull()
            if (best != null) t.voice = best
        } catch (e: RuntimeException) {
            // Some engines throw from getVoices(); the default voice still works.
            Log.w(TAG, "could not list TTS voices", e)
        }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        ready = false
        error = message
    }

    // ----------------------------------------------------------------- speaking

    /** Hands the pending phrase to the engine if it is free, or if the phrase is a P0. */
    private fun pump() {
        synchronized(lock) {
            val next = arbiter.pending ?: return
            if (speakingId != null && !next.interrupts) return
            val u = arbiter.takeNext(clock()) ?: return
            lastSpoken = u.text
            if (u.priority == Priority.STOP) haptics.stop()
            val engineInstance = tts
            if (!ready || engineInstance == null) {
                // Captions only; vibration carries P2 (P0 already vibrated above).
                if (u.priority == Priority.AVOID) haptics.avoid()
                Log.i(TAG, "speech unavailable, caption only: ${u.text}")
                return
            }
            val id = "u${++seq}"
            speakingId = id
            speakingText = u.text
            started = false
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
            val mode = if (u.interrupts) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (engineInstance.speak(u.text, mode, params, id) != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() refused: ${u.text}")
                silentEngine(id, u)
                return
            }
            main.postDelayed({ checkStarted(id, u) }, START_TIMEOUT_MS)
        }
    }

    private fun checkStarted(id: String, u: Utterance) {
        synchronized(lock) {
            if (speakingId != id || started) return
            silentEngine(id, u)
        }
        pump()
        publish()
    }

    /** The engine took the phrase but made no sound. Fall back, rebuild, carry on. */
    private fun silentEngine(id: String, u: Utterance) {
        if (speakingId == id) {
            speakingId = null
            speakingText = null
        }
        if (u.priority == Priority.AVOID) haptics.avoid()
        val now = clock()
        if (now - lastRebuildMs >= REBUILD_INTERVAL_MS) {
            lastRebuildMs = now
            recoveries++
            Log.w(TAG, "TTS went silent - rebuilding the engine (recovery #$recoveries)")
            tts?.shutdown()
            tts = null
            ready = false
            error = "Speech engine went silent; restarting it"
            main.post { createEngine() }
        }
    }

    private fun finished(id: String?) {
        synchronized(lock) {
            if (id == null || id != speakingId) return
            speakingId = null
            speakingText = null
            abandonFocus()
        }
        pump()
        publish()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            synchronized(lock) {
                if (utteranceId != speakingId) return
                started = true
                requestFocus()
            }
            // An engine that starts but never finishes must not block speech for ever.
            main.postDelayed({ finished(utteranceId) }, MAX_UTTERANCE_MS)
            publish()
        }

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "TTS error $errorCode on $utteranceId")
            finished(utteranceId)
        }
    }

    // -------------------------------------------------------------- audio focus

    /** Duck music while an instruction plays. */
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
                .also { focus = it }
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }

    /** Where speech will play, for the debug captions (AT-07). */
    private fun outputRoute(): String {
        val types = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        val bluetooth = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetooth += AudioDeviceInfo.TYPE_BLE_HEADSET
            bluetooth += AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        val wired = mutableSetOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) wired += AudioDeviceInfo.TYPE_USB_HEADSET
        return when {
            types.any { it in bluetooth } -> "Bluetooth earphones"
            types.any { it in wired } -> "Wired earphones"
            else -> "Phone speaker"
        }
    }

    private fun publish() {
        val route = outputRoute()
        synchronized(lock) {
            _state.value = SpeakerState(
                ttsReady = ready,
                engine = engine,
                error = error,
                speaking = speakingText,
                pending = arbiter.pending?.text,
                lastSpoken = lastSpoken,
                lastSuppressed = lastSuppressed,
                output = route,
                recoveries = recoveries,
            )
        }
    }

    private companion object {
        /** A real utterance starts within a few hundred ms; longer means the engine is silent. */
        const val START_TIMEOUT_MS = 2_000L

        /** Instructions are at most five words (FR-VOICE-04); nothing legitimate lasts this long. */
        const val MAX_UTTERANCE_MS = 10_000L

        /** Do not rebuild a failing engine more often than this. */
        const val REBUILD_INTERVAL_MS = 30_000L
    }
}
