package com.wearsic.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.MaterialTheme

/**
 * The app-wide accent colour. Components read this instead of hardcoding
 * [WearsicVibrantLavender] so the optional dynamic-accent feature can re-theme
 * the whole app from the current track's artwork. Defaults to the static
 * lavender when dynamic colour is off or no artwork is available.
 */
val LocalWearsicAccent = staticCompositionLocalOf { WearsicVibrantLavender }

@Composable
fun WearsicTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WearsicColorScheme,
        typography = WearsicTypography,
        content = content
    )
}
