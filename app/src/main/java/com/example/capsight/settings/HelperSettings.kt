package com.example.capsight.settings

import com.example.capsight.camera.CameraContract

/**
 * The settings a helper edits on the Settings screen (FR-APP-04, FR-DIST-05, FR-VOICE-05), as
 * text drafts. [validate] is pure Kotlin so the rules are unit-tested.
 */
data class HelperSettingsDraft(
    val cameraBaseUrl: String,
    val capFovDeg: String,
    val userHeightCm: String,
    val stopDistanceM: String,
    val warnDistanceM: String,
    val phraseCooldownS: String,
    val clearRepeatS: String,
    val speechRate: Float,
    val speechVolume: Float,
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
                errors[field] = "Enter $min to $max $unit"
                return null
            }
            return v
        }
        range(FOV, capFovDeg, MIN_FOV, MAX_FOV, "degrees")
        range(HEIGHT, userHeightCm, MIN_HEIGHT_CM, MAX_HEIGHT_CM, "cm")
        val stop = range(STOP, stopDistanceM, MIN_STOP_M, MAX_STOP_M, "m")
        val warn = range(WARN, warnDistanceM, MIN_WARN_M, MAX_WARN_M, "m")
        if (stop != null && warn != null && warn <= stop) {
            errors[WARN] = "Must be more than the stop distance"
        }
        range(COOLDOWN, phraseCooldownS, MIN_COOLDOWN_S, MAX_COOLDOWN_S, "s")
        range(CLEAR_REPEAT, clearRepeatS, MIN_COOLDOWN_S, MAX_CLEAR_REPEAT_S, "s")
        return errors
    }

    companion object {
        const val CAMERA = "camera"
        const val FOV = "fov"
        const val HEIGHT = "height"
        const val STOP = "stop"
        const val WARN = "warn"
        const val COOLDOWN = "cooldown"
        const val CLEAR_REPEAT = "clearRepeat"

        // Plausible bounds: a typo must not silently scale every distance.
        const val MIN_FOV = 30.0
        const val MAX_FOV = 120.0
        const val MIN_HEIGHT_CM = 100.0
        const val MAX_HEIGHT_CM = 220.0
        const val MIN_STOP_M = 0.5
        const val MAX_STOP_M = 3.0
        const val MIN_WARN_M = 1.0
        const val MAX_WARN_M = 6.0
        const val MIN_COOLDOWN_S = 1.0
        const val MAX_COOLDOWN_S = 10.0
        const val MAX_CLEAR_REPEAT_S = 60.0
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f

        fun from(cam: CameraSettings, nav: NavigationSettings, sp: SpeechSettings) = HelperSettingsDraft(
            cameraBaseUrl = cam.cameraBaseUrl,
            capFovDeg = trim(nav.capFovDeg),
            userHeightCm = nav.userHeightCm.toString(),
            stopDistanceM = trim(nav.stopDistanceM),
            warnDistanceM = trim(nav.warnDistanceM),
            phraseCooldownS = trim(sp.phraseCooldownMs / MS_PER_S),
            clearRepeatS = trim(sp.clearRepeatMs / MS_PER_S),
            speechRate = sp.rate,
            speechVolume = sp.volume,
        )

        val DEFAULTS = from(CameraSettings(), NavigationSettings(), SpeechSettings())

        private const val MS_PER_S = 1_000.0

        /** 54.0 -> "54", 1.2 -> "1.2". */
        private fun trim(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }
}
