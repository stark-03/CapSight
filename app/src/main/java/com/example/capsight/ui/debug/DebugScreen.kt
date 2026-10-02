package com.example.capsight.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.camera.CameraContract
import com.example.capsight.camera.Frame
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.ReceiverStatus
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.SourceKind
import java.util.Locale

private val Green = Color(0xFF1B5E20)
private val Amber = Color(0xFF8D6E00)
private val Red = Color(0xFFB71C1C)
private val Grey = Color(0xFF424242)
private val MinTouch = 48.dp

/** Camera debug screen: live frame, FPS, link state, source and camera address (FR-APP-05). */
@Composable
fun DebugScreen(modifier: Modifier = Modifier, vm: DebugViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        onStopOrDispose { vm.setVisible(false) }
    }
    // Keep the screen on while testing so the receiver keeps running (until step 7's service).
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    var permissionDenied by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionDenied = !granted
        if (granted) vm.start()
    }
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.setVideo(uri) }

    val s = settings ?: return
    val onStart = {
        val needsPermission = s.source == SourceKind.PHONE_CAMERA &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) cameraPermission.launch(Manifest.permission.CAMERA) else vm.start()
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Camera debug",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        StatusBanner(running, status)
        FrameView(frame, status, running)
        StartStopButton(running, onStart = onStart, onStop = vm::stop)
        if (permissionDenied) {
            Text(
                "Camera permission denied. Allow it in phone Settings > Apps > Cap Sight.",
                color = Red,
            )
        }
        StatsBlock(status)
        HorizontalDivider()
        SourcePicker(s.source, vm::setSource)
        when (s.source) {
            SourceKind.ESP32 -> Esp32Settings(s, vm::setCameraBaseUrl, vm::setBindToWifi)
            SourceKind.VIDEO_FILE -> VideoSettings(s) { pickVideo.launch(arrayOf("video/*")) }
            SourceKind.PHONE_CAMERA -> Text(
                "Uses the phone's rear camera at 640x480. Point it where you walk.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusBanner(running: Boolean, status: ReceiverStatus) {
    val (text, colour) = when {
        !running -> "Stopped" to Grey
        status.link == LinkState.CONNECTED -> "Camera connected" to Green
        status.link == LinkState.DISCONNECTED -> "Camera disconnected" to Red
        else -> "Connecting to camera…" to Amber
    }
    Text(
        text,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(colour, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            // TalkBack announces link changes without the user touching the screen.
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun FrameView(frame: Frame?, status: ReceiverStatus, running: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(Color.Black),
    ) {
        if (frame != null) {
            val image = remember(frame) { frame.bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "Live camera frame",
                contentScale = ContentScale.Fit,
                alpha = if (status.link == LinkState.CONNECTED) 1f else 0.35f,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (running) {
            Text(
                overlayText(status),
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0xAA000000))
                    .padding(6.dp),
            )
        }
        if (running && status.link == LinkState.DISCONNECTED) {
            Text(
                "Camera disconnected",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Red)
                    .padding(12.dp),
            )
        }
    }
}

private fun overlayText(s: ReceiverStatus): String {
    val fps = String.format(Locale.US, "%.1f fps", s.fps)
    val size = if (s.frameWidth > 0) "${s.frameWidth}x${s.frameHeight}" else "no frame"
    val age = s.frameAgeMs?.let { "age ${it} ms" } ?: "age -"
    return "$fps  $size  $age"
}

@Composable
private fun StartStopButton(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Button(
        onClick = if (running) onStop else onStart,
        colors = if (running) ButtonDefaults.buttonColors(containerColor = Red) else ButtonDefaults.buttonColors(),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Text(if (running) "Stop" else "Start", fontSize = 22.sp)
    }
}

@Composable
private fun StatsBlock(s: ReceiverStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (s.running) {
            Text("Source: ${s.sourceLabel}", style = MaterialTheme.typography.bodyMedium)
            Text("Status: ${s.detail}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Frames: ${s.framesReceived}   Disconnects: ${s.disconnects}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SourcePicker(selected: SourceKind, onSelect: (SourceKind) -> Unit) {
    Text("Frame source", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Column(Modifier.selectableGroup()) {
        SourceKind.entries.forEach { kind ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouch)
                    .selectable(selected = kind == selected, role = Role.RadioButton) { onSelect(kind) },
            ) {
                RadioButton(selected = kind == selected, onClick = null)
                Text(sourceName(kind), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private fun sourceName(kind: SourceKind) = when (kind) {
    SourceKind.ESP32 -> "ESP32-CAM over Wi-Fi (real cap or fake camera)"
    SourceKind.PHONE_CAMERA -> "Phone camera"
    SourceKind.VIDEO_FILE -> "Video file"
}

@Composable
private fun Esp32Settings(
    s: CameraSettings,
    onSaveUrl: (String) -> Unit,
    onBindToWifi: (Boolean) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(s.cameraBaseUrl) }
    LaunchedEffect(s.cameraBaseUrl) { draft = s.cameraBaseUrl }
    val endpoints = CameraContract.endpoints(draft)

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Camera address") },
        singleLine = true,
        isError = endpoints == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        supportingText = {
            Text(
                if (endpoints == null) {
                    "Enter an address like http://192.168.4.1 or http://192.168.1.20:8080"
                } else {
                    "Probe ${endpoints.probeUrl}\nStream ${endpoints.streamUrl}"
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onSaveUrl(draft) },
            enabled = endpoints != null && draft.trim() != s.cameraBaseUrl,
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text("Save address") }
        TextButton(
            onClick = { draft = CameraContract.DEFAULT_BASE_URL; onSaveUrl(CameraContract.DEFAULT_BASE_URL) },
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text("Use real cap (192.168.4.1)") }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = s.bindToWifi, role = Role.Switch, onValueChange = onBindToWifi),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Bind to Wi-Fi network")
            Text(
                "Leave on. Turn off only if the laptop joined this phone's hotspot.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = s.bindToWifi, onCheckedChange = null)
    }
}

@Composable
private fun VideoSettings(s: CameraSettings, onPick: () -> Unit) {
    OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = MinTouch)) {
        Text("Choose video…")
    }
    Text(
        s.videoUri?.let { "Selected: ${it.toUri().lastPathSegment}" } ?: "No video selected",
        style = MaterialTheme.typography.bodyMedium,
    )
}
