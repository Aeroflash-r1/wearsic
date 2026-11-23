package com.wearsic.app.ui.theme

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Wearsic 2.0 shape- & size-adaptive layout spec (blueprint §2 "Automatic
 * Watch Size & Shape Adaptation"). Computed once at the root and provided via
 * [LocalWearAdaptiveSpec] so every screen adapts in O(1) without re-reading
 * configuration per frame.
 *
 * - Shape: round watches need bezel-chord insets near the 12/6 o'clock curves;
 *   square/rectangular watches use clean uniform edge margins.
 * - Size tiers: Compact <192dp (40mm), Standard 192–224dp, Large ≥225dp
 *   (44–47mm / Ultra). Larger faces get richer density (side-by-side hero
 *   tiles, bigger preview art), compact faces get protection from clipping.
 */
enum class WatchSizeTier { Compact, Standard, Large }

data class WearAdaptiveSpec(
    val isRound: Boolean,
    val sizeTier: WatchSizeTier,
    val widthDp: Int,
    val heightDp: Int,
    /** Proportional scale factor (compact faces shrink tokens proportionally). */
    val scale: Float,
) {
    val isCompact: Boolean get() = sizeTier == WatchSizeTier.Compact
    val isLarge: Boolean get() = sizeTier == WatchSizeTier.Large

    /** ≥192dp shows hero tiles side-by-side; <192dp stacks them. */
    val heroTilesInRow: Boolean get() = sizeTier != WatchSizeTier.Compact

    /** Song-row / list thumbnail size for this tier. */
    val rowThumbnail: Dp = when (sizeTier) {
        WatchSizeTier.Compact -> 36.dp
        WatchSizeTier.Standard -> 40.dp
        WatchSizeTier.Large -> 52.dp
    }

    /** Larger artwork preview used on Home shelves / hero cards. */
    val shelfArt: Dp = when (sizeTier) {
        WatchSizeTier.Compact -> 64.dp
        WatchSizeTier.Standard -> 76.dp
        WatchSizeTier.Large -> 88.dp
    }

    /** Minimum comfortable touch target on this face. */
    val touchTarget: Dp = when (sizeTier) {
        WatchSizeTier.Compact -> 42.dp
        else -> 48.dp
    }

    /** Uniform edge margin. Square faces get clean symmetric margins. */
    val edgeMargin: Dp = (if (isRound) 14.dp else 12.dp) * scale

    /**
     * Horizontal inset required at a vertical distance [fromCenter] from the
     * screen centre so content stays inside the inscribed circle chord on a
     * round watch. Returns 0.dp for square/rectangular faces and at the
     * vertical centre (where the chord is widest).
     */
    fun chordInset(fromCenter: Dp): Dp {
        if (!isRound) return 0.dp
        val radius = widthDp / 2f
        val y = fromCenter.value.coerceIn(0f, radius)
        val halfChord = sqrt((radius * radius - y * y).coerceAtLeast(0f))
        return (radius - halfChord).toFloat().dp
    }
}

/** The spec for this device, computed once from window configuration. */
@Composable
fun rememberWearAdaptiveSpec(): WearAdaptiveSpec {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp, config.screenLayout) {
        val round = (config.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK) ==
            Configuration.SCREENLAYOUT_ROUND_YES
        val width = config.screenWidthDp
        val tier = when {
            width < 192 -> WatchSizeTier.Compact
            width < 225 -> WatchSizeTier.Standard
            else -> WatchSizeTier.Large
        }
        val scale = (width.toFloat() / WearsicDimens.BaselineViewportDp)
            .coerceIn(WearsicDimens.MinWatchScale, WearsicDimens.MaxWatchScale)
        WearAdaptiveSpec(
            isRound = round,
            sizeTier = tier,
            widthDp = width,
            heightDp = config.screenHeightDp,
            scale = scale,
        )
    }
}

/** Spec made available to every composable below the theme root. */
val LocalWearAdaptiveSpec = staticCompositionLocalOf {
    WearAdaptiveSpec(
        isRound = true,
        sizeTier = WatchSizeTier.Standard,
        widthDp = 230,
        heightDp = 230,
        scale = 1f,
    )
}
