package com.example.capsight.ui

import com.example.capsight.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The high-contrast theme's colour pairs meet WCAG AAA (7:1) for text, 3:1 for control edges. */
class PaletteTest {

    private fun assertContrast(min: Double, fg: Long, bg: Long, what: String) {
        val ratio = Palette.contrastRatio(fg, bg)
        assertTrue("$what: ${"%.2f".format(ratio)}:1 < $min:1", ratio >= min)
    }

    @Test
    fun `contrast ratio matches the WCAG reference values`() {
        assertEquals(21.0, Palette.contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.01)
        assertEquals(1.0, Palette.contrastRatio(0xFF777777L, 0xFF777777L), 0.0001)
        // #767676 on white is the classic 4.54:1 AA limit.
        assertEquals(4.54, Palette.contrastRatio(0xFF767676L, 0xFFFFFFFFL), 0.01)
    }

    @Test
    fun `main text on every dark background is AAA`() {
        for (bg in listOf(Palette.BACKGROUND, Palette.SURFACE, Palette.SURFACE_HIGH)) {
            assertContrast(AAA, Palette.TEXT, bg, "white text")
            assertContrast(AAA, Palette.TEXT_MUTED, bg, "muted text")
            assertContrast(AAA, Palette.YELLOW, bg, "yellow text")
            assertContrast(AAA, Palette.ERROR_TEXT, bg, "error text")
            assertContrast(AAA, Palette.CAUTION_TEXT, bg, "caution text")
            assertContrast(AAA, Palette.OK_TEXT, bg, "ok text")
            assertContrast(AAA, Palette.BLUE, bg, "blue text")
        }
    }

    @Test
    fun `button labels are AAA`() {
        assertContrast(AAA, Palette.ON_YELLOW, Palette.YELLOW, "start with camera")
        assertContrast(AAA, Palette.TEXT, Palette.SURFACE_HIGH, "start without camera")
        assertContrast(AAA, Palette.TEXT, Palette.STOP_RED, "stop navigation")
        assertContrast(AAA, Palette.ON_YELLOW, Palette.BLUE, "text on blue")
    }

    @Test
    fun `control edges stand out from the background`() {
        assertContrast(NON_TEXT, Palette.YELLOW, Palette.BACKGROUND, "yellow button")
        // The red stop button alone is too dark against black, so it has a white border.
        assertContrast(NON_TEXT, Palette.OUTLINE, Palette.STOP_RED, "stop button border vs button")
        assertContrast(NON_TEXT, Palette.OUTLINE, Palette.BACKGROUND, "border vs background")
        assertContrast(NON_TEXT, Palette.OUTLINE_MUTED, Palette.BACKGROUND, "muted border")
    }

    private companion object {
        const val AAA = 7.0
        const val NON_TEXT = 3.0
    }
}
