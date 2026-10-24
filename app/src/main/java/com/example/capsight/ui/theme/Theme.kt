package com.example.capsight.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Always the high-contrast dark scheme (Palette.kt): no dynamic colour and no light variant, so
 * text contrast never drops below AAA whatever the system theme or wallpaper is. Low-vision users
 * who need a light screen can use Android's own "Color inversion".
 */
private val HighContrastScheme = darkColorScheme(
    primary = Yellow,
    onPrimary = OnYellow,
    primaryContainer = Yellow,
    onPrimaryContainer = OnYellow,
    secondary = Blue,
    onSecondary = OnYellow,
    secondaryContainer = SurfaceHigh,
    onSecondaryContainer = TextColor,
    tertiary = Blue,
    onTertiary = OnYellow,
    background = Background,
    onBackground = TextColor,
    surface = Background,
    onSurface = TextColor,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    inverseSurface = TextColor,
    inverseOnSurface = Background,
    error = ErrorText,
    onError = OnYellow,
    errorContainer = StopRed,
    onErrorContainer = TextColor,
    outline = Outline,
    outlineVariant = OutlineMuted,
)

@Composable
fun CapSightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HighContrastScheme,
        typography = Typography,
        content = content,
    )
}
