package com.wearsic.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

// Wearsic Vibrant Palette Theme - Production Quality
val WearsicBlack = Color(0xFF000000)

/**
 * Shared app backdrop: PURE BLACK on purpose. The watch has an OLED panel —
 * black pixels are switched off, so a black backdrop is measurably kinder to
 * battery and thermals than any tinted/gradient background, and it is what
 * production music apps on Wear OS ship. Applied by every screen's
 * ScreenScaffold.
 */
// Wearsic Midnight Lavender — production palette (v1.8).
// OLED black stays black; surfaces deepened a step for separation;
// the lavender primary is one notch deeper for small-size legibility;
// sage = offline/saved, amber = warnings only, rose = errors.
val WearsicAppBackground: Color = WearsicBlack
val WearsicCanvasDark = Color(0xFF0A0A0A)
val WearsicSurface = Color(0xFF141318)
val WearsicSurfaceActive = Color(0xFF232027)
val WearsicSurfaceBorder = Color(0x1AFFFFFF) // white/10
val WearsicSurfaceBorderSubtle = Color(0x0DFFFFFF) // white/5

// Vibrant Accents - Enhanced for better contrast and visual appeal
val WearsicVibrantLavender = Color(0xFFC4B0FF)
val WearsicViolet = Color(0xFF8A5CF6) // deep signature violet
val WearsicLavenderSecondary = Color(0xFFCCC2DC)
val WearsicLavenderTertiary = Color(0xFFB8A1FF)
val WearsicLavenderSubtle = Color(0x33C4B0FF)
val WearsicLavenderContainer = Color(0xFF382959)

// Depth: an elevated surface for cards/heroes that should read as "above"
// the flat canvas, plus a soft accent glow used behind titles and art.
val WearsicSurfaceRaised = Color(0xFF1E1C22)
val WearsicGlow = Color(0x33C4B0FF)          // lavender 20%
val WearsicGlowWarm = Color(0x2E8A5CF6)      // violet 18%

// Per-screen accents. Each screen's header medallion + glow uses one of these
// so the screens read as distinct places instead of one identical list.
val WearsicAccentSky = Color(0xFF8FD3FE)
val WearsicAccentMint = Color(0xFF7FD8B4)
val WearsicAccentPeach = Color(0xFFF0A87E)
val WearsicAccentRose = Color(0xFFFF8FA3)
val WearsicAccentViolet = Color(0xFFB8A1FF)
val WearsicAccentAmber = Color(0xFFF2C879)

// Additional production colors
val WearsicPrimary = Color(0xFFC4B0FF)
val WearsicPrimaryContainer = Color(0xFF4A3B6B)
val WearsicOnPrimary = Color(0xFF000000)
val WearsicOnPrimaryContainer = Color(0xFFFFFFFF)

// Glassmorphism surfaces: translucent white fills + hairline borders.
val WearsicGlassFill = Color(0x14FFFFFF)     // white 8%
val WearsicGlassBorder = Color(0x2EFFFFFF)   // white 18%

// Text Tokens
val WearsicTextPrimary = Color(0xFFE6E1E5)
val WearsicTextPrimaryDark = Color(0xFF000000)
val WearsicTextSecondary = Color(0xFFC4B0FF)
val WearsicTextWhite80 = Color(0xCCFFFFFF)
val WearsicTextWhite60 = Color(0x99FFFFFF)
val WearsicTextWhite40 = Color(0x66FFFFFF)
val WearsicTextMuted = Color(0xFF938F99)

// Status: sage = saved/offline (never decoration), amber = warnings only
// (timeouts, bot-wall), rose = errors.
val WearsicError = Color(0xFFF2B8C6)
val WearsicSuccess = Color(0xFF9DB89A)
val WearsicWarning = Color(0xFFE8C547)

// Wear Material 3 ColorScheme
val WearsicColorScheme = ColorScheme(
    primary = WearsicVibrantLavender,
    primaryDim = WearsicLavenderTertiary,
    primaryContainer = WearsicLavenderContainer,
    onPrimary = WearsicBlack,
    onPrimaryContainer = WearsicVibrantLavender,
    secondary = WearsicLavenderSecondary,
    secondaryDim = WearsicLavenderSecondary,
    secondaryContainer = WearsicSurface,
    onSecondary = WearsicBlack,
    onSecondaryContainer = WearsicTextPrimary,
    tertiary = WearsicLavenderTertiary,
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
