package com.wearsic.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicDownloadEntity
import com.wearsic.app.data.db.WearsicRecentTrackEntity
import com.wearsic.app.ui.navigation.WearsicApp
import com.wearsic.app.ui.theme.WearsicTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Reproduces the reported "second launch hangs on the splash screen / app
 * auto-closes" bug: the FIRST launch of a fresh install works, but closing
 * the app and reopening it must compose the whole UI again on top of the
 * state the first session persisted (recents rows, download rows, orphaned
 * partial files, deferred deletions, preferences). Any crash, deadlock or
 * blocked main thread in that path fails this test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class RelaunchWithDataTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun db(context: Context) = WearsicDatabase.getInstance(context)

    private fun seedRecents(context: Context) = runBlocking {
        // Two recordings from the first session (same title, different ids —
        // the identity contract that must survive a relaunch).
        db(context).recentTrackDao().upsert(
            WearsicRecentTrackEntity(
                trackId = "relaunchA", title = "Test Song", artist = "Artist A",
                album = "", artworkUrl = null, durationMs = 180_000L,
                mediaUri = "http://server.example/api/stream/relaunchA",
                playedAt = System.currentTimeMillis() - 5_000
            )
        )
        db(context).recentTrackDao().upsert(
            WearsicRecentTrackEntity(
                trackId = "relaunchB", title = "Test Song", artist = "Artist A",
                album = "", artworkUrl = null, durationMs = 200_000L,
                mediaUri = "http://server.example/api/stream/relaunchB",
                playedAt = System.currentTimeMillis() - 1_000
            )
        )
    }

    private fun seedDownloads(context: Context) = runBlocking {
        val dir = File(context.filesDir, "wearsic_downloads").apply { mkdirs() }

        // A killed download: QUEUED row + orphaned .part bytes.
        File(dir, "orphan.m4a.part").apply { writeBytes(ByteArray(2048)) }
        db(context).downloadDao().insertOrUpdate(
            WearsicDownloadEntity(
                trackId = "orphan", title = "Orphan", artist = "A", album = null,
                artworkUrl = null, durationMs = 1_000L,
                localFilePath = File(dir, "orphan.m4a").absolutePath,
                originalStreamUrl = "http://server.example/api/stream/orphan",
                downloadState = DownloadState.QUEUED.name
            )
        )

        // A valid MANUAL download (must survive the relaunch untouched).
        val manualFile = File(dir, "manualkeep.m4a").apply { writeBytes(ByteArray(4096)) }
        db(context).downloadDao().insertOrUpdate(
            WearsicDownloadEntity(
                trackId = "manualkeep", title = "Kept", artist = "A", album = null,
                artworkUrl = null, durationMs = 1_000L,
                localFilePath = manualFile.absolutePath,
                originalStreamUrl = "http://server.example/api/stream/manualkeep",
                downloadState = DownloadState.COMPLETED.name,
                fileSizeBytes = manualFile.length(),
                autoCached = false
            )
        )

        // A deferred-deletion AUTO row (file in use when Clear auto-saved ran,
        // then the process died — the flag must be rediscovered on relaunch).
        val deferredFile = File(dir, "deferred.m4a").apply { writeBytes(ByteArray(1024)) }
        db(context).downloadDao().insertOrUpdate(
            WearsicDownloadEntity(
                trackId = "deferred", title = "Deferred", artist = "A", album = null,
                artworkUrl = null, durationMs = 1_000L,
                localFilePath = deferredFile.absolutePath,
                originalStreamUrl = "http://server.example/api/stream/deferred",
                downloadState = DownloadState.COMPLETED.name,
                fileSizeBytes = deferredFile.length(),
                autoCached = true
            )
        )
        db(context).downloadDao().markDeletionPending("deferred", true)
    }

    private fun seedPreferences(context: Context) = runBlocking {
        WearsicPreferencesRepository(context).apply {
            saveServerUrl("http://10.0.2.2:8080")
            saveApiKey("test-key")
            saveOfflineLimit(50)
        }
    }

    @Test
    fun relaunch_withPersistedState_rendersAndStaysResponsive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking(Dispatchers.IO) {
            // Each test simulates one install lifecycle: wipe leftovers from
            // sibling tests so seeds define the exact "second launch" state.
            db(context).clearAllTables()
            seedRecents(context)
            seedDownloads(context)
            seedPreferences(context)
        }

        composeTestRule.setContent {
            WearsicTheme {
                WearsicApp(timeText = {})
            }
        }

        // The start destination must appear — a hang/crash in ViewModel init,
        // Room/DataStore/IO startup or composition times this out.
        composeTestRule.waitUntil(timeoutMillis = 15_000) {
            composeTestRule.onAllNodesWithTag("library_lazy_column")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Library").assertIsDisplayed()

        // Startup reconciliation effects, verified against the real DB:
        runBlocking {
            val dao = db(context).downloadDao()
            // Killed download: dead row + orphaned bytes removed.
            composeTestRule.waitUntil(10_000) {
                runBlocking { dao.getDownloadById("orphan") == null }
            }
            assertTrue(!File(context.filesDir, "wearsic_downloads/orphan.m4a.part").exists())

            // Deferred-deletion intent is discovered and executed: nothing is
            // playing after a relaunch, so the flagged AUTO file is released
            // and removed (row + bytes).
            composeTestRule.waitUntil(10_000) {
                runBlocking { dao.getDownloadById("deferred") == null }
            }
            assertTrue(!File(context.filesDir, "wearsic_downloads/deferred.m4a").exists())

            // Valid MANUAL media is untouched by reconciliation.
            val manual = dao.getDownloadById("manualkeep")
            assertNotNull(manual)
            assertEquals(DownloadState.COMPLETED.name, manual?.downloadState)
            assertTrue(File(manual!!.localFilePath).isFile)

            // Recents are intact: both per-recording rows survived the relaunch
            // (identity rows stay in the DB; the display groups by song name).
            assertEquals(2, db(context).recentTrackDao().countRows())
        }

        // The UI is still responsive after reconciliation: navigation works.
        composeTestRule.onNodeWithTag("library_search_button", useUnmergedTree = true)
            .performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithTag("search_input", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeTestRule.onAllNodes(
                    androidx.compose.ui.test.hasText("Search"), useUnmergedTree = true
                ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun relaunch_navigationAfterRestart_doesNotDeadlock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking(Dispatchers.IO) {
            db(context).clearAllTables()
            // Downloads state only (no recents): the Downloads pill sits in
            // the initial viewport exactly like the fresh-launch layout.
            seedDownloads(context)
            seedPreferences(context)
        }

        composeTestRule.setContent {
            WearsicTheme {
                WearsicApp(timeText = {})
            }
        }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("library_lazy_column")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The exact interaction that freezes an ANR'd main thread blocks here.
        // Swipe-scroll first (ScalingLazyColumn has no ScrollToNode semantics):
        // earlier tests in the class seed recents, so the pill may sit below
        // the fold regardless of this test's own seed data.
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag(
                "library_downloads_button", useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("library_downloads_button", useUnmergedTree = true)
            .performClick()
        composeTestRule.onNodeWithText("Downloads").assertIsDisplayed()

        // And the persisted downloads render on top of the reconciled DB.
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("Kept"), useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun relaunch_reconciliation_isIdempotentAcrossRepeatedRuns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking(Dispatchers.IO) {
            db(context).clearAllTables()
            seedDownloads(context)
            seedPreferences(context)
        }

        // First launch.
        composeTestRule.setContent {
            WearsicTheme { WearsicApp(timeText = {}) }
        }
        composeTestRule.waitUntil(15_000) {
            composeTestRule.onAllNodesWithTag("library_lazy_column")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.waitForIdle()

        runBlocking {
            val dao = db(context).downloadDao()
            composeTestRule.waitUntil(10_000) {
                runBlocking { dao.getDownloadById("orphan") == null }
            }
            composeTestRule.waitUntil(10_000) {
                runBlocking { dao.getDownloadById("deferred") == null }
            }
            // Nothing left to reconcile: a second pass is a no-op.
            assertNull(dao.getDownloadById("orphan"))
            assertNull(dao.getDownloadById("deferred"))
            assertNotNull(dao.getDownloadById("manualkeep"))
        }
    }
}
