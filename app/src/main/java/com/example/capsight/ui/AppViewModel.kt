package com.example.capsight.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.capsight.TAG
import com.example.capsight.service.NavigationService
import com.example.capsight.service.Pipeline
import com.example.capsight.settings.HelperSettingsDraft
import com.example.capsight.settings.SourceKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What every screen observes. The pipeline itself runs in NavigationService (FR-APP-03); this only
 * exposes its StateFlows, starts and stops the service, and edits settings (CLAUDE.md §5).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val pipeline = Pipeline.get(app)
    private val store = pipeline.settings

    val camera = pipeline.camera
    val detectionSettings = pipeline.detectionSettings
    val speechSettings = pipeline.speechSettings
    val navigationSettings = pipeline.navigationSettings
    val frame = pipeline.frame
    val status = pipeline.status
    val detection = pipeline.detection
    val perceived = pipeline.perceived
    val speech = pipeline.speech
    val guidance = pipeline.guidance
    val running = pipeline.running

    /** Null until loaded; false shows the first-run safety statement (NFR-SAFE-01). */
    val safetyAccepted: StateFlow<Boolean?> = store.safetyAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val usesPhoneCamera: Boolean get() = camera.value?.source == SourceKind.PHONE_CAMERA

    fun startNavigation() = NavigationService.start(getApplication(), usePhoneCamera = usesPhoneCamera)

    fun stopNavigation() = NavigationService.stop(getApplication())

    fun testSpeech(test: Pipeline.SpeechTest) = pipeline.testSpeech(test)

    fun acceptSafety() = viewModelScope.launch { store.acceptSafety() }

    suspend fun saveHelperSettings(draft: HelperSettingsDraft): Map<String, String> = store.saveHelperSettings(draft)

    fun setSource(kind: SourceKind) = viewModelScope.launch { store.setSource(kind) }

    fun setCameraBaseUrl(url: String) = viewModelScope.launch { store.setCameraBaseUrl(url) }

    fun setBindToWifi(bind: Boolean) = viewModelScope.launch { store.setBindToWifi(bind) }

    fun setUseGpu(use: Boolean) = viewModelScope.launch { store.setUseGpu(use) }

    fun setShowAllClasses(show: Boolean) = viewModelScope.launch { store.setShowAllClasses(show) }

    fun setSpeechRate(rate: Float) = viewModelScope.launch { store.setSpeechRate(rate) }

    fun setSpeakGuidance(on: Boolean) = viewModelScope.launch { store.setSpeakGuidance(on) }

    fun setVideo(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable permission for $uri; it works until reboot", e)
        }
        viewModelScope.launch { store.setVideoUri(uri.toString()) }
    }
}
