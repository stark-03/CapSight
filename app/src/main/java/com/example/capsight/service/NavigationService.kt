package com.example.capsight.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.capsight.MainActivity
import com.example.capsight.R
import com.example.capsight.TAG
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the guidance pipeline, so guidance keeps running with the screen
 * off (FR-APP-03, CLAUDE.md §5, §6.1, §6.4).
 *
 * - Type connectedDevice (the cap camera over Wi-Fi), plus camera when the phone's own camera is
 *   the source (it can only be added while the app is in the foreground, API 34+).
 * - A partial wake lock keeps the CPU running and a Wi-Fi lock keeps the camera link awake.
 * - The notification has a Stop action.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        // A null intent means the system restarted us after killing the process: resume guidance.
        startNavigation(useCamera = intent?.getBooleanExtra(EXTRA_USE_CAMERA, false) == true)
        return START_STICKY
    }

    private fun startNavigation(useCamera: Boolean) {
        if (job?.isActive == true) return
        if (!enterForeground(useCamera)) {
            stopSelf()
            return
        }
        acquireLocks()
        val pipeline = Pipeline.get(this)
        job = scope.launch { pipeline.run() }
        Log.i(TAG, "navigation service started (phone camera: $useCamera)")
    }

    private fun stopNavigation() {
        Pipeline.get(this).let { p ->
            if (p.running.value) p.speaker.say(Phrases.NAV_STOPPED, Priority.INFO, 0) // FR-APP-02
        }
        job?.cancel()
        job = null
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "navigation service stopped")
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    /** startForeground with the right service types; false if Android refused. */
    private fun enterForeground(useCamera: Boolean): Boolean {
        createChannel(this)
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (useCamera && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasCameraPermission()) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
            true
        } catch (e: RuntimeException) {
            // e.g. camera type requested from the background after a system restart.
            Log.e(TAG, "startForeground with types $types refused", e)
            if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) enterForeground(useCamera = false) else false
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_capsight)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0, getString(R.string.notification_stop),
            PendingIntent.getService(
                this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    @SuppressLint("WakelockTimeout") // released in stopNavigation/onDestroy; guidance has no fixed length
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CapSight:guidance").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "CapSight:camera").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    companion object {
        private const val ACTION_STOP = "com.example.capsight.action.STOP"
        private const val EXTRA_USE_CAMERA = "use_camera"
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 1

        /** Starts guidance. [usePhoneCamera] adds the camera service type (phone-camera source). */
        fun start(context: Context, usePhoneCamera: Boolean) {
            val intent = Intent(context, NavigationService::class.java).putExtra(EXTRA_USE_CAMERA, usePhoneCamera)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP))
        }

        private fun createChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.notification_channel))
                    .setShowBadge(false)
                    .build(),
            )
        }
    }
}
