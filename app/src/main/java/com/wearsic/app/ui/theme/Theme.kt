package com.wearsic.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

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
