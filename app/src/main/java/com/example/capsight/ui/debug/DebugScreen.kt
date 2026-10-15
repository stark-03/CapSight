package com.example.capsight.ui.debug

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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.camera.CameraContract
import com.example.capsight.service.Pipeline
import com.example.capsight.ui.AppViewModel
import com.example.capsight.ui.rememberNavigationStarter
import com.example.capsight.camera.Frame
import com.example.capsight.camera.LinkState
import com.example.capsight.camera.ReceiverStatus
import com.example.capsight.detect.DetectionState
import com.example.capsight.perception.Threat
import com.example.capsight.settings.SpeechSettings
import com.example.capsight.voice.SpeakerState
import com.example.capsight.service.GuidanceState
import androidx.compose.material3.Slider
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import com.example.capsight.settings.CameraSettings
import com.example.capsight.settings.SourceKind
import java.util.Locale

private val Green = Color(0xFF1B5E20)
private val Amber = Color(0xFF8D6E00)
private val Red = Color(0xFFB71C1C)
private val Grey = Color(0xFF424242)
private val MinTouch = 48.dp

/** Debug screen: live frame with detection boxes, FPS, link state, source settings (FR-APP-05). */
@Composable
fun DebugScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val settings by vm.camera.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val detection by vm.detection.collectAsStateWithLifecycle()
    val perceived by vm.perceived.collectAsStateWithLifecycle()
    val detectionSettings by vm.detectionSettings.collectAsStateWithLifecycle()
    val speech by vm.speech.collectAsStateWithLifecycle()
    val speechSettings by vm.speechSettings.collectAsStateWithLifecycle()
    val guidance by vm.guidance.collectAsStateWithLifecycle()
    // Guidance runs in NavigationService either way; keeping the screen on is only for watching.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    val onStart = rememberNavigationStarter(vm)
    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.setVideo(uri) }

    val s = settings ?: return

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) { Text("← Back") }
        Text(
            "CapSight debug",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        StatusBanner(running, status)
        FrameView(frame, status, running, detection, perceived)
        StartStopButton(running, onStart = onStart, onStop = { vm.stopNavigation() })
        DecisionBlock(guidance)
        CaptionBlock(speech)
        StatsBlock(status)
        DetectionBlock(detection, perceived)
        HorizontalDivider()
        detectionSettings?.let { d ->
            Text("Detection", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            SwitchRow("Use GPU", "Off forces the CPU (4 threads), to compare speed.", d.useGpu, vm::setUseGpu)
            SwitchRow(
                "Show all 80 classes",
                "Off shows only person and the obstacle classes navigation uses.",
                d.showAllClasses,
                vm::setShowAllClasses,
            )
        }
        HorizontalDivider()
        speechSettings?.let { sp -> SpeechSection(speech, sp, vm) }
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
private fun FrameView(
    frame: Frame?,
    status: ReceiverStatus,
    running: Boolean,
    detection: DetectionState,
    perceived: PerceivedView,
) {
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
            DetectionOverlay(perceived, Modifier.fillMaxSize())
        }
        if (running) {
            Text(
                overlayText(status, detection, perceived),
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

private fun overlayText(s: ReceiverStatus, d: DetectionState, p: PerceivedView): String {
    val fps = String.format(Locale.US, "%.1f fps", s.fps)
    val size = if (s.frameWidth > 0) "${s.frameWidth}x${s.frameHeight}" else "no frame"
    val age = s.frameAgeMs?.let { "age ${it} ms" } ?: "age -"
    val det = when {
        d.error != null && !d.running -> "detector: error"
        !d.running -> "detector: loading"
        else -> String.format(Locale.US, "det %.1f fps  %s  infer %d ms  hazards: %s", d.fps, d.backend, d.inferenceMs, d.hazards)
    }
    val fov = if (p.fovDeg > 0) String.format(Locale.US, "FOV %.1f° (%s)", p.fovDeg, p.fovSource) else ""
    val zones = zonesText(p)
    return listOf("cam $fps  $size  $age", det, fov, zones).filter { it.isNotEmpty() }.joinToString("\n")
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
private fun DetectionBlock(d: DetectionState, p: PerceivedView) {
    if (d.error != null) Text(d.error, color = Red, style = MaterialTheme.typography.bodyMedium)
    if (!d.running) return
    val found = if (p.objects.isEmpty()) {
        "nothing"
    } else {
        p.objects.joinToString { o ->
            objectLabel(o) + when (o.threat) {
                Threat.STOP -> " STOP"
                Threat.WARN -> " WARN"
                Threat.NONE -> ""
            }
        }
    }
    Text(
        "Detections: $found   (frame ${d.frameSeq}, ${d.totalMs} ms total)",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** The guidance loop's current decision (FR-NAV-01/02). */
@Composable
private fun DecisionBlock(g: GuidanceState) {
    val d = g.decision ?: return
    val text = if (d.speak) "P${d.priority}  ${d.message}" else "(silent)"
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(
        String.format(Locale.US, "%s  ·  %.1f Hz", d.reason, g.tickHz),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Captions: what is being said, so a sighted helper can follow without sound (§8). */
@Composable
private fun CaptionBlock(sp: SpeakerState) {
    val text = sp.speaking?.let { "Saying: $it" } ?: sp.lastSpoken.takeIf { it.isNotEmpty() }?.let { "Said: $it" }
    if (text != null) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeechSection(sp: SpeakerState, settings: SpeechSettings, vm: AppViewModel) {
    Text("Speech", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    val engine = when {
        sp.ttsReady -> "Ready (${sp.engine})"
        sp.error != null -> sp.error
        else -> "Starting text-to-speech…"
    }
    Text("Engine: $engine", style = MaterialTheme.typography.bodyMedium, color = if (sp.ttsReady) Color.Unspecified else Red)
    Text("Output: ${sp.output}", style = MaterialTheme.typography.bodyMedium)
    Text(
        "Pending: ${sp.pending ?: "-"}   Suppressed: ${sp.lastSuppressed ?: "-"}" +
            if (sp.recoveries > 0) "   Engine restarts: ${sp.recoveries}" else "",
        style = MaterialTheme.typography.bodySmall,
    )
    val tests = listOf(
        Pipeline.SpeechTest.CLEAR to "Path clear (P4)",
        Pipeline.SpeechTest.AVOID to "Chair, move left (P2)",
        Pipeline.SpeechTest.STOP to "Stop (P0, vibrates)",
        Pipeline.SpeechTest.BURST to "Burst: 4 at once",
        Pipeline.SpeechTest.INTERRUPT to "P0 interrupts",
    )
    tests.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            row.forEach { (test, label) ->
                OutlinedButton(
                    onClick = { vm.testSpeech(test) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouch),
                ) { Text(label) }
            }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
    Text(
        "Speech rate: ${String.format(Locale.US, "%.2f", settings.rate)}x",
        style = MaterialTheme.typography.bodyMedium,
    )
    Slider(
        value = settings.rate,
        onValueChange = { vm.setSpeechRate((it * RATE_STEPS).roundToInt() / RATE_STEPS) },
        valueRange = MIN_RATE..MAX_RATE,
        modifier = Modifier.semantics { contentDescription = "Speech rate" },
    )
    SwitchRow(
        "Speak guidance",
        "Speaks the 10 Hz decisions (\"Path clear.\", \"Chair ahead. Move left.\", \"Camera disconnected. Please stop.\"). Off: decide and show only.",
        settings.speakGuidance,
        vm::setSpeakGuidance,
    )
}

private const val MIN_RATE = 0.5f
private const val MAX_RATE = 2.0f
private const val RATE_STEPS = 20f

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
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
    SwitchRow(
        "Bind to Wi-Fi network",
        "Leave on. Turn off only if the laptop joined this phone's hotspot.",
        s.bindToWifi,
        onBindToWifi,
    )
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
