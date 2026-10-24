package com.example.capsight.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Large, bold, legible type. All sizes are sp, so they follow the system font size. Body text is
 * at least 16-18 sp on the helper screens; the blind user's main screen uses 20 sp and more.
 */
private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

val Typography = Typography(
    displaySmall = style(40, 48, FontWeight.Bold),
    headlineLarge = style(34, 42, FontWeight.Bold),
    headlineMedium = style(30, 38, FontWeight.Bold),
    headlineSmall = style(26, 34, FontWeight.Bold),
    titleLarge = style(24, 32, FontWeight.Bold),
    titleMedium = style(22, 30, FontWeight.Bold),
    titleSmall = style(20, 28, FontWeight.Bold),
    bodyLarge = style(20, 28),
    bodyMedium = style(18, 26),
    bodySmall = style(16, 22),
    labelLarge = style(20, 26, FontWeight.Bold),
    labelMedium = style(18, 24, FontWeight.Bold),
    labelSmall = style(16, 22, FontWeight.Bold),
)
