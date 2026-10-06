package com.example.capsight.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** FR-DET-01 / FR-DET-03: decoding the YOLOv8n [1, 84, 2100] output. */
class YoloDecoderTest {

    private val n = 2100
    private val decoder = YoloDecoder()
    private val vga = Letterbox.compute(640, 480, 320)

    /** An all-zero output with one candidate set. Box values as given (normalised or pixels). */
    private fun output(vararg cands: FloatArray): FloatArray {
        val out = FloatArray(84 * n)
        cands.forEachIndexed { i, c ->
            for (ch in 0 until 4) out[ch * n + i] = c[ch]
            out[(4 + c[4].toInt()) * n + i] = c[5]
        }
        return out
    }

    private fun cand(cx: Float, cy: Float, w: Float, h: Float, cls: Int, score: Float) =
        floatArrayOf(cx, cy, w, h, cls.toFloat(), score)

    @Test
    fun `normalised box maps to frame pixels`() {
        // Centre of the square, 0.25 x 0.25 of it -> 80x80 input px -> 160x160 frame px.
        val d = decoder.decode(output(cand(0.5f, 0.5f, 0.25f, 0.25f, 56, 0.9f)), 0.4f, vga, null).single()
        assertEquals("chair", d.label)
        assertEquals(240f, d.left, 0.01f)
        assertEquals(160f, d.top, 0.01f)
        assertEquals(400f, d.right, 0.01f)
        assertEquals(320f, d.bottom, 0.01f)
    }

    @Test
    fun `pixel-coordinate output from older exports gives the same box`() {
        val d = decoder.decode(output(cand(160f, 160f, 80f, 80f, 56, 0.9f)), 0.4f, vga, null).single()
        assertEquals(240f, d.left, 0.01f)
        assertEquals(320f, d.bottom, 0.01f)
    }

    @Test
    fun `FR-DET-03 candidates below the minimum confidence are dropped`() {
        val out = output(cand(0.5f, 0.5f, 0.2f, 0.2f, 0, 0.39f), cand(0.2f, 0.5f, 0.2f, 0.2f, 0, 0.40f))
        val dets = decoder.decode(out, 0.40f, vga, null)
        assertEquals(1, dets.size)
        assertEquals(0.40f, dets[0].score, 1e-6f)
    }

    @Test
    fun `class filter keeps only requested classes`() {
        // 9 = traffic light, not a navigation class.
        val out = output(cand(0.3f, 0.5f, 0.2f, 0.2f, 9, 0.9f), cand(0.7f, 0.5f, 0.2f, 0.2f, 0, 0.8f))
        val dets = decoder.decode(out, 0.4f, vga, CocoLabels.NAVIGATION)
        assertEquals(listOf("person"), dets.map { it.label })
    }

    @Test
    fun `box spilling into the padding is clipped to the frame`() {
        val d = decoder.decode(output(cand(0.5f, 0.2f, 0.2f, 0.3f, 0, 0.9f)), 0.4f, vga, null).single()
        assertEquals(0f, d.top, 0f)
    }

    // ------------------------------------------------- real model output, bus.jpg

    /** Raw output of yolov8n_320_fp32.tflite for Ultralytics' bus.jpg (810x1080), see export notes. */
    private fun busOutput(): FloatArray {
        val bytes = requireNotNull(javaClass.classLoader!!.getResourceAsStream("yolov8n_320_bus_output.f32"))
            .use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    @Test
    fun `FR-DET-01 real model output matches the Python reference detections`() {
        val lb = Letterbox.compute(810, 1080, 320)
        val dets = decoder.decode(busOutput(), 0.40f, lb, null)
        // From the Python check of the same model: label, conf, x1, y1, x2, y2.
        val expected = listOf(
            listOf("person", 0.85f, 47f, 394f, 231f, 901f),
            listOf("bus", 0.84f, 5f, 228f, 798f, 753f),
            listOf("person", 0.75f, 220f, 403f, 348f, 854f),
            listOf("person", 0.69f, 670f, 415f, 812f, 872f),
        )
        assertEquals(dets.joinToString(), expected.size, dets.size)
        for ((d, e) in dets.zip(expected)) {
            assertEquals(e[0], d.label)
            assertEquals(e[1] as Float, d.score, 0.01f)
            assertEquals(e[2] as Float, d.left, 2f)
            assertEquals(e[3] as Float, d.top, 2f)
            assertEquals(e[4] as Float, d.right, 2f)
            assertEquals(e[5] as Float, d.bottom, 2f)
        }
    }

    @Test
    fun `navigation classes include the bus (outdoor classes added), a person-only filter drops it`() {
        val lb = Letterbox.compute(810, 1080, 320)
        val nav = decoder.decode(busOutput(), 0.40f, lb, CocoLabels.NAVIGATION)
        assertEquals(listOf("person", "bus", "person", "person"), nav.map { it.label })
        val dets = decoder.decode(busOutput(), 0.40f, lb, setOf(CocoLabels.PERSON))
        assertEquals(List(3) { "person" }, dets.map { it.label })
        assertTrue(dets.all { it.right <= 810f && it.bottom <= 1080f })
    }
}
