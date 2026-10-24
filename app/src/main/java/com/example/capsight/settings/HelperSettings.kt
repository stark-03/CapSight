package com.example.capsight.settings

import com.example.capsight.ble.Calibration
import com.example.capsight.camera.CameraContract
import java.util.Locale

/**
 * The settings a helper edits on the Settings screen (FR-APP-04, FR-DIST-05, FR-VOICE-05), as
 * text drafts. [validate] is pure Kotlin so the rules are unit-tested.
 */
data class HelperSettingsDraft(
    val cameraBaseUrl: String,
    val capFovDeg: String,
    val userHeightCm: String,
    val cameraHeightM: String,
    val stopDistanceM: String,
    val warnDistanceM: String,
    val phraseCooldownS: String,
    val clearRepeatS: String,
    val speechRate: Float,
    val speechVolume: Float,
    val landmarksEnabled: Boolean,
    val landmarkAnnounceDbm: String,
    val landmarkRearmDbm: String,
    val landmarkRepeatS: String,
    val landmarkSayDistance: Boolean,
    val landmarkRssiAt1mDbm: String,
    val landmarkPathLoss: String,
    val volumeButtonControl: Boolean,
    val depthEnabled: Boolean,
    val depthThreshold: String,
) {
    /** Field name -> problem, empty when everything is valid. */
    fun validate(): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (CameraContract.endpoints(cameraBaseUrl) == null) {
            errors[CAMERA] = "Enter an address like http://192.168.4.1"
        }
        fun range(field: String, text: String, min: Double, max: Double, unit: String): Double? {
            val v = text.trim().replace(',', '.').toDoubleOrNull()
            if (v == null || v < min || v > max) {
                errors[field] = "Enter $min to $max $unit".trimEnd()
                return null
            }
            return v
        }
        range(FOV, capFovDeg, MIN_FOV, MAX_FOV, "degrees")
        range(HEIGHT, userHeightCm, MIN_HEIGHT_CM, MAX_HEIGHT_CM, "cm")
        range(CAMERA_HEIGHT, cameraHeightM, MIN_CAMERA_HEIGHT_M, MAX_CAMERA_HEIGHT_M, "m")
        val stop = range(STOP, stopDistanceM, MIN_STOP_M, MAX_STOP_M, "m")
        val warn = range(WARN, warnDistanceM, MIN_WARN_M, MAX_WARN_M, "m")
        if (stop != null && warn != null && warn <= stop) {
            errors[WARN] = "Must be more than the stop distance"
        }
        range(COOLDOWN, phraseCooldownS, MIN_COOLDOWN_S, MAX_COOLDOWN_S, "s")
        range(CLEAR_REPEAT, clearRepeatS, MIN_COOLDOWN_S, MAX_CLEAR_REPEAT_S, "s")
        val announce = range(ANNOUNCE, landmarkAnnounceDbm, MIN_DBM, MAX_DBM, "dBm")
        val rearm = range(REARM, landmarkRearmDbm, MIN_DBM, MAX_DBM, "dBm")
        if (announce != null && rearm != null && rearm >= announce) {
            errors[REARM] = "Must be below the announce level (more negative)"
        }
        range(LANDMARK_REPEAT, landmarkRepeatS, MIN_LANDMARK_REPEAT_S, MAX_LANDMARK_REPEAT_S, "s")
        range(RSSI_1M, landmarkRssiAt1mDbm, Calibration.MIN_DBM.toDouble(), Calibration.MAX_DBM.toDouble(), "dBm")
        range(PATH_LOSS, landmarkPathLoss, MIN_PATH_LOSS, MAX_PATH_LOSS, "")
        range(DEPTH_THRESHOLD, depthThreshold, MIN_DEPTH_THRESHOLD, MAX_DEPTH_THRESHOLD, "")
        return errors
    }

    companion object {
        const val CAMERA = "camera"
        const val FOV = "fov"
        const val HEIGHT = "height"
        const val CAMERA_HEIGHT = "cameraHeight"
        const val STOP = "stop"
        const val WARN = "warn"
        const val COOLDOWN = "cooldown"
        const val CLEAR_REPEAT = "clearRepeat"
        const val ANNOUNCE = "landmarkAnnounce"
        const val REARM = "landmarkRearm"
        const val LANDMARK_REPEAT = "landmarkRepeat"
        const val RSSI_1M = "landmarkRssiAt1m"
        const val PATH_LOSS = "landmarkPathLoss"
        const val DEPTH_THRESHOLD = "depthThreshold"

        // Plausible bounds: a typo must not silently scale every distance.
        const val MIN_FOV = 30.0
        const val MAX_FOV = 120.0
        const val MIN_HEIGHT_CM = 100.0
        const val MAX_HEIGHT_CM = 220.0
        const val MIN_CAMERA_HEIGHT_M = 0.5
        const val MAX_CAMERA_HEIGHT_M = 2.2
        const val MIN_STOP_M = 0.5
        const val MAX_STOP_M = 3.0
        const val MIN_WARN_M = 1.0
        const val MAX_WARN_M = 6.0
        const val MIN_COOLDOWN_S = 1.0
        const val MAX_COOLDOWN_S = 10.0
        const val MAX_CLEAR_REPEAT_S = 60.0
        // RSSI: -30 is touching the beacon, -100 is at the edge of hearing it.
        const val MIN_DBM = -100.0
        const val MAX_DBM = -30.0
        const val MIN_LANDMARK_REPEAT_S = 10.0
        const val MAX_LANDMARK_REPEAT_S = 600.0
        // Path-loss exponent: 1.5 = corridor wave-guiding, 4.0 = heavy walls / crowds.
        const val MIN_PATH_LOSS = 1.5
        const val MAX_PATH_LOSS = 4.0
        // Depth score: below 0.6 a plain corridor would read as near; above 1.5 only touching does.
        const val MIN_DEPTH_THRESHOLD = 0.6
        const val MAX_DEPTH_THRESHOLD = 1.5
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f

        fun from(
            cam: CameraSettings,
            nav: NavigationSettings,
            sp: SpeechSettings,
            lm: LandmarkSettings,
            ctl: ControlSettings,
        ) = HelperSettingsDraft(
            cameraBaseUrl = cam.cameraBaseUrl,
            capFovDeg = trim(nav.capFovDeg),
            userHeightCm = nav.userHeightCm.toString(),
            cameraHeightM = trim(nav.cameraHeightM),
            stopDistanceM = trim(nav.stopDistanceM),
            warnDistanceM = trim(nav.warnDistanceM),
            phraseCooldownS = trim(sp.phraseCooldownMs / MS_PER_S),
            clearRepeatS = trim(sp.clearRepeatMs / MS_PER_S),
            speechRate = sp.rate,
            speechVolume = sp.volume,
            landmarksEnabled = lm.enabled,
            landmarkAnnounceDbm = lm.announceDbm.toString(),
            landmarkRearmDbm = lm.rearmDbm.toString(),
            landmarkRepeatS = trim(lm.repeatMs / MS_PER_S),
            landmarkSayDistance = lm.sayDistance,
            landmarkRssiAt1mDbm = lm.rssiAt1mDbm.toString(),
            landmarkPathLoss = String.format(Locale.US, "%.1f", lm.pathLossExponent),
            volumeButtonControl = ctl.volumeButtonControl,
            depthEnabled = nav.depthEnabled,
            depthThreshold = String.format(Locale.US, "%.2f", nav.depthThreshold),
        )

        val DEFAULTS = from(CameraSettings(), NavigationSettings(), SpeechSettings(), LandmarkSettings(), ControlSettings())

        private const val MS_PER_S = 1_000.0

        /** 54.0 -> "54", 1.2 -> "1.2". */
        private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}
