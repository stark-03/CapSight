package com.example.capsight.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.capsight.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** What the debug view shows about the landmark scanner. */
data class LandmarkState(
    /** "BLE: scanning, 2 beacons", "BLE: permission denied", "BLE: Bluetooth off"... */
    val status: String = "BLE: not running",
    val beacons: List<BeaconView> = emptyList(),
    /** The last landmark phrase the speaker accepted this session, or "". */
    val lastSpoken: String = "",
)

/**
 * Scans for BLE landmark beacons while navigation runs (CLAUDE.md §4.6) and feeds every
 * advertisement into a [LandmarkTracker]. Low-latency scan mode, filtered on the CapSight
 * service UUID ([Landmark.SERVICE_UUID]) so Android keeps delivering results with the screen off;
 * the `CS:` name arrives in the beacon's scan response.
 *
 * Never a reason to stop guidance: if BLE is unsupported, the permission was refused or Bluetooth
 * (or, on API 24-30, Location) is off, it reports that in [state] and keeps checking, so turning
 * Bluetooth on later starts the scan.
 */
class LandmarkScanner(context: Context) {

    private val app = context.applicationContext
    private val lock = Any()

    private val _state = MutableStateFlow(LandmarkState())
    val state: StateFlow<LandmarkState> = _state.asStateFlow()

    /**
     * Scans until cancelled. [announce] is called (on the main thread) with each landmark phrase that
     * is due (first announcement, closer, arrived); it returns true if the speaker accepted it.
     */
    suspend fun run(cfg: LandmarkConfig, announce: (LandmarkCue) -> Boolean) {
        val callback = Callback(LandmarkTracker(cfg), announce)
        Log.i(TAG, "landmark scanner start ($cfg)")
        try {
            scanLoop(callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Contained here: an exception must not cancel the pipeline and its guidance loop.
            Log.e(TAG, "landmark scanner failed - landmarks off, guidance continues", e)
            publish("BLE: error (${e.javaClass.simpleName})", emptyList())
            awaitCancellation()
        } finally {
            Log.i(TAG, "landmark scanner stop")
            _state.value = LandmarkState()
        }
    }

    /** Shown while landmarks are switched off in Settings; returns only when cancelled. */
    suspend fun off() {
        try {
            _state.value = LandmarkState(status = "BLE: landmarks off in Settings")
            awaitCancellation()
        } finally {
            _state.value = LandmarkState()
        }
    }

    private suspend fun scanLoop(callback: Callback) {
        var scanner: BluetoothLeScanner? = null
        var startedMs = 0L
        try {
            while (true) {
                val problem = problem()
                val failed = synchronized(lock) { callback.failure.also { callback.failure = null } }
                val now = SystemClock.elapsedRealtime()
                when {
                    problem != null || failed != null -> {
                        scanner = stop(scanner, callback)
                        publish(problem ?: "BLE: scan failed (error $failed)", emptyList())
                        delay(RETRY_MS)
                        continue
                    }
                    // Android turns scans older than 30 min into opportunistic ones (hears nothing).
                    scanner != null && now - startedMs > RESTART_SCAN_MS -> scanner = stop(scanner, callback)
                }
                if (scanner == null) {
                    scanner = start(callback)
                    startedMs = now
                    if (scanner == null) {
                        publish("BLE: could not start scan", emptyList())
                        delay(RETRY_MS)
                        continue
                    }
                }
                val (beacons, said) = synchronized(lock) { callback.tracker.snapshot(now) to callback.lastSpoken }
                publish("BLE: scanning, ${beacons.size} beacon${if (beacons.size == 1) "" else "s"}", beacons, said)
                delay(PUBLISH_MS)
            }
        } finally {
            stop(scanner, callback)
        }
    }

    /** Called by Android on the main thread; the tracker is shared with [scanLoop] under [lock]. */
    private inner class Callback(val tracker: LandmarkTracker, val announce: (LandmarkCue) -> Boolean) : ScanCallback() {
        /** Set by [onScanFailed], read and cleared by [scanLoop]. Guarded by [lock]. */
        var failure: Int? = null

        /** The last phrase accepted, for the screens. Guarded by [lock]. */
        var lastSpoken = ""

        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onResult(it) }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            synchronized(lock) { failure = errorCode }
        }

        private fun onResult(r: ScanResult) {
            // Contained: nothing may throw into Android's scan callback.
            try {
                val name = r.scanRecord?.deviceName ?: return
                val now = SystemClock.elapsedRealtime()
                val key = r.device.address
                val due = synchronized(lock) { tracker.onAdvert(key, name, r.rssi, now) } ?: return
                if (announce(due)) {
                    synchronized(lock) {
                        tracker.announced(key, now)
                        lastSpoken = due.phrase
                    }
                    Log.i(TAG, "landmark ${due.kind}: ${due.phrase} (${r.rssi} dBm, smoothed ${due.rssiDbm}, ~${"%.1f".format(Locale.US, due.distanceM)} m)")
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "landmark reading failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ Android

    /** Why we cannot scan right now, or null if we can. */
    private fun problem(): String? {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return "BLE: not supported"
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "BLE: not supported"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(app, Manifest.permission.BLUETOOTH_SCAN)) {
            return "BLE: Nearby devices permission denied"
        }
        if (!granted(app, Manifest.permission.ACCESS_FINE_LOCATION)) return "BLE: precise location permission denied"
        if (!adapter.isEnabled) return "BLE: Bluetooth off"
        // Without neverForLocation, every API level delivers no scan results while Location is off
        // (CLAUDE.md §6.1).
        val location = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (location != null && !LocationManagerCompat.isLocationEnabled(location)) return "BLE: Location off"
        return null
    }

    @SuppressLint("MissingPermission") // checked in problem() just before; SecurityException caught
    private fun start(callback: Callback): BluetoothLeScanner? = try {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter.bluetoothLeScanner?.also { s ->
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(Landmark.SERVICE_UUID)).build()
            s.startScan(listOf(filter), settings, callback)
            Log.i(TAG, "BLE scan started")
        }
    } catch (e: RuntimeException) {
        // SecurityException if the permission was revoked meanwhile; IllegalStateException if BT just went off.
        Log.w(TAG, "BLE scan could not start", e)
        null
    }

    @SuppressLint("MissingPermission")
    private fun stop(scanner: BluetoothLeScanner?, callback: Callback): BluetoothLeScanner? {
        try {
            scanner?.stopScan(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "BLE scan stop failed", e)
        }
        return null
    }

    private fun publish(status: String, beacons: List<BeaconView>, lastSpoken: String = "") {
        _state.value = LandmarkState(status, beacons, lastSpoken)
    }

    companion object {
        private const val PUBLISH_MS = 1_000L
        /** Android blocks an app that starts more than 5 scans in 30 s, so retry slowly. */
        private const val RETRY_MS = 7_000L
        private const val RESTART_SCAN_MS = 25 * 60_000L

        /**
         * The runtime permissions BLE scanning needs on this Android version (CLAUDE.md §6.1).
         * Fine location on every version: we do not assert neverForLocation (it would drop beacon
         * advertisements), and without it Android 12+ delivers no scan results unless the app also
         * holds ACCESS_FINE_LOCATION. Coarse is requested alongside, as Android 12+ requires.
         */
        val scanPermissions: List<String>
            get() = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

        /** Coarse alone is not enough for scan results; fine location (and BLUETOOTH_SCAN on 31+) is. */
        fun hasScanPermission(context: Context) =
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_SCAN))

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
