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
import com.example.capsight.camera.CameraContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Where frames come from (CLAUDE.md §1A). Stays in the debug settings in the final app. */
enum class SourceKind { ESP32, PHONE_CAMERA, VIDEO_FILE }

/** Camera settings with their defaults (FR-APP-04). More settings join in step 7. */
data class CameraSettings(
    val source: SourceKind = SourceKind.ESP32,
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
    /** Camera height is about this minus 10 cm (ground-plane distance, later). */
    val userHeightCm: Int = 165,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(context: Context) {
    private val store = context.applicationContext.dataStore

    val camera: Flow<CameraSettings> = store.data.map { p ->
        val defaults = CameraSettings()
        CameraSettings(
            source = p[SOURCE]?.let { name -> SourceKind.entries.firstOrNull { it.name == name } }
                ?: defaults.source,
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
        )
    }

    /** NFR-SAFE-01: the user has seen the safety statement on first run. */
    val safetyAccepted: Flow<Boolean> = store.data.map { it[SAFETY_ACCEPTED] ?: false }

    suspend fun acceptSafety() = store.edit { it[SAFETY_ACCEPTED] = true }

    /** Saves a validated Settings-screen draft (FR-APP-04). Returns the problems if it is invalid. */
    suspend fun saveHelperSettings(d: HelperSettingsDraft): Map<String, String> {
        val errors = d.validate()
        if (errors.isNotEmpty()) return errors
        fun num(t: String) = t.trim().replace(',', '.').toDouble()
        store.edit {
            it[BASE_URL] = d.cameraBaseUrl.trim()
            it[CAP_FOV_DEG] = num(d.capFovDeg).toFloat()
            it[USER_HEIGHT_CM] = num(d.userHeightCm).toInt()
            it[STOP_DISTANCE_M] = num(d.stopDistanceM).toFloat()
            it[WARN_DISTANCE_M] = num(d.warnDistanceM).toFloat()
            it[PHRASE_COOLDOWN_MS] = (num(d.phraseCooldownS) * MS_PER_S).toLong()
            it[CLEAR_REPEAT_MS] = (num(d.clearRepeatS) * MS_PER_S).toLong()
            it[SPEECH_RATE] = d.speechRate
            it[SPEECH_VOLUME] = d.speechVolume.coerceIn(0f, 1f)
        }
        return emptyMap()
    }

    suspend fun setSpeechRate(rate: Float) = store.edit { it[SPEECH_RATE] = rate }

    suspend fun setSpeakGuidance(on: Boolean) = store.edit { it[SPEAK_GUIDANCE] = on }

    suspend fun setUseGpu(use: Boolean) = store.edit { it[USE_GPU] = use }

    suspend fun setShowAllClasses(show: Boolean) = store.edit { it[SHOW_ALL_CLASSES] = show }

    suspend fun setSource(kind: SourceKind) = store.edit { it[SOURCE] = kind.name }

    suspend fun setCameraBaseUrl(url: String) = store.edit { it[BASE_URL] = url.trim() }

    suspend fun setBindToWifi(bind: Boolean) = store.edit { it[BIND_WIFI] = bind }

    suspend fun setVideoUri(uri: String) = store.edit { it[VIDEO_URI] = uri }

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
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val PHRASE_COOLDOWN_MS = longPreferencesKey("speech_phrase_cooldown_ms")
        val STOP_COOLDOWN_MS = longPreferencesKey("speech_stop_cooldown_ms")
        val CLEAR_REPEAT_MS = longPreferencesKey("speech_clear_repeat_ms")
        val STAIRS_COOLDOWN_MS = longPreferencesKey("speech_stairs_cooldown_ms")
        val SPEAK_GUIDANCE = booleanPreferencesKey("speech_speak_guidance")
    }
}
