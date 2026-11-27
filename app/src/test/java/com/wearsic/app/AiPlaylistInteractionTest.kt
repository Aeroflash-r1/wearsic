package com.wearsic.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.wearsic.app.ui.screens.PlaylistsScreen
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class AiPlaylistInteractionTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun createButtonSubmitsNameAndVibe() {
        var created: Pair<String, String?>? = null
        composeTestRule.setContent {
            WearsicTheme {
                PlaylistsScreen(
                    playlistsState = PlaylistsUiState(),
                    onRefresh = {},
                    onNavigateToFavorites = {},
                    onOpenPlaylist = {},
                    onCreateAiPlaylist = { name, genre -> created = name to genre },
                )
            }
        }

        composeTestRule.onNodeWithTag("ai_playlist_button").performClick()
        composeTestRule.onNodeWithText("Playlist name...").performTextInput("Evening")
        composeTestRule.onNodeWithText("Chill").performClick()
        composeTestRule.onNodeWithText("Create AI Playlist").performClick()

        assertEquals("Evening" to "chill", created)
    }
}
