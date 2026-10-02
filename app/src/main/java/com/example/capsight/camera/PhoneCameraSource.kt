package com.example.capsight.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
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
                val bitmap = rotate(it.toBitmap(), it.imageInfo.rotationDegrees)
                if (first) {
                    onStatus("Streaming ${bitmap.width}x${bitmap.height} from phone camera")
                    first = false
                }
                onFrame(bitmap)
            }
        }
        try {
            withContext(Dispatchers.Main) {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
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
