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

@Composable
fun Modifier.wearsicRotaryScroll(
    listState: ScalingLazyListState,
    focusRequester: FocusRequester = remember { FocusRequester() },
    enabled: Boolean = true
): Modifier {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
            // Focus request fallback
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
