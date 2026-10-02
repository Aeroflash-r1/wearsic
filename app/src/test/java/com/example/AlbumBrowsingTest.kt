package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.model.Album
import com.example.ui.screens.AlbumsScreen
import com.example.ui.theme.WearsicTheme
import com.example.ui.viewmodel.LibraryViewModel.AlbumsUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class AlbumBrowsingTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun emptyCatalog_explainsHowToDiscoverAlbums() {
        composeTestRule.setContent {
            WearsicTheme { AlbumsScreen(AlbumsUiState(), {}, {}) }
        }
        composeTestRule.onNodeWithText("Listen from start to finish").assertExists()
    }

    @Test
    fun artworkCard_opensExactAlbum_andDeduplicatesIds() {
        val album = Album(id = "release", name = "A beautiful album", uploader = "Artist", trackCount = 12)
        var selected: Album? = null
        composeTestRule.setContent {
            WearsicTheme {
                AlbumsScreen(AlbumsUiState(query = "album", albums = listOf(album, album)), {}, { selected = it })
            }
        }
        composeTestRule.onNodeWithTag("album_release").performClick()
        assertEquals(album, selected)
    }
}
