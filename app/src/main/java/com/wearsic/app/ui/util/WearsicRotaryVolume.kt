package com.wearsic.app.ui.util

import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import kotlin.math.round
import kotlinx.coroutines.delay

/**
 * Maps one rotary scroll delta to volume steps: one crown notch = exactly
 * one step, a fast spin = several, and a sub-notch event still steps once
 * in its direction (the same convention [rotaryVolumeStep] pinned for the
 * Sound screen). Positive scroll (crown forward) = volume up.
 *
 * Pure so JVM unit tests can pin the mapping without rotary hardware.
 */
fun wearsicVolumeStepsForScrollDelta(
    scrollDeltaPx: Float,
    notchPx: Float = 48f,
): Int {
    if (scrollDeltaPx == 0f || notchPx <= 0f) return 0
    val notches = round(scrollDeltaPx / notchPx).toInt()
    return when {
        notches != 0 -> notches
        scrollDeltaPx > 0f -> 1
        else -> -1
    }
}

/**
 * Bezel/crown = VOLUME for the Now Playing page.
 *
 * Delegates to the framework's [rotaryScrollable] exactly like
 * [wearsicRotaryScroll] does for lists — but feeds a scroll-less
 * [rememberScrollableState] whose deltas become discrete volume steps,
 * so focus acquisition, event routing and the short per-notch haptic tick
 * all come from the same proven path (no hand-rolled focus plumbing, no
 * "bezel dead sometimes").
 *
 * FOCUS mirrors [wearsicRotaryScroll]: [rememberActiveFocusRequester] +
 * [Modifier.requestFocusOnHierarchyActive] own the crown while this
 * modifier is enabled. The player screen arms it only while the Now
 * Playing page is up and lyrics are closed — the Actions list and the
 * lyrics sheet keep their own claims when visible, so exactly one owner
 * holds the bezel at any time.
 *
 * @param onVolumeStep Called with +1 / -1 per crown notch (or more per
 *   event on a fast spin); the caller maps steps onto the system media
 *   stream and flashes its own feedback.
 */
@Composable
fun Modifier.wearsicRotaryVolume(
    onVolumeStep: (Int) -> Unit,
    focusRequester: FocusRequester = rememberActiveFocusRequester(),
    enabled: Boolean = true,
    reverseDirection: Boolean = LocalRotaryDirectionInverted.current,
): Modifier {
    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        // Same bounded retry as the list scroller: covers the first-frame
        // windows the hierarchy-active coordinator cannot see.
        repeat(4) {
            if (runCatching { focusRequester.requestFocus() }.getOrNull() == true) {
                return@LaunchedEffect
            }
            delay(60)
        }
    }
    val volumeScroll = rememberScrollableState { delta ->
        onVolumeStep(wearsicVolumeStepsForScrollDelta(delta))
        0f // every delta is consumed as a step; nothing to fill.
    }
    val armed = this.rotaryScrollable(
        RotaryScrollableDefaults.behavior(
            volumeScroll,
            null,          // no snap/fling: notch = one discrete step.
            true,          // per-step short tick haptics, same feel as lists.
        ),
        focusRequester,
        reverseDirection,
    )
    // Arm the coordinator only while this owner really wants the crown —
    // when disabled the Actions list / lyrics sheet reclaim it untouched.
    return if (enabled) armed.requestFocusOnHierarchyActive() else armed
}
