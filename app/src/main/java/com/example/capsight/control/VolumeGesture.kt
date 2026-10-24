package com.example.capsight.control

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.example.capsight.TAG

/**
 * FR-APP-06: the volume-key gestures, on the Android side. Thin: [MultiPressDetector] decides.
 *
 * Every press still changes the volume (the user must be able to turn speech down); when a gesture
 * is recognised, the volume from before the burst's first press is put back and [onGesture] runs
 * on the main thread. [onGesture] should not throw; it is contained here anyway.
 *
 * [holdCpu] (the MediaSession path, screen off): each press takes a partial wake lock with a
 * timeout, so the 500 ms decision and the reply are not delayed by the phone going to sleep
 * mid-burst. Nothing is held while idle. Main thread only.
 */
class VolumeGesture(context: Context, private val holdCpu: Boolean = false, private val onGesture: (Gesture) -> Unit) {

    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val detector = MultiPressDetector()
    private var savedVolume: Int? = null
    private val pollTask = Runnable {
        decide(SystemClock.elapsedRealtime())
        schedule()
    }

    private val wakeLock: PowerManager.WakeLock? = if (!holdCpu) {
        null
    } else {
        try {
            (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CapSight:volume-gesture")
                .apply { setReferenceCounted(false) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "no wake lock for volume gestures", e)
            null
        }
    }

    /**
     * One press of [key]. [adjust] true: change the volume here (the MediaSession path, where the
     * system leaves it to us); false: Android already changes it (key event in the Activity).
     * [repeat]: an auto-repeat of a held key (known only in the Activity).
     */
    fun onPress(key: VolumeKey, adjust: Boolean, repeat: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // A burst that is already over is decided before this press opens a new one.
        decide(now)
        if (detector.press(key, now, repeat)) savedVolume = currentVolume()
        if (adjust) adjust(if (key == VolumeKey.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
        try {
            wakeLock?.acquire(BURST_WAKE_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "volume-gesture wake lock refused", e)
        }
        schedule()
    }

    /** Changes the speech stream's volume by one step, as a plain volume key would. */
    fun adjust(direction: Int) = adjustStream(SPEECH_STREAM, direction)

    /** In a phone or VoIP call the keys must change the call volume, never ours (and are no gesture). */
    fun inCall(): Boolean = try {
        audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (e: RuntimeException) {
        false
    }

    fun adjustCall(direction: Int) = adjustStream(AudioManager.STREAM_VOICE_CALL, direction)

    fun currentVolume(): Int = audio.getStreamVolume(SPEECH_STREAM)

    val maxVolume: Int get() = audio.getStreamMaxVolume(SPEECH_STREAM)

    fun release() {
        main.removeCallbacks(pollTask)
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "volume-gesture wake lock release failed", e)
        }
    }

    private fun adjustStream(stream: Int, direction: Int) {
        try {
            audio.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            // Do Not Disturb policy can refuse volume changes; the gesture still works.
            Log.w(TAG, "volume change refused", e)
        }
    }

    private fun schedule() {
        main.removeCallbacks(pollTask)
        val at = detector.pollAtMs ?: return
        main.postDelayed(pollTask, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun decide(now: Long) {
        val gesture = detector.poll(now) ?: return
        savedVolume?.let { v ->
            try {
                audio.setStreamVolume(SPEECH_STREAM, v, 0)
            } catch (e: SecurityException) {
                Log.w(TAG, "could not restore the volume", e)
            }
        }
        savedVolume = null
        Log.i(TAG, "volume gesture: ${gesture.label}")
        try {
            onGesture(gesture)
        } catch (e: RuntimeException) {
            // A gesture must never take anything else down with it (guidance keeps running).
            Log.e(TAG, "volume gesture ${gesture.label} failed", e)
        }
    }

    companion object {
        /**
         * The stream speech plays on: USAGE_ASSISTANCE_NAVIGATION_GUIDANCE (voice/Speaker.kt) maps
         * to the media stream.
         */
        const val SPEECH_STREAM = AudioManager.STREAM_MUSIC

        /** Wake lock per press: the 500 ms decision, the reply starting, and some slack. */
        private const val BURST_WAKE_MS = 4_000L

        /** The gesture key for an Activity key code, or null for any other key. */
        fun keyOf(keyCode: Int): VolumeKey? = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKey.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKey.DOWN
            else -> null
        }
    }
}

/**
 * Lets the volume keys reach the app while the screen is off (FR-APP-06), owned by
 * NavigationService: always while navigating, and in standby while the screen is off or locked
 * (CLAUDE.md §8.2).
 *
 * A MediaSession with REMOTE playback and a [VolumeProvider] that is "playing": Android then sends
 * volume-key presses to [VolumeProvider.onAdjustVolume] instead of changing a stream itself. We
 * pass single presses on to the speech (media) stream, so they still work as volume keys; during a
 * phone or VoIP call they change the call volume instead and never count as a gesture.
 *
 * Limits (verify on each phone): some Android 13+ builds ignore remote sessions that have no media
 * route; another app that is playing media may take the volume keys while it plays. Create, use
 * and release on the main thread. Created inactive.
 */
class VolumeKeySession(context: Context, onGesture: (Gesture) -> Unit) {

    private val gesture = VolumeGesture(context, holdCpu = true, onGesture = onGesture)
    private val session = MediaSession(context.applicationContext, "CapSight volume keys")

    private val provider = object : VolumeProvider(
        VOLUME_CONTROL_RELATIVE,
        gesture.maxVolume,
        gesture.currentVolume(),
    ) {
        override fun onAdjustVolume(direction: Int) {
            // Called by Android on the main thread: nothing may throw out of here.
            try {
                if (direction == 0) return
                if (gesture.inCall()) {
                    gesture.adjustCall(direction)
                    return
                }
                gesture.onPress(if (direction > 0) VolumeKey.UP else VolumeKey.DOWN, adjust = true)
                currentVolume = gesture.currentVolume()
            } catch (e: RuntimeException) {
                Log.e(TAG, "volume key handling failed", e)
            }
        }
    }

    init {
        // Without a callback the session drops volume commands; this one needs no methods.
        session.setCallback(object : MediaSession.Callback() {}, Handler(Looper.getMainLooper()))
        session.setPlaybackToRemote(provider)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(0L)
                .build(),
        )
        Log.i(TAG, "volume-key session created")
    }

    /** Active = Android routes the volume keys here. Inactive = they work exactly as without CapSight. */
    var active: Boolean
        get() = session.isActive
        set(value) {
            if (value == session.isActive) return
            session.isActive = value
            Log.i(TAG, "volume-key session ${if (value) "active" else "inactive"}")
        }

    fun release() {
        gesture.release()
        session.isActive = false
        session.release()
        Log.i(TAG, "volume-key session released")
    }
}
