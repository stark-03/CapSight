package com.example.capsight.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** FR-RX-01 / contract §4.2: parsing the ESP32-CAM multipart stream. */
class MjpegParserTest {

    private val boundary = "123456789000000000000987654321"

    /** Builds a body exactly as esp32_cam_ap.ino's stream_handler writes it. */
    private fun firmwareBody(vararg jpegs: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (j in jpegs) {
            out.write("Content-Type: image/jpeg\r\nContent-Length: ${j.size}\r\n\r\n".toByteArray())
            out.write(j)
            out.write("\r\n--$boundary\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    private fun fakeJpeg(size: Int, seed: Int): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed) and 0xFF).toByte() }.also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
            it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
        }

    private fun parser(bytes: ByteArray) = MjpegParser(BufferedInputStream(ByteArrayInputStream(bytes)))

    private fun readAll(p: MjpegParser): List<ByteArray> =
        generateSequence { p.nextFrame() }.toList()

    // ------------------------------------------------------------ recorded sample

    private fun sample(): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("sample.mjpeg")) {
            "sample.mjpeg missing - record it from tools/fake_esp32cam.py (CLAUDE.md §11)"
        }.use { it.readBytes() }

    @Test
    fun `FR-RX-01 recorded sample starts with headers, not a boundary`() {
        val head = String(sample(), 0, 40, Charsets.ISO_8859_1)
        assertTrue(head, head.startsWith("Content-Type: image/jpeg\r\n"))
    }

    @Test
    fun `FR-RX-01 recorded sample yields complete 640x480 JPEG frames`() {
        val p = parser(sample())
        val frames = mutableListOf<ByteArray>()
        // curl --max-time cuts the recording mid-frame, so the tail may be truncated.
        try {
            while (true) frames += p.nextFrame() ?: break
        } catch (_: EOFException) {
        }
        assertTrue("only ${frames.size} frames", frames.size >= 20)
        for ((i, f) in frames.withIndex()) {
            assertTrue("frame $i lacks SOI", f[0] == 0xFF.toByte() && f[1] == 0xD8.toByte())
            assertTrue("frame $i lacks EOI", f[f.size - 2] == 0xFF.toByte() && f[f.size - 1] == 0xD9.toByte())
            assertEquals("frame $i size", 640 to 480, jpegSize(f))
        }
    }

    // ---------------------------------------------------------- synthetic streams

    @Test
    fun `first frame without leading boundary is parsed`() {
        val a = fakeJpeg(500, 1)
        val b = fakeJpeg(700, 2)
        val frames = readAll(parser(firmwareBody(a, b)))
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test
    fun `leading boundary is tolerated`() {
        val a = fakeJpeg(300, 3)
        val body = "--$boundary\r\n".toByteArray() + firmwareBody(a)
        assertArrayEquals(a, readAll(parser(body)).single())
    }

    @Test
    fun `JPEG bytes that look like a boundary do not split the frame`() {
        val inner = "\r\n--$boundary\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray()
        val a = fakeJpeg(400, 4)
        inner.copyInto(a, destinationOffset = 50)
        assertArrayEquals(a, readAll(parser(firmwareBody(a))).single())
    }

    @Test
    fun `header names are case-insensitive and extra headers are ignored`() {
        val a = fakeJpeg(200, 5)
        val body = "content-type: image/jpeg\r\nX-Timestamp: 12.5\r\ncontent-length:  ${a.size} \r\n\r\n"
            .toByteArray() + a + "\r\n--$boundary\r\n".toByteArray()
        assertArrayEquals(a, readAll(parser(body)).single())
    }

    @Test
    fun `bare LF line endings are accepted`() {
        val a = fakeJpeg(200, 6)
        val body = "Content-Type: image/jpeg\nContent-Length: ${a.size}\n\n".toByteArray() + a
        assertArrayEquals(a, readAll(parser(body)).single())
    }

    @Test
    fun `empty stream ends cleanly`() {
        assertNull(parser(ByteArray(0)).nextFrame())
    }

    @Test
    fun `stream ending after the trailing boundary ends cleanly`() {
        val frames = readAll(parser(firmwareBody(fakeJpeg(100, 7))))
        assertEquals(1, frames.size)
    }

    @Test(expected = EOFException::class)
    fun `truncated JPEG throws EOFException`() {
        val full = firmwareBody(fakeJpeg(1000, 8))
        parser(full.copyOf(full.size / 2)).nextFrame()
    }

    @Test(expected = EOFException::class)
    fun `stream ending inside headers throws EOFException`() {
        parser("Content-Type: image/jpeg\r\nContent-Len".toByteArray()).nextFrame()
    }

    @Test(expected = MjpegFormatException::class)
    fun `part without Content-Length is rejected`() {
        parser("Content-Type: image/jpeg\r\n\r\nÿØ".toByteArray()).nextFrame()
    }

    @Test(expected = MjpegFormatException::class)
    fun `non-numeric Content-Length is rejected`() {
        parser("Content-Length: lots\r\n\r\n".toByteArray()).nextFrame()
    }

    @Test(expected = MjpegFormatException::class)
    fun `oversized Content-Length is rejected`() {
        parser("Content-Length: 999999999\r\n\r\n".toByteArray()).nextFrame()
    }

    @Test
    fun `binary garbage is rejected rather than read forever`() {
        val garbage = ByteArray(5000) { 'x'.code.toByte() }
        try {
            parser(garbage).nextFrame()
            fail("expected an exception")
        } catch (_: MjpegFormatException) {
        }
    }

    // ----------------------------------------------------------------- helpers

    /** Width and height from the JPEG SOF0/SOF2 marker. */
    private fun jpegSize(j: ByteArray): Pair<Int, Int> {
        var i = 2
        while (i + 9 < j.size) {
            if (j[i] != 0xFF.toByte()) { i++; continue }
            val marker = j[i + 1].toInt() and 0xFF
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            if (marker == 0xC0 || marker == 0xC2) {
                val h = ((j[i + 5].toInt() and 0xFF) shl 8) or (j[i + 6].toInt() and 0xFF)
                val w = ((j[i + 7].toInt() and 0xFF) shl 8) or (j[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        error("no SOF marker")
    }
}
