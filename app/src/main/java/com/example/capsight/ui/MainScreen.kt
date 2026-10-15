package com.example.capsight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.R
import com.example.capsight.camera.LinkState

private val StartGreen = Color(0xFF1B5E20)
private val StopRed = Color(0xFFB71C1C)
private val MinTouch = 56.dp

/**
 * The primary user's screen (FR-APP-01, NFR-USE-01): one full-screen Start/Stop control that
 * TalkBack reads as "Start navigation" / "Stop navigation", and a status line announced as it
 * changes. Settings and the debug view are small buttons for the sighted helper.
 */
@Composable
fun MainScreen(
    onSettings: () -> Unit,
    onDebug: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AppViewModel = viewModel(),
) {
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val speech by vm.speech.collectAsStateWithLifecycle()
    val start = rememberNavigationStarter(vm)

    val state = when {
        !running -> "Ready"
        status.link == LinkState.CONNECTED -> "Camera connected"
        status.link == LinkState.DISCONNECTED -> "Camera disconnected"
        else -> "Connecting to camera"
    }
    val label = if (running) "Stop navigation" else "Start navigation"

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "CapSight",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Button(
            onClick = if (running) vm::stopNavigation else start,
            colors = ButtonDefaults.buttonColors(containerColor = if (running) StopRed else StartGreen),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = label },
        ) {
            Text(label, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 48.sp)
        }
        // Announced by TalkBack whenever it changes; the spoken guidance itself comes from the service.
        Text(
            state,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (running && speech.lastSpoken.isNotEmpty()) {
            Text("Last said: ${speech.lastSpoken}", fontSize = 18.sp, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = MinTouch)) {
                Text("Settings")
            }
            OutlinedButton(onClick = onDebug, modifier = Modifier.weight(1f).heightIn(min = MinTouch)) {
                Text("Debug view")
            }
        }
    }
}

/** NFR-SAFE-01: shown on first run until the user confirms. */
@Composable
fun SafetyScreen(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Before you start", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.safety_statement), fontSize = 24.sp, textAlign = TextAlign.Center, lineHeight = 32.sp)
        Text(
            "CapSight warns about objects it recognises. It cannot see every hazard: glass, holes and " +
                "walls may be missed. Stop and check with your cane whenever you are unsure.",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
            Text("I understand", fontSize = 24.sp)
        }
    }
}
