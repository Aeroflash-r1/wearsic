package com.wearsic.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Wearsic 2.0 motion tokens (blueprint §2 "Spring Physics & Haptics").
 * Centralised so button squish, pager transitions, panel reveals and HUD
 * fades all share one feel instead of per-screen magic numbers.
 */
object WearsicMotion {
    /** Tactile press squish — buttons scale to 0.94f on press. */
    val PressScale = 0.94f

    /** Springs — all animation lives in graphicsLayer/draw phases (no recomposition). */
    fun <T> pressSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    fun <T> morphSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> panelSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow,
    )

    /** HUD / overlay fades. */
    fun <T> hudFade(): AnimationSpec<T> = tween(180, easing = FastOutSlowInEasing)

    /** Page transition (paired with PagerScaffoldDefaults.snapWithSpringFlingBehavior). */
    fun <T> pageFade(): AnimationSpec<T> = tween(220, easing = FastOutSlowInEasing)

    /** Shimmer cycle duration — kept slow enough to avoid distracting flicker. */
    const val ShimmerDurationMs = 1200
}
