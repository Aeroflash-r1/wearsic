package com.wearsic.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
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
 * snaps the list like a built-in Wear app.
 *
 * AUTO-FOCUS: `rotaryScrollable` creates its own focus target but does NOT
 * grab focus by itself; a notch only routes to it while focused. Claim focus
 * on composition. [FocusRequester.requestFocus] throws while the node is not
 * laid out yet (cold screen entry) and a single failed attempt used to leave
 * the bezel dead until something else took focus — retry briefly, then stop
 * (never fighting a later explicit focus request).
 *
 * DIRECTION: the framework contract is reverseDirection = the direction of
 * the backing modifier's touch scroll — for our vertical lists that is the
 * DEFAULT (false, i.e. notch-down advances through the list exactly like
 * touch). 1.9.44 shipped this flipped to `true`, which the user calls
 * "inverted". The default stays touch-aligned; users who prefer the other
 * way flip "Bezel Scroll" in Settings ([LocalRotaryDirectionInverted]).
 *
 * HAPTICS: the framework's rotary haptic handler ticks each notch — that is
 * the per-step feel of every built-in Wear list. 1.9.44 had it disabled,
 * which removed all bezel feedback; it stays on.
 */
@Composable
fun Modifier.wearsicRotaryScroll(
    listState: ScalingLazyListState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true,
    reverseDirection: Boolean = LocalRotaryDirectionInverted.current,
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
            true,
        ),
        focusRequester,
        reverseDirection,
    )
}
