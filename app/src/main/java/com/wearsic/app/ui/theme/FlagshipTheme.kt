package com.wearsic.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import com.wearsic.app.R

/**
 * Wearsic "Aurora Nocturne" flagship design system.
 *
 * This theme is applied to every screen EXCEPT the Now Playing screen, which
 * keeps its existing look. It is intentionally a separate theme (rather than
 * an edit of [WearsicTheme]) so the player can remain visually identical while
 * the rest of the app gets a complete redesign.
 *
 * Palette: a deep blue-black OLED canvas with cold obsidian surfaces and a
 * three-stop aurora accent (electric cyan → orchid → lime). Cyan is the
 * primary action colour, orchid the secondary, lime the "energy" highlight.
 * Every colour stays vivid against black and legible at 40mm watch sizes.
 */
object FlagshipColors {
    // Canvas / surfaces (cold midnight rather than warm charcoal)
    val Canvas = Color(0xFF05070E)
    val SurfaceLow = Color(0xFF0A1020)
    val Surface = Color(0xFF111A2C)
    val SurfaceHigh = Color(0xFF1A2742)
    val SurfaceRaised = Color(0xFF233354)
    val SurfaceGlow = Color(0xFF2E4470)

    // Signature aurora accents
    val Cyan = Color(0xFF5FE7F0)
    val Orchid = Color(0xFFB494FF)
    val Lime = Color(0xFFB9F56E)
    val Rose = Color(0xFFFF7B97)
    val Amber = Color(0xFFFFC46B)

    // Text
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFC2D0E6)
    val TextMuted = Color(0xFF8192AC)
    val TextOnAccent = Color(0xFF04121A)

    // Glass
    val GlassFill = Color(0x16FFFFFF)
    val GlassBorder = Color(0x33FFFFFF)
    val Hairline = Color(0x1AFFFFFF)

    // Semantic
    val Success = Color(0xFF6EE7A8)
    val Warning = Amber
    val Error = Rose

    /** The full aurora sweep used by hero cards and progress accents. */
    val Aurora = listOf(Cyan, Orchid, Lime)

    /** A calmer two-stop sweep for small pills and icon medallions. */
    val AuroraCool = listOf(Cyan, Orchid)

    /** Accent glow behind headers (radial gradients). */
    val GlowCyan = Color(0x335FE7F0)
    val GlowOrchid = Color(0x29B494FF)
}

/** Display = Plus Jakarta Sans (geometric, flagship), body = Outfit (soft,
 * highly legible at watch sizes), mono = JetBrains Mono for timers/counters. */
val FlagshipDisplayFont = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans, FontWeight.ExtraBold),
)
val FlagshipBodyFont = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium),
    Font(R.font.outfit, FontWeight.SemiBold),
    Font(R.font.outfit, FontWeight.Bold),
)
private val FlagshipMonoFont = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold),
)

val FlagshipTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FlagshipDisplayFont,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 27.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.7).sp,
        textAlign = TextAlign.Center,
    ),
    displayMedium = TextStyle(
        fontFamily = FlagshipDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.5).sp,
        textAlign = TextAlign.Center,
    ),
    titleLarge = TextStyle(
        fontFamily = FlagshipDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FlagshipDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FlagshipBodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FlagshipBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FlagshipBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FlagshipDisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FlagshipBodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FlagshipBodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
    ),
)

/** Build a cold-obsidian Material 3 scheme around the chosen accent colour.
 * Dynamic artwork tint still works: the accent drives primary/secondary. */
fun flagshipColorScheme(accent: Color, light: Boolean = false): ColorScheme {
    if (light) return lightFlagshipColorScheme(accent)
    return ColorScheme(
        primary = accent,
        primaryDim = lerp(accent, Color.White, 0.18f),
        primaryContainer = lerp(accent, Color.Black, 0.62f),
        onPrimary = FlagshipColors.TextOnAccent,
        onPrimaryContainer = lerp(accent, Color.White, 0.6f),
        secondary = FlagshipColors.Orchid,
        secondaryDim = lerp(FlagshipColors.Orchid, Color.Black, 0.35f),
        secondaryContainer = Color(0xFF2B1F4A),
        onSecondary = FlagshipColors.TextOnAccent,
        onSecondaryContainer = Color(0xFFE9DEFF),
        tertiary = FlagshipColors.Lime,
        onTertiary = Color(0xFF10240A),
        surfaceContainer = FlagshipColors.Surface,
        surfaceContainerLow = FlagshipColors.SurfaceLow,
        surfaceContainerHigh = FlagshipColors.SurfaceHigh,
        onSurface = FlagshipColors.TextPrimary,
        onSurfaceVariant = FlagshipColors.TextSecondary,
        outline = FlagshipColors.Hairline,
        outlineVariant = Color(0x0DFFFFFF),
        background = FlagshipColors.Canvas,
        onBackground = FlagshipColors.TextPrimary,
        error = FlagshipColors.Error,
        onError = FlagshipColors.TextOnAccent,
    )
}

/**
 * The white / light flagship palette: near-white surfaces, dark ink text and
 * slightly deepened accents for contrast. Same accent flow as the dark theme.
 */
