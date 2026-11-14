package com.wearsic.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import com.wearsic.app.model.LyricLine
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.screens.LyricsFullscreen
import com.wearsic.app.ui.screens.PlayerScreen
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.util.ArtworkColors
import com.wearsic.app.ui.viewmodel.LyricsViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class PlayerInteractionTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun emptyPlayer_disablesTransport() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("player_previous_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("player_next_button").assertIsNotEnabled()
    }

    @Test
    fun pageSwitch_opensActions_andReturnsToTransport() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(currentTrack = Track(id = "track", title = "Song", artist = "Artist")),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.onNodeWithTag("player_favorite_button").assertExists()
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        // The return flip disposes page 1 and REcomposes page 0 from scratch
        // in the same frame batch; don't race that recomposition — wait for
        // the transport nodes to (re)materialize before asserting.
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule
                .onAllNodesWithTag("player_play_pause_button")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("player_play_pause_button").assertExists()
    }

    @Test
    fun pageSwitch_return_diagnostic() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(currentTrack = Track(id = "track", title = "Song", artist = "Artist")),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        fun count(tag: String): Int =
            composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

        fun diag(step: String) {
            val pp = count("player_play_pause_button")
            val fav = count("player_favorite_button")
            val np = count("nowplaying_page_root")
            val ac = count("actions_page_root")
            val dots = count("player_page_switch")
            val cd = try {
                composeTestRule.onNodeWithTag("player_page_switch")
                    .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
            } catch (e: Exception) { "n/a" }
            println("PAGEDIAG $step: playPause=$pp favorite=$fav nowplayingRoot=$np actionsRoot=$ac dots=$dots cd=[$cd]")
        }

        diag("t0 composed")
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            count("actions_page_root") == 1
        }
        diag("after click1 (actions visible)")
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            count("nowplaying_page_root") == 1
        }
        diag("after click2 (nowplaying visible)")
        composeTestRule.onNodeWithTag("player_play_pause_button").assertExists()
    }

    @Test
    fun nowPlaying_keepsEveryControlInsideTheRoundFace() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(
                            id = "track",
                            title = "A Very Long Song Title That Must Not Push Controls Off Screen",
                            artist = "A Very Long Artist Name Indeed"
                        ),
                        isPlaying = true,
                        durationMs = 240_000L,
                        currentPositionMs = 61_000L
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        // All four controls and the page indicator are on the face at once:
        // the round-screen composition never requires scrolling.
        composeTestRule.onNodeWithTag("player_play_pause_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_previous_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_next_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_page_switch").assertIsDisplayed()
    }

    @Test
    fun nowPlaying_keepsALongSongTitleOnOneLine() {
        val longTitle = "An Extremely Long Song Title That Must Never Be Allowed To Wrap Onto A Second Line On A Round Watch Face"
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(
                            id = "track",
                            title = longTitle,
                            artist = "Artist With A Long Name"
                        ),
                        isPlaying = true,
                        durationMs = 240_000L
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }

        val titleHeight = composeTestRule
            .onNodeWithText(longTitle, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        // The artist line is a known single line, so it works as a
        // density-independent yardstick. Measured on WearOSLargeRound both
        // report 36px (one 18sp title line vs the artist's 11sp line plus its
        // 3dp top padding). A title that wrapped would be ~72px, so a 1.5x
        // bound separates the two cases with room to spare. This is what
        // proves the title scrolls sideways instead of reflowing.
        val artistHeight = composeTestRule
            .onNode(hasText("Artist With A Long Name", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().size.height

        assertTrue(
            "Long title must stay on one scrolling line (title ${titleHeight}px, artist line ${artistHeight}px)",
            titleHeight <= artistHeight * 1.5f
        )
    }

    @Test
    fun nowPlaying_showsElapsedAndTotalTime() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(id = "t", title = "Song", artist = "Artist"),
                        durationMs = 245_000L,
                        currentPositionMs = 65_000L
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.onNodeWithText("Artist · 1:05 / 4:05").assertExists()
    }

    @Test
    fun songRow_longPress_doesNotAlsoPlay() {
        var clicks = 0
        var longClicks = 0
        composeTestRule.setContent {
            WearsicTheme {
                WearsicSongRow(
                    title = "Song", artist = "Artist", artworkUrl = null,
                    onClick = { clicks++ }, onLongClick = { longClicks++ }
                )
            }
        }
        composeTestRule.onNodeWithTag("song_row").performTouchInput { longClick() }
        assertEquals(0, clicks)
        assertEquals(1, longClicks)
        composeTestRule.onNodeWithTag("song_row").performClick()
        assertEquals(1, clicks)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun nowPlaying_rotaryScroll_showsVolumeHudInsideBounds_andAutoHides() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(id = "t", title = "Song", artist = "Artist"),
                        isPlaying = true,
                        durationMs = 200_000L
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.onNodeWithTag("nowplaying_page_root")
            .performRotaryScrollInput { rotateToScrollVertically(24f) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(200L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("player_volume_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_volume_percent").assertIsDisplayed()

        // Ensure the floating pill and overlay stay strictly inside the watch viewport.
        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val pillBounds = composeTestRule.onNodeWithTag("player_volume_percent")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Volume pill left must be inside screen", pillBounds.left >= rootBounds.left)
        assertTrue("Volume pill right must not clip", pillBounds.right <= rootBounds.right)
        assertTrue("Volume pill top must clear top edge", pillBounds.top > rootBounds.top)

        // Auto-hide removes the overlay after the inactivity timeout + fade-out.
        composeTestRule.mainClock.advanceTimeBy(2_300L)
        composeTestRule.waitForIdle()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("player_volume_overlay").fetchSemanticsNodes().size
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun actionsPage_rotaryScroll_doesNotTriggerNowPlayingVolumeHud() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(id = "t", title = "Song", artist = "Artist"),
                        isPlaying = true
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithTag("actions_page_root").fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("actions_page_root")
            .performRotaryScrollInput { rotateToScrollVertically(24f) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(180L)
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("player_volume_overlay").fetchSemanticsNodes().size
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pageSwitchRoundTrip_reacquiresNowPlayingRotaryVolumeFocus() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(id = "t", title = "Song", artist = "Artist"),
                        isPlaying = true
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithTag("actions_page_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("player_page_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 3_000) {
            composeTestRule.onAllNodesWithTag("nowplaying_page_root").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("nowplaying_page_root")
            .performRotaryScrollInput { rotateToScrollVertically(24f) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(180L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("player_volume_overlay").assertIsDisplayed()
        composeTestRule.onNodeWithTag("player_volume_percent").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound, sdk = [36])
    @Test
    fun compactRoundFace_volumePillStaysInsideInscribedWatchCircle() {
        composeTestRule.setContent {
            WearsicTheme {
                PlayerScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = Track(id = "t", title = "Song", artist = "Artist"),
                        isPlaying = true
                    ),
                    onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
                    onToggleFavorite = {}, onNavigateToVolume = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.onNodeWithTag("nowplaying_page_root")
            .performRotaryScrollInput { rotateToScrollVertically(24f) }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(180L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("player_volume_overlay").assertIsDisplayed()
        val root = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val pill = composeTestRule.onNodeWithTag("player_volume_percent")
            .fetchSemanticsNode().boundsInRoot

        val cx = root.width / 2f
        val cy = root.height / 2f
        val r = minOf(root.width, root.height) / 2f
        val corners = listOf(
            pill.left to pill.top,
            pill.right to pill.top,
            pill.left to pill.bottom,
            pill.right to pill.bottom
        )
        corners.forEach { (x, y) ->
            val dx = x - cx
            val dy = y - cy
            assertTrue(
                "Volume pill corner ($x,$y) must stay inside compact circle (r=$r)",
                dx * dx + dy * dy < r * r
            )
        }
    }

    @Test
    fun lyrics_synced_firstMiddleAndLastLine_remainVerticallyCentered() {
        val sampleLines = listOf(
            "Look at the stars",
            "Look how they shine for you",
            "And everything you do",
            "Yeah, they were all yellow",
            "I came along and wrote a song for you today",
            "And all the things you do keep shining bright",
            "It was called Yellow in the midnight glow",
            "Your skin, oh yeah, your skin and bones",
            "Turn into something beautiful",
            "You know I love you so",
            "You know I love you so much more",
            "Final chorus fades into the night"
        ).mapIndexed { idx, text -> LyricLine(timeMs = idx * 4_000L, text = text) }

        var activeIndex by mutableIntStateOf(0)
        composeTestRule.setContent {
            WearsicTheme {
                LyricsFullscreen(
                    track = Track(id = "track-lyrics", title = "Yellow", artist = "Coldplay"),
                    lyricsState = LyricsViewModel.LyricsUiState(
                        trackId = "track-lyrics",
                        mode = LyricsMode.Synced,
                        source = "LRCLIB",
                        lines = sampleLines
                    ),
                    activeIndex = activeIndex,
                    scrollState = rememberScrollState(),
                    colors = ArtworkColors.Default,
                    onExit = {},
                    onRetry = {}
                )
            }
        }

        val indicesToCheck = listOf(0, 5, sampleLines.lastIndex)
        indicesToCheck.forEach { targetIndex ->
            activeIndex = targetIndex
            composeTestRule.waitForIdle()
            composeTestRule.mainClock.advanceTimeBy(500L)
            composeTestRule.waitForIdle()

            val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
            val headerExitBounds = composeTestRule.onNodeWithTag("lyrics_back_button")
                .fetchSemanticsNode().boundsInRoot
            val activeNode = composeTestRule.onNodeWithTag("lyrics_active_line")
            activeNode.assertIsDisplayed()
            val activeBounds = activeNode.fetchSemanticsNode().boundsInRoot

            val centerDelta = abs(activeBounds.center.y - rootBounds.center.y)
            assertTrue(
                "Active lyric line #$targetIndex center (${activeBounds.center.y}) must be near screen center (${rootBounds.center.y}), delta=$centerDelta",
                centerDelta <= 12f
            )
            assertTrue(
                "Active lyric line #$targetIndex top (${activeBounds.top}) must clear header (${headerExitBounds.bottom})",
                activeBounds.top > headerExitBounds.bottom
            )
            assertTrue(
                "Active lyric line #$targetIndex bottom (${activeBounds.bottom}) must stay above bottom bezel (${rootBounds.bottom})",
                activeBounds.bottom < rootBounds.bottom
            )
        }
    }

    @Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound, sdk = [36])
    @Test
    fun lyrics_compactRoundFace_activeLineStaysCenteredAndClearsHeader() {
        val sampleLines = (0 until 10).map { idx ->
            val text = if (idx == 6) {
                "Highlighted lyric wrapping across two lines on compact watch"
            } else {
                "Line number $idx in the song"
            }
            LyricLine(timeMs = idx * 3_500L, text = text)
        }

        composeTestRule.setContent {
            WearsicTheme {
                LyricsFullscreen(
                    track = Track(id = "track-compact", title = "Song", artist = "Artist"),
                    lyricsState = LyricsViewModel.LyricsUiState(
                        trackId = "track-compact",
                        mode = LyricsMode.Synced,
                        source = "LRCLIB",
                        lines = sampleLines
                    ),
                    activeIndex = 6,
                    scrollState = rememberScrollState(),
                    colors = ArtworkColors.Default,
                    onExit = {},
                    onRetry = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(500L)
        composeTestRule.waitForIdle()

        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val headerExitBounds = composeTestRule.onNodeWithTag("lyrics_back_button")
            .fetchSemanticsNode().boundsInRoot
        val activeNode = composeTestRule.onNodeWithTag("lyrics_active_line")
        activeNode.assertIsDisplayed()
        val activeBounds = activeNode.fetchSemanticsNode().boundsInRoot

        val centerDelta = abs(activeBounds.center.y - rootBounds.center.y)
        assertTrue(
            "Compact face active lyric center (${activeBounds.center.y}) must match screen center (${rootBounds.center.y}), delta=$centerDelta",
            centerDelta <= 12f
        )
        assertTrue(
            "Compact face active lyric top (${activeBounds.top}) must clear header (${headerExitBounds.bottom})",
            activeBounds.top > headerExitBounds.bottom
        )
        assertTrue(
            "Compact face active lyric bottom (${activeBounds.bottom}) must stay inside viewport (${rootBounds.bottom})",
            activeBounds.bottom < rootBounds.bottom
        )
    }
}


