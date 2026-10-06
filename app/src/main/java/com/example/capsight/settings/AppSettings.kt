package com.example.capsight.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    /** "Path clear." at most this often. */
    val clearRepeatMs: Long = 6_000L,
    /** Debug: announce camera link changes (moves into the guidance loop in step 6). */
    val announceCameraLink: Boolean = true,
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
            clearRepeatMs = p[CLEAR_REPEAT_MS] ?: defaults.clearRepeatMs,
            announceCameraLink = p[ANNOUNCE_LINK] ?: defaults.announceCameraLink,
        )
    }

    suspend fun setSpeechRate(rate: Float) = store.edit { it[SPEECH_RATE] = rate }

    suspend fun setAnnounceCameraLink(on: Boolean) = store.edit { it[ANNOUNCE_LINK] = on }

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
        val SPEECH_VOLUME = floatPreferencesKey("speech_volume")
        val PHRASE_COOLDOWN_MS = longPreferencesKey("speech_phrase_cooldown_ms")
        val STOP_COOLDOWN_MS = longPreferencesKey("speech_stop_cooldown_ms")
        val CLEAR_REPEAT_MS = longPreferencesKey("speech_clear_repeat_ms")
        val ANNOUNCE_LINK = booleanPreferencesKey("speech_announce_camera_link")
    }
}
