package com.example.capsight.voice

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

    enum class Side(val word: String) { LEFT("left"), RIGHT("right") }

    /** P0: "Stop. Chair ahead." */
    fun stop(label: String) = "Stop. ${spoken(label)} ahead."

    /** P2: "Chair ahead. Move left." */
    fun avoid(label: String, side: Side) = "${spoken(label)} ahead. Move ${side.word}."

    /** COCO label as said aloud: "dining table" -> "Dining table" (Python str.capitalize). */
    fun spoken(label: String): String =
        label.lowercase().replaceFirstChar { it.uppercaseChar() }
}
