package com.example.capsight.camera

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the debug screen (and later the guidance loop) knows about the camera link. */
data class ReceiverStatus(
    val running: Boolean = false,
    val sourceLabel: String = "",
    val link: LinkState = LinkState.CONNECTING,
    val detail: String = "",
    val fps: Float = 0f,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameAgeMs: Long? = null,
    val framesReceived: Long = 0,
    val disconnects: Int = 0,
)

/**
 * Runs one [FrameSource] and publishes only its newest frame (FR-RX-02): [frame] is
 * a StateFlow, so a slow consumer simply sees the latest one - nothing queues.
 * A watchdog applies the 2 s rule (FR-RX-03) whatever the source is, and a source
 * that throws is restarted after [CameraContract.RETRY_INTERVAL_MS].
 *
 * Lifecycle-agnostic: whoever calls [run] owns it. That is the debug screen's
 * ViewModel for now, and NavigationService from build step 7.
 */
class FrameReceiver(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    private val _status = MutableStateFlow(ReceiverStatus())
    val status: StateFlow<ReceiverStatus> = _status.asStateFlow()

    /** Receives from [source] until cancelled. */
    suspend fun run(source: FrameSource, lostAfterMs: Long) {
        val lock = Any()
        val monitor = LinkMonitor(lostAfterMs, clock())
        val fps = FpsMeter()
        var seq = 0L
        var detail = "Starting"
        var width = 0
        var height = 0
        var lastLink = monitor.state
        Log.i(TAG, "receiver start: ${source.label}")

        fun publish() {
            synchronized(lock) {
                val now = clock()
                val link = monitor.update(now)
                if (link != lastLink) {
                    Log.i(TAG, "camera link $lastLink -> $link (${source.label})")
                    lastLink = link
                }
                _status.value = ReceiverStatus(
                    running = true,
                    sourceLabel = source.label,
                    link = link,
                    detail = detail,
                    fps = fps.fps(now),
                    frameWidth = width,
                    frameHeight = height,
                    frameAgeMs = monitor.frameAgeMs(now),
                    framesReceived = seq,
                    disconnects = monitor.disconnects,
                )
            }
        }

        val onFrame: (Bitmap) -> Unit = { bitmap ->
            val f = synchronized(lock) {
                val now = clock()
                seq++
                monitor.onFrame(now)
                fps.onFrame(now)
                width = bitmap.width
                height = bitmap.height
                Frame(bitmap, seq, now)
            }
            _frame.value = f
        }
        val onStatus: (String) -> Unit = { message ->
            synchronized(lock) {
                if (message != detail) {
                    detail = message
                    Log.i(TAG, "${source.label}: $message")
                }
            }
        }

        try {
            coroutineScope {
                launch {
                    while (true) {
                        publish()
                        delay(STATUS_PERIOD_MS)
                    }
                }
                launch(Dispatchers.IO) {
                    while (true) {
                        try {
                            source.run(onFrame, onStatus)
                            onStatus("Source stopped")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "${source.label} failed", e)
                            onStatus("Error: ${e.message ?: e.javaClass.simpleName}")
                        }
                        delay(CameraContract.RETRY_INTERVAL_MS)
                    }
                }
            }
        } finally {
            Log.i(TAG, "receiver stop: ${source.label}")
            _frame.value = null
            _status.value = ReceiverStatus()
        }
    }

    companion object {
        /** Debug-screen refresh; also how quickly a lost link is noticed past 2 s. */
        private const val STATUS_PERIOD_MS = 100L
    }
}
