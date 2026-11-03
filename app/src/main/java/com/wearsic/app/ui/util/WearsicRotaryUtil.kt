package com.wearsic.app.ui.util

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlinx.coroutines.delay

@Composable
fun Modifier.wearsicRotaryScroll(
    listState: ScalingLazyListState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true
): Modifier {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        // requestFocus() throws when the target is not laid out yet (a screen
        // entering composition on a slower device) — a single failed attempt
        // used to leave the bezel dead until something else focused. Retry a
        // few times across the first half-second, then stop (never fighting
        // a TextField the user later focused).
        repeat(3) { attempt ->
            try {
                focusRequester.requestFocus()
                return@LaunchedEffect
            } catch (_: Exception) {
                if (attempt == 2) return@LaunchedEffect
                delay(160)
            }
        }
    }
    return this
        .focusRequester(focusRequester)
        .focusable()
        .onRotaryScrollEvent { event ->
            if (enabled && event.verticalScrollPixels != 0f) {
                listState.dispatchRawDelta(event.verticalScrollPixels)
                true
            } else {
                false
            }
        }
}
