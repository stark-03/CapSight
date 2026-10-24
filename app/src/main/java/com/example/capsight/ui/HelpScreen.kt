package com.example.capsight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.R
import com.example.capsight.ui.theme.OnYellow
import com.example.capsight.ui.theme.Yellow

/**
 * The spoken welcome (first run, after the safety statement) and the main screen's Help: the same
 * lines (Phrases.help) are spoken one by one and shown as large text. "Hear again" restarts the
 * speech; "Got it" (large, fixed at the bottom, as the last spoken line says) closes it. Leaving the
 * screen in any way stops the reading after the current line.
 */
@Composable
fun HelpScreen(firstRun: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val lines = remember { vm.helpLines }
    LaunchedEffect(Unit) { vm.readHelp() }
    DisposableEffect(Unit) { onDispose { vm.stopHelp() } }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (firstRun) R.string.welcome_title else R.string.help_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Yellow,
                modifier = Modifier.semantics { heading() },
            )
            // One Text per line, so TalkBack users can step through them one at a time.
            lines.forEach { line ->
                Text(line, fontSize = 22.sp, lineHeight = 30.sp)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(top = 16.dp),
        ) {
            SecondaryButton(label = stringResource(R.string.hear_again), modifier = Modifier.fillMaxWidth()) { vm.readHelp() }
            Button(
                onClick = onDone,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnYellow),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp),
            ) {
                Text(stringResource(R.string.got_it), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
