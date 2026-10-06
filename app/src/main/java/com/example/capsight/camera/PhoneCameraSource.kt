package com.example.capsight.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import com.example.capsight.TAG
import com.example.capsight.perception.CameraFov
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The phone's rear camera via CameraX - the main Phase-A test source (FR-RX-05,
 * CLAUDE.md §1A). Asks for 640x480 like the ESP32-CAM, keeps only the latest image
 * (STRATEGY_KEEP_ONLY_LATEST, the same newest-frame policy as CAMERA_GRAB_LATEST)
 * and rotates frames upright.
 *
 * Owns its own lifecycle instead of borrowing an Activity's, so it works the same
 * when NavigationService runs it later (build step 7).
 */
class PhoneCameraSource(private val context: Context) : FrameSource {

    override val label = "Phone camera"

    @Volatile
    override var horizontalFovDeg: Float? = null
        private set

    /** Lens and sensor geometry, read once the camera is bound. */
    private class Optics(val focalMm: Double, val activeWidthMm: Double, val activeHeightMm: Double)

    @Volatile
    private var optics: Optics? = null

    override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Camera permission not granted")
            awaitCancellation()
        }
        onStatus("Opening phone camera")
        val provider = ProcessCameraProvider.awaitInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val owner = SourceLifecycleOwner()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(TARGET_WIDTH, TARGET_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        var first = true
        analysis.setAnalyzer(executor) { image ->
            image.use {
                val rotation = it.imageInfo.rotationDegrees
                if (horizontalFovDeg == null) {
                    optics?.let { o ->
                        horizontalFovDeg = CameraFov.horizontalFovDeg(
                            o.focalMm, o.activeWidthMm, o.activeHeightMm, it.width, it.height, rotation,
                        ).toFloat()
                        Log.i(TAG, "phone camera horizontal FOV $horizontalFovDeg deg (${it.width}x${it.height}, rot $rotation)")
                    }
                }
                val bitmap = rotate(it.toBitmap(), rotation)
                if (first) {
                    onStatus("Streaming ${bitmap.width}x${bitmap.height} from phone camera")
                    first = false
                }
                onFrame(bitmap)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                optics = readOptics(camera.cameraInfo)
                owner.start()
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                provider.unbind(analysis)
                owner.destroy()
            }
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }

    /** Focal length and active sensor size from Camera2, or null if the device hides them. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readOptics(info: CameraInfo): Optics? = try {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (focal == null || physical == null || pixels == null || active == null || focal <= 0f) {
            null
        } else {
            Optics(
                focalMm = focal.toDouble(),
                activeWidthMm = physical.width.toDouble() * active.width() / pixels.width,
                activeHeightMm = physical.height.toDouble() * active.height() / pixels.height,
            )
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not read camera optics; using the FOV setting", e)
        null
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /** A lifecycle we drive ourselves. LifecycleRegistry must be touched on the main thread. */
    private class SourceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            // INITIALIZED -> DESTROYED is not a legal move; nothing to tear down then.
            if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    companion object {
        /** Same as the ESP32-CAM's VGA frames (contract §4.2). */
        private const val TARGET_WIDTH = 640
        private const val TARGET_HEIGHT = 480
    }
}
