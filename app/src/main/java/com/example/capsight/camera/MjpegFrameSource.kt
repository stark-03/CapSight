package com.example.capsight.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The ESP32-CAM (or tools/fake_esp32cam.py) over HTTP - contract v1, CLAUDE.md §4.
 *
 * Each attempt: wait for the Wi-Fi network (when [bindToWifi]), probe the landing
 * page on port 80, then read the port-81 stream until it fails. Retries every 500 ms
 * with 2 s timeouts, because the device can hold a dead client for ~5 s (§4.3).
 *
 * [bindToWifi] is false only when the phone itself is the hotspot (a laptop running
 * the fake camera joined the phone's hotspot); then there is no Wi-Fi client network
 * to bind to and the system route is correct.
 */
class MjpegFrameSource(
    private val context: Context,
    private val baseUrl: String,
    private val bindToWifi: Boolean,
) : FrameSource {

    override val label = "ESP32-CAM $baseUrl"

    override suspend fun run(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit) {
        val endpoints = CameraContract.endpoints(baseUrl)
        if (endpoints == null) {
            onStatus("Invalid camera address \"$baseUrl\"")
            awaitCancellation()
        }
        val cameraNetwork = if (bindToWifi) CameraNetwork(context).also { it.request() } else null
        try {
            while (true) {
                attempt(cameraNetwork, endpoints, onFrame, onStatus)
                delay(CameraContract.RETRY_INTERVAL_MS)
            }
        } finally {
            cameraNetwork?.release()
        }
    }

    private suspend fun attempt(
        cameraNetwork: CameraNetwork?,
        endpoints: CameraEndpoints,
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val network: Network? = if (cameraNetwork != null) {
            cameraNetwork.network.value ?: run {
                onStatus("Waiting for Wi-Fi - join BLENAV_CAM in phone Settings")
                withTimeoutOrNull(WIFI_WAIT_MS) { cameraNetwork.network.first { it != null } }
            } ?: return
        } else {
            null
        }
        try {
            onStatus("Checking ${endpoints.probeUrl}")
            if (!probe(network, endpoints.probeUrl, onStatus)) return
            onStatus("Opening ${endpoints.streamUrl}")
            stream(network, endpoints.streamUrl, onFrame, onStatus)
            onStatus("Stream ended by camera")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "camera connection failed: $e")
            onStatus("No camera: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    /** §4.1 health probe: HTML only, never the stream (the device serves one stream client). */
    private suspend fun probe(network: Network?, url: String, onStatus: (String) -> Unit): Boolean {
        val conn = open(network, url)
        val (code, body) = closingOnCancel(conn) {
            val code = conn.responseCode
            val input = if (code < HttpURLConnection.HTTP_BAD_REQUEST) conn.inputStream else conn.errorStream
            val body = input?.use { readAtMost(it, CameraContract.PROBE_MAX_BYTES) }.orEmpty()
            code to body
        }
        if (CameraContract.isCameraLandingPage(code, body)) return true
        onStatus("$url answered HTTP $code but is not a BLENAV_CAM camera")
        return false
    }

    private suspend fun stream(
        network: Network?,
        url: String,
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val conn = open(network, url)
        closingOnCancel(conn) {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("stream answered HTTP $code")
            // HttpURLConnection removes the chunked transfer encoding the device uses.
            val parser = MjpegParser(BufferedInputStream(conn.inputStream, STREAM_BUFFER_BYTES))
            var first = true
            while (true) {
                val jpeg = parser.nextFrame() ?: break
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bitmap == null) {
                    Log.w(TAG, "undecodable JPEG (${jpeg.size} bytes) skipped")
                    continue
                }
                if (first) {
                    onStatus("Streaming ${bitmap.width}x${bitmap.height} from $url")
                    first = false
                }
                onFrame(bitmap)
            }
        }
    }

    private fun open(network: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        val conn = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        conn.connectTimeout = CameraContract.CONNECT_TIMEOUT_MS
        conn.readTimeout = CameraContract.READ_TIMEOUT_MS
        conn.useCaches = false
        // Keep the device's few sockets free: no keep-alive pool holding one open.
        conn.setRequestProperty("Connection", "close")
        return conn
    }

    companion object {
        private const val WIFI_WAIT_MS = 1_000L
        private const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

private fun readAtMost(input: java.io.InputStream, max: Int): String {
    val buf = ByteArray(max)
    var off = 0
    while (off < max) {
        val n = input.read(buf, off, max - off)
        if (n < 0) break
        off += n
    }
    return String(buf, 0, off, Charsets.ISO_8859_1)
}

/**
 * Runs blocking socket I/O on [Dispatchers.IO] and disconnects [conn] when the
 * coroutine is cancelled - socket reads ignore thread interrupts, so closing the
 * connection is the only way to unblock them promptly.
 */
private suspend fun <T> closingOnCancel(conn: HttpURLConnection, block: () -> T): T = coroutineScope {
    val closer = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }
    try {
        runInterruptible(Dispatchers.IO) { block() }
    } finally {
        closer.cancel()
    }
}
