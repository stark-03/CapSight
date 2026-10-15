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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.R
import com.example.capsight.camera.CameraContract
import com.example.capsight.settings.HelperSettingsDraft
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val MinTouch = 48.dp
private val ErrorRed = Color(0xFFB71C1C)

/**
 * The helper's settings (FR-APP-04, FR-DIST-05, FR-VOICE-05): camera address, field of view, user
 * height, distances, cooldowns, voice rate and volume; phone setup against OEM battery killers
 * (CLAUDE.md §10); the safety statement (NFR-SAFE-01). Values are validated before saving.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val cam by vm.camera.collectAsStateWithLifecycle()
    val nav by vm.navigationSettings.collectAsStateWithLifecycle()
    val sp by vm.speechSettings.collectAsStateWithLifecycle()
    val c = cam ?: return
    val n = nav ?: return
    val s = sp ?: return

    var draft by remember { mutableStateOf(HelperSettingsDraft.from(c, n, s)) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { message = "" }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) { Text("← Back") }
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

        Section("Camera")
        Field("Camera address", draft.cameraBaseUrl, errors[HelperSettingsDraft.CAMERA], KeyboardType.Uri,
            help = "Real cap: http://192.168.4.1") { draft = draft.copy(cameraBaseUrl = it) }
        TextButton(onClick = { draft = draft.copy(cameraBaseUrl = CameraContract.DEFAULT_BASE_URL) }) {
            Text("Use the real cap (192.168.4.1)")
        }
        Field("Cap camera horizontal field of view (degrees)", draft.capFovDeg, errors[HelperSettingsDraft.FOV],
            KeyboardType.Decimal, help = "Measure it: CLAUDE.md §8.1. Wrong values scale every distance.") {
            draft = draft.copy(capFovDeg = it)
        }
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text("Wi-Fi settings (join BLENAV_CAM)") }

        Section("User")
        Field("User height (cm)", draft.userHeightCm, errors[HelperSettingsDraft.HEIGHT], KeyboardType.Number) {
            draft = draft.copy(userHeightCm = it)
        }

        Section("Distances")
        Field("Stop distance (m)", draft.stopDistanceM, errors[HelperSettingsDraft.STOP], KeyboardType.Decimal,
            help = "\"Stop. Chair ahead.\" when an obstacle is this close") { draft = draft.copy(stopDistanceM = it) }
        Field("Warning distance (m)", draft.warnDistanceM, errors[HelperSettingsDraft.WARN], KeyboardType.Decimal,
            help = "\"Chair ahead. Move left.\" from this distance") { draft = draft.copy(warnDistanceM = it) }

        Section("Voice")
        Text("Speech rate: ${String.format(Locale.US, "%.2f", draft.speechRate)}x")
        Slider(
            value = draft.speechRate,
            onValueChange = { draft = draft.copy(speechRate = (it * RATE_STEPS).roundToInt() / RATE_STEPS) },
            valueRange = HelperSettingsDraft.MIN_RATE..HelperSettingsDraft.MAX_RATE,
            modifier = Modifier.semantics { contentDescription = "Speech rate" },
        )
        Text("Volume: ${(draft.speechVolume * PERCENT).roundToInt()}% of media volume")
        Slider(
            value = draft.speechVolume,
            onValueChange = { draft = draft.copy(speechVolume = it) },
            modifier = Modifier.semantics { contentDescription = "Speech volume" },
        )
        Field("Repeat the same instruction after (s)", draft.phraseCooldownS, errors[HelperSettingsDraft.COOLDOWN],
            KeyboardType.Decimal) { draft = draft.copy(phraseCooldownS = it) }
        Field("Repeat \"Path clear\" every (s)", draft.clearRepeatS, errors[HelperSettingsDraft.CLEAR_REPEAT],
            KeyboardType.Decimal) { draft = draft.copy(clearRepeatS = it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        errors = vm.saveHelperSettings(draft)
                        message = if (errors.isEmpty()) "Saved" else "Not saved - fix the fields marked in red"
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
                color = if (errors.isEmpty()) Color.Unspecified else ErrorRed,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        HorizontalDivider()
        Section("Phone setup (helper)")
        Text(
            "Many phones (Xiaomi/Redmi, Oppo, Vivo, Realme, Samsung) stop background apps even with the " +
                "notification showing. In the app's settings: allow Autostart, set Battery to " +
                "\"No restrictions\" / \"Unrestricted\", and lock CapSight in the recent-apps screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            modifier = Modifier.heightIn(min = MinTouch),
        ) { Text("Open CapSight app settings") }

        HorizontalDivider()
        Section("About")
        Text(stringResource(R.string.safety_statement), style = MaterialTheme.typography.bodyLarge)
        Text("Version ${versionName(context)} · camera contract v1", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
        supportingText = (error ?: help)?.let { t -> { Text(t) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun versionName(context: android.content.Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
} catch (e: android.content.pm.PackageManager.NameNotFoundException) {
    "?"
}

private const val RATE_STEPS = 20f
private const val PERCENT = 100
