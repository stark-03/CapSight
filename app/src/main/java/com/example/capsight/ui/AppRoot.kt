package com.example.capsight.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.ui.debug.DebugScreen

private enum class Screen { MAIN, HELP, SETTINGS, DEBUG }

/**
 * First run: the safety statement (NFR-SAFE-01), then the spoken welcome once. Then the blind
 * user's main screen; Help replays the welcome; the helper area (Settings, and the debug view
 * reached from Settings) sits behind a small "Helper settings" button (FR-APP-01/04/05).
 */
@Composable
fun AppRoot(modifier: Modifier = Modifier, vm: AppViewModel = viewModel()) {
    val accepted by vm.safetyAccepted.collectAsStateWithLifecycle()
    val welcomed by vm.welcomeShown.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    val stage = AppStage.of(accepted, welcomed)
    if (stage == AppStage.WELCOME || stage == AppStage.MAIN) {
        // FR-APP-06: a volume-key start that still needs permissions runs the normal start flow.
        // Active as soon as the safety statement is accepted (standby starts then, MainActivity).
        val start by rememberUpdatedState(rememberNavigationStarter(vm, byVolumeKeys = true))
        LaunchedEffect(vm) { vm.startRequests.collect { wish -> start(wish) } }
    }
    when (stage) {
        AppStage.LOADING -> Unit // settings still loading (a fraction of a second)
        AppStage.SAFETY -> {
            LaunchedEffect(Unit) { vm.readSafety() }
            SafetyScreen(onAccept = { vm.acceptSafety() }, modifier = modifier)
        }
        AppStage.WELCOME -> HelpScreen(firstRun = true, onDone = { vm.finishWelcome() }, modifier = modifier, vm = vm)
        AppStage.MAIN -> {
            BackHandler(enabled = screen != Screen.MAIN) {
                screen = if (screen == Screen.DEBUG) Screen.SETTINGS else Screen.MAIN
            }
            when (screen) {
                Screen.MAIN -> MainScreen(
                    onHelp = { screen = Screen.HELP },
                    onHelper = { screen = Screen.SETTINGS },
                    modifier = modifier,
                    vm = vm,
                )
                Screen.HELP -> HelpScreen(
                    firstRun = false,
                    onDone = { screen = Screen.MAIN },
                    modifier = modifier,
                    vm = vm,
                )
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.MAIN },
                    onDebug = { screen = Screen.DEBUG },
                    modifier = modifier,
                    vm = vm,
                )
                Screen.DEBUG -> DebugScreen(onBack = { screen = Screen.SETTINGS }, modifier = modifier, vm = vm)
            }
        }
    }
}
