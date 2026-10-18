package com.wearsic.app

import android.content.Context
import android.os.Looper
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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    companion object {
        init {
            // Forensics only (2026-10-18 wedge): track every coroutine this
            // class creates so the watchdog can dump their suspension points —
            // suspended coroutines are invisible to jstack.
            runCatching {
                DebugProbes.install()
                println("WEDGE-PROBE [init] DebugProbes installed")
                // Forensics: prove whether the kotlinx-coroutines-debug java
                // agent is really on this test JVM's command line.
                println(
                    "WEDGE-PROBE [init] jvm args: " +
                        java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
                            .filter { it.contains("javaagent") || it.contains("coroutines") }
                )
                System.out.flush()
            }.onFailure {
                println("WEDGE-PROBE [init] DebugProbes install failed: $it")
            }
        }
    }

    /**
     * Forensics only: daemon thread that waits 10s; if the DataStore write
     * still has not returned, it dumps every live coroutine (with suspension
     * point) plus full thread stacks, then dumps coroutines again at 20s.
     * Interrupted — and thus silent — when the write completes in time.
     */
    private fun startWedgeWatchdog(): Thread {
        val thread = Thread {
            try {
                Thread.sleep(10_000)
                probe("[watchdog] 10s inside saveServerUrl")
                probeCoroutineDump("watchdog @10s")
                probeLooperState("watchdog @10s")
                probe("[watchdog] thread dump follows")
                for ((t, stack) in Thread.getAllStackTraces()) {
                    println("WEDGE-PROBE [watchdog] thread=${t.name} state=${t.state}")
                    stack.forEach { frame -> println("    at $frame") }
                }
                System.out.flush()
                Thread.sleep(10_000)
                probe("[watchdog] 20s inside saveServerUrl")
                probeCoroutineDump("watchdog @20s")
                probeLooperState("watchdog @20s")
                fireAndForgetDispatchProbe("watchdog@20s-main", Dispatchers.Main)
                fireAndForgetDispatchProbe("watchdog@20s-io", Dispatchers.IO)
                System.out.flush()
            } catch (_: InterruptedException) {
                // Write completed in time — nothing to dump.
            } catch (t: Throwable) {
                probe("[watchdog] dump failed: $t")
            }
        }
        thread.isDaemon = true
        thread.name = "WEDGE-WATCHDOG"
        thread.start()
        return thread
    }

    /** Forensics: dump every live coroutine with its suspension point. */
    private fun probeCoroutineDump(label: String) {
        runCatching {
            println("WEDGE-PROBE [${Thread.currentThread().name}] coroutine dump ($label) start")
            DebugProbes.dumpCoroutines(System.out)
            println("WEDGE-PROBE [${Thread.currentThread().name}] coroutine dump ($label) end")
            System.out.flush()
        }.onFailure { probe("coroutine dump ($label) failed: $it") }
    }

    /** Forensics: print every get/is getter of the main Looper's shadow. */
    private fun probeLooperState(label: String) {
        val shadow = runCatching { shadowOf(Looper.getMainLooper()) }.getOrNull()
        if (shadow == null) {
            probe("looper state ($label): no shadow")
            return
        }
        val getters = shadow.javaClass.methods
            .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) }
            .sortedBy { it.name }
        for (method in getters) {
            val value = runCatching { method.invoke(shadow) }
                .getOrElse { "err:${it::class.java.simpleName}" }
            probe("looper state ($label): ${method.name}=$value")
        }
    }

    /**
     * Forensics: enqueue a marker on [dispatcher] WITHOUT waiting for it.
     * A waiting version (withContext + withTimeoutOrNull) must not be used
     * here: on Robolectric the main Looper is paused unless the test idles
     * it, and a coroutine dispatched to a paused Looper never completes —
     * a timeout cannot rescue it because the parent waits for the child's
     * queued task to run, so the caller would hang forever (confirmed by
     * the first v3 run, which stalled before the write). Fire-and-forget
     * markers print only once the dispatcher actually runs them, which is
     * exactly the signal we want (e.g. after a looper pump).
     */
    private fun fireAndForgetDispatchProbe(label: String, dispatcher: CoroutineDispatcher) {
        CoroutineScope(dispatcher).launch {
            probe("$label RAN")
        }
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

        // Pre-configure server URL as the real production app would require.
        // Forensics: the write stays the very FIRST DataStore operation. If it
        // wedges, the caller is cancelled and a series of experiments isolates
        // the exact stuck stage: coroutine dump (via the debug java agent),
        // looper state, pump-vs-cancel separation, and a write from IO.
        probe("saveServerUrl begin interceptor=${coroutineContext[ContinuationInterceptor]}")
        // Fire-and-forget sanity markers. The main one stays queued while the
        // Looper is paused and must appear right after the first looper pump
        // in the wedge experiments; the IO one prints within milliseconds.
        fireAndForgetDispatchProbe("prewrite-main", Dispatchers.Main)
        fireAndForgetDispatchProbe("prewrite-io", Dispatchers.IO)
        val watchdog = startWedgeWatchdog()
        val saveOutcome: String? = try {
            withTimeoutOrNull(30_000) {
                repository.saveServerUrl("https://wearsic.server.internal")
                "ok"
            }
        } catch (t: Throwable) {
            probe("saveServerUrl threw ${t::class.java.simpleName}: ${t.message}")
            "threw"
        } finally {
            watchdog.interrupt()
        }
        probe("saveServerUrl outcome=${saveOutcome ?: "TIMEOUT_30s"}")
        val wedgeReproduced = saveOutcome == null
        if (wedgeReproduced) {
            probeCoroutineDump("main-thread after wedge")
            probeLooperState("after wedge")
            probe("post-wedge getServerUrl begin")
            val readOutcome = try {
                withTimeoutOrNull(15_000) { repository.getServerUrl(); "ok" }
            } catch (t: Throwable) {
                "threw ${t::class.java.simpleName}"
            }
            probe("post-wedge getServerUrl outcome=${readOutcome ?: "TIMEOUT_15s"}")

            // Experiment A: does pumping the paused main Looper alone release
            // the stuck write? (v2 could not separate pump from write-cancel.)
            probe("experimentA: pump main looper, then write#2")
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
                .onFailure { probe("experimentA pump failed: $it") }
            probeLooperState("after pumpA")
            fireAndForgetDispatchProbe("postPumpA-main", Dispatchers.Main)
            val write2 = try {
                withTimeoutOrNull(15_000) {
                    repository.saveServerUrl("https://wearsic.server.internal")
                    "ok"
                }
            } catch (t: Throwable) {
                "threw ${t::class.java.simpleName}"
            }
            probe("saveServerUrl#2(after pumpA) outcome=${write2 ?: "TIMEOUT_15s"}")

            // Experiment B: second pump, then write#3 from the main thread.
            probe("experimentB: second pump, then write#3")
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
                .onFailure { probe("experimentB pump failed: $it") }
            val write3 = try {
                withTimeoutOrNull(15_000) {
                    repository.saveServerUrl("https://wearsic.server.internal")
                    "ok"
                }
            } catch (t: Throwable) {
                "threw ${t::class.java.simpleName}"
            }
            probe("saveServerUrl#3(from main) outcome=${write3 ?: "TIMEOUT_15s"}")

            // Experiment C: identical write issued from a background dispatcher.
            probe("experimentC: write#4 from Dispatchers.IO")
            val write4 = try {
                withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        repository.saveServerUrl("https://wearsic.server.internal")
                    }
                    "ok"
                }
            } catch (t: Throwable) {
                "threw ${t::class.java.simpleName}"
            }
            probe("saveServerUrl#4(from IO) outcome=${write4 ?: "TIMEOUT_15s"}")
        }

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
        if (wedgeReproduced) {
            throw AssertionError(
                "WEDGE-PROBE: saveServerUrl wedged (30s timeout) — see coroutine/thread dumps above"
            )
        }
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
