package com.example.capsight.voice

import com.example.capsight.ble.Landmark
import com.example.capsight.perception.Side

/**
 * Every spoken instruction, worded exactly as SRS §4.7 / CLAUDE.md §8 (FR-VOICE-04: short and the
 * same every time). Pure Kotlin; decide() in step 6 builds its messages from here.
 */
object Phrases {
    const val CAMERA_LOST = "Camera disconnected. Please stop."
    const val CAMERA_CONNECTED = "Camera connected."
    const val STAIRS = "Caution. Stairs ahead."
    const val PATH_BLOCKED = "Path blocked. Stop and turn."
    const val PATH_CLEAR = "Path clear."

    /** FR-APP-02: the app announces its own state. */
    const val NAV_STARTED = "Navigation started."
    const val NAV_STOPPED = "Navigation stopped."
    const val CONNECTING = "Connecting to camera."

    /** Landmarks-only mode (no camera, CLAUDE.md §5.1): said instead of [NAV_STARTED]. */
    const val NAV_STARTED_LANDMARKS_ONLY = "Navigation started. Camera off, landmarks only."

    /** Start refused: no camera and BLE landmarks switched off. */
    const val NOTHING_TO_GUIDE = "Camera off and landmarks off. Nothing to guide with."

    // ---- Volume-key control (FR-APP-06, CLAUDE.md §8.2). Hints only while "Volume-button control" is on. ----

    /** Started without the camera (landmarks only), with the hint for the camera gesture. */
    const val NAV_STARTED_NO_CAMERA = "Navigation started without camera. To use the camera, press volume up three times."

    /** Started with a camera, with the hint for the stop gesture. */
    const val NAV_STARTED_CAMERA = "Navigation started with camera. Press volume down twice to stop."

    /**
     * The camera was asked for (volume up x3) but is not allowed or could not be opened from here,
     * e.g. with the phone locked, or permanently refused (after two refusals Android no longer asks,
     * only the phone's settings can allow it): navigation runs (or keeps running) without it.
     */
    const val CAMERA_UNAVAILABLE =
        "Camera is not available. Navigating without camera. Unlock the phone and press volume up three times, " +
            "or ask a helper to allow the camera in Helper settings, Phone setup."

    /** Camera not available and landmarks switched off: nothing can start. */
    const val CAMERA_UNAVAILABLE_NOTHING = "Camera is not available and landmarks are switched off. Open CapSight to start."

    /** Volume up x2 while BLE landmarks are switched off in Settings. */
    const val LANDMARKS_SWITCHED_OFF = "Landmarks are switched off. To use the camera, press volume up three times."

    /** Added to a no-camera start when the BLE permissions are missing (no dialogs with the screen off). */
    const val LANDMARKS_NOT_ALLOWED = "Landmarks are not allowed yet. Open CapSight to allow location and nearby devices."

    const val NAV_STOPPED_HINT = "Navigation stopped. Press volume up twice to start."
    const val ALREADY_STOPPED = "Navigation is already stopped. Press volume up twice to start."
    const val ALREADY_NO_CAMERA = "Navigation is already running without camera."
    const val ALREADY_CAMERA = "Navigation is already running with camera."
    const val STATUS_STOPPED = "Navigation is stopped. Press volume up twice to start."

    /** "Repeat status" on screen while stopped and volume-button control is off (no gesture hint). */
    const val STATUS_STOPPED_PLAIN = "Navigation is stopped."

    /** A gesture could not be carried out (e.g. Android refused to start the service). */
    const val START_FAILED = "Could not start navigation. Open CapSight."

    // ---- Welcome and Help (spoken one line at a time, and shown as large text on screen) ----

    /**
     * The first-run welcome and the main screen's Help: what CapSight does, the safety rule, the
     * four volume gestures (or that they are off), that the camera is optional, and the screen layout.
     * Short sentences, so a warning can still be spoken between two lines.
     */
    fun help(volumeButtons: Boolean): List<String> = buildList {
        add("Welcome to CapSight. It warns you about obstacles and names landmarks such as doors and stairs.")
        add("CapSight supplements your white cane. Keep using your cane.")
        if (volumeButtons) {
            add("You can use the volume buttons, even with the screen off.")
            add("Volume up twice: start without camera. Landmarks only.")
            add("Volume up three times: start with the camera, for obstacle warnings.")
            add("Volume down twice: stop.")
            add("Volume down three times: hear the status.")
        } else {
            add("The volume buttons are switched off. A helper can turn them on in Helper settings.")
        }
        add("The camera is optional. Without it, CapSight still announces landmarks.")
        add("On the screen, the upper big button starts with the camera. The lower big button starts without camera.")
        add("While navigating, the big button in the middle of the screen stops.")
        add("Tap Help at any time to hear this again.")
    }

