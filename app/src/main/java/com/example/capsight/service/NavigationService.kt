package com.example.capsight.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.capsight.MainActivity
import com.example.capsight.R
import com.example.capsight.TAG
import com.example.capsight.ble.LandmarkScanner
import com.example.capsight.control.VolumeKeySession
import com.example.capsight.settings.SourceKind
import com.example.capsight.settings.StartPlan
import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Foreground service that owns the guidance pipeline, so guidance keeps running with the screen
 * off (FR-APP-03, CLAUDE.md §5, §6.1, §6.4), and the volume-key standby (FR-APP-06, §8.2).
 *
 * Two states:
 * - **Navigating**: type connectedDevice (the cap camera over Wi-Fi, BLE landmarks), plus camera when
 *   the phone's own camera is the source (it can only be added while the app may use the camera,
 *   API 34+). A partial wake lock keeps the CPU running and a Wi-Fi lock keeps the camera link awake
 *   (neither Wi-Fi lock nor camera type in landmarks-only mode). Notification: Stop + Exit.
 * - **Standby** (stopped, "Volume-button control" on, CapSight opened once): type connectedDevice only,
 *   notification "CapSight ready - press volume up twice to start" with Exit. Nothing runs: no camera,
 *   YOLO, depth or BLE scan, no wake lock or Wi-Fi lock. Only the volume-key MediaSession, and only
 *   while the screen is off or locked, so with the phone in use the keys behave normally.
 *
 * Stop (gesture, button, notification) returns to standby while volume-button control is on; Exit
 * (notification) or switching the setting off ends the service.
 *
 * All state below is touched on the main thread only.
 */
class NavigationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Restarting the session in another mode (volume up x3 while running without camera). */
    private var switching: Job? = null
    private var settingsJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var volumeKeys: VolumeKeySession? = null
    private var volumeKeysError: String? = null
    private var volumeControl = true
    private var standby = false

    /** The running session's source (NONE = landmarks only); null while not navigating. */
    private var current: SourceKind? = null

    /** The foreground-service types in force; null while not in the foreground. */
    private var foregroundTypes: Int? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val navigating: Boolean get() = job?.isActive == true || switching?.isActive == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        val pipeline = Pipeline.get(this)
        volumeControl = pipeline.controlSettings.value?.volumeButtonControl ?: true
        settingsJob = scope.launch {
            pipeline.controlSettings.filterNotNull().map { it.volumeButtonControl }.distinctUntilChanged()
                .collect { on -> withContext(Dispatchers.Main) { onVolumeControlSetting(on) } }
        }
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handle(intent)
        if (foregroundTypes == null && (intent?.action == ACTION_START || intent?.action == ACTION_STANDBY)) {
            // Sent with startForegroundService(): Android requires startForeground() even when we
            // decided not to run (e.g. the setting was just switched off), or it crashes the app.
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            if (startForegroundAs(base, standbyNotification())) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foregroundTypes = null
            }
            stopSelf()
        }
        return if (foregroundTypes == null) START_NOT_STICKY else START_STICKY
    }

    /** One command, from an intent or a direct call (main thread). Never throws. */
    private fun handle(intent: Intent?) {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopNavigation(intent.getStringExtra(EXTRA_TRIGGER))
                ACTION_EXIT -> exit()
                ACTION_STANDBY -> enterStandby()
                ACTION_START -> startNavigation(StartRequest.from(intent))
                // A null intent means the system restarted us after killing the process.
                else -> restore()
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "navigation service command ${intent?.action} failed", e)
            if (!navigating && !standby) shutDown()
        }
    }

    // ------------------------------------------------------------------ navigating

    private fun startNavigation(req: StartRequest) {
        if (navigating) {
            if (current == req.source && switching?.isActive != true) {
                Log.i(TAG, "already navigating with ${req.source}")
            } else {
                switchTo(req)
            }
            return
        }
        launchSession(req)
    }

    private fun launchSession(req: StartRequest) {
        val pipeline = Pipeline.get(this)
        val wantsCamera = req.source == SourceKind.PHONE_CAMERA
        val fg = ensureForeground(navigatingNotification(), camera = wantsCamera)
        if (fg == Fg.FAILED) {
            Log.e(TAG, "could not enter the foreground - navigation not started")
            pipeline.speaker.say(Phrases.START_FAILED, Priority.INFO, 0)
            if (!standby) shutDown()
            return
        }
        var r = req
        if (wantsCamera && fg != Fg.CAMERA) {
            // Android refused the camera service type (API 34+, started from the background or the
            // lock screen): landmarks only, and say so (CLAUDE.md §8.2).
            if (pipeline.landmarkSettings.value?.enabled == false) {
                pipeline.speaker.say(Phrases.CAMERA_UNAVAILABLE_NOTHING, Priority.INFO, 0)
                if (standby || volumeControl) enterStandby() else shutDown()
                return
            }
            val ble = LandmarkScanner.hasScanPermission(this)
            r = req.copy(
                source = SourceKind.NONE,
                cameraFallback = true,
                announce = Phrases.CAMERA_UNAVAILABLE + if (ble) "" else " " + Phrases.LANDMARKS_NOT_ALLOWED,
            )
        }
        setStandby(false)
        current = r.source
        persist(MODE_NAVIGATING, r.source)
        acquireLocks(wifi = r.source != SourceKind.NONE)
        val session = SessionStart(
            landmarksOnly = r.source == SourceKind.NONE,
            cameraFallback = r.cameraFallback,
            cameraSource = r.source.takeIf { it.hasCamera },
            announce = r.announce,
            trigger = r.trigger,
            verifyCamera = r.source == SourceKind.PHONE_CAMERA && !pipeline.appInFront.value,
        )
        job = scope.launch {
            try {
                pipeline.run(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // FR-NAV-05: never go silent. The pipeline contains its own failures; this is the last net.
                Log.e(TAG, "pipeline failed - stopping navigation", e)
                pipeline.speaker.say(Phrases.FAIL_SAFE, Priority.STOP, 0)
                val self = coroutineContext[Job]
                withContext(Dispatchers.Main) { if (job === self) stopNavigation(null) }
            }
        }
        updateVolumeKeys()
        Log.i(TAG, "navigation started (source ${r.source}, fallback ${r.cameraFallback}, by ${r.trigger ?: "screen"})")
    }

    /** Restart in [req]'s mode. A refused camera keeps the running session (and says so). */
    private fun switchTo(req: StartRequest) {
        val pipeline = Pipeline.get(this)
        if (req.source == SourceKind.PHONE_CAMERA && ensureForeground(navigatingNotification(), camera = true) != Fg.CAMERA) {
            Log.w(TAG, "camera service type refused - staying landmarks only")
            pipeline.speaker.say(Phrases.CAMERA_UNAVAILABLE, Priority.INFO, 0)
            req.trigger?.let { pipeline.recordControl(Phrases.CAMERA_UNAVAILABLE, "$it: camera refused by Android") }
            return
        }
        val old = job
        job = null
        switching?.cancel()
        switching = scope.launch(Dispatchers.Main) {
            old?.cancelAndJoin()
            switching = null
            launchSession(req)
        }
        Log.i(TAG, "switching navigation to ${req.source}")
    }

    private fun stopNavigation(trigger: String?) {
        val pipeline = Pipeline.get(this)
        if (navigating) {
            val phrase = Phrases.navStopped(volumeControl)
            pipeline.speaker.say(phrase, Priority.INFO, 0) // FR-APP-02
            trigger?.let { pipeline.recordControl(phrase, "$it: stop") }
        }
        endSession()
        Log.i(TAG, "navigation stopped${trigger?.let { " by $it" } ?: ""}")
        if (volumeControl) enterStandby() else shutDown()
    }

    private fun endSession() {
        switching?.cancel()
        switching = null
        job?.cancel()
        job = null
        current = null
        releaseLocks()
    }

    /** System restart after the process was killed: resume what was running (CLAUDE.md §8.2). */
    private fun restore() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        when (prefs.getString(KEY_MODE, null)) {
            MODE_NAVIGATING -> {
                val source = SourceKind.fromStored(prefs.getString(KEY_SOURCE, null), SourceKind.NONE)
                Log.i(TAG, "restarted by the system - resuming navigation ($source)")
                startNavigation(StartRequest(source, cameraFallback = false, announce = null, trigger = null))
            }
            MODE_STANDBY -> enterStandby()
            else -> shutDown()
        }
    }

    // ------------------------------------------------------------------ standby

    private fun enterStandby() {
        if (navigating) return
        if (!volumeControl) {
            shutDown()
            return
        }
        if (ensureForeground(standbyNotification(), camera = false) == Fg.FAILED) {
            Log.e(TAG, "standby refused by Android")
            shutDown()
            return
        }
        setStandby(true)
        persist(MODE_STANDBY, null)
        updateVolumeKeys()
        // Also warms up text-to-speech, so the first reply from the pocket is not late.
        Log.i(TAG, "standby: volume up twice starts navigation (speech ready: ${Pipeline.get(this).speaker.state.value.ttsReady})")
    }

    private fun exit() {
        val pipeline = Pipeline.get(this)
        if (navigating) pipeline.speaker.say(Phrases.NAV_STOPPED, Priority.INFO, 0)
        endSession()
        Log.i(TAG, "exit from the notification")
        shutDown()
    }

    /** Ends everything: no session, no standby, no notification. */
    private fun shutDown() {
        endSession()
        setStandby(false)
        persist(null, null)
        releaseVolumeKeys()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundTypes = null
        stopSelf()
    }

    private fun setStandby(on: Boolean) {
        standby = on
        Pipeline.get(this).standby.value = on
    }

    private fun onVolumeControlSetting(on: Boolean) {
        volumeControl = on
        if (!on && standby && !navigating) shutDown() else updateVolumeKeys()
    }

    override fun onDestroy() {
        instance = null
        job?.cancel()
        switching?.cancel()
        settingsJob?.cancel()
        scope.cancel()
        releaseVolumeKeys()
        unregisterScreenReceiver()
        releaseLocks()
        setStandby(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ volume keys

    /**
     * FR-APP-06: the MediaSession takes the volume keys while navigating, and in standby while the
     * screen is off or locked. A failure only loses the gestures, never guidance.
     */
    private fun updateVolumeKeys() {
        val pipeline = Pipeline.get(this)
        val want = volumeControl && (navigating || (standby && screenOffOrLocked()))
        if (want && volumeKeys == null) {
            volumeKeys = try {
                VolumeKeySession(this) { g -> GestureControl.handle(this, g) }.also { volumeKeysError = null }
            } catch (e: RuntimeException) {
                Log.e(TAG, "volume-key session failed; use the screen or notification", e)
                volumeKeysError = "failed: ${e.message}"
                null
            }
        }
        val active = try {
            volumeKeys?.let { it.active = want }
            want && volumeKeys != null
        } catch (e: RuntimeException) {
            Log.e(TAG, "volume-key session could not change state", e)
            false
        }
        if (!volumeControl) releaseVolumeKeys()
        pipeline.volumeKeySessionActive.value = active
        pipeline.volumeKeys.value = when {
            !volumeControl -> Pipeline.VOLUME_KEYS_OFF
            want && !active -> volumeKeysError ?: "failed"
            navigating -> "navigating: up x3 camera, down x2 stop, down x3 status"
            active -> "standby (screen off/locked): up x2 / up x3 start"
            standby -> "standby, screen on: CapSight's screen watches the keys, other apps unaffected"
            else -> "app screen only"
        }
    }

    private fun releaseVolumeKeys() {
        try {
            volumeKeys?.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "volume-key session release failed", e)
        }
        volumeKeys = null
        Pipeline.get(this).volumeKeySessionActive.value = false
    }

    private fun screenOffOrLocked(): Boolean = try {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val interactive = power.isInteractive
        !interactive || keyguard.isKeyguardLocked
    } catch (e: RuntimeException) {
        true
    }

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    updateVolumeKeys()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "screen change handling failed", e)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
        } catch (e: RuntimeException) {
            Log.w(TAG, "no screen on/off updates; volume keys follow the start state only", e)
        }
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: RuntimeException) {
                Log.w(TAG, "screen receiver unregister failed", e)
            }
        }
        screenReceiver = null
    }

    // ------------------------------------------------------------------ foreground

    private enum class Fg { CAMERA, NO_CAMERA, FAILED }

    /**
     * Puts the service in the foreground with [notification] and the right types: connectedDevice,
     * plus camera when [camera] (phone camera) and permitted. Already in the foreground with the
     * same types: only the notification changes. [Fg.CAMERA] = the phone camera may run as far as
     * the service type goes; [Fg.NO_CAMERA] = foreground without the camera type (or not asked);
     * [Fg.FAILED] = not in the foreground at all.
     *
     * Calling startForeground again from the background can be refused on API 31+; a service that is
     * already in the foreground then simply stays there with its old types.
     */
    private fun ensureForeground(notification: Notification, camera: Boolean): Fg {
        createChannel(this)
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val permitted = camera && hasCameraPermission()
        val needsType = permitted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val wanted = if (needsType) base or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else base
        val ok = if (permitted) Fg.CAMERA else Fg.NO_CAMERA
        if (foregroundTypes == wanted) {
            notify(notification)
            return ok
        }
        if (startForegroundAs(wanted, notification)) return ok
        if (wanted != base && foregroundTypes == null && startForegroundAs(base, notification)) return Fg.NO_CAMERA
        if (foregroundTypes != null) {
            // Still in the foreground with the old types; keep them.
            notify(notification)
            val types = foregroundTypes ?: 0
            val stillCamera = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0
            return if (permitted && (stillCamera || !needsType)) Fg.CAMERA else Fg.NO_CAMERA
        }
        return Fg.FAILED
    }

    private fun startForegroundAs(types: Int, notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
        foregroundTypes = types
        true
    } catch (e: RuntimeException) {
        // e.g. camera type, or any type, requested from the background (API 31+ / 34+).
        Log.w(TAG, "startForeground with types $types refused", e)
        false
    }

    @SuppressLint("MissingPermission") // checked just before
    private fun notify(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            Log.w(TAG, "notification update failed", e)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Read by TalkBack from the shade: what is on, and how to stop it (volume keys only while that control is on). */
    private fun navigatingNotification() = baseNotification(
        R.string.notification_title,
        if (volumeControl) R.string.notification_text else R.string.notification_text_no_keys,
    )
        .addAction(0, getString(R.string.notification_stop), commandIntent(ACTION_STOP, REQUEST_STOP))
        .addAction(0, getString(R.string.notification_exit), commandIntent(ACTION_EXIT, REQUEST_EXIT))
        .build()

    private fun standbyNotification() = baseNotification(R.string.notification_standby_title, R.string.notification_standby_text)
        .addAction(0, getString(R.string.notification_exit), commandIntent(ACTION_EXIT, REQUEST_EXIT))
        .build()

    private fun baseNotification(title: Int, text: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_capsight)
        .setContentTitle(getString(title))
        .setContentText(getString(text))
        // The whole sentence when expanded, not cut after one line.
        .setStyle(NotificationCompat.BigTextStyle().bigText(getString(text)))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

    private fun commandIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, NavigationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("WakelockTimeout") // released in endSession/onDestroy; guidance has no fixed length
    private fun acquireLocks(wifi: Boolean) {
        releaseLocks()
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CapSight:guidance").apply {
            setReferenceCounted(false)
            acquire()
        }
        if (!wifi) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "CapSight:camera").apply {
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

    /** What survives a process kill, so a system restart resumes the right state ([restore]). */
    private fun persist(mode: String?, source: SourceKind?) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                putString(KEY_MODE, mode)
                putString(KEY_SOURCE, source?.storedName)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "service state not saved", e)
        }
    }

    /** One start command: the session's effective source and how it is announced. */
    private data class StartRequest(
        val source: SourceKind,
        val cameraFallback: Boolean,
        val announce: String?,
        val trigger: String?,
    ) {
        companion object {
            fun from(intent: Intent) = StartRequest(
                source = SourceKind.fromStored(intent.getStringExtra(EXTRA_SOURCE), SourceKind.NONE),
                cameraFallback = intent.getBooleanExtra(EXTRA_CAMERA_FALLBACK, false),
                announce = intent.getStringExtra(EXTRA_ANNOUNCE),
                trigger = intent.getStringExtra(EXTRA_TRIGGER),
            )
        }
    }

    companion object {
        private const val ACTION_START = "com.example.capsight.action.START"
        private const val ACTION_STOP = "com.example.capsight.action.STOP"
        private const val ACTION_STANDBY = "com.example.capsight.action.STANDBY"
        private const val ACTION_EXIT = "com.example.capsight.action.EXIT"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_CAMERA_FALLBACK = "camera_fallback"
        private const val EXTRA_ANNOUNCE = "announce"
        private const val EXTRA_TRIGGER = "trigger"
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_STOP = 1
        private const val REQUEST_EXIT = 2
        private const val PREFS = "navigation_service"
        private const val KEY_MODE = "mode"
        private const val KEY_SOURCE = "source"
        private const val MODE_NAVIGATING = "navigating"
        private const val MODE_STANDBY = "standby"

        /** The live service, for direct calls on the main thread (no IPC, no background-start rules). */
        private var instance: WeakReference<NavigationService>? = null

        /**
         * Starts guidance as [plan] says (which must be able to start). The phone camera adds the
         * camera service type; landmarks only forces the no-camera mode for this session. Running in
         * another mode: restarts in this one. [announce] replaces the default start phrase; [trigger]
         * names the volume-key gesture for the decision log. False if Android refused.
         */
        fun start(context: Context, plan: StartPlan, announce: String? = null, trigger: String? = null): Boolean {
            val source = requireNotNull(plan.source) { "nothing to guide with" }
            val intent = Intent(context, NavigationService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SOURCE, source.storedName)
                .putExtra(EXTRA_CAMERA_FALLBACK, plan.cameraFallback)
                .putExtra(EXTRA_ANNOUNCE, announce)
                .putExtra(EXTRA_TRIGGER, trigger)
            return send(context, intent, startIfNotRunning = true)
        }

        /** Stops guidance (back to standby while volume-button control is on). */
        fun stop(context: Context, trigger: String? = null): Boolean =
            send(context, Intent(context, NavigationService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_TRIGGER, trigger), startIfNotRunning = false)

        /** Volume-key standby (CLAUDE.md §8.2). Call while CapSight is on screen. */
        fun standby(context: Context): Boolean =
            send(context, Intent(context, NavigationService::class.java).setAction(ACTION_STANDBY), startIfNotRunning = true)

        private fun send(context: Context, intent: Intent, startIfNotRunning: Boolean): Boolean {
            val svc = instance?.get()
            if (svc != null && Looper.myLooper() == Looper.getMainLooper()) {
                svc.handle(intent)
                return true
            }
            if (svc == null && !startIfNotRunning) return true // nothing running, nothing to stop
            return try {
                if (svc == null) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
                true
            } catch (e: RuntimeException) {
                // ForegroundServiceStartNotAllowedException / IllegalStateException from the background.
                Log.e(TAG, "navigation service command ${intent.action} refused", e)
                false
            }
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
