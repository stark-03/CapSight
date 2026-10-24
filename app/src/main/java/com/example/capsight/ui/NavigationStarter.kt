package com.example.capsight.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.capsight.ble.LandmarkScanner
import com.example.capsight.settings.StartPlan
import com.example.capsight.settings.StartWish

/** A short on-screen note next to the spoken phrase (the helper may be watching). */
fun showStartNotice(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/**
 * Returns the action for "Start navigation" (with [StartWish.SAVED]) and for volume-key starts that
 * need a permission dialog first (CapSight on screen, CLAUDE.md §8.2): asks for the notification
 * permission (API 33+, so the foreground-service notification with its Stop button shows), the BLE
 * scan permissions for landmarks (precise location on every API level, plus BLUETOOTH_SCAN on API 31+;
 * CLAUDE.md §6.1) and, when the wish uses the phone camera, the camera permission - then starts
 * NavigationService.
 * Guidance starts even if the notification or BLE permission is refused; only the notification
 * or the landmarks are missing. A refused (or permanently denied) camera permission starts this
 * session in landmarks-only mode (CLAUDE.md §5.1) without changing the saved source; the next Start
 * asks again. With no camera and landmarks switched off it does not start and says why.
 * The main screen's start buttons use it with [StartWish.CAMERA] / [StartWish.NO_CAMERA], exactly like
 * volume up x3 / x2; [byVolumeKeys] only changes how the start is named in the decision log.
 */
@Composable
fun rememberNavigationStarter(vm: AppViewModel, byVolumeKeys: Boolean = false): (StartWish) -> Unit {
    val context = LocalContext.current
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // The wish being started while the permission dialogs are open.
    val wish = remember { mutableStateOf(StartWish.SAVED) }

    fun startWith(plan: StartPlan) {
        if (!plan.canStart) {
            showStartNotice(context, StartPlan.NOTHING_TO_GUIDE)
            vm.sayNothingToGuide()
            return
        }
        if (plan.cameraFallback) showStartNotice(context, StartPlan.CAMERA_REFUSED)
        vm.startNavigation(plan, wish.value, byVolumeKeys)
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        startWith(vm.startPlan(wish.value, cameraPermission = ok))
    }
    fun askCameraThenStart() {
        if (vm.wantsPhoneCamera(wish.value) && !granted(Manifest.permission.CAMERA)) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startWith(vm.startPlan(wish.value, cameraPermission = granted(Manifest.permission.CAMERA)))
        }
    }
    // Refused: landmarks stay off ("BLE: ... permission denied" on the screens); navigation starts anyway.
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askCameraThenStart()
    }
    fun askBleThenCamera() {
        if (vm.usesLandmarks && !LandmarkScanner.hasScanPermission(context)) {
            bleLauncher.launch(LandmarkScanner.scanPermissions.toTypedArray())
        } else {
            askCameraThenStart()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBleThenCamera()
    }
    return { w ->
        wish.value = w
        val best = vm.startPlan(w, cameraPermission = true)
        when {
            // Nothing to guide with whatever the camera answer: say so before any dialog.
            !best.canStart -> startWith(best)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS) ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> askBleThenCamera()
        }
    }
}