    /** Last help line on the help screen: where the close button is (it is fixed at the bottom). */
    const val HELP_CLOSE = "To close, tap Got it, the large button at the bottom of the screen."

    /** First run, safety statement screen (NFR-SAFE-01), for a user who cannot read it. */
    val SAFETY_INTRO: List<String> = listOf(
        "Before you start. CapSight supplements the white cane. It does not replace it.",
        "Keep using your cane at all times.",
        "To continue, tap I understand, the large button at the bottom of the screen.",
    )

    /** The start phrase (FR-APP-02), with the gesture hint while volume-button control is on. */
    fun navStarted(camera: Boolean, volumeHints: Boolean): String = when {
        camera && volumeHints -> NAV_STARTED_CAMERA
        camera -> NAV_STARTED
        volumeHints -> NAV_STARTED_NO_CAMERA
        else -> NAV_STARTED_LANDMARKS_ONLY
    }

    /** The stop phrase (FR-APP-02), with the start hint while volume-button control is on. */
    fun navStopped(volumeHints: Boolean): String = if (volumeHints) NAV_STOPPED_HINT else NAV_STOPPED

    /**
     * Volume down x3, "where am I": the running mode, then the nearest landmark heard now.
     * [withCamera] null = stopped (then only the start hint: no BLE scan runs while stopped).
     * [nearest] = the strongest beacon heard now and its distance step, worded like the BLE distance
     * updates (CLAUDE.md §4.6) when [sayDistance] is on; [landmarksListening] = the scanner is scanning
     * (false: landmarks off, permission missing, Bluetooth off).
     * "Navigating with camera. Room 304 door, about 3 metres." / "Navigating without camera. No landmark nearby."
     */
    fun status(withCamera: Boolean?, nearest: Pair<Landmark, Int>?, sayDistance: Boolean, landmarksListening: Boolean): String {
        if (withCamera == null) return STATUS_STOPPED
        val mode = if (withCamera) "Navigating with camera." else "Navigating without camera."
        val place = when {
            nearest != null -> if (sayDistance) nearest.first.closerPhrase(nearest.second) else nearest.first.phrase
            landmarksListening -> "No landmark nearby."
            else -> "Landmarks are not available."
        }
        return "$mode $place"
    }

    /** FR-NAV-05: on any internal failure, say "Stop" rather than go silent. */
    const val FAIL_SAFE = "Stop."

    /** P0: "Stop. Chair ahead." */
    fun stop(label: String) = "Stop. ${spoken(label)} ahead."

    /** P2: "Chair ahead. Move left." */
    fun avoid(label: String, side: Side) = "${spoken(label)} ahead. Move ${side.name.lowercase()}."

    // ---- BLE landmarks (CLAUDE.md §4.6) ----

    /** STAIRS beacon, P1. "nearby", not "ahead": a beacon gives distance, not direction. */
    const val STAIRS_NEARBY = "Caution. Stairs nearby."

    /** DOOR beacon: "Room 304 door nearby." / "Door nearby." */
    fun doorNearby(label: String) = if (label.isEmpty()) "Door nearby." else "$label door nearby."

    /** LIFT / WASHROOM / EXIT beacon: "Lift nearby." / "Lift nearby, Block A." */
    fun placeNearby(place: String, label: String) =
        if (label.isEmpty()) "${spoken(place)} nearby." else "${spoken(place)} nearby, $label."

    /** ROOM / OTHER beacon (label required): "Library nearby." */
    fun labelNearby(label: String) = "$label nearby."

    /** A rounded beacon distance step: "about 1 metre", "about 3 metres". */
    fun aboutMetres(step: Int) = if (step == 1) "about 1 metre" else "about $step metres"

    /** First announcement with distance: "Room 304 door nearby." -> "Room 304 door nearby, about 4 metres." */
    fun withDistance(phrase: String, step: Int) = "${phrase.removeSuffix(".")}, ${aboutMetres(step)}."

    /** Approach update: "Room 304 door, about 2 metres." / "Caution. Stairs, about 2 metres." */
    fun landmarkCloser(name: String, step: Int, caution: Boolean) =
        "${if (caution) "Caution. " else ""}$name, ${aboutMetres(step)}."

    /** Arrival at a beacon: "Room 304 door, you are here." */
    fun landmarkHere(name: String) = "$name, you are here."

    /** Arrival at a STAIRS beacon (P1). */
    const val STAIRS_HERE = "Caution. Stairs right in front of you."

    /** COCO label as said aloud: "dining table" -> "Dining table" (Python str.capitalize). */
    fun spoken(label: String): String =
        label.lowercase().replaceFirstChar { it.uppercaseChar() }
}
