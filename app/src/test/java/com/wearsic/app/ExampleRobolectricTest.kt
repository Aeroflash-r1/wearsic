package com.wearsic.app

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.media.AudioOutputHelper
import com.wearsic.app.media.WearsicMediaItemFactory
import com.wearsic.app.model.Track
import com.wearsic.app.network.WearsicMockApiClient
import com.wearsic.app.network.model.ConnectionTestState
import com.wearsic.app.ui.navigation.WearsicApp
import com.wearsic.app.ui.theme.WearsicTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class ExampleRobolectricTest {

    @get:Rule val composeTestRule = createComposeRule()

    /**
     * CI forensics for the 2026-10-18 wedge: the suite used to hang ~50% of
     * runs inside [testMusicRepositoryIntegration] with every thread idle, so
     * a jstack dump could not show what the runBlocking coroutine awaited.
     * These markers are streamed to the CI log (testLogging.showStandardStreams
     * in app/build.gradle.kts) and name the exact suspension point a wedged
     * run dies in; the 30s bounds turn the former 10-minute stall into a
     * fast, self-diagnosing failure.
     */
    private fun probe(message: String) {
        println("WEDGE-PROBE [${Thread.currentThread().name}] $message")
        System.out.flush()
    }

    @Test
    fun testAppNameResource() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Wearsic", appName)
    }

    @Test
    fun testServerUrlValidation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = WearsicPreferencesRepository(context)
        assertTrue(prefs.isValidServerUrl("https://example.com"))
        assertTrue(prefs.isValidServerUrl("http://192.168.1.100:8080"))
        assertTrue(prefs.isValidServerUrl("https://wearsic.server.internal"))
        assertFalse(prefs.isValidServerUrl(""))
        assertFalse(prefs.isValidServerUrl("not-a-url"))
        assertFalse(prefs.isValidServerUrl("ftp://test.com"))
    }

    @Test
    fun testMockApiClient() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockClient = WearsicMockApiClient(context)

        // Health test
        val healthResult = mockClient.checkHealth("https://wearsic.server.internal")
        assertTrue(healthResult.isSuccess)
        val health = healthResult.getOrThrow()
        assertEquals("ok", health.status)

        // Search test
        val searchResult = mockClient.searchTracks("https://wearsic.server.internal", "Weather")
        assertTrue(searchResult.isSuccess)
        val searchDto = searchResult.getOrThrow()
        assertTrue(searchDto.tracks.isNotEmpty())
        assertEquals("Weather with You", searchDto.tracks[0].title)
    }

    @Test
    fun testMusicRepositoryIntegration() = runBlocking {
        probe("start main=${Dispatchers.Main}")

        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockClient = WearsicMockApiClient(context)
        val repository = WearsicMusicRepository(context, httpApiClient = mockClient)
        probe("repository constructed")

        // Pre-configure server URL as the real production app would require
        withTimeout(30_000) { repository.saveServerUrl("https://wearsic.server.internal") }
        probe("saveServerUrl done")

        withTimeout(30_000) {
            val connectionState = repository.testServerConnection("https://wearsic.server.internal")
            probe("testServerConnection(valid) returned")
            assertTrue(connectionState is ConnectionTestState.Success)
        }
        probe("testServerConnection(valid) done")

        withTimeout(30_000) {
            val invalidState = repository.testServerConnection("invalid-url")
            probe("testServerConnection(invalid) returned")
            assertTrue(invalidState is ConnectionTestState.Error)
        }
        probe("testServerConnection(invalid) done")

        withTimeout(30_000) {
            probe("getServerUrl (first dataStore read) begin")
            repository.getServerUrl()
            probe("getServerUrl (first dataStore read) done")

            probe("searchMusic begin")
            val searchResult = repository.searchMusic("Crowded House")
            probe("searchMusic returned")
            assertTrue(searchResult.isSuccess)
            val tracks = searchResult.getOrThrow()
            assertTrue(tracks.isNotEmpty())
        }
        probe("TEST COMPLETE")
    }

    @Test
    fun testMediaItemFactoryCreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracks = WearsicMediaItemFactory.getTestTracks(context)
        assertEquals(2, tracks.size)
        assertEquals("Weather with You", tracks[0].title)
        assertEquals("Crowded House", tracks[0].artist)
        assertEquals("Don't Dream It's Over", tracks[1].title)

        val mediaItems = WearsicMediaItemFactory.buildMediaItems(tracks)
        assertEquals(2, mediaItems.size)
        assertEquals("Weather with You", mediaItems[0].mediaMetadata.title.toString())
        assertEquals("Crowded House", mediaItems[0].mediaMetadata.artist.toString())
    }

    @Test
    fun testAudioOutputHelperDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = AudioOutputHelper.getCurrentOutputInfo(context)
        assertNotNull(output.name)
        assertFalse(output.isBluetooth)
    }

    @Test
    fun testRoomDownloadDatabaseAndRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = WearsicDownloadRepository(context)

        val sampleTrack = Track(
            id = "test_offline_1",
            title = "Offline Song",
            artist = "Wearsic Artist",
            album = "Offline Album",
            durationMs = 5000L,
            mediaUri = "https://wearsic.server.internal/stream/test_offline_1"
        )

        val targetPath = "${context.filesDir}/wearsic_downloads/test_offline_1.mp3"

        // 1. Record queued
        repository.recordQueued(sampleTrack, targetPath)
        val initialDownload = repository.getDownloadFlow("test_offline_1").first()
        assertNotNull(initialDownload)
        assertEquals(DownloadState.QUEUED.name, initialDownload?.downloadState)

        // 2. Update progress
        repository.updateProgress("test_offline_1", 45, 1024000L)
        val inProgressDownload = repository.getDownloadFlow("test_offline_1").first()
        assertEquals(DownloadState.DOWNLOADING.name, inProgressDownload?.downloadState)
        assertEquals(45, inProgressDownload?.progress)

        // 3. Mark completed
        repository.markCompleted("test_offline_1", 2048000L)
        val completedDownload = repository.getDownloadFlow("test_offline_1").first()
        assertEquals(DownloadState.COMPLETED.name, completedDownload?.downloadState)
        assertEquals(100, completedDownload?.progress)

        // 4. Delete download
        repository.deleteDownload("test_offline_1")
        val deletedDownload = repository.getDownloadFlow("test_offline_1").first()
        assertNull(deletedDownload)
    }

    @Test
    fun testLibraryAndSearchNavigation() {
        composeTestRule.setContent {
            WearsicTheme {
                WearsicApp(timeText = {})
            }
        }

        // Verify Library Header and buttons with unmerged tree
        composeTestRule.onNodeWithText("Library").assertExists()
        composeTestRule.onNodeWithTag("library_search_button", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("library_downloads_button", useUnmergedTree = true).assertExists()

        // Navigate to Search
        composeTestRule.onNodeWithTag("library_search_button", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Search").assertExists()
    }

    @Test
    fun testDownloadsScreenNavigation() {
        composeTestRule.setContent {
            WearsicTheme {
                WearsicApp(timeText = {})
            }
        }

        // Navigate to Downloads
        composeTestRule.onNodeWithTag("library_downloads_button", useUnmergedTree = true).performClick()
        composeTestRule.onNodeWithText("Downloads").assertExists()
    }

    @Test
    @Config(qualifiers = "w480dp-h800dp", sdk = [36])
    fun testVolumeAndOutputScreenNavigation() {
        composeTestRule.setContent {
            WearsicTheme {
                WearsicApp(timeText = {})
            }
        }

        // Navigate to Settings from Library (fully visible in high-height viewport without scrolling)
        composeTestRule.onNodeWithTag("library_settings_button", useUnmergedTree = true).performClick()
        
        // Wait for Datastore state flow emissions and layout transition to settle
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Server & Storage", useUnmergedTree = true).assertExists()

        // Test server URL and cache limit UI elements
        composeTestRule.onNodeWithTag("settings_server_url", useUnmergedTree = true).assertExists()

        // The Offline Audio section sits below the URL field; scroll down so
        // the limit pills enter the lazy viewport. There is no persistent
        // stream-cache limit anymore — playback buffers in memory only.
        repeat(2) {
            composeTestRule.onRoot().performTouchInput { swipeUp() }
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithTag("settings_offline_limit", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("settings_cache_limit", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun testWearsicHttpApiClientSchemeValidation() = runBlocking {
        val client = com.wearsic.app.network.WearsicHttpApiClient()
        val badUrlResult = client.checkHealth("ftp://bad-scheme.com")
        assertTrue(badUrlResult.isFailure)
        assertTrue(badUrlResult.exceptionOrNull()?.message?.contains("Invalid URL scheme") == true)

        val badSearchUrlResult = client.searchTracks("ftp://bad-scheme.com", "test")
        assertTrue(badSearchUrlResult.isFailure)
        assertTrue(badSearchUrlResult.exceptionOrNull()?.message?.contains("Invalid URL scheme") == true)
    }

    @Test
    fun testWearsicPlaybackControllerRelease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = com.wearsic.app.media.WearsicPlaybackController(context)
        // Verify release completes successfully without crashing
        controller.release()
    }

    @Test
    fun testSettingsScreenRendersInIsolation() {
        composeTestRule.setContent {
            WearsicTheme {
                com.wearsic.app.ui.screens.SettingsScreen()
            }
        }
        composeTestRule.onNodeWithText("Server & Storage", useUnmergedTree = true).assertExists()
    }
}
