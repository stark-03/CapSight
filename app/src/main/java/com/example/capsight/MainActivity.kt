package com.example.capsight

import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.capsight.control.VolumeGesture
import com.example.capsight.service.NavigationService
import com.example.capsight.ui.AppRoot
import com.example.capsight.ui.AppViewModel
import com.example.capsight.ui.theme.CapSightTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    /** FR-APP-06: the volume-key gestures while this screen is shown and the service's MediaSession is not taking them. */
    private val volumeGesture by lazy { VolumeGesture(this) { vm.onGesture(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Volume keys in the app change the stream speech plays on.
        volumeControlStream = VolumeGesture.SPEECH_STREAM
        // Light status / navigation bar icons on the always-dark high-contrast theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CapSightTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(Modifier.padding(innerPadding), vm = vm)
                }
            }
        }
        // CLAUDE.md §8.2: once CapSight has been opened (safety statement accepted) and while
        // "Volume-button control" is on, the service waits in standby so the volume keys can start
        // navigation from the pocket. Started from here because Android only allows it while visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.safetyAccepted, vm.controlSettings) { accepted, controls ->
                    accepted == true && controls?.volumeButtonControl == true
                }.distinctUntilChanged().collect { on ->
                    if (on && !NavigationService.standby(this@MainActivity)) Log.w(TAG, "standby could not start")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.setInFront(true)
    }

    override fun onPause() {
        vm.setInFront(false)
        super.onPause()
    }

    /**
     * Watches the volume keys for the gestures without consuming them, so single presses change the
     * volume as usual. While the service's MediaSession takes the keys (navigating, or standby with
     * the screen locked), it sees them through super.onKeyDown instead - never both, or every press
     * would count twice. Auto-repeats are fed too: holding a key is never a gesture.
     * (No Compose view consumes volume keys, so they always reach onKeyDown.)
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = VolumeGesture.keyOf(keyCode)
        if (key != null) {
            try {
                if (vm.activityWatchesVolumeKeys) {
                    volumeGesture.onPress(key, adjust = false, repeat = event.repeatCount > 0)
                }
                // With the session active, send the key to it even while speech plays (a suggested
                // stream that is playing would otherwise get the press directly).
                volumeControlStream = if (vm.volumeKeySessionActive) AudioManager.USE_DEFAULT_STREAM_TYPE else VolumeGesture.SPEECH_STREAM
            } catch (e: RuntimeException) {
                Log.e(TAG, "volume key handling failed", e)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        volumeGesture.release()
        super.onDestroy()
    }
}
