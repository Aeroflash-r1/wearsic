package com.wearsic.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import kotlinx.coroutines.delay

/**
 * Bezel/crown scrolling for list screens.
 *
 * DELEGATES to the framework's [rotaryScrollable]: the previous hand-rolled
 * focusable()+requestFocus plumbing intermittently ended up with no focus
 * target, so a notch produced only the system's touch-bezel tick and NO
 * scroll ("works and not working" on the real Galaxy Watch7). The framework
 * owns focus acquisition, event routing and rotary haptics — every notch
 * snaps the list like a built-in Wear app.
 *
 * AUTO-FOCUS: `rotaryScrollable` creates its own focus target but does NOT
 * grab focus by itself; a notch only routes to it while focused. Claim focus
 * on composition. [FocusRequester.requestFocus] throws while the node is not
 * laid out yet (cold screen entry) and a single failed attempt used to leave
 * the bezel dead until something else took focus — retry briefly, then stop
 * (never fighting a later explicit focus request).
 *
 * REVERSE DIRECTION: the framework default scrolls opposite to the rest of
 * the app's rotary gestures (real-device report: "works but inverted"). We
 * align to the previous behavior — [reverseDirection] default true.
 *
 * HAPTICS: the framework's built-in rotary haptics feel different from the
 * old system bezel tick. We disable the framework haptic and keep the
 * platform's own tick (the old feel).
 */
@Composable
fun Modifier.wearsicRotaryScroll(
    listState: ScalingLazyListState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true,
    reverseDirection: Boolean = true,
): Modifier {
    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(5) {
            if (runCatching { focusRequester.requestFocus() }.getOrNull() == true) {
                return@LaunchedEffect
            }
            delay(40)
        }
    }
    return this.rotaryScrollable(
        RotaryScrollableDefaults.snapBehavior(
            listState,
            0.dp,
            false,
        ),
        focusRequester,
        reverseDirection,
    )
}
