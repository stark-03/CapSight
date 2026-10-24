package com.example.capsight.ui

import com.example.capsight.camera.LinkState

/**
 * Which first-run step the app shows (pure, JVM-tested): the safety statement until accepted
 * (NFR-SAFE-01), then the spoken welcome once, then the main screen. [LOADING] while the settings
 * are still being read (nothing is shown for that fraction of a second).
 */
enum class AppStage {
    LOADING,
    SAFETY,
    WELCOME,
    MAIN,
    ;

    companion object {
        fun of(safetyAccepted: Boolean?, welcomeShown: Boolean?): AppStage = when {
            safetyAccepted == null -> LOADING
            !safetyAccepted -> SAFETY
            welcomeShown == null -> LOADING
            !welcomeShown -> WELCOME
            else -> MAIN
        }
    }
}

/** What the run state is, as the main screen and its buttons see it. */
enum class MainMode { STOPPED, WITH_CAMERA, WITHOUT_CAMERA }

/**
 * The main screen's status area (pure, JVM-tested). [mode] is the plain-words headline, a polite
 * live region (TalkBack reads it when it changes); [detail] is one supporting line (camera link,
 * why landmarks may be silent, or the start hint), or null.
 */
data class MainStatus(val mainMode: MainMode, val mode: String, val detail: String?) {
    companion object {
        const val STOPPED = "Stopped"
        const val WITH_CAMERA = "Navigating with camera"
        const val WITHOUT_CAMERA = "Navigating without camera - landmarks only"
        const val CAMERA_NOT_AVAILABLE = "Camera not available, so landmarks only."
        const val START_HINT = "Press volume up twice to start without camera, three times with camera."
        const val START_HINT_NO_KEYS = "Choose a start button below."
        const val CAMERA_CONNECTED = "Camera connected"
        const val CAMERA_CONNECTING = "Connecting to camera"
        const val CAMERA_DISCONNECTED = "Camera disconnected - please stop"

        /**
         * [landmarksOnly] / [cameraFallback] describe the running session (ignored while stopped);
         * [link] is the camera link; [landmarkStatus] the BLE scanner line ("BLE: scanning, 2 beacons",
         * "BLE: permission denied"); [volumeHints] = volume-button control is on.
         */
        fun build(
            running: Boolean,
            landmarksOnly: Boolean,
            cameraFallback: Boolean,
            link: LinkState,
            landmarkStatus: String,
            volumeHints: Boolean,
        ): MainStatus = when {
            !running -> MainStatus(MainMode.STOPPED, STOPPED, if (volumeHints) START_HINT else START_HINT_NO_KEYS)
            landmarksOnly -> MainStatus(
                MainMode.WITHOUT_CAMERA,
                WITHOUT_CAMERA,
                listOfNotNull(CAMERA_NOT_AVAILABLE.takeIf { cameraFallback }, landmarkLine(landmarkStatus)).joinToString(" "),
            )
            else -> MainStatus(
                MainMode.WITH_CAMERA,
                WITH_CAMERA,
                when (link) {
                    LinkState.CONNECTED -> CAMERA_CONNECTED
                    LinkState.DISCONNECTED -> CAMERA_DISCONNECTED
                    LinkState.CONNECTING -> CAMERA_CONNECTING
                },
            )
        }

        /** "BLE: scanning, 2 beacons" -> "Landmarks: scanning, 2 beacons." */
        fun landmarkLine(status: String): String {
            val body = status.removePrefix("BLE:").trim().ifEmpty { "not running" }
            return "Landmarks: ${body.removeSuffix(".")}."
        }
    }
}
