package com.example.capsight.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.ui.debug.DebugScreen

private enum class Screen { MAIN, SETTINGS, DEBUG }

/** First-run safety statement, then the main screen with Settings and Debug behind it (FR-APP-01/04/05). */
@Composable
fun AppRoot(modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val accepted by vm.safetyAccepted.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    when (accepted) {
        null -> Unit // settings still loading
        false -> SafetyScreen(onAccept = { vm.acceptSafety() }, modifier = modifier)
        true -> {
            BackHandler(enabled = screen != Screen.MAIN) { screen = Screen.MAIN }
            when (screen) {
                Screen.MAIN -> MainScreen(onSettings = { screen = Screen.SETTINGS }, onDebug = { screen = Screen.DEBUG }, modifier = modifier, vm = vm)
                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.MAIN }, modifier = modifier, vm = vm)
                Screen.DEBUG -> DebugScreen(onBack = { screen = Screen.MAIN }, modifier = modifier, vm = vm)
            }
        }
    }
}
