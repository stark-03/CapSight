package com.example.capsight.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.capsight.ble.LandmarkConfig
import com.example.capsight.camera.CameraContract
import com.example.capsight.depth.DepthParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** Camera settings with their defaults (FR-APP-04). More settings join in step 7. */
data class CameraSettings(
    // Phone camera by default (decided 22 Oct 2026): the phone sees, the ESP32 is a BLE landmark beacon.
    val source: SourceKind = SourceKind.PHONE_CAMERA,
    /** Real cap: leave at the default. Fake camera: the laptop's address. */
    val cameraBaseUrl: String = CameraContract.DEFAULT_BASE_URL,
    /** Off only when the phone is the hotspot and has no Wi-Fi client network. */
    val bindToWifi: Boolean = true,
    /** content:// URI picked with the system file picker, or null. */
    val videoUri: String? = null,
    /** FR-RX-03: "Camera disconnected" after this long without a frame. */
    val cameraLostMs: Long = DEFAULT_CAMERA_LOST_MS,
) {
    companion object {
        const val DEFAULT_CAMERA_LOST_MS = 2_000L
    }
}

/** Detection settings (FR-DET-03). Defaults from CLAUDE.md §8. */
data class DetectionSettings(
    val minConfidence: Float = DEFAULT_MIN_CONFIDENCE,
    /** Try the GPU delegate first; CPU is the fallback either way (§6.4). */
    val useGpu: Boolean = true,
    /** Debug only: draw all 80 COCO classes, not just person + obstacles (§7.3). */
    val showAllClasses: Boolean = false,
) {
    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.40f
    }
}

/** Speech settings (FR-VOICE-05, FR-NAV-04). Defaults from CLAUDE.md §8. */
data class SpeechSettings(
    /** 1.0 = the engine's normal rate. */
    val rate: Float = 1.0f,
    /** 0..1, relative to the phone's media volume. */
    val volume: Float = 1.0f,
    /** Same phrase is not repeated within this. */
    val phraseCooldownMs: Long = 3_000L,
    /** P0 ("Stop. ...") repeats sooner. */
    val stopCooldownMs: Long = 2_000L,
    /** "Caution. Stairs ahead." repeat (guidance.py used 4 s). */
    val stairsCooldownMs: Long = 4_000L,
    /** "Path clear." at most this often. */
    val clearRepeatMs: Long = 6_000L,
    /** Speak the guidance loop's decisions (off = decide and show only). */
    val speakGuidance: Boolean = true,
)

/** Navigation thresholds (FR-NAV-03, FR-DIST-05). Defaults from CLAUDE.md §8. */
data class NavigationSettings(
    val stopDistanceM: Double = 1.2,
    val warnDistanceM: Double = 2.5,
    val stairsWarnM: Double = 4.0,
    /** BODY_HALF_WIDTH_M: shoulder width plus clearance. */
    val corridorHalfWidthM: Double = 0.55,
    /** Skip keyboard, book, cup, laptop (FR-OBS-03). */
    val skipUnreliable: Boolean = true,
    /** Ticks a vanished obstacle is still reported (0.6 s at 10 Hz). */
    val holdTicks: Int = 6,
    /** FR-PATH-04: keep a chosen steering side at least this long... */
    val sideHoldMs: Long = 2_000L,
    /** ...unless the other side is clearer by more than this. */
    val sideSwitchMarginM: Double = 0.5,
    /** Cap camera horizontal FOV. 54 until measured (§8.1) - it scales every distance. */
    val capFovDeg: Double = 54.0,
    /** Used for recorded videos, and for the phone camera if it cannot report its own FOV. */
    val phoneFovDeg: Double = 65.0,
    /** FR-NAV-02: fixed decision rate, independent of detection speed. */
    val decisionRateHz: Int = 10,
    /** The user's height (the cap camera sits about 10 cm below it). */
    val userHeightCm: Int = 165,
    /**
     * FR-DIST-02: camera lens height above the floor for ground-plane distance. 1.30 m = phone
     * worn at chest; for the cap camera it is the user's height minus about 0.1 m.
     */
    val cameraHeightM: Double = 1.30,
    /** FR-OBS-04 / FR-PATH-03: monocular depth for obstacles YOLO cannot name (walls, pillars, boxes). */
    val depthEnabled: Boolean = true,
    /** Zone score counted as "near" (1.0 = as near as the floor just ahead). Valid 0.6-1.5. */
    val depthThreshold: Double = DepthParams.DEFAULT_THRESHOLD,
)

