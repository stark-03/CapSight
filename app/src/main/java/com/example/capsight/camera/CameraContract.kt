package com.example.capsight.camera

import java.net.URI
import java.net.URISyntaxException

/** Where to probe and where to stream, derived from the configurable base URL. */
data class CameraEndpoints(val probeUrl: String, val streamUrl: String)

/**
 * Device contract v1 (CLAUDE.md §4). Values copied from esp32_cam_ap.ino and
 * mirrored by tools/fake_esp32cam.py - change all three together.
 */
object CameraContract {
    const val DEFAULT_BASE_URL = "http://192.168.4.1"
    const val HTTP_PORT = 80
    const val STREAM_PORT = 81
    const val STREAM_PATH = "/stream"

    /** The landing page title; HTTP 200 + this text = we are on the camera's network. */
    const val LANDING_MARKER = "BLENAV_CAM"

    /** §4.3: short timeouts and fast retries; the device may hold a dead client ~5 s. */
    const val CONNECT_TIMEOUT_MS = 2_000
    const val READ_TIMEOUT_MS = 2_000
    const val RETRY_INTERVAL_MS = 500L

    /** The landing page is ~400 bytes; never read more (and never the embedded stream). */
    const val PROBE_MAX_BYTES = 4_096

    /**
     * Probe and stream URLs for a base URL such as `http://192.168.4.1`, `192.168.4.1`
     * or `http://10.42.0.1:8080`. The stream is on port 81 when the base uses port 80,
     * otherwise on base port + 1, which lets the fake camera run on unprivileged ports
     * (`--port 8080` serves the stream on 8081). Returns null for anything that is not
     * a plain http host address.
     */
    fun endpoints(baseUrl: String): CameraEndpoints? {
        val text = baseUrl.trim().let { if ("://" in it) it else "http://$it" }
        val uri = try {
            URI(text)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        if (uri.path.orEmpty().trimEnd('/').isNotEmpty() || uri.query != null || uri.userInfo != null) {
            return null
        }
        val httpPort = if (uri.port == -1) HTTP_PORT else uri.port
        if (httpPort !in 1..65534) return null
        val streamPort = if (httpPort == HTTP_PORT) STREAM_PORT else httpPort + 1
        val probe = if (httpPort == HTTP_PORT) "http://$host/" else "http://$host:$httpPort/"
        return CameraEndpoints(probeUrl = probe, streamUrl = "http://$host:$streamPort$STREAM_PATH")
    }

    /** §4.1 health probe verdict. */
    fun isCameraLandingPage(httpCode: Int, body: String): Boolean =
        httpCode == 200 && LANDING_MARKER in body
}
