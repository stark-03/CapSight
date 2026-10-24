package com.example.capsight.settings

/**
 * What a start asks for: [SAVED] = the source chosen in Settings (Start button); [NO_CAMERA] /
 * [CAMERA] = the volume-key gestures' explicit choice (CLAUDE.md §8.2), for this session only.
 */
enum class StartWish { SAVED, NO_CAMERA, CAMERA }

/**
 * What one navigation session runs with (CLAUDE.md §5.1): the frame source actually used, or why
 * nothing can start. Pure Kotlin; permission state is passed in.
 */
data class StartPlan(
    /** The effective source for this session; null = cannot start. */
    val source: SourceKind?,
    /** True when the phone camera was selected but its permission is missing: landmarks only, this session only. */
    val cameraFallback: Boolean = false,
) {
    val canStart: Boolean get() = source != null
    val landmarksOnly: Boolean get() = source == SourceKind.NONE

    /** The phone camera runs: the service adds the camera foreground-service type. */
    val usesPhoneCamera: Boolean get() = source == SourceKind.PHONE_CAMERA

    companion object {
        /** Shown when there is nothing to guide with (no camera, landmarks switched off). */
        const val NOTHING_TO_GUIDE = "Camera is off and BLE landmarks are switched off - nothing to guide with"

        /** Shown (and the start phrase says it) when the camera permission was refused. */
        const val CAMERA_REFUSED = "No camera permission - navigating with BLE landmarks only"

        /**
         * The effective mode. Rules (CLAUDE.md §5.1):
         * - PHONE_CAMERA with the camera permission -> PHONE_CAMERA.
         * - PHONE_CAMERA without it -> NONE for this session (the saved setting is not changed), if
         *   landmarks are on; otherwise nothing to guide with.
         * - ESP32 / VIDEO_FILE -> themselves (they need no camera permission).
         * - NONE -> NONE if landmarks are on; otherwise nothing to guide with.
         */
        fun decide(selected: SourceKind, cameraPermission: Boolean, landmarksEnabled: Boolean): StartPlan =
            when (selected) {
                SourceKind.ESP32, SourceKind.VIDEO_FILE -> StartPlan(selected)
                SourceKind.PHONE_CAMERA -> when {
                    cameraPermission -> StartPlan(SourceKind.PHONE_CAMERA)
                    landmarksEnabled -> StartPlan(SourceKind.NONE, cameraFallback = true)
                    else -> StartPlan(null)
                }
                SourceKind.NONE -> if (landmarksEnabled) StartPlan(SourceKind.NONE) else StartPlan(null)
            }

        /**
         * The camera a [StartWish.CAMERA] start uses: the selected source if it is a camera (the cap,
         * the phone camera or a video file), otherwise - "No camera" chosen in Settings - the phone camera.
         */
        fun cameraFor(selected: SourceKind): SourceKind = if (selected.hasCamera) selected else SourceKind.PHONE_CAMERA

        /**
         * The effective mode for [wish] (CLAUDE.md §5.1, §8.2): [StartWish.SAVED] = [decide];
         * [StartWish.NO_CAMERA] = landmarks only (nothing if landmarks are off); [StartWish.CAMERA] =
         * [decide] for [cameraFor] the selected source, so a missing camera permission falls back to
         * landmarks only for this session ([cameraFallback]), the saved source never changes.
         */
        fun decide(wish: StartWish, selected: SourceKind, cameraPermission: Boolean, landmarksEnabled: Boolean): StartPlan =
            when (wish) {
                StartWish.SAVED -> decide(selected, cameraPermission, landmarksEnabled)
                StartWish.NO_CAMERA -> decide(SourceKind.NONE, cameraPermission, landmarksEnabled)
                StartWish.CAMERA -> decide(cameraFor(selected), cameraPermission, landmarksEnabled)
            }
    }
}
