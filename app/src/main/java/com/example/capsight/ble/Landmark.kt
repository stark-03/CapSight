package com.example.capsight.ble

import com.example.capsight.voice.Phrases
import com.example.capsight.voice.Priority

/** What a BLE landmark beacon marks (CLAUDE.md §4.6). Anything unknown is [OTHER]. */
enum class LandmarkType { DOOR, STAIRS, LIFT, WASHROOM, EXIT, ROOM, OTHER }

/**
 * One landmark, decoded from a beacon's advertised name `CS:<TYPE>:<LABEL>`
 * (firmware/ble_landmark/ble_landmark.ino). Pure Kotlin.
 */
data class Landmark(val type: LandmarkType, val label: String) {

    /** Exactly the §4.6 table: short and the same every time (FR-VOICE-04). */
    val phrase: String
        get() = when (type) {
            LandmarkType.DOOR -> Phrases.doorNearby(label)
            LandmarkType.STAIRS -> Phrases.STAIRS_NEARBY
            LandmarkType.LIFT, LandmarkType.WASHROOM, LandmarkType.EXIT -> Phrases.placeNearby(type.name, label)
            LandmarkType.ROOM, LandmarkType.OTHER -> Phrases.labelNearby(label)
        }

    /**
     * What the landmark is called in distance updates: "Room 304 door", "Door", "Stairs", "Lift",
     * "Lift, Block A", "Library".
     */
    val name: String
        get() = when (type) {
            LandmarkType.DOOR -> if (label.isEmpty()) "Door" else "$label door"
            LandmarkType.STAIRS -> "Stairs"
            LandmarkType.LIFT, LandmarkType.WASHROOM, LandmarkType.EXIT ->
                if (label.isEmpty()) Phrases.spoken(type.name) else "${Phrases.spoken(type.name)}, $label"
            LandmarkType.ROOM, LandmarkType.OTHER -> label
        }

    /** First announcement with the distance step: "Room 304 door nearby, about 4 metres." */
    fun phraseAt(step: Int): String = Phrases.withDistance(phrase, step)

    /** Approach update when the distance step drops: "Room 304 door, about 2 metres." */
    fun closerPhrase(step: Int): String = Phrases.landmarkCloser(name, step, caution = type == LandmarkType.STAIRS)

    /** The step reached 1 m: "Room 304 door, you are here." / "Caution. Stairs right in front of you." */
    val herePhrase: String
        get() = if (type == LandmarkType.STAIRS) Phrases.STAIRS_HERE else Phrases.landmarkHere(name)

    /**
     * Stairs are a fall risk: P1, the same level as stairs seen by the camera. Everything else is
     * [Priority.INFO], below every navigation instruction, so it never interrupts or replaces
     * P0-P4 speech in the speaker's single slot.
     */
    val priority: Int
        get() = if (type == LandmarkType.STAIRS) Priority.STAIRS else Priority.INFO

    companion object {
        const val PREFIX = "CS:"

        /**
         * 128-bit service UUID every CapSight beacon advertises (CLAUDE.md §4.6). The scanner filters
         * on it, because Android pauses unfiltered scans while the screen is off.
         */
        const val SERVICE_UUID = "6c616e64-6d61-726b-4353-000000000001"

        /** Kept at the original 26 characters; the name now travels in the scan response (max 29). */
        const val MAX_NAME_LENGTH = 26

        /**
         * Decodes an advertised name, or null if it is not a CapSight landmark: no `CS:` prefix,
         * longer than [MAX_NAME_LENGTH], or a ROOM / OTHER without a label (nothing to say).
         * TYPE is matched ignoring case; an unknown TYPE is [LandmarkType.OTHER].
         */
        fun parse(name: String?): Landmark? {
            if (name == null || !name.startsWith(PREFIX) || name.length > MAX_NAME_LENGTH) return null
            val rest = name.substring(PREFIX.length)
            val typeText = rest.substringBefore(':')
            val label = if (':' in rest) rest.substringAfter(':').trim() else ""
            val type = LandmarkType.entries.firstOrNull { it.name.equals(typeText.trim(), ignoreCase = true) }
                ?: LandmarkType.OTHER
            if ((type == LandmarkType.ROOM || type == LandmarkType.OTHER) && label.isEmpty()) return null
            return Landmark(type, label)
        }
    }
}
