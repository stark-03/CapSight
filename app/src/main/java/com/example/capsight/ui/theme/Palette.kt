package com.example.capsight.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The high-contrast palette as ARGB values (pure Kotlin, so the contrast rules are JVM-tested in
 * PaletteTest). CapSight always uses this dark theme: near-black background, white and yellow text,
 * yellow for the main "start" action, dark red with a white border for "stop".
 *
 * Contrast (WCAG 2.x, see [contrastRatio]): body text >= 7:1 (AAA) everywhere; button labels >= 7:1;
 * control edges against the background >= 3:1 (the stop button gets its edge from a white border).
 * New screens must pick text / background pairs from here, never ad-hoc colours.
 */
object Palette {
    /** Window and screen background. */
    const val BACKGROUND = 0xFF000000L

    /** Cards, the status area, text fields. */
    const val SURFACE = 0xFF121212L

    /** Raised controls: the "start without camera" button, secondary buttons. */
    const val SURFACE_HIGH = 0xFF262626L

    /** Main text on any of the dark colours above. */
    const val TEXT = 0xFFFFFFFFL

    /** Secondary text (explanations). */
    const val TEXT_MUTED = 0xFFE0E0E0L

    /** Accent: the main start action, headings of the status, focus. Black text on it. */
    const val YELLOW = 0xFFFFD600L

    /** Text and icons on [YELLOW]. */
    const val ON_YELLOW = 0xFF000000L

    /** "Stop navigation" button; white text, white border. */
    const val STOP_RED = 0xFF9A0007L

    /** Light blue: secondary accent (links, selected switches in helper screens). Black text on it. */
    const val BLUE = 0xFF80D8FFL

    /** Error / warning text on the dark background. */
    const val ERROR_TEXT = 0xFFFFABA3L

    /** Caution text (amber) on the dark background. */
    const val CAUTION_TEXT = 0xFFFFD54FL

    /** "OK" text (green) on the dark background. */
    const val OK_TEXT = 0xFF69F0AEL

    /** Borders of outlined controls. */
    const val OUTLINE = 0xFFFFFFFFL

    /** Dividers and switch tracks (decorative, not text). */
    const val OUTLINE_MUTED = 0xFF9E9E9EL

    /** WCAG relative luminance of an ARGB colour (alpha ignored). */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two colours, 1.0 .. 21.0. */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
