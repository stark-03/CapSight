package com.example.capsight.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
    }
}
