package com.wearsic.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlinx.coroutines.delay

/**
 * User's bezel/crown direction preference, provided app-wide from Settings.
 * `false` = the standard direction every built-in Wear app uses (aligned
 * with touch scrolling per the framework's reverseDirection contract).
 * Flippable in Settings because the right feel is wrist-specific.
 */
val LocalRotaryDirectionInverted = staticCompositionLocalOf { false }

/**
 * Bezel/crown scrolling for list screens.
 *
 * DELEGATES to the framework's [rotaryScrollable]: the previous hand-rolled
 * focusable()+requestFocus plumbing intermittently ended up with no focus
 * target, so a notch produced only the system's touch-bezel tick and NO
 * scroll ("works and not working" on the real Galaxy Watch7). The framework
 * owns focus acquisition, event routing and rotary haptics — every notch
 * scrolls like a built-in Wear app.
 *
 * FOCUS (the "bezel sometimes stuck" fix): the framework's own
 * [rememberActiveFocusRequester] + [Modifier.requestFocusOnHierarchyActive]
 * pair owns focus the way built-in apps do — the coordinator claims focus
 * whenever the screen hierarchy becomes active (cold entry, screen off/on,
 * resume after navigation), and a small bounded manual retry covers the
 * first-frame edge cases the coordinator cannot see.
 *
 * BEHAVIOR/HAPTICS: continuous [RotaryScrollableDefaults.behavior] with NO
 * fling — one scroll increment per notch and the framework's per-step
 * haptic: a SHORT tick each encoder step (the old hand-rolled feel). The
 * v1.9.45 snap wiring vibrated once per entire item snap, which read as a
 * long single buzz while jumping (user report). Lyrics text uses the same
 * combination on its own state.
 */
@Composable
fun Modifier.wearsicRotaryScroll(
    listState: ScalingLazyListState,
    // Framework-recommended focus ownership: claims whenever the hierarchy
    // is active. Override only with special cases (dialog flows etc.).
    focusRequester: FocusRequester = rememberActiveFocusRequester(),
    enabled: Boolean = true,
    reverseDirection: Boolean = LocalRotaryDirectionInverted.current,
): Modifier {
    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        // Fallback retry for the one-frame windows the coordinator misses
        // (the node may not be attached on the very first composition pass).
        repeat(4) {
            if (runCatching { focusRequester.requestFocus() }.getOrNull() == true) {
                return@LaunchedEffect
            }
            delay(60)
        }
    }
    val armed = this.rotaryScrollable(
        RotaryScrollableDefaults.behavior(
            listState,
            null,          // no fling: notch = one measured increment
            true,          // per-step short tick haptics (old feel)
        ),
        focusRequester,
        reverseDirection,
    )
    // Arm the hierarchy-active coordinator ONLY while this screen actually
    // wants the crown: inside the player pager the neighboring page stays in
    // the composition tree, and an armed coordinator there would claim focus
    // for an off-screen list (invisible bezel scrolling / focus fights).
    // Dismissable-nav destinations don't need this guard — each destination
    // carries its own lifecycle owner, so a covered screen cannot be active.
    return if (enabled) armed.requestFocusOnHierarchyActive() else armed
}
