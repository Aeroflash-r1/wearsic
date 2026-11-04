package com.wearsic.app.util

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Pins the fullscreen lyrics auto-centering WIRING: switching the active line
 * must engage the measured auto-center (the shared scroll state moves) and the
 * active row must exist with its highlight tag. Exact pixel geometry is pinned
 * separately in [LyricsAutoCenterTargetTest]; the measured centering itself
 * adds verified on the device.
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

    @Test
    fun `every activation engages the auto-center and shows the highlight`() {
        val active = mutableStateOf(0)
        var scrollState: ScrollState? = null
        composeTestRule.setContent {
            val ss = rememberScrollState()
            scrollState = ss
            LyricsFullscreen(
                track = Track(id = "t1", title = "Yellow", artist = "Coldplay"),
                lyricsState = LyricsViewModel.LyricsUiState(
                    trackId = "t1",
                    mode = LyricsMode.Synced,
                    source = "LRCLIB",
                    lines = lines,
                ),
                activeIndex = active.value,
                scrollState = ss,
                colors = com.wearsic.app.ui.util.ArtworkColors.Default,
                onExit = {},
                onRetry = {},
            )
        }

        // 0 → 6 and 6 → 2 are large moves; both must visibly scroll.
        for (index in listOf(6, 2)) {
            val before = checkNotNull(scrollState).value
            active.value = index
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                checkNotNull(scrollState).value != before
            }
            composeTestRule.onNodeWithTag("lyrics_active_line").assertExists()
        }
    }
}
