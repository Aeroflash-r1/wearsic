package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import org.junit.Assert.assertEquals
import com.example.model.Track
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.WearsicTheme
import com.example.ui.viewmodel.LibraryViewModel.SearchUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class SearchResultsRenderTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val sampleResults = listOf(
        Track(
            id = "Zkqhiil2kSo",
            title = "Kesariya",
            artist = "Arijit Singh",
            durationMs = 250_000,
            artworkUrl = "https://yt3.googleusercontent.com/GTNqgIvQx2szu5yMc1ReBehP8I4oErbexFAQbPPQEC4X9X6PXrMykP1mrTiUP47RBkhUbbTHEYJ3489p=w60-h60-l90-rj"
        ),
        Track(
            id = "Z0VbANbyH2o",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            durationMs = 261_000,
            artworkUrl = "https://yt3.googleusercontent.com/qdplC9v00uvgYWGJ39cXWHOlPuFaY_NLd5-vNjYMjv0KzKfXZ4aQg-4k8CVT_wZP6b6TYRzu3tzrvzE=w60-h60-l90-rj"
        ),
        Track(
            id = "YALvuUpY_b0",
            title = "A Sky Full of Stars",
            artist = "Coldplay",
            durationMs = 267_000,
            artworkUrl = "https://yt3.googleusercontent.com/mOpz-iyGkbZV_hK31ivj5WQCQL66JBniXOXoNb63CU-DZwrgBVMebFsCN7xVvZdDipRod2QL7LYa67zP=w60-h60-l90-rj"
        ),
        Track(
            id = "wx89ZdkwtS8",
            title = "Photograph",
            artist = "Ed Sheeran",
            durationMs = 258_000,
            artworkUrl = "https://yt3.googleusercontent.com/GTNqgIvQx2szu5yMc1ReBehP8I4oErbexFAQbPPQEC4X9X6PXrMykP1mrTiUP47RBkhUbbTHEYJ3489p=w60-h60-l90-rj"
        ),
        Track(
            id = "q1uPPBJ2tcI",
            title = "Perfect",
            artist = "Ed Sheeran",
            durationMs = 263_000
        ),
        // Deliberate duplicate id: v1.1.0 servers return the same video twice.
        Track(
            id = "Zkqhiil2kSo",
            title = "Kesariya (Acoustic)",
            artist = "Arijit Singh",
            durationMs = 255_000
        ),
        Track(
            id = "pUwpLoLlzNQ",
            title = "Calm Down",
            artist = "Rema",
            durationMs = 239_000
        ),
        Track(
            id = "NJAv_7lHUIU",
            title = "Unstoppable",
            artist = "Sia",
            durationMs = 217_000
        ),
        Track(
            id = "fsiPzT50ZiM",
            title = "Believer",
            artist = "Imagine Dragons",
            durationMs = 204_000
        ),
        Track(
            id = "FOA9iyxsW_A",
            title = "Shape of You",
            artist = "Ed Sheeran",
            durationMs = 233_000
        ),
        Track(
            id = "eXkHvT--DBU",
            title = "Someone Like You",
            artist = "Adele",
            durationMs = 285_000
        )
    )

    @Test
    fun searchResults_render_rows_when_listHasResults() {
        composeTestRule.setContent {
            WearsicTheme {
                SearchScreen(
                    searchState = SearchUiState(
                        query = "arjit singh",
                        results = sampleResults,
                        isSearching = false,
                        hasSearched = true
                    ),
                    onQuerySelected = {},
                    onTrackSelected = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Rows must exist and show their titles. The list is lazy, so assert
        // the rows that compose in the viewport — including the row whose id
        // is duplicated later in the list (v1.1.0 servers do this) — which
        // confirms result rows render without crashing.
        composeTestRule.onNodeWithTag("search_track_Zkqhiil2kSo").assertExists()
        composeTestRule.onNodeWithText("Kesariya").assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToIndex(4)
        composeTestRule.onNodeWithTag("search_track_Z0VbANbyH2o").assertExists()
        composeTestRule.onNodeWithText("Tum Hi Ho").assertExists()
    }

    @Test
    fun searchRow_longPressOpensActions_withoutPlaying() {
        var playCount = 0
        composeTestRule.setContent {
            WearsicTheme {
                SearchScreen(
                    searchState = SearchUiState(query = "music", results = sampleResults.take(1), hasSearched = true),
                    onQuerySelected = {},
                    onTrackSelected = { playCount++ }
                )
            }
        }
        composeTestRule.onNodeWithTag("search_track_Zkqhiil2kSo").performTouchInput { longClick() }
        composeTestRule.onNodeWithTag("track_action_sheet").assertExists()
        assertEquals(0, playCount)
    }

    @Test
    fun searchResults_showEmptyMessage_whenNoResults() {
        composeTestRule.setContent {
            WearsicTheme {
                SearchScreen(
                    searchState = SearchUiState(
                        query = "zzz nothing",
                        results = emptyList(),
                        isSearching = false,
                        hasSearched = true
                    ),
                    onQuerySelected = {},
                    onTrackSelected = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("No tracks found for \"zzz nothing\"").assertExists()
    }
}