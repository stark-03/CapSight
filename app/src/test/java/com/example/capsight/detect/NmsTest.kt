package com.example.capsight.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class NmsTest {

    private fun det(cls: Int, score: Float, l: Float, t: Float, r: Float, b: Float) =
        Detection(cls, CocoLabels.name(cls), score, l, t, r, b)

    @Test
    fun `iou of identical, disjoint and half-overlapping boxes`() {
        val a = det(0, 1f, 0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(det(0, 1f, 20f, 20f, 30f, 30f)), 0f)
        assertEquals(1f / 3f, a.iou(det(0, 1f, 5f, 0f, 15f, 10f)), 1e-6f)
    }

    @Test
    fun `overlapping boxes of one class keep only the best`() {
        val best = det(56, 0.9f, 0f, 0f, 100f, 100f)
        val dup = det(56, 0.6f, 5f, 5f, 105f, 105f)
        assertEquals(listOf(best), Nms.apply(listOf(dup, best)))
    }

    @Test
    fun `different classes are never suppressed by each other`() {
        val person = det(0, 0.9f, 0f, 0f, 100f, 100f)
        val chair = det(56, 0.5f, 0f, 0f, 100f, 100f)
        assertEquals(2, Nms.apply(listOf(person, chair)).size)
    }

    @Test
    fun `boxes overlapping less than the threshold both survive`() {
        val a = det(0, 0.9f, 0f, 0f, 10f, 10f)
        val b = det(0, 0.8f, 5f, 0f, 15f, 10f) // IoU 1/3 < 0.45
        assertEquals(listOf(a, b), Nms.apply(listOf(b, a)))
    }
}
