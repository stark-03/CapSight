package com.example.capsight.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.capsight.TAG

/**
 * Vibration alerts (FR-VOICE-06, CLAUDE.md §6.4 version paths): P0 always vibrates in
 * addition to speech; P2 vibrates only while speech is unavailable.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** Three long pulses: stop now. */
    fun stop() = play(STOP_PATTERN)

    /** Two short pulses: obstacle ahead, steer. */
    fun avoid() = play(AVOID_PATTERN)

    private fun play(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, NO_REPEAT)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    private companion object {
        /** off/on pairs in ms, starting with an initial delay. */
        val STOP_PATTERN = longArrayOf(0, 400, 150, 400, 150, 400)
        val AVOID_PATTERN = longArrayOf(0, 150, 100, 150)
        const val NO_REPEAT = -1
    }
}