object FlagshipLightColors {
    val Canvas = Color(0xFFF7F7FA)
    val SurfaceLow = Color(0xFFEDEDF2)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF1F1F6)
    val SurfaceRaised = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF15171C)
    val TextSecondary = Color(0xFF4A4E58)
    val TextMuted = Color(0xFF7A7F8A)
    val TextOnAccent = Color(0xFFFFFFFF)
    val Hairline = Color(0x1F000000)
    val GlassFill = Color(0x0FFFFFFF)
    val GlassBorder = Color(0x33000000)
    val Orchid = Color(0xFF7C5CD6)
    val Lime = Color(0xFF5A8F2A)
    val Amber = Color(0xFF9A6A12)
    val Rose = Color(0xFFC04A68)
    val Error = Color(0xFFB3261E)
}

private fun lightFlagshipColorScheme(accent: Color): ColorScheme = ColorScheme(
    primary = accent,
    primaryDim = lerp(accent, Color.White, 0.22f),
    primaryContainer = lerp(accent, Color.White, 0.86f),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = FlagshipLightColors.TextPrimary,
    secondary = FlagshipLightColors.Orchid,
    secondaryDim = lerp(FlagshipLightColors.Orchid, Color.White, 0.35f),
    secondaryContainer = Color(0xFFE7DEF8),
    onSecondary = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF2B1F4A),
    tertiary = FlagshipLightColors.Lime,
    onTertiary = Color(0xFFFFFFFF),
    surfaceContainer = FlagshipLightColors.Surface,
    surfaceContainerLow = FlagshipLightColors.SurfaceLow,
    surfaceContainerHigh = FlagshipLightColors.SurfaceHigh,
    onSurface = FlagshipLightColors.TextPrimary,
    onSurfaceVariant = FlagshipLightColors.TextSecondary,
    outline = FlagshipLightColors.Hairline,
    outlineVariant = Color(0x0D000000),
    background = FlagshipLightColors.Canvas,
    onBackground = FlagshipLightColors.TextPrimary,
    error = FlagshipLightColors.Error,
    onError = Color(0xFFFFFFFF),
)

/**
 * The surface/text palette a screen reads. Components take it from
 * [LocalFlagshipColors] so the same code renders the dark OR white palette
 * without a second set of components.
 */
data class FlagshipPalette(
    val canvas: Color,
    val surfaceLow: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textOnAccent: Color,
    val hairline: Color,
    val glassFill: Color,
    val glassBorder: Color,
    val orchid: Color,
    val lime: Color,
    val amber: Color,
    val rose: Color,
    val error: Color,
)

private fun darkFlagshipPalette() = FlagshipPalette(
    canvas = FlagshipColors.Canvas,
    surfaceLow = FlagshipColors.SurfaceLow,
    surface = FlagshipColors.Surface,
    surfaceHigh = FlagshipColors.SurfaceHigh,
    surfaceRaised = FlagshipColors.SurfaceRaised,
    textPrimary = FlagshipColors.TextPrimary,
    textSecondary = FlagshipColors.TextSecondary,
    textMuted = FlagshipColors.TextMuted,
    textOnAccent = FlagshipColors.TextOnAccent,
    hairline = FlagshipColors.Hairline,
    glassFill = FlagshipColors.GlassFill,
    glassBorder = FlagshipColors.GlassBorder,
    orchid = FlagshipColors.Orchid,
    lime = FlagshipColors.Lime,
    amber = FlagshipColors.Amber,
    rose = FlagshipColors.Rose,
    error = FlagshipColors.Error,
)

private fun lightFlagshipPalette() = FlagshipPalette(
    canvas = FlagshipLightColors.Canvas,
    surfaceLow = FlagshipLightColors.SurfaceLow,
    surface = FlagshipLightColors.Surface,
    surfaceHigh = FlagshipLightColors.SurfaceHigh,
    surfaceRaised = FlagshipLightColors.SurfaceRaised,
    textPrimary = FlagshipLightColors.TextPrimary,
    textSecondary = FlagshipLightColors.TextSecondary,
    textMuted = FlagshipLightColors.TextMuted,
    textOnAccent = FlagshipLightColors.TextOnAccent,
    hairline = FlagshipLightColors.Hairline,
    glassFill = FlagshipLightColors.GlassFill,
    glassBorder = FlagshipLightColors.GlassBorder,
    orchid = FlagshipLightColors.Orchid,
    lime = FlagshipLightColors.Lime,
    amber = FlagshipLightColors.Amber,
    rose = FlagshipLightColors.Rose,
    error = FlagshipLightColors.Error,
)

/** Provided by [FlagshipTheme]; defaults to the dark palette. */
val LocalFlagshipColors = staticCompositionLocalOf { darkFlagshipPalette() }

/**
 * Apply the flagship look. Reads the app-wide [LocalWearsicAccent] so the
 * user's saved colour / dynamic artwork tint still flows through the redesign.
 * [light] selects the white palette from Settings.
 */
@Composable
fun FlagshipTheme(light: Boolean = false, content: @Composable () -> Unit) {
    val accent = LocalWearsicAccent.current
    val scheme = remember(accent, light) { flagshipColorScheme(accent, light) }
    val palette = remember(light) { if (light) lightFlagshipPalette() else darkFlagshipPalette() }
    androidx.compose.runtime.CompositionLocalProvider(LocalFlagshipColors provides palette) {
        MaterialTheme(colorScheme = scheme, typography = FlagshipTypography) {
            content()
        }
    }
}
