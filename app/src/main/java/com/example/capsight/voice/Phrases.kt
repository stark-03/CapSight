package com.example.capsight.voice

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

    /** FR-NAV-05: on any internal failure, say "Stop" rather than go silent. */
    const val FAIL_SAFE = "Stop."

    /** P0: "Stop. Chair ahead." */
    fun stop(label: String) = "Stop. ${spoken(label)} ahead."

    /** P2: "Chair ahead. Move left." */
    fun avoid(label: String, side: Side) = "${spoken(label)} ahead. Move ${side.name.lowercase()}."

    /** COCO label as said aloud: "dining table" -> "Dining table" (Python str.capitalize). */
    fun spoken(label: String): String =
        label.lowercase().replaceFirstChar { it.uppercaseChar() }
}
