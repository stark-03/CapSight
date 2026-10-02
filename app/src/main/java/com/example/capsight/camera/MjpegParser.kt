package com.example.capsight.camera

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The stream does not follow the device contract (CLAUDE.md §4.2). */
class MjpegFormatException(message: String) : IOException(message)

/**
 * Splits the ESP32-CAM multipart body into JPEG frames (FR-RX-01, contract §4.2).
 *
 * Each part is `Content-Type: image/jpeg\r\nContent-Length: N\r\n\r\n` + N bytes +
 * `\r\n--<boundary>\r\n`. The FIRST part has no boundary before it, so this parser
 * never searches for a boundary: it reads headers up to the blank line, takes exactly
 * Content-Length bytes, and on the next call skips the blank and boundary lines that
 * precede the following headers. A leading boundary is tolerated too.
 *
 * Pass a buffered stream; reads are byte-at-a-time while parsing headers.
 * Pure Kotlin, no Android imports, so it is JVM-unit-tested.
 */
class MjpegParser(
    private val input: InputStream,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    /**
     * The next JPEG, or null when the stream ended cleanly between parts.
     *
     * @throws EOFException if the stream ends inside a part (truncated frame).
     * @throws MjpegFormatException if the framing is wrong.
     */
    fun nextFrame(): ByteArray? {
        val length = readHeaders() ?: return null
        val frame = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(frame, off, length - off)
            if (n < 0) throw EOFException("stream ended after $off of $length JPEG bytes")
            off += n
        }
        return frame
    }

    /** Returns Content-Length of the next part, or null on clean EOF before it. */
    private fun readHeaders(): Int? {
        // Skip the blank line and "--boundary" line left over from the previous part.
        var line: String
        while (true) {
            line = readLine() ?: return null
            if (line.isNotEmpty() && !line.startsWith("--")) break
        }
        var contentLength: Int? = null
        while (line.isNotEmpty()) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw MjpegFormatException("bad part header: ${line.take(LOG_CHARS)}")
            if (line.substring(0, colon).trim().equals(CONTENT_LENGTH, ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull()
                    ?: throw MjpegFormatException("bad Content-Length: ${line.take(LOG_CHARS)}")
            }
            line = readLine() ?: throw EOFException("stream ended inside part headers")
        }
        val length = contentLength ?: throw MjpegFormatException("part without Content-Length")
        if (length <= 0 || length > maxFrameBytes) {
            throw MjpegFormatException("implausible Content-Length $length")
        }
        return length
    }

    /** One header line without its CR/LF, or null at EOF before any byte. */
    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (sb.isEmpty()) return null
                throw EOFException("stream ended mid-line")
            }
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (sb.length >= MAX_LINE_CHARS) {
                throw MjpegFormatException("header line too long - not an MJPEG part")
            }
            sb.append(b.toChar())
        }
    }

    companion object {
        /** A 640x480 frame at quality 12 is ~20-60 KB; anything near this is garbage. */
        const val DEFAULT_MAX_FRAME_BYTES = 2 * 1024 * 1024
        private const val MAX_LINE_CHARS = 1024
        private const val LOG_CHARS = 80
        private const val CONTENT_LENGTH = "Content-Length"
    }
}
