package com.wearsic.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme

/**
 * The app-wide accent colour. Components read this instead of hardcoding
 * [WearsicVibrantLavender] so the optional dynamic-accent feature can re-theme
 * the whole app from the current track's artwork. Defaults to the static
 * lavender when dynamic colour is off or no artwork is available.
 */
val LocalWearsicAccent = staticCompositionLocalOf { WearsicVibrantLavender }

/**
 * Build the full Material 3 scheme from an accent colour. When dynamic
 * accent is enabled, EVERY theme-derived token follows the artwork accent —
 * primary buttons, focused/selected states, cursor, progress, switches and
 * text accents — not just the few components that read
 * [LocalWearsicAccent] directly. Surfaces/background stay OLED black; only
 * the accent-bearing tokens move.
 */
fun wearsicColorSchemeFor(accent: Color): ColorScheme {
    val container = lerp(accent, Color.Black, 0.55f)
    return ColorScheme(
        primary = accent,
        primaryDim = lerp(accent, Color.White, 0.22f),
        primaryContainer = container,
        onPrimary = WearsicBlack,
        onPrimaryContainer = accent,
        secondary = lerp(accent, Color.White, 0.18f),
        secondaryDim = lerp(accent, Color.Black, 0.35f),
        secondaryContainer = WearsicSurface,
        onSecondary = WearsicBlack,
        onSecondaryContainer = WearsicTextPrimary,
        tertiary = lerp(accent, Color.White, 0.35f),
        onTertiary = WearsicBlack,
        surfaceContainer = WearsicSurface,
        surfaceContainerLow = WearsicCanvasDark,
        surfaceContainerHigh = WearsicSurfaceActive,
        onSurface = WearsicTextPrimary,
        onSurfaceVariant = WearsicTextSecondary,
        outline = WearsicSurfaceBorder,
        outlineVariant = WearsicSurfaceBorderSubtle,
        background = WearsicBlack,
        onBackground = WearsicTextPrimary,
        error = WearsicError,
        onError = WearsicBlack
    )
}

@Composable
fun WearsicTheme(
    accent: Color = WearsicVibrantLavender,
    content: @Composable () -> Unit
) {
    val colorScheme = remember(accent) { wearsicColorSchemeFor(accent) }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WearsicTypography,
        content = content
    )
}

/** Container tone derived from the current accent (used behind accent icons). */
@Composable
fun wearsicAccentContainer(): Color = lerp(LocalWearsicAccent.current, Color.Black, 0.55f)
