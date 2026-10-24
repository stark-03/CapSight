package com.example.capsight.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capsight.R
import com.example.capsight.settings.StartWish
import com.example.capsight.ui.theme.OnYellow
import com.example.capsight.ui.theme.Outline
import com.example.capsight.ui.theme.OutlineMuted
import com.example.capsight.ui.theme.StopRed
import com.example.capsight.ui.theme.Surface
import com.example.capsight.ui.theme.SurfaceHigh
import com.example.capsight.ui.theme.TextColor
import com.example.capsight.ui.theme.TextMuted
import com.example.capsight.ui.theme.Yellow

/** Minimum size of every control the blind user may touch (CLAUDE.md §5: >= 72 dp). */
val UserTouchMin = 72.dp

/** Space between big controls, so a slightly-off tap does not hit the neighbour. */
private val BigGap = 24.dp

/**
 * The blind user's screen (FR-APP-01, NFR-USE-01). Top to bottom, which is also TalkBack's order:
 * - status area: the mode in plain words (a polite live region and a heading), one supporting line,
 *   and the last spoken message;
 * - stopped: two huge buttons, "Start with camera" above "Start without camera" (each about a quarter
 *   of the screen); running: one huge "Stop navigation" (about half the screen), plus "Use the camera
 *   too" while running without camera;
 * - "Help" and "Repeat status" (72 dp+);
 * - a small "Helper settings" button for the sighted helper.
 * Buttons behave like the volume gestures (same start flow, vibrations and phrases). The column
 * scrolls, so nothing is clipped at a large system font size.
 */
@Composable
fun MainScreen(
    onHelp: () -> Unit,
    onHelper: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AppViewModel = viewModel(),
) {
    val running by vm.running.collectAsStateWithLifecycle()
    val link by vm.status.collectAsStateWithLifecycle()
    val speech by vm.speech.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val landmarks by vm.landmarks.collectAsStateWithLifecycle()
    val controls by vm.controlSettings.collectAsStateWithLifecycle()
    val start = rememberNavigationStarter(vm)

    val status = MainStatus.build(
        running = running,
        landmarksOnly = session?.landmarksOnly == true,
        cameraFallback = session?.cameraFallback == true,
        link = link.link,
        landmarkStatus = landmarks.status,
        volumeHints = controls?.volumeButtonControl ?: true,
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusArea(
                status = status,
                lastSaid = speech.lastSpoken,
                lastLandmark = landmarks.lastSpoken.takeIf { running && status.mainMode == MainMode.WITHOUT_CAMERA },
            )
            when (status.mainMode) {
                MainMode.STOPPED -> {
                    BigButton(
                        label = stringResource(R.string.start_with_camera),
                        detail = stringResource(R.string.start_with_camera_detail),
                        container = Yellow,
                        content = OnYellow,
                        border = null,
                        minHeight = max(160.dp, screenHeight * 0.27f),
                    ) {
                        vm.confirmStartPressed()
                        start(StartWish.CAMERA)
                    }
                    BigButton(
                        label = stringResource(R.string.start_without_camera),
                        detail = stringResource(R.string.start_without_camera_detail),
                        container = SurfaceHigh,
                        content = TextColor,
                        border = BorderStroke(3.dp, Outline),
                        minHeight = max(160.dp, screenHeight * 0.27f),
                        modifier = Modifier.padding(top = BigGap - 16.dp),
                    ) {
                        vm.confirmStartPressed()
                        start(StartWish.NO_CAMERA)
                    }
                }
                MainMode.WITH_CAMERA, MainMode.WITHOUT_CAMERA -> {
                    BigButton(
                        label = stringResource(R.string.stop_navigation),
                        detail = null,
                        container = StopRed,
                        content = TextColor,
                        border = BorderStroke(3.dp, Outline),
                        minHeight = max(200.dp, screenHeight * 0.45f),
                    ) { vm.stopFromScreen() }
                    if (status.mainMode == MainMode.WITHOUT_CAMERA) {
                        SecondaryButton(
                            label = stringResource(R.string.switch_to_camera),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = BigGap - 16.dp),
                        ) {
                            vm.confirmStartPressed()
                            start(StartWish.CAMERA)
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(BigGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BigGap - 16.dp),
            ) {
                val helpDescription = stringResource(R.string.help_description)
                SecondaryButton(
                    label = stringResource(R.string.help),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = helpDescription },
                    onClick = onHelp,
                )
                val statusDescription = stringResource(R.string.repeat_status_description)
                SecondaryButton(
                    label = stringResource(R.string.repeat_status),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = statusDescription },
                    onClick = vm::repeatStatus,
                )
            }
            val helperDescription = stringResource(R.string.helper_settings_description)
            OutlinedButton(
                onClick = onHelper,
                border = BorderStroke(1.dp, OutlineMuted),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = helperDescription },
            ) {
                Text(stringResource(R.string.helper_settings), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Mode headline (30 sp, yellow, heading, polite live region: TalkBack reads it when it changes),
 * one supporting line and the last spoken phrases. Only the headline is a live region: the app
 * already speaks every message itself, so reading them again would talk over it.
 */
@Composable
private fun StatusArea(status: MainStatus, lastSaid: String, lastLandmark: String?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(20.dp))
            .border(2.dp, Yellow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Text(
            status.mode,
            color = Yellow,
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        status.detail?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = TextColor, fontSize = 20.sp, lineHeight = 28.sp)
        }
        if (!lastLandmark.isNullOrEmpty() && lastLandmark != lastSaid) {
            Text(stringResource(R.string.last_landmark, lastLandmark), color = TextMuted, fontSize = 20.sp, lineHeight = 28.sp)
        }
        if (lastSaid.isNotEmpty()) {
            Text(stringResource(R.string.last_said, lastSaid), color = TextMuted, fontSize = 20.sp, lineHeight = 28.sp)
        }
    }
}

/** A huge, high-contrast button: bold label (32 sp) and an optional explanation (22 sp). */
@Composable
private fun BigButton(
    label: String,
    detail: String?,
    container: Color,
    content: Color,
    border: BorderStroke?,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = border,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (detail != null) {
                Text(detail, fontSize = 22.sp, lineHeight = 28.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A 72 dp+ outlined button with a white border and bold 22 sp text. */
@Composable
fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(2.dp, Outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceHigh, contentColor = TextColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = UserTouchMin),
    ) {
        Text(label, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Status line for landmarks-only mode (CLAUDE.md §5.1); used by the debug view's banner. */
fun landmarksOnlyStatus(cameraFallback: Boolean) =
    if (cameraFallback) "Landmarks only - camera off (camera not available)" else "Landmarks only - camera off"

/**
 * NFR-SAFE-01: shown on first run until the user confirms; also spoken (Phrases.SAFETY_INTRO) for a
 * user who cannot read it. The confirm button is large and at the bottom, as the speech says.
 */
@Composable
fun SafetyScreen(onAccept: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                stringResource(R.string.safety_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Yellow,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.safety_statement),
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.safety_limits), fontSize = 22.sp, lineHeight = 30.sp)
        }
        Button(
            onClick = onAccept,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = OnYellow),
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        ) {
            Text(stringResource(R.string.safety_accept), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
        }
    }
}
