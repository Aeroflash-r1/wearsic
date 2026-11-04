package com.wearsic.app.util

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.model.LyricLine
import com.wearsic.app.model.Track
import com.wearsic.app.ui.screens.LyricsFullscreen
import com.wearsic.app.ui.viewmodel.LyricsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the fullscreen lyrics auto-centering BEHAVIOUR on a round face: the
 * active line must end up fully visible and parked BELOW the screen middle
 * (the user-facing guarantee — "upper part hidden by the lyrics UI design").
 * Implementation is measured (onGloballyPositioned) and anchors at 60% of
 * the body, so the test pins the observable outcome, not pixel math.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class LyricsCenteringTest {

    @get:Rule val composeTestRule = createComposeRule()

    // Deliberately UNEVEN heights: short lines and long wrapping lines — the
    // old uniform-pitch centering demonstrably drifted on rows like these.
    private val lines = listOf(
        "Look at the stars",
        "Look how they shine for you and everything that you do so clearly tonight",
        "And everything you do",
        "Yeah, they were all yellow",
        "I came along and wrote a song for the whole world",
        "I wrote a song for you",
        "And all the things you do it was called yellow so fine",
        "And it was called Yellow",
    ).mapIndexed { i, text -> LyricLine(timeMs = i * 4200L, text = text) }

    private data class ActiveRowState(val visible: Boolean, val centerY: Float, val screenHeight: Float)

    private fun activeRowState(): ActiveRowState? = try {
        val screen = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val active = composeTestRule.onNodeWithTag("lyrics_active_line")
            .fetchSemanticsNode().boundsInRoot
        ActiveRowState(
            visible = active.top >= 0f && active.bottom <= screen.height,
            centerY = active.center.y,
            screenHeight = screen.height,
        )
    } catch (_: Exception) {
        null
    }

    @Test
    fun `active line ends up fully visible and below middle no matter which line activates`() {
        val active = mutableStateOf(0)
        composeTestRule.setContent {
            LyricsFullscreen(
                track = Track(id = "t1", title = "Yellow", artist = "Coldplay"),
                lyricsState = LyricsViewModel.LyricsUiState(
                    trackId = "t1",
                    mode = LyricsMode.Synced,
                    source = "LRCLIB",
                    lines = lines,
                ),
                activeIndex = active.value,
                scrollState = rememberScrollState(),
                colors = com.wearsic.app.ui.util.ArtworkColors.Default,
                onExit = {},
                onRetry = {},
            )
        }

        for (index in listOf(0, 6, 2, 7)) {
            active.value = index
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                val s = activeRowState()
                s != null && s.visible && s.centerY > s.screenHeight * 0.52f
            }
            val s = checkNotNull(activeRowState()) {
                "lyrics_active_line missing for index $index"
            }
            check(s.visible) { "line $index clipped by the face" }
            check(s.centerY > s.screenHeight * 0.52f) {
                "line $index parked above middle: center=${s.centerY} on ${s.screenHeight}px"
            }
        }
    }
}
