package com.wearsic.app.ui.util

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import kotlinx.coroutines.delay

/**
 * Bezel/crown = VOLUME for the Now Playing page.
 *
 * The modifier emits RAW vertical-scroll pixels rather than discrete steps,
 * because the two rotary sources behave very differently:
 *
 *  · A mechanical crown fires one clean detent impulse per notch.
 *  · A touch bezel streams sub-detent deltas while a finger glides around
 *    the rim — mapping every one of those to a step would blast the volume
 *    to the limit during a single slow drag.
 *
 * The caller therefore smooths the pixels through [RotaryVolumeAccumulator]
 * before turning them into volume steps, which keeps one detent = one step
 * while a continuous drag never overshoots.
 *
 * FOCUS: [requestFocusOnHierarchyActive] plus a [FocusRequester] own the
 * crown while this modifier is enabled, and the node joins a
 * [hierarchicalFocusGroup] so the Actions list and the lyrics sheet can
 * reclaim the bezel untouched. Exactly one owner is armed at any time, so
 * rotary events always land on the visible surface.
 *
 * @param onVolumePixels called with the raw vertical scroll delta, in pixels.
 *   Return false to leave the event unconsumed (it then keeps travelling up
 *   the hierarchy), true to consume it.
 */
@Composable
fun Modifier.wearsicRotaryVolumePixels(
    onVolumePixels: (Float) -> Boolean,
    enabled: Boolean = true,
): Modifier {
    val focusRequester = remember { FocusRequester() }
    // The gesture lambda is created once; without this it would capture a
    // stale callback and keep calling it after the screen recomposed.
    val latestHandler by rememberUpdatedState(onVolumePixels)

    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        // Bounded retry: covers the first-frame windows the hierarchy-active
        // coordinator cannot see yet (same recovery as the list scroller).
        repeat(4) {
            if (runCatching { focusRequester.requestFocus() }.getOrNull() == true) {
                return@LaunchedEffect
            }
            delay(60)
        }
    }

    return this
        .hierarchicalFocusGroup(active = enabled)
        .then(
            if (enabled) {
                Modifier
                    .onRotaryScrollEvent { event -> latestHandler(event.verticalScrollPixels) }
                    .requestFocusOnHierarchyActive()
                    .focusRequester(focusRequester)
                    .focusable()
            } else {
                Modifier
            }
        )
}
