package com.wearsic.app.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Cached watch-face scale factor provided once at the theme root so list rows
 * and buttons do not re-read [LocalConfiguration] on every recomposition.
 */
val LocalWatchScale = staticCompositionLocalOf { 1f }

/**
 * The Wearsic dimension system — tuned for the Galaxy Watch 7's 44 mm round
 * display (~230dp circular viewport; see [WearsicDimens.BaselineViewportDp]).
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
    val TouchTarget: Dp = 48.dp

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
    val SheetRowMinHeight: Dp = 48.dp

    // ---- Hub chrome (top rail + mini-player) ----
    /** Height of one section chip in the Hub's top rail. */
    val HubRailChip: Dp = 26.dp

    /** Invisible touch margin per chip side (segmented tap-slop, keeps the row inside the chord). */
    val HubRailChipPad: Dp = 2.dp

    /** Height of the selected-section label under the rail chips. */
    val HubRailLabelLine: Dp = 12.dp

    /** Compact mini-player pill height. */
    val MiniPlayerBarHeight: Dp = 34.dp

    /** Gap between the mini-player pill and the bottom bezel curve. */
    val MiniPlayerEdgeInset: Dp = 10.dp

    /** Extra list breathing room under the mini-player pill (visual, not core). */
    val MiniPlayerBelowGap: Dp = 6.dp

    /** Artwork square on discovery shelves (Home/Mix/Most played). */
    val ShelfCardArt: Dp = 64.dp

    // ---- Viewport-aware scaling ----
    /**
     * The logical width (dp) the tokens above were tuned for — the Galaxy
     * Watch 7's 44 mm round face (450px at ~2.0 density ≈ 228.6dp, kept at
     * 230 for round margin). On faces that report less logical room (40mm
     * devices), fixed dp sizes that are comfortable here consume a bigger
     * share of the circle and can clip at the curved edge; [rememberWatchScale]
     * shrinks them in lockstep instead of shipping a second, hand-maintained
     * layout.
     */
    const val BaselineViewportDp = 230

    /** Floor so content never becomes fiddly on tiny faces. */
    const val MinWatchScale = 0.85f

    /**
     * Anything larger than the baseline is left alone: a face that reports
     * MORE logical room (older 40mm watches often do) needs no shrinking.
     */
    const val MaxWatchScale = 1f
}

/** Compute the viewport scale once (used at theme root). */
@Composable
fun computeWatchScale(): Float {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        (widthDp.toFloat() / WearsicDimens.BaselineViewportDp)
            .coerceIn(WearsicDimens.MinWatchScale, WearsicDimens.MaxWatchScale)
    }
}

/**
 * The ratio between this face's logical width and the [WearsicDimens.BaselineViewportDp]
 * reference (clamped to [WearsicDimens.MinWatchScale]..[WearsicDimens.MaxWatchScale]).
 * Reads [LocalWatchScale] without re-subscribing to configuration on every row.
 */
@Composable
fun rememberWatchScale(): Float = LocalWatchScale.current

/** [base] scaled by [rememberWatchScale] — call inside a @Composable. */
@Composable
fun watchScaled(base: Dp): Dp = base * LocalWatchScale.current

/** Compose the scale once and reuse it for several values. */
@Composable
fun rememberWatchScaledDp(base: Dp): Dp = base * LocalWatchScale.current

/**
 * Canonical content padding for scrolling screens: the Wearsic design
 * padding MERGED with the ScreenScaffold insets (never smaller than either,
 * never doubled). Screens must use this instead of raw literals so system
 * UI (time text, gesture areas, curved insets) is always respected while
 * the visual safe area stays identical on devices that report no insets.
 *
 * The base padding scales with [rememberWatchScale] so a smaller/denser
 * round face gets proportionally more breathing room at the curved edges.
 *
 * Floating app chrome is merged in as FLOORS too: the Hub's section rail
 * raises `top`, the persistent mini-player pill raises `bottom` (see
 * [LocalWearsicTopChromeReserve] / [LocalWearsicMiniPlayerReserve]).
 *
 * ── WHY THIS RESOLVES LAZILY ──────────────────────────────────────────────
 * Wear's [androidx.wear.compose.material3.ScreenScaffold] hands the screen a
 * PaddingValues whose TOP inset is the system status bar (the clock) height —
 * but ONLY while that status bar is visible. The visibility is resolved in
 * the scaffold's `onAttach()`, i.e. AFTER the first composition, so reading
 * the inset during composition returns 0.dp on frame 0. A plain
 * `maxOf(design, scaffoldPadding.calculateTopPadding())` therefore lays the
 * list out with NO top inset on the very first frame: the first row starts
 * under the clock and is clipped by the round bezel.
 *
 * The framework is explicit about this: "pass this padding to containers like
 * TransformingLazyColumn or layout modifiers like Modifier.padding, rather
 * than inspecting its values directly during composition via functions like
 * calculateTopPadding" (ScreenScaffold.kt, contentPadding KDoc).
 *
 * So this returns a PaddingValues implementation that defers EVERY read to
 * the measure phase, where the status-bar state is already resolved. The
 * list then remeasures on its own when the clock slides away or comes back,
 * instead of being laid out under it.
 */
@Composable
fun rememberWearsicListContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
    val density = LocalDensity.current
    val scale = rememberWatchScale()
    // The design padding is static for the life of the composition, so it is
    // resolved here; only the scaffold's own inset has to stay lazy.
    val designH = WearsicDimens.ScreenPaddingH * scale
    val designV = WearsicDimens.ScreenPaddingV * scale
    // Floating chrome arrives pre-scaled: the Hub rail and the mini-player
    // reserve are provided at their final dp, so never re-scale them.
    val topChrome = LocalWearsicTopChromeReserve.current
    val miniPlayer = LocalWearsicMiniPlayerReserve.current

    return remember(scaffoldPadding, density, designH, designV, topChrome, miniPlayer) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
                maxOf(designH, scaffoldPadding.calculateLeftPadding(layoutDirection))

            override fun calculateTopPadding(): Dp =
                maxOf(designV, topChrome, scaffoldPadding.calculateTopPadding())

            override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
                maxOf(designH, scaffoldPadding.calculateRightPadding(layoutDirection))

            override fun calculateBottomPadding(): Dp =
                maxOf(designV, miniPlayer, scaffoldPadding.calculateBottomPadding())

            override fun equals(other: Any?): Boolean =
                this === other ||
                    (other is PaddingValues &&
                        calculateLeftPadding(LayoutDirection.Ltr) ==
                        other.calculateLeftPadding(LayoutDirection.Ltr) &&
                        calculateTopPadding() == other.calculateTopPadding() &&
                        calculateRightPadding(LayoutDirection.Ltr) ==
                        other.calculateRightPadding(LayoutDirection.Ltr) &&
                        calculateBottomPadding() == other.calculateBottomPadding())

            override fun hashCode(): Int {
                var result = calculateLeftPadding(LayoutDirection.Ltr).hashCode()
                result = 31 * result + calculateTopPadding().hashCode()
                result = 31 * result + calculateRightPadding(LayoutDirection.Ltr).hashCode()
                result = 31 * result + calculateBottomPadding().hashCode()
                return result
            }
        }
    }
}
