package com.wearsic.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

// ============================================================================
// Wearsic — Wear OS Material 3 dark palette
//
// OLED black background (battery-first on watch AMOLED), M3-toned surfaces
// and a lavender primary. All tokens follow Material roles so components read
// like standard Wear M3 rather than a custom skin.
// ============================================================================

val WearsicBlack = Color(0xFF000000)

/** App backdrop: pure OLED black. */
val WearsicAppBackground: Color = WearsicBlack

// M3 dark surface roles (baseline-inspired)
val WearsicCanvasDark = Color(0xFF141218)
val WearsicSurface = Color(0xFF1C1B1F)
val WearsicSurfaceActive = Color(0xFF35323A)
val WearsicSurfaceRaised = Color(0xFF2A2831)
val WearsicSurfaceBorder = Color(0xFF49454F)          // outlineVariant
val WearsicSurfaceBorderSubtle = Color(0x7349454F)   // outlineVariant @ 45%

// Primary — lavender (M3 tonal)
val WearsicVibrantLavender = Color(0xFFD0BCFF)
val WearsicViolet = Color(0xFF8A5CF6)
val WearsicLavenderSecondary = Color(0xFFCCC2DC)
val WearsicLavenderTertiary = Color(0xFFC4B0FF)
val WearsicLavenderSubtle = Color(0x33D0BCFF)
val WearsicLavenderContainer = Color(0xFF4F378B)
val WearsicPrimaryContainer = WearsicLavenderContainer
val WearsicOnPrimaryContainer = Color(0xFFEADDFF)

// Glow tokens (soft, only used for hero/background washes — not as fills)
val WearsicGlow = Color(0x33D0BCFF)
val WearsicGlowWarm = Color(0x2E8A5CF6)

// Section accents remain for *subtle* identity (headers/avatars)
val WearsicAccentSky = Color(0xFF9ACBFF)
val WearsicAccentMint = Color(0xFF88D9B8)
val WearsicAccentPeach = Color(0xFFF2B98E)
val WearsicAccentRose = Color(0xFFFFB0BE)
val WearsicAccentViolet = Color(0xFFC4B0FF)
val WearsicAccentAmber = Color(0xFFF2C879)

// Extra roles
val WearsicOnPrimary = Color(0xFF381E72)
val WearsicOnSecondary = Color(0xFF332D41)
val WearsicSecondaryContainer = Color(0xFF4A4458)
val WearsicOnSecondaryContainer = Color(0xFFE8DEF8)
val WearsicTertiary = Color(0xFFEFB8C8)
val WearsicOnTertiary = Color(0xFF4A2532)
val WearsicTertiaryContainer = Color(0xFF633B48)
val WearsicOnTertiaryContainer = Color(0xFFFFD8E4)

// Glass tokens kept for compatibility (legacy names), now aliased to M3 tints
val WearsicGlassFill = Color(0x14FFFFFF)
val WearsicGlassBorder = Color(0x2EFFFFFF)

// Text tokens
val WearsicTextPrimary = Color(0xFFE6E0E9)
val WearsicTextPrimaryDark = Color(0xFF381E72)
val WearsicTextSecondary = Color(0xFFCAC4D0)
val WearsicTextWhite80 = Color(0xCCE6E0E9)
val WearsicTextWhite60 = Color(0x99CAC4D0)
val WearsicTextWhite40 = Color(0x66CAC4D0)
val WearsicTextMuted = Color(0xFF938F99)

// Status tokens
val WearsicError = Color(0xFFF2B8C5)
val WearsicOnError = Color(0xFF601410)
val WearsicErrorContainer = Color(0xFF8C1D18)
val WearsicOnErrorContainer = Color(0xFFF9DEDC)
val WearsicSuccess = Color(0xFF88D9B8)
val WearsicWarning = Color(0xFFF2C879)

// Wear Material 3 ColorScheme — full roles filled from the palette above.
val WearsicColorScheme = ColorScheme(
    primary = WearsicVibrantLavender,
    primaryDim = WearsicLavenderTertiary,
    primaryContainer = WearsicLavenderContainer,
    onPrimary = WearsicOnPrimary,
    onPrimaryContainer = WearsicOnPrimaryContainer,
    secondary = WearsicLavenderSecondary,
    secondaryDim = WearsicLavenderSecondary,
    secondaryContainer = WearsicSecondaryContainer,
    onSecondary = WearsicOnSecondary,
    onSecondaryContainer = WearsicOnSecondaryContainer,
    tertiary = WearsicTertiary,
    onTertiary = WearsicOnTertiary,
    tertiaryContainer = WearsicTertiaryContainer,
    onTertiaryContainer = WearsicOnTertiaryContainer,
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
    onError = WearsicOnError,
    errorContainer = WearsicErrorContainer,
    onErrorContainer = WearsicOnErrorContainer
)
