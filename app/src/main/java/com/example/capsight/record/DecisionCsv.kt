package com.example.capsight.record

import com.example.capsight.perception.RelativeObstacle
import java.util.Locale
import kotlin.math.abs

/** What produced a log line. CONTROL = a volume-key gesture (start / stop / status, FR-APP-06). */
enum class EntryKind { DECISION, STATUS, LANDMARK, CONTROL }

/**
 * One spoken instruction or announcement, for the evaluation log (FR-APP-07). Never holds image
 * data: frames are processed in memory only (CLAUDE.md §10, Privacy).
 */
data class DecisionEntry(
    /** SystemClock.elapsedRealtime() when it was spoken. */
    val elapsedMs: Long,
    val kind: EntryKind,
    val priority: Int,
    val phrase: String,
    val reason: String,
    /** Frame source label ("Phone camera", "ESP32-CAM", ...). */
    val source: String,
    val cameraFps: Float,
    val detectorFps: Float,
    /** Nearest obstacle the decision saw (after the dropout hold), or null. */
    val nearest: RelativeObstacle?,
)

/**
 * CSV formatting and file housekeeping for the decision log (FR-APP-07). Pure Kotlin: the wall
 * clock and the time-zone offset are passed in (no java.time, which would need desugaring on API 24-25).
 */
object DecisionCsv {

    const val HEADER =
        "wall_time,elapsed_ms,kind,priority,phrase,reason,source,camera_fps,detector_fps,nearest_label,nearest_m"

    const val FILE_PREFIX = "decisions_"
    const val FILE_SUFFIX = ".csv"

    /** At most this many session files are kept; older ones are deleted at the start of a session. */
    const val KEEP_FILES = 20

    /** RFC 4180: quote a field holding a comma, quote or line break; double inner quotes. */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    /** One CSV line (no line break) for [e], spoken at wall time [wallMs] in a zone [tzOffsetMs] from UTC. */
    fun row(e: DecisionEntry, wallMs: Long, tzOffsetMs: Int): String = listOf(
        isoTime(wallMs, tzOffsetMs),
        e.elapsedMs.toString(),
        e.kind.name.lowercase(Locale.US),
        e.priority.toString(),
        e.phrase,
        e.reason,
        e.source,
        String.format(Locale.US, "%.1f", e.cameraFps),
        String.format(Locale.US, "%.1f", e.detectorFps),
        e.nearest?.label.orEmpty(),
        e.nearest?.let { String.format(Locale.US, "%.2f", it.forwardM) }.orEmpty(),
    ).joinToString(",") { escape(it) }

    /** The obstacle nearest ahead, or null. */
    fun nearest(obstacles: List<RelativeObstacle>): RelativeObstacle? = obstacles.minByOrNull { it.forwardM }

    /** ISO 8601 local time with offset: "2026-10-22T19:33:05.123+05:30". */
    fun isoTime(wallMs: Long, tzOffsetMs: Int): String {
        val local = wallMs + tzOffsetMs
        val days = local.floorDiv(MS_PER_DAY)
        val msOfDay = local - days * MS_PER_DAY
        val (y, m, d) = civilFromDays(days)
        val offMin = tzOffsetMs / MS_PER_MIN
        val sign = if (offMin < 0) '-' else '+'
        return String.format(
            Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.%03d%c%02d:%02d",
            y, m, d,
            msOfDay / MS_PER_H, msOfDay / MS_PER_MIN % 60, msOfDay / MS_PER_S % 60, msOfDay % MS_PER_S,
            sign, abs(offMin) / 60, abs(offMin) % 60,
        )
    }

    /** "decisions_2026-10-22_19-33-05-123.csv": sorts by name in time order. */
    fun fileName(wallMs: Long, tzOffsetMs: Int): String {
        val iso = isoTime(wallMs, tzOffsetMs) // 2026-10-22T19:33:05.123+05:30
        val stamp = iso.substring(0, ISO_STAMP_LENGTH).replace('T', '_').replace(':', '-').replace('.', '-')
        return FILE_PREFIX + stamp + FILE_SUFFIX
    }

    fun isLogFile(name: String) = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /** Log files beyond the newest [keep], to delete. Other files are never touched. */
    fun filesToDelete(names: List<String>, keep: Int = KEEP_FILES): List<String> =
        names.filter(::isLogFile).sortedDescending().drop(keep.coerceAtLeast(0))

    /** The newest log file name, or null. */
    fun newest(names: List<String>): String? = names.filter(::isLogFile).maxOrNull()

    /** Days since 1970-01-01 -> (year, month, day), proleptic Gregorian (H. Hinnant's algorithm). */
    private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    private const val MS_PER_S = 1_000L
    private const val MS_PER_MIN = 60_000
    private const val MS_PER_H = 3_600_000L
    private const val MS_PER_DAY = 86_400_000L

    /** "2026-10-22T19:33:05.123".length */
    private const val ISO_STAMP_LENGTH = 23
}
