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
// ── Solar Ember palette (Wearsic 2.0) ──────────────────────────────────────
// Pure OLED black canvas; warm obsidian tonal containers and a Neon Coral /
// Warm Amber accent pair. Coral is the primary action colour (play, focus,
// active progress), Amber the secondary highlight (shuffle, badges, equalizer).
// Dynamic artwork tint remains an OPT-IN override in Settings.
val WearsicAppBackground: Color = WearsicBlack
val WearsicSolarCoral = Color(0xFFFF6B57)
val WearsicSolarAmber = Color(0xFFFFB347)
val WearsicSolarCoralDark = Color(0xFF4A1D18)   // primaryContainer
val WearsicSolarAmberDark = Color(0xFF3B2914)   // secondaryContainer
val WearsicSolarCoralOn = Color(0xFF1E0705)     // onPrimary
val WearsicSolarCoralContainerOn = Color(0xFFFFDAD4)
val WearsicSolarAmberOn = Color(0xFFFFE6BF)

// Warm obsidian surface progression (replaces the old cold lav-grey surfaces).
val WearsicSurfaceLow = Color(0xFF120F10)       // recessed wells, chips
val WearsicSurface = Color(0xFF1B1617)          // standard cards, nav rows
val WearsicSurfaceHigh = Color(0xFF282020)      // active cards, hero backdrops
// Legacy aliases kept for components that still reference the old names.
val WearsicCanvasDark = WearsicSurfaceLow
val WearsicSurfaceActive = WearsicSurfaceHigh
val WearsicSurfaceBorder = Color(0x1AFFFFFF) // white/10
val WearsicSurfaceBorderSubtle = Color(0x0DFFFFFF) // white/5

// Vibrant Accents - Enhanced for better contrast and visual appeal
val WearsicVibrantLavender = Color(0xFFC4B0FF) // kept for the "Pixel Lavender" catalog entry
val WearsicViolet = Color(0xFF8A5CF6) // deep signature violet
val WearsicLavenderSecondary = Color(0xFFCCC2DC)
val WearsicLavenderTertiary = Color(0xFFB8A1FF)
val WearsicLavenderSubtle = Color(0x33C4B0FF)
val WearsicLavenderContainer = Color(0xFF382959)

// Depth: an elevated surface for cards/heroes that should read as "above"
// the flat canvas, plus a soft accent glow used behind titles and art.
val WearsicSurfaceRaised = WearsicSurfaceHigh
val WearsicGlow = Color(0x33FF6B57)          // Solar Coral 20%
val WearsicGlowWarm = Color(0x2EFFB347)      // Warm Amber 18%

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
// v1.9.50 "art-first" pass: pure white primary + neutral 70% white secondary.
// The old lavender secondary read greyish at watch sizes and lowered
// contrast against OLED black; the accent colour is now carried by
// artwork-driven dynamic tint instead of the body text.
val WearsicTextPrimary = Color(0xFFFFFFFF)
val WearsicTextPrimaryDark = Color(0xFF000000)
val WearsicTextSecondary = Color(0xB3FFFFFF)
val WearsicTextWhite80 = Color(0xCCFFFFFF)
val WearsicTextWhite60 = Color(0x99FFFFFF)
val WearsicTextWhite40 = Color(0x66FFFFFF)
val WearsicTextMuted = Color(0xFF938F99)

// Semantic status (Solar Ember): emerald success, amber warning, rose error.
val WearsicSuccess = Color(0xFF6CE0A7)
val WearsicWarning = WearsicSolarAmber
val WearsicError = Color(0xFFFF758F)
val WearsicErrorContainer = Color(0xFF3D1520)

// Wear Material 3 ColorScheme — Solar Ember default.
val WearsicColorScheme = ColorScheme(
    primary = WearsicSolarCoral,
    primaryDim = WearsicSolarCoral,
    primaryContainer = WearsicSolarCoralDark,
    onPrimary = WearsicSolarCoralOn,
    onPrimaryContainer = WearsicSolarCoralContainerOn,
    secondary = WearsicSolarAmber,
    secondaryDim = WearsicSolarAmber,
    secondaryContainer = WearsicSolarAmberDark,
    onSecondary = WearsicSolarCoralOn,
    onSecondaryContainer = WearsicSolarAmberOn,
    tertiary = WearsicSolarAmber,
    onTertiary = WearsicSolarCoralOn,
    surfaceContainer = WearsicSurface,
    surfaceContainerLow = WearsicSurfaceLow,
    surfaceContainerHigh = WearsicSurfaceHigh,
    onSurface = WearsicTextPrimary,
    onSurfaceVariant = WearsicTextSecondary,
    outline = WearsicSurfaceBorder,
    outlineVariant = WearsicSurfaceBorderSubtle,
    background = WearsicBlack,
    onBackground = WearsicTextPrimary,
    error = WearsicError,
    onError = WearsicSolarCoralOn
)

/**
 * The app-colour catalog shown in Settings. Each option maps to an accent
 * colour the whole app adopts (headers, buttons, highlights) when selected.
 * Kept to pleasant, OLED-friendly shades that read clearly on black.
 */
/**
 * Sentinel stored as the user accent when the catalog's "Artwork (auto)"
 * option is chosen — the app then colours key UI (including the player
 * transport) from the current track's artwork.
 */
const val ARTWORK_ACCENT_AUTO = 0x7F000001

val WearsicAppColorChoices: List<Pair<String, Color>> = listOf(
    // Aurora Nocturne catalog — the new flagship palette. Aurora Cyan is the
    // signature preset (also the app default when no choice is saved).
    // "Artwork (auto)" is first: it lets the player transport and accents
    // follow the current track's palette.
    "Artwork (auto)" to Color(ARTWORK_ACCENT_AUTO),
    "Aurora Cyan" to Color(0xFF5FE7F0),
    "Orchid" to Color(0xFFB494FF),
    "Aurora Lime" to Color(0xFFB9F56E),
    "Electric Blue" to Color(0xFF5B9CFF),
    "Rose Quartz" to Color(0xFFFF8FA3),
    "Amber Glow" to Color(0xFFFFC46B),
    "Emerald" to Color(0xFF6CE0A7),
    "Sky" to Color(0xFF8FD3FE),
    "Mint" to Color(0xFF7FD8B4),
    "Violet" to Color(0xFF8A5CF6),
    "Teal" to Color(0xFF5FD4C8)
)
