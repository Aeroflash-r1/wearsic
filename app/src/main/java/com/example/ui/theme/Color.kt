package com.example.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme

// Wearsic Vibrant Palette Theme - Production Quality
val WearsicBlack = Color(0xFF000000)

/**
 * Shared app backdrop: a very subtle violet-tinted vertical gradient instead
 * of flat black, so every screen gains a little depth without stealing
 * attention from the content. Applied by every screen's ScreenScaffold.
 */
val WearsicAppBackground = Brush.verticalGradient(
    0.00f to Color(0xFF17142A),
    0.35f to Color(0xFF0C0A16),
    0.70f to Color(0xFF08070F),
    1.00f to Color(0xFF050408)
)
val WearsicCanvasDark = Color(0xFF0A0A0A)
val WearsicSurface = Color(0xFF1C1B1F)
val WearsicSurfaceActive = Color(0xFF2C2B2F)
val WearsicSurfaceBorder = Color(0x1AFFFFFF) // white/10
val WearsicSurfaceBorderSubtle = Color(0x0DFFFFFF) // white/5

// Vibrant Accents - Enhanced for better contrast and visual appeal
val WearsicVibrantLavender = Color(0xFFD0BCFF)
val WearsicViolet = Color(0xFF8A5CF6) // deep signature violet
val WearsicLavenderSecondary = Color(0xFFCCC2DC)
val WearsicLavenderTertiary = Color(0xFFB8A1FF)
val WearsicLavenderSubtle = Color(0x33D0BCFF)
val WearsicLavenderContainer = Color(0xFF382959)

// Depth: an elevated surface for cards/heroes that should read as "above"
// the flat canvas, plus a soft accent glow used behind titles and art.
val WearsicSurfaceRaised = Color(0xFF26242B)
val WearsicGlow = Color(0x33D0BCFF)          // lavender 20%
val WearsicGlowWarm = Color(0x2E8A5CF6)      // violet 18%

// Signature gradient stops (used by titles, hero cards and primary pills).
val WearsicGradientStart = Color(0xFFD9C8FF)
val WearsicGradientMid = Color(0xFFB69CFF)
val WearsicGradientEnd = Color(0xFF8A5CF6)

// Per-screen accents. Each screen's header medallion + glow uses one of these
// so the screens read as distinct places instead of one identical list.
val WearsicAccentSky = Color(0xFF8FD3FE)
val WearsicAccentMint = Color(0xFF7FD8B4)
val WearsicAccentPeach = Color(0xFFF0A87E)
val WearsicAccentRose = Color(0xFFFF8FA3)
val WearsicAccentViolet = Color(0xFFB8A1FF)
val WearsicAccentAmber = Color(0xFFF2C879)

// Additional production colors
val WearsicPrimary = Color(0xFFD0BCFF)
val WearsicPrimaryContainer = Color(0xFF4A3B6B)
val WearsicOnPrimary = Color(0xFF000000)
val WearsicOnPrimaryContainer = Color(0xFFFFFFFF)

// Glassmorphism surfaces: translucent white fills + hairline borders.
val WearsicGlassFill = Color(0x14FFFFFF)     // white 8%
val WearsicGlassBorder = Color(0x2EFFFFFF)   // white 18%

// Text Tokens
val WearsicTextPrimary = Color(0xFFFFFFFF)
val WearsicTextPrimaryDark = Color(0xFF000000)
val WearsicTextSecondary = Color(0xFFD0BCFF)
val WearsicTextWhite80 = Color(0xCCFFFFFF)
val WearsicTextWhite60 = Color(0x99FFFFFF)
val WearsicTextWhite40 = Color(0x66FFFFFF)
val WearsicTextMuted = Color(0xFF8E8A98)

// Status
val WearsicError = Color(0xFFFFB4AB)
val WearsicSuccess = Color(0xFF81C784)

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
