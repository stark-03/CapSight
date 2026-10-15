package com.example.capsight.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Returns the action for "Start navigation": asks for the notification permission (API 33+, so the
 * foreground-service notification with its Stop button shows) and, for the phone-camera test
 * source, the camera permission - then starts NavigationService. Guidance starts even if the
 * notification permission is refused; only the notification is hidden.
 */
@Composable
fun rememberNavigationStarter(vm: AppViewModel): () -> Unit {
    val context = LocalContext.current
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            vm.startNavigation()
        } else {
            Toast.makeText(context, "Camera permission is needed for the phone-camera source", Toast.LENGTH_LONG).show()
        }
    }
    fun afterNotifications() {
        if (vm.usesPhoneCamera && !granted(Manifest.permission.CAMERA)) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        } else {
            vm.startNavigation()
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotifications()
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            afterNotifications()
        }
    }
}
