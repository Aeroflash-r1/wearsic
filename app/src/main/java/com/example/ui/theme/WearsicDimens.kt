package com.example.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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

    // ---- Content sizes ----
    /** Song-row / list thumbnail (also the Coil request size baseline). */
    val RowThumbnail: Dp = 40.dp

    /** Player artwork on the 44mm screen. */
    val PlayerArtwork: Dp = 46.dp

    /** Minimum row height inside action sheets and menus. */
    val SheetRowMinHeight: Dp = 44.dp
}

/**
 * Canonical content padding for scrolling screens: the Wearsic design
 * padding MERGED with the ScreenScaffold insets (never smaller than either,
 * never doubled). Screens must use this instead of raw literals so system
 * UI (time text, gesture areas, curved insets) is always respected while
 * the visual safe area stays identical on devices that report no insets.
 */
@Composable
fun wearsicListContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    val start = maxOf(WearsicDimens.ScreenPaddingH, scaffoldPadding.calculateStartPadding(direction))
    val end = maxOf(WearsicDimens.ScreenPaddingH, scaffoldPadding.calculateEndPadding(direction))
    val top = maxOf(WearsicDimens.ScreenPaddingV, scaffoldPadding.calculateTopPadding())
    val bottom = maxOf(WearsicDimens.ScreenPaddingV, scaffoldPadding.calculateBottomPadding())
    return PaddingValues(start = start, top = top, end = end, bottom = bottom)
}
