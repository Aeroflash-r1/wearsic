package com.wearsic.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme

/**
 * The app-wide accent colour. Components read this instead of hardcoding a
 * shade so the optional dynamic-accent feature can re-theme the whole app from
 * the current track's artwork. Defaults to Solar Coral (the Wearsic 2.0
 * signature) when dynamic colour is off or no artwork is available.
 */
val LocalWearsicAccent = staticCompositionLocalOf { WearsicSolarCoral }

/**
 * Whether the dynamic-accent feature is currently enabled (opt-in override).
 */
val LocalWearsicDynamicAccent = staticCompositionLocalOf { false }

/**
 * Secondary text colour — neutral whitish-grey so it stays readable against
 * both the static Solar Ember palette and any artwork-derived accent on OLED.
 */
@Composable
fun wearsicSecondaryText(): Color = WearsicTextWhite80

/**
 * Build the full Material 3 scheme from an accent colour. When dynamic
 * accent is enabled, EVERY theme-derived token follows the artwork accent —
 * primary buttons, focused/selected states, cursor, progress, switches and
 * text accents — not just the few components that read
 * [LocalWearsicAccent] directly. Surfaces/background stay OLED black; only
 * the accent-bearing tokens move. Secondary/variant text goes whitish-grey
 * under dynamic accent so it stays readable against the artwork hue.
 */
fun wearsicColorSchemeFor(accent: Color, dynamic: Boolean = false): ColorScheme {
    val container = lerp(accent, Color.Black, 0.55f)
    // Solar Ember secondary is always Warm Amber for the static palette; only
    // under dynamic artwork tint do we let the secondary follow the accent.
    val secondary = if (dynamic) lerp(accent, Color.White, 0.18f) else WearsicSolarAmber
    val onSurfaceVariant = if (dynamic) WearsicTextWhite80 else WearsicTextSecondary
    return ColorScheme(
        primary = accent,
        primaryDim = lerp(accent, Color.White, 0.22f),
        primaryContainer = container,
        onPrimary = WearsicSolarCoralOn,
        onPrimaryContainer = lerp(accent, Color.White, 0.55f),
        secondary = secondary,
        secondaryDim = lerp(accent, Color.Black, 0.35f),
        secondaryContainer = WearsicSolarAmberDark,
        onSecondary = WearsicSolarCoralOn,
        onSecondaryContainer = WearsicSolarAmberOn,
        tertiary = if (dynamic) lerp(accent, Color.White, 0.35f) else WearsicSolarAmber,
        onTertiary = WearsicSolarCoralOn,
        surfaceContainer = WearsicSurface,
        surfaceContainerLow = WearsicSurfaceLow,
        surfaceContainerHigh = WearsicSurfaceHigh,
        onSurface = WearsicTextPrimary,
        onSurfaceVariant = onSurfaceVariant,
        outline = WearsicSurfaceBorder,
        outlineVariant = WearsicSurfaceBorderSubtle,
        background = WearsicBlack,
        onBackground = WearsicTextPrimary,
        error = WearsicError,
        onError = WearsicSolarCoralOn
    )
}

@Composable
fun WearsicTheme(
    accent: Color = WearsicSolarCoral,
    dynamic: Boolean = false,
    content: @Composable () -> Unit
) {
    val watchScale = computeWatchScale()
    val adaptiveSpec = rememberWearAdaptiveSpec()
    val colorScheme = remember(accent, dynamic) { wearsicColorSchemeFor(accent, dynamic) }
    CompositionLocalProvider(
        LocalWatchScale provides watchScale,
        LocalWearAdaptiveSpec provides adaptiveSpec,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WearsicTypography,
            content = content
        )
    }
}

/** Container tone derived from the current accent (used behind accent icons). */
@Composable
fun wearsicAccentContainer(): Color = lerp(LocalWearsicAccent.current, Color.Black, 0.55f)
