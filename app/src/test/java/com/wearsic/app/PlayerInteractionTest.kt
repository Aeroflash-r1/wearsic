package com.wearsic.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.screens.PlayerScreen
import com.wearsic.app.ui.theme.WearsicTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
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
}