/** BLE landmark beacons (CLAUDE.md §4.6). Defaults from §4.6. */
data class LandmarkSettings(
    val enabled: Boolean = true,
    /** Announce when the smoothed RSSI reaches this (about 2-4 m). */
    val announceDbm: Int = -70,
    /** Re-arm only after it falls below this. */
    val rearmDbm: Int = -80,
    /** Never the same beacon twice within this. */
    val repeatMs: Long = 60_000L,
    /** Say the rounded distance and speak approach updates / arrival ("Room 304 door, about 2 metres."). */
    val sayDistance: Boolean = true,
    /** Smoothed RSSI one metre from a beacon (calibrate in the debug view). Valid -90..-20. */
    val rssiAt1mDbm: Int = -52,
    /** Path-loss exponent: 2 = open space, 3+ = walls / people. Valid 1.5..4.0. */
    val pathLossExponent: Double = 2.5,
) {
    val config: LandmarkConfig
        get() = LandmarkConfig(
            announceDbm = announceDbm,
            rearmDbm = rearmDbm,
            repeatMs = repeatMs,
            sayDistance = sayDistance,
            rssiAt1mDbm = rssiAt1mDbm,
            pathLossExponent = pathLossExponent,
        )
}

/** Hardware-key control (FR-APP-06). */
data class ControlSettings(
    /**
     * Volume-key gestures (CLAUDE.md §8.2): up x2 start without camera, up x3 with camera, down x2 stop,
     * down x3 status - also from standby with the screen off. Single presses still change the volume.
     */
    val volumeButtonControl: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(context: Context) {
    private val store = context.applicationContext.dataStore

    val camera: Flow<CameraSettings> = store.data.map { p ->
        val defaults = CameraSettings()
        CameraSettings(
            source = SourceKind.fromStored(p[SOURCE], defaults.source),
            cameraBaseUrl = p[BASE_URL] ?: defaults.cameraBaseUrl,
            bindToWifi = p[BIND_WIFI] ?: defaults.bindToWifi,
            videoUri = p[VIDEO_URI],
            cameraLostMs = p[CAMERA_LOST_MS] ?: defaults.cameraLostMs,
        )
    }

    val detection: Flow<DetectionSettings> = store.data.map { p ->
        val defaults = DetectionSettings()
        DetectionSettings(
            minConfidence = p[MIN_CONFIDENCE] ?: defaults.minConfidence,
            useGpu = p[USE_GPU] ?: defaults.useGpu,
            showAllClasses = p[SHOW_ALL_CLASSES] ?: defaults.showAllClasses,
        )
    }

    val speech: Flow<SpeechSettings> = store.data.map { p ->
        val defaults = SpeechSettings()
        SpeechSettings(
            rate = p[SPEECH_RATE] ?: defaults.rate,
            volume = p[SPEECH_VOLUME] ?: defaults.volume,
            phraseCooldownMs = p[PHRASE_COOLDOWN_MS] ?: defaults.phraseCooldownMs,
            stopCooldownMs = p[STOP_COOLDOWN_MS] ?: defaults.stopCooldownMs,
            stairsCooldownMs = p[STAIRS_COOLDOWN_MS] ?: defaults.stairsCooldownMs,
            clearRepeatMs = p[CLEAR_REPEAT_MS] ?: defaults.clearRepeatMs,
            speakGuidance = p[SPEAK_GUIDANCE] ?: defaults.speakGuidance,
        )
    }

    val navigation: Flow<NavigationSettings> = store.data.map { p ->
        val d = NavigationSettings()
        NavigationSettings(
            stopDistanceM = p[STOP_DISTANCE_M]?.toDouble() ?: d.stopDistanceM,
            warnDistanceM = p[WARN_DISTANCE_M]?.toDouble() ?: d.warnDistanceM,
            stairsWarnM = p[STAIRS_WARN_M]?.toDouble() ?: d.stairsWarnM,
            corridorHalfWidthM = p[CORRIDOR_HALF_WIDTH_M]?.toDouble() ?: d.corridorHalfWidthM,
            skipUnreliable = p[SKIP_UNRELIABLE] ?: d.skipUnreliable,
            holdTicks = p[HOLD_TICKS] ?: d.holdTicks,
            sideHoldMs = p[SIDE_HOLD_MS] ?: d.sideHoldMs,
            sideSwitchMarginM = p[SIDE_SWITCH_MARGIN_M]?.toDouble() ?: d.sideSwitchMarginM,
            capFovDeg = p[CAP_FOV_DEG]?.toDouble() ?: d.capFovDeg,
            phoneFovDeg = p[PHONE_FOV_DEG]?.toDouble() ?: d.phoneFovDeg,
            userHeightCm = p[USER_HEIGHT_CM] ?: d.userHeightCm,
            cameraHeightM = p[CAMERA_HEIGHT_M]?.toDouble() ?: d.cameraHeightM,
            depthEnabled = p[DEPTH_ENABLED] ?: d.depthEnabled,
            depthThreshold = p[DEPTH_THRESHOLD]?.toDouble() ?: d.depthThreshold,
        )
    }

    val landmarks: Flow<LandmarkSettings> = store.data.map { p ->
        val d = LandmarkSettings()
        LandmarkSettings(
            enabled = p[LANDMARKS_ENABLED] ?: d.enabled,
            announceDbm = p[LANDMARK_ANNOUNCE_DBM] ?: d.announceDbm,
            rearmDbm = p[LANDMARK_REARM_DBM] ?: d.rearmDbm,
            repeatMs = p[LANDMARK_REPEAT_MS] ?: d.repeatMs,
            sayDistance = p[LANDMARK_SAY_DISTANCE] ?: d.sayDistance,
            rssiAt1mDbm = p[LANDMARK_RSSI_1M_DBM] ?: d.rssiAt1mDbm,
            pathLossExponent = p[LANDMARK_PATH_LOSS]?.toDouble() ?: d.pathLossExponent,
        )
    }

    val controls: Flow<ControlSettings> = store.data.map { p ->
        val d = ControlSettings()
        ControlSettings(volumeButtonControl = p[VOLUME_BUTTON_CONTROL] ?: d.volumeButtonControl)
    }

    /** NFR-SAFE-01: the user has seen the safety statement on first run. */
    val safetyAccepted: Flow<Boolean> = store.data.map { it[SAFETY_ACCEPTED] ?: false }

    suspend fun acceptSafety() = store.edit { it[SAFETY_ACCEPTED] = true }

    /** The spoken welcome (volume gestures, camera optional) was shown once after the safety statement. */
    val welcomeShown: Flow<Boolean> = store.data.map { it[WELCOME_SHOWN] ?: false }

    suspend fun markWelcomeShown() = store.edit { it[WELCOME_SHOWN] = true }

    /** Saves a validated Settings-screen draft (FR-APP-04). Returns the problems if it is invalid. */
    suspend fun saveHelperSettings(d: HelperSettingsDraft): Map<String, String> {
        val errors = d.validate()
        if (errors.isNotEmpty()) return errors
        fun num(t: String) = t.trim().replace(',', '.').toDouble()
        store.edit {
            it[BASE_URL] = d.cameraBaseUrl.trim()
            it[CAP_FOV_DEG] = num(d.capFovDeg).toFloat()
            it[USER_HEIGHT_CM] = num(d.userHeightCm).toInt()
            it[CAMERA_HEIGHT_M] = num(d.cameraHeightM).toFloat()
            it[STOP_DISTANCE_M] = num(d.stopDistanceM).toFloat()
            it[WARN_DISTANCE_M] = num(d.warnDistanceM).toFloat()
            it[PHRASE_COOLDOWN_MS] = (num(d.phraseCooldownS) * MS_PER_S).toLong()
            it[CLEAR_REPEAT_MS] = (num(d.clearRepeatS) * MS_PER_S).toLong()
            it[SPEECH_RATE] = d.speechRate
            it[SPEECH_VOLUME] = d.speechVolume.coerceIn(0f, 1f)
            it[LANDMARKS_ENABLED] = d.landmarksEnabled
            it[LANDMARK_ANNOUNCE_DBM] = num(d.landmarkAnnounceDbm).toInt()
            it[LANDMARK_REARM_DBM] = num(d.landmarkRearmDbm).toInt()
            it[LANDMARK_REPEAT_MS] = (num(d.landmarkRepeatS) * MS_PER_S).toLong()
            it[LANDMARK_SAY_DISTANCE] = d.landmarkSayDistance
            it[LANDMARK_RSSI_1M_DBM] = num(d.landmarkRssiAt1mDbm).roundToInt()
            it[LANDMARK_PATH_LOSS] = num(d.landmarkPathLoss).toFloat()
            it[VOLUME_BUTTON_CONTROL] = d.volumeButtonControl
            it[DEPTH_ENABLED] = d.depthEnabled
            it[DEPTH_THRESHOLD] = num(d.depthThreshold).toFloat()
        }
        return emptyMap()
    }

    suspend fun setSpeechRate(rate: Float) = store.edit { it[SPEECH_RATE] = rate }

    suspend fun setSpeakGuidance(on: Boolean) = store.edit { it[SPEAK_GUIDANCE] = on }

    suspend fun setUseGpu(use: Boolean) = store.edit { it[USE_GPU] = use }

    suspend fun setShowAllClasses(show: Boolean) = store.edit { it[SHOW_ALL_CLASSES] = show }

    suspend fun setSource(kind: SourceKind) = store.edit { it[SOURCE] = kind.storedName }

    suspend fun setCameraBaseUrl(url: String) = store.edit { it[BASE_URL] = url.trim() }

    suspend fun setBindToWifi(bind: Boolean) = store.edit { it[BIND_WIFI] = bind }

    suspend fun setVideoUri(uri: String) = store.edit { it[VIDEO_URI] = uri }

    /** "Calibrate at 1 m" in the debug view: the beacon signal at 1 m, already validated. */
    suspend fun setLandmarkRssiAt1m(dbm: Int) = store.edit { it[LANDMARK_RSSI_1M_DBM] = dbm }

    private companion object {
        val SOURCE = stringPreferencesKey("camera_source")
        val BASE_URL = stringPreferencesKey("camera_base_url")
        val BIND_WIFI = booleanPreferencesKey("camera_bind_wifi")
        val VIDEO_URI = stringPreferencesKey("camera_video_uri")
        val CAMERA_LOST_MS = longPreferencesKey("camera_lost_ms")
        val MIN_CONFIDENCE = floatPreferencesKey("det_min_confidence")
        val USE_GPU = booleanPreferencesKey("det_use_gpu")
        val SHOW_ALL_CLASSES = booleanPreferencesKey("det_show_all_classes")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SAFETY_ACCEPTED = booleanPreferencesKey("safety_accepted")
        val WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")
        const val MS_PER_S = 1_000.0
        val STOP_DISTANCE_M = floatPreferencesKey("nav_stop_distance_m")
        val WARN_DISTANCE_M = floatPreferencesKey("nav_warn_distance_m")
        val STAIRS_WARN_M = floatPreferencesKey("nav_stairs_warn_m")
        val CORRIDOR_HALF_WIDTH_M = floatPreferencesKey("nav_corridor_half_width_m")
        val SKIP_UNRELIABLE = booleanPreferencesKey("nav_skip_unreliable")
        val HOLD_TICKS = intPreferencesKey("nav_hold_ticks")
        val SIDE_HOLD_MS = longPreferencesKey("nav_side_hold_ms")
        val SIDE_SWITCH_MARGIN_M = floatPreferencesKey("nav_side_switch_margin_m")
        val CAP_FOV_DEG = floatPreferencesKey("nav_cap_fov_deg")
        val PHONE_FOV_DEG = floatPreferencesKey("nav_phone_fov_deg")
        val USER_HEIGHT_CM = intPreferencesKey("nav_user_height_cm")
        val CAMERA_HEIGHT_M = floatPreferencesKey("nav_camera_height_m")
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val PHRASE_COOLDOWN_MS = longPreferencesKey("speech_phrase_cooldown_ms")
        val STOP_COOLDOWN_MS = longPreferencesKey("speech_stop_cooldown_ms")
        val CLEAR_REPEAT_MS = longPreferencesKey("speech_clear_repeat_ms")
        val STAIRS_COOLDOWN_MS = longPreferencesKey("speech_stairs_cooldown_ms")
        val SPEAK_GUIDANCE = booleanPreferencesKey("speech_speak_guidance")
        val LANDMARKS_ENABLED = booleanPreferencesKey("ble_landmarks_enabled")
        val LANDMARK_ANNOUNCE_DBM = intPreferencesKey("ble_announce_dbm")
        val LANDMARK_REARM_DBM = intPreferencesKey("ble_rearm_dbm")
        val LANDMARK_REPEAT_MS = longPreferencesKey("ble_repeat_ms")
        val LANDMARK_SAY_DISTANCE = booleanPreferencesKey("ble_say_distance")
        val LANDMARK_RSSI_1M_DBM = intPreferencesKey("ble_rssi_1m_dbm")
        val LANDMARK_PATH_LOSS = floatPreferencesKey("ble_path_loss_exponent")
        val VOLUME_BUTTON_CONTROL = booleanPreferencesKey("control_volume_button")
        val DEPTH_ENABLED = booleanPreferencesKey("nav_depth_enabled")
        val DEPTH_THRESHOLD = floatPreferencesKey("nav_depth_threshold")
    }
}
