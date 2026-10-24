package com.example.capsight.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import com.example.capsight.perception.CameraTilt
import com.example.capsight.perception.Gravity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest gravity reading and when it arrived (SystemClock.elapsedRealtime). */
data class GravityReading(val gravity: Gravity, val atMs: Long, val sensor: String)

/**
 * The phone's tilt sensor for ground-plane distance in PHONE_CAMERA mode (FR-DIST-02). Thin: it
 * only publishes the gravity vector; [CameraTilt] turns it into pitch and roll.
 *
 * TYPE_GRAVITY when present (already filtered by the phone's sensor fusion), else TYPE_ACCELEROMETER
 * through a low-pass filter so walking jolts do not swing the pitch. Events arrive on their own
 * thread, not the main one. Non-wake-up sensors keep reporting with the screen off while the
 * service holds its partial wake lock.
 */
class PhoneTiltSensor(context: Context) {

    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    private val _reading = MutableStateFlow<GravityReading?>(null)
    val reading: StateFlow<GravityReading?> = _reading.asStateFlow()

    private val _status = MutableStateFlow("off")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Reads the sensor until cancelled. Missing sensor: says so and waits; never throws. */
    suspend fun run() {
        val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravity ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            _status.value = "no tilt sensor - size-based distance only"
            awaitCancellation()
        }
        val name = if (gravity != null) "gravity" else "accelerometer (low-pass)"
        val thread = HandlerThread("CapSight-tilt").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sample = Gravity(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                val g = if (gravity != null) sample else CameraTilt.lowPass(_reading.value?.gravity, sample, LOW_PASS_K)
                _reading.value = GravityReading(g, SystemClock.elapsedRealtime(), name)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(thread.looper))) {
                _status.value = "tilt sensor refused - size-based distance only"
                awaitCancellation()
            }
            _status.value = name
            Log.i(TAG, "tilt sensor: $name")
            awaitCancellation()
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
            _reading.value = null
            _status.value = "off"
        }
    }

    private companion object {
        /** ~50 Hz samples: this weight gives a time constant of about 0.2 s. */
        const val LOW_PASS_K = 0.1
    }
}
