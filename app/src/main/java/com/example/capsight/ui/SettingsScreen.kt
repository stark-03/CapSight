package com.example.capsight.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.R
import com.example.capsight.ble.Calibration
import com.example.capsight.camera.CameraContract
import com.example.capsight.settings.HelperSettingsDraft
import com.example.capsight.settings.SourceKind
import com.example.capsight.ui.theme.ErrorText
import com.example.capsight.ui.theme.OkText
import com.example.capsight.ui.theme.TextMuted
import com.example.capsight.ui.theme.Yellow
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val MinTouch = 48.dp

/**
 * The helper's settings (FR-APP-04, FR-DIST-05, FR-VOICE-05), for a sighted helper or teacher,
 * reached from the main screen's "Helper settings". Sections: Camera and obstacle warnings, BLE
 * landmarks (distance and calibration), Voice, Volume buttons, Advanced (depth, cap camera address
 * and field of view, debug view), phone setup against OEM battery killers (CLAUDE.md §10), and About
 * with the safety statement (NFR-SAFE-01). Values are validated and kept only with "Save settings"
 * (fixed at the bottom); "Use a camera" and "Calibrate at 1 m" are saved at once.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onDebug: () -> Unit, modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val cam by vm.camera.collectAsStateWithLifecycle()
    val nav by vm.navigationSettings.collectAsStateWithLifecycle()
    val sp by vm.speechSettings.collectAsStateWithLifecycle()
    val lms by vm.landmarkSettings.collectAsStateWithLifecycle()
    val ctls by vm.controlSettings.collectAsStateWithLifecycle()
    val c = cam ?: return
    val n = nav ?: return
    val s = sp ?: return
    val l = lms ?: return
    val ct = ctls ?: return

    var draft by remember { mutableStateOf(HelperSettingsDraft.from(c, n, s, l, ct)) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var message by remember { mutableStateOf("") }
    var calibration by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier
                        .heightIn(min = MinTouch)
                        .semantics { contentDescription = "Back to the main screen" },
                ) { Text("← Back") }
                Text(
                    "Helper settings",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                "For a sighted helper or teacher. Changes are kept when you tap Save settings at the bottom; " +
                    "\"Use a camera\" and \"Calibrate at 1 m\" are saved at once.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            OutlinedButton(onClick = onDebug, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text("Open debug view (live camera, beacons, decision log)")
            }

            // ------------------------------------------------------------------ camera
            Section("Camera and obstacle warnings")
            // Using the camera is the user's choice (CLAUDE.md §5.1). Saved at once, like the debug view's
            // source picker; on = the phone camera (pick the cap camera in the debug view).
            SwitchRow(
                "Use a camera",
                (
                    if (c.source.hasCamera) {
                        "On (${sourceSummary(c.source)}): the debug view's Start detects obstacles with the camera."
                    } else {
                        "Off: the debug view's Start runs BLE landmarks only - obstacles are NOT detected."
                    }
                    ) + " The main screen's \"Start with camera\" / \"Start without camera\" buttons and the volume " +
                    "buttons always choose for themselves. Saved at once.",
                c.source.hasCamera,
            ) { on -> vm.setSource(if (on) SourceKind.PHONE_CAMERA else SourceKind.NONE) }
            Field("Stop distance (m)", draft.stopDistanceM, errors[HelperSettingsDraft.STOP], KeyboardType.Decimal,
                help = "\"Stop. Chair ahead.\" when an obstacle is this close. Default 1.2.") { draft = draft.copy(stopDistanceM = it) }
            Field("Warning distance (m)", draft.warnDistanceM, errors[HelperSettingsDraft.WARN], KeyboardType.Decimal,
                help = "\"Chair ahead. Move left.\" from this distance. Default 2.5.") { draft = draft.copy(warnDistanceM = it) }
            Field("User height (cm)", draft.userHeightCm, errors[HelperSettingsDraft.HEIGHT], KeyboardType.Number,
                help = "The person who walks with CapSight.") { draft = draft.copy(userHeightCm = it) }
            Field("Camera height above floor (m)", draft.cameraHeightM, errors[HelperSettingsDraft.CAMERA_HEIGHT],
                KeyboardType.Decimal,
                help = "For distance from where objects meet the floor. Phone worn at chest: about 1.30. " +
                    "Cap camera: the user's height minus about 0.1 m.") {
                draft = draft.copy(cameraHeightM = it)
            }

            // ------------------------------------------------------------------ landmarks
            Section("BLE landmarks (beacons)")
            SwitchRow(
                "BLE landmarks",
                "Speaks beacons at doors, stairs and rooms: \"Room 304 door nearby.\" The only guidance without a camera.",
                draft.landmarksEnabled,
            ) { draft = draft.copy(landmarksEnabled = it) }
            SwitchRow(
                "Say distance to beacons",
                "\"Room 304 door nearby, about 4 metres.\", then \"about 2 metres\" as you get closer and " +
                    "\"you are here\" at about 1 m. Approximate (about ±1 m up close), no direction.",
                draft.landmarkSayDistance,
            ) { draft = draft.copy(landmarkSayDistance = it) }
            Field("Beacon signal at 1 m (dBm)", draft.landmarkRssiAt1mDbm, errors[HelperSettingsDraft.RSSI_1M],
                KeyboardType.Text, help = "Set it with \"Calibrate at 1 m\" below. Default -52.") {
                draft = draft.copy(landmarkRssiAt1mDbm = it)
            }
            Text(
                "Calibrate: start navigation, hold the phone 1 m from one beacon for a few seconds, then tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
            OutlinedButton(
                onClick = {
                    val result = vm.calibrateBeacon()
                    calibration = result.message
                    if (result is Calibration.Ok) draft = draft.copy(landmarkRssiAt1mDbm = result.dbm.toString())
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            ) { Text("Calibrate at 1 m") }
            if (calibration.isNotEmpty()) {
                Text(
                    calibration,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            SubSection("Fine-tuning")
            Field("Announce at signal (dBm)", draft.landmarkAnnounceDbm, errors[HelperSettingsDraft.ANNOUNCE],
                KeyboardType.Text, help = "-70 is about 2-4 m. Closer to 0 = announce nearer the beacon.") {
                draft = draft.copy(landmarkAnnounceDbm = it)
            }
            Field("Re-arm below signal (dBm)", draft.landmarkRearmDbm, errors[HelperSettingsDraft.REARM],
                KeyboardType.Text, help = "Spoken again only after the signal drops below this. Default -80.") {
                draft = draft.copy(landmarkRearmDbm = it)
            }
            Field("Same landmark at most every (s)", draft.landmarkRepeatS, errors[HelperSettingsDraft.LANDMARK_REPEAT],
                KeyboardType.Decimal, help = "Default 60.") { draft = draft.copy(landmarkRepeatS = it) }
            Field("Signal fall-off (path-loss exponent)", draft.landmarkPathLoss, errors[HelperSettingsDraft.PATH_LOSS],
                KeyboardType.Decimal, help = "2 = open space, 3 or more = walls or people in the way. Default 2.5.") {
                draft = draft.copy(landmarkPathLoss = it)
            }

            // ------------------------------------------------------------------ voice
            Section("Voice")
            val rateText = "Speech rate: ${String.format(Locale.US, "%.2f", draft.speechRate)} times normal"
            Text(rateText, style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = draft.speechRate,
                onValueChange = { draft = draft.copy(speechRate = (it * RATE_STEPS).roundToInt() / RATE_STEPS) },
                valueRange = HelperSettingsDraft.MIN_RATE..HelperSettingsDraft.MAX_RATE,
                modifier = Modifier.semantics {
                    contentDescription = "Speech rate"
                    stateDescription = rateText
                },
            )
            val volumeText = "Volume: ${(draft.speechVolume * PERCENT).roundToInt()}% of the media volume"
            Text(volumeText, style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = draft.speechVolume,
                onValueChange = { draft = draft.copy(speechVolume = it) },
                modifier = Modifier.semantics {
                    contentDescription = "Speech volume"
                    stateDescription = volumeText
                },
            )
            Field("Repeat the same instruction after (s)", draft.phraseCooldownS, errors[HelperSettingsDraft.COOLDOWN],
                KeyboardType.Decimal, help = "Default 3.") { draft = draft.copy(phraseCooldownS = it) }
            Field("Repeat \"Path clear\" every (s)", draft.clearRepeatS, errors[HelperSettingsDraft.CLEAR_REPEAT],
                KeyboardType.Decimal, help = "Default 6.") { draft = draft.copy(clearRepeatS = it) }

            // ------------------------------------------------------------------ volume buttons
            Section("Volume buttons")
            SwitchRow(
                "Volume-button control",
                "Volume up twice: start without camera. Volume up three times: start with camera. " +
                    "Volume down twice: stop. Volume down three times: hear the status. Works with the screen off " +
                    "once CapSight has been opened (a \"CapSight ready\" notification shows). Single presses still " +
                    "change the volume.",
                draft.volumeButtonControl,
            ) { draft = draft.copy(volumeButtonControl = it) }

            // ------------------------------------------------------------------ advanced
            Section("Advanced")
            SwitchRow(
                "Depth obstacle detection",
                "Estimates depth to catch walls, pillars and boxes the object detector cannot name: " +
                    "\"Stop. Obstacle ahead.\", and never \"Move left/right\" into a near wall.",
                draft.depthEnabled,
            ) { draft = draft.copy(depthEnabled = it) }
            Field("Depth threshold", draft.depthThreshold, errors[HelperSettingsDraft.DEPTH_THRESHOLD], KeyboardType.Decimal,
                help = "1.00 = as near as the floor just ahead (about arm's length). Higher = only very close " +
                    "objects; lower = earlier warnings but more false alarms in pillared corridors.") {
                draft = draft.copy(depthThreshold = it)
            }
            Field("Cap camera address", draft.cameraBaseUrl, errors[HelperSettingsDraft.CAMERA], KeyboardType.Uri,
                help = "Real cap: http://192.168.4.1. Pick the cap as the source in the debug view.") {
                draft = draft.copy(cameraBaseUrl = it)
            }
            TextButton(
                onClick = { draft = draft.copy(cameraBaseUrl = CameraContract.DEFAULT_BASE_URL) },
                modifier = Modifier.heightIn(min = MinTouch),
            ) { Text("Use the real cap (192.168.4.1)") }
            Field("Cap camera horizontal field of view (degrees)", draft.capFovDeg, errors[HelperSettingsDraft.FOV],
                KeyboardType.Decimal, help = "Measure it: CLAUDE.md §8.1. Wrong values scale every distance.") {
                draft = draft.copy(capFovDeg = it)
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            ) { Text("Wi-Fi settings (join BLENAV_CAM)") }
            OutlinedButton(onClick = onDebug, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) {
                Text("Open debug view")
            }

            // ------------------------------------------------------------------ phone setup
            HorizontalDivider()
            Section("Phone setup")
            Text(
                "Many phones (Xiaomi/Redmi, Oppo, Vivo, Realme, Samsung) stop background apps even with the " +
                    "notification showing. In the app's settings: allow Autostart, set Battery to " +
                    "\"No restrictions\" / \"Unrestricted\", and lock CapSight in the recent-apps screen. " +
                    "If the camera was refused twice, Android stops asking: allow it here under Permissions.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch),
            ) { Text("Allow camera and other permissions (opens phone settings)") }

            // ------------------------------------------------------------------ about
            HorizontalDivider()
            Section("About")
            Text(stringResource(R.string.safety_statement), style = MaterialTheme.typography.bodyLarge)
            Text("Version ${versionName(context)} · camera contract v1", style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }

        // Fixed at the bottom, so Save is always in reach and its result always visible.
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            errors = vm.saveHelperSettings(draft)
                            message = if (errors.isEmpty()) "Saved" else "Not saved - fix the fields marked in red (${errors.size})"
                        }
                    },
                    modifier = Modifier.heightIn(min = MinTouch),
                ) { Text("Save settings") }
                TextButton(
                    onClick = { draft = HelperSettingsDraft.DEFAULTS; errors = emptyMap(); message = "Defaults loaded - tap Save" },
                    modifier = Modifier.heightIn(min = MinTouch),
                ) { Text("Reset to defaults") }
            }
            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = if (errors.isEmpty()) OkText else ErrorText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(help, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = Yellow,
        modifier = Modifier
            .padding(top = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SubSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: String?,
    keyboard: KeyboardType,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        supportingText = (error ?: help)?.let { t -> { Text(t, color = if (error != null) ErrorText else Color.Unspecified) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun sourceSummary(kind: SourceKind) = when (kind) {
    SourceKind.ESP32 -> "cap camera"
    SourceKind.PHONE_CAMERA -> "phone camera"
    SourceKind.VIDEO_FILE -> "video file"
    SourceKind.NONE -> "no camera"
}

private fun versionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: android.content.pm.PackageManager.NameNotFoundException) {
    "?"
}

private const val RATE_STEPS = 20f
private const val PERCENT = 100
