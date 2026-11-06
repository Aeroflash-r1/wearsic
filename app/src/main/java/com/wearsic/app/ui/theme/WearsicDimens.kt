package com.wearsic.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The Wearsic dimension system — tuned for the 44mm round Galaxy Watch 7
 * display (~240dp circular viewport).
 *
 * Every screen used to scatter its own literals (14/18, 14/14, 22/30…).
 * These tokens give the app one consistent safe-area and spacing language:
 *
 *   outer edge → ScreenPadding → content → ScreenPadding → outer edge
 *
 * Values are deliberately conservative: on a round display the usable width
 * shrinks toward the top and bottom of the circle, so full-width content
 * needs a horizontal inset at mid-height and ScalingLazyColumn's edge
 * scaling takes care of the curvature.
 */
object WearsicDimens {

    // ---- Round-screen safe area ----
    /** Horizontal inset for full-width rows on the circular viewport. */
    val ScreenPaddingH: Dp = 14.dp

    /** Top/bottom content inset (before system insets are added). */
    val ScreenPaddingV: Dp = 18.dp

    // ---- Spacing scale ----
    val SpacingXs: Dp = 2.dp
    val SpacingS: Dp = 4.dp
    val SpacingM: Dp = 8.dp
    val SpacingL: Dp = 12.dp
    val SpacingXl: Dp = 16.dp

    // ---- Touch targets ----
    /** Minimum comfortable touch box for a tappable control on Wear OS. */
    val TouchTarget: Dp = 44.dp

    // ---- Corner radii ----
    /**
     * The ONE radius for cards, pills and action rows. The player's action
     * rows (18dp), the library hero card (24dp) and every list pill (20dp)
     * were three different corners on the same two screens, which read as
     * three components instead of one design language.
     */
    val CardRadius: Dp = 20.dp

    /** Radius for square artwork thumbnails (hero chip, list rows). */
    val ArtRadius: Dp = 12.dp

    // ---- Content sizes ----
    /** Song-row / list thumbnail (also the Coil request size baseline). */
    val RowThumbnail: Dp = 40.dp

    /** Player artwork on the 44mm screen. */
    val PlayerArtwork: Dp = 46.dp

    /** Minimum row height inside action sheets and menus. */
    val SheetRowMinHeight: Dp = 44.dp

    // ---- Viewport-aware scaling ----
    /**
     * The logical width (dp) the tokens above were tuned for — a 44mm round
     * watch (~240dp circle). On faces that report less logical room (some
     * 40mm devices and emulators), fixed dp sizes that are comfortable here
     * consume a bigger share of the circle and can clip at the curved edge;
     * [rememberWatchScale] shrinks them in lockstep instead of shipping a
     * second, hand-maintained layout.
     */
    const val BaselineViewportDp = 240

    /** Floor so content never becomes fiddly on tiny faces. */
    const val MinWatchScale = 0.85f

    /**
     * Anything larger than the baseline is left alone: a face that reports
     * MORE logical room (older 40mm watches often do) needs no shrinking.
     */
    const val MaxWatchScale = 1f
}

/**
 * The ratio between this face's logical width and the [WearsicDimens.BaselineViewportDp]
 * reference (clamped to [WearsicDimens.MinWatchScale]..[WearsicDimens.MaxWatchScale]).
 * 1f on a 44mm-class face; <1f on physically smaller/wickedly dense faces.
 */
@Composable
fun rememberWatchScale(): Float {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    return (widthDp / WearsicDimens.BaselineViewportDp)
        .coerceIn(WearsicDimens.MinWatchScale, WearsicDimens.MaxWatchScale)
}

/** [base] scaled by [rememberWatchScale] — call inside a @Composable. */
@Composable
fun watchScaled(base: Dp): Dp = base * rememberWatchScale()

/** Compose the scale once and reuse it for several values. */
@Composable
fun rememberWatchScaledDp(base: Dp): Dp = base * rememberWatchScale()

/**
 * Canonical content padding for scrolling screens: the Wearsic design
 * padding MERGED with the ScreenScaffold insets (never smaller than either,
 * never doubled). Screens must use this instead of raw literals so system
 * UI (time text, gesture areas, curved insets) is always respected while
 * the visual safe area stays identical on devices that report no insets.
 *
 * The base padding scales with [rememberWatchScale] so a smaller/denser
 * round face gets proportionally more breathing room at the curved edges.
 */
@Composable
fun wearsicListContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    val scale = rememberWatchScale()
    val h = WearsicDimens.ScreenPaddingH * scale
    val v = WearsicDimens.ScreenPaddingV * scale
    val start = maxOf(h, scaffoldPadding.calculateStartPadding(direction))
    val end = maxOf(h, scaffoldPadding.calculateEndPadding(direction))
    val top = maxOf(v, scaffoldPadding.calculateTopPadding())
    val bottom = maxOf(v, scaffoldPadding.calculateBottomPadding())
    return PaddingValues(start = start, top = top, end = end, bottom = bottom)
}
