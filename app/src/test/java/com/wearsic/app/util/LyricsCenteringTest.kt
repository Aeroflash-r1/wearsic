package com.wearsic.app.util

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlin.math.abs

/**
 * Pins the fullscreen lyrics auto-centering BEHAVIOUR (not math): on a round
 * 454px face, whatever line becomes active must end up visually centred.
 * The implementation is measured (onGloballyPositioned of the active row)
 * rather than pitch-arithmetic, precisely because wrapped lines make row
 * heights non-uniform — so the test pins the observable: the active row's
 * centre sits at half the viewport height (± a deadband) no matter which
 * line is active and how much the rows wrap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class LyricsCenteringTest {

    @get:Rule val composeTestRule = createComposeRule()

    // Deliberately UNEVEN heights: short lines, and long lines that wrap —
    // the old uniform-pitch centering demonstrably drifted on rows like
    // these (live device report).
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

    private fun activeCenterOffset(): Float? = try {
        val screenHeight = composeTestRule.onRoot().fetchSemanticsNode()
            .boundsInRoot.height.toFloat()
        val center = composeTestRule.onNodeWithTag("lyrics_active_line")
            .fetchSemanticsNode().boundsInRoot.center.y
        center - screenHeight / 2f
    } catch (_: Exception) {
        null
    }

    @Test
    fun `active line ends up centred no matter which line activates`() {
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

        // Each activation must re-centre — including AFTER the content has
        // already been scrolled (exercises the delta-from-current path).
        for (index in listOf(0, 6, 2, 7)) {
            active.value = index
            composeTestRule.waitUntil(timeoutMillis = 3_000) {
                activeCenterOffset()?.let { abs(it) <= 24f } ?: false
            }
            val offset = activeCenterOffset()
            checkNotNull(offset) { "lyrics_active_line missing for index $index" }
            check(abs(offset) <= 24f) {
                "line $index not centred: offset ${offset}px from viewport middle"
            }
        }
    }
}
