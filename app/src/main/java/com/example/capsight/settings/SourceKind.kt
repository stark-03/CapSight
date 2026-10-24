package com.example.capsight.settings

/**
 * Where frames come from (CLAUDE.md §1A). Stays in the debug settings in the final app.
 *
 * [NONE] = no camera at all, BLE landmarks only (CLAUDE.md §5.1): using the camera is the user's
 * choice, and landmarks still work without it.
 */
enum class SourceKind {
    ESP32,
    PHONE_CAMERA,
    VIDEO_FILE,
    NONE,
    ;

    /** True when this source delivers frames (obstacle guidance runs). */
    val hasCamera: Boolean get() = this != NONE

    /** The value written to settings. Never rename an entry: stored values would stop matching. */
    val storedName: String get() = name

    companion object {
        /** Reads a stored value; null or unknown (e.g. from a newer/older app version) -> [default]. */
        fun fromStored(stored: String?, default: SourceKind = PHONE_CAMERA): SourceKind =
            entries.firstOrNull { it.name == stored } ?: default
    }
}
