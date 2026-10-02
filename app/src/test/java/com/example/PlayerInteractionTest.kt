package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.example.model.PlaybackUiState
import com.example.model.Track
import com.example.ui.components.WearsicSongRow
import com.example.ui.screens.PlayerScreen
import com.example.ui.theme.WearsicTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
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
