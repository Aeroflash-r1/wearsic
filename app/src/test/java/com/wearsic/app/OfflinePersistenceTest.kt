package com.wearsic.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.WearsicRecentRepository
import com.wearsic.app.data.db.RecentTrackDao
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.media.download.WearsicDownloadManager
import com.wearsic.app.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.net.InetSocketAddress

/**
 * Offline persistence behaviors that protect the two most-reported UX bugs:
 *
 * 1. Recently Played identity correctness: since the 1.5 YTM migration every
 *    recording carries ONE real YouTube videoId. The DB keeps one row PER
 *    RECORDING (replayed rows bump to the top, different recordings sharing a
 *    title/artist never collapse or lose data), while the visible Recents
 *    list groups by song name so the same song never appears twice — each
 *    shown row resolves the EXACT id stored on it, never a title re-search.
 * 2. Interrupted downloads resuming from the last written byte instead of
 *    restarting (matters on flaky tunnel connections).
 *
 * Both run fully offline. Recents tests use an IN-MEMORY Room instance so
 * they cannot collide with the app singleton (the historic source of nav-test
 * flakes); the resume test serves bytes from a local com.sun.net.httpserver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflinePersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var memoryDb: WearsicDatabase
    private lateinit var recents: WearsicRecentRepository

    @Before
    fun setUp() {
        memoryDb = Room.inMemoryDatabaseBuilder(context, WearsicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Main dispatcher is the Room transaction executor path here; route it
        // through a test dispatcher so runBlocking + Room never deadlock.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        recents = WearsicRecentRepository(context, recentTrackDao = memoryDb.recentTrackDao())
    }

    @After
    fun tearDown() {
        memoryDb.close()
        Dispatchers.resetMain()
    }

    private fun track(id: String, title: String = "Poster Boy", artist: String = "Artist") = Track(
        id = id,
        title = title,
        artist = artist,
        album = "Album",
        durationMs = 200_000L,
        mediaUri = "https://server.example/api/stream/$id",
    )

    // ---------------- Recently Played identity ----------------

    @Test
    fun `same-title recordings keep per-id rows but show once as the latest play`() = runBlocking {
        // Two REAL recordings (distinct videoIds) sharing title+artist.
        recents.recordPlayed(track("AAA", title = "Test Song", artist = "Artist"))
        recents.recordPlayed(track("BBB", title = "Test Song", artist = "Artist"))

        // DB keeps both recordings (identity preserved — no collapse/data loss).
        assertEquals(2, memoryDb.recentTrackDao().countRows())
        // The visible Recents list shows ONE row per song name — the latest
        // play (BBB) — so the same song never stacks on screen.
        val rows = recents.recentTracksFlow.first()
        assertEquals("visible list groups by song: " + rows.map { it.id }, 1, rows.size)
        assertEquals("BBB", rows[0].id)
        // The visible row still resolves BBB's own recording exactly.
        assertEquals("https://server.example/api/stream/BBB", rows[0].mediaUri)
    }

    @Test
    fun `replaying a song bumps it to the top without duplicating`() = runBlocking {
        recents.recordPlayed(track("x1", title = "First"))
        recents.recordPlayed(track("x2", title = "Second"))
        recents.recordPlayed(track("x1", title = "First"))

        val rows = recents.recentTracksFlow.first()
        assertEquals(2, rows.size)
        assertEquals("x1", rows[0].id)
    }

    @Test
    fun `replaying AAA after BBB keeps both rows and shows the exact AAA recording`() = runBlocking {
        recents.recordPlayed(track("AAA", title = "Test Song", artist = "Artist"))
        recents.recordPlayed(track("BBB", title = "Test Song", artist = "Artist"))
        recents.recordPlayed(track("AAA", title = "Test Song", artist = "Artist")) // AAA again

        // Both recordings still exist under the hood.
        assertEquals(2, memoryDb.recentTrackDao().countRows())
        // The visible row is AAA again (the latest play), pointing at AAA's
        // stream — replaying never plays the wrong recording.
        val rows = recents.recentTracksFlow.first()
        assertEquals(1, rows.size)
        assertEquals("AAA", rows[0].id)
        assertEquals("https://server.example/api/stream/AAA", rows[0].mediaUri)
    }

    @Test
    fun `similar titles from different artists stay separate rows`() = runBlocking {
        recents.recordPlayed(track("a1", artist = "Crowded House"))
        recents.recordPlayed(track("b1", artist = "Some Cover Band"))
        assertEquals(2, recents.recentTracksFlow.first().size)
    }

    // ---------------- Download resume ----------------

    private var httpServer: HttpServer? = null
    private var serverPort: Int = 0

    /** Serves deterministic bytes honoring open-ended Range requests. */
    private fun startFakeCdn(totalBytes: Int): Int {
        val payload = ByteArray(totalBytes) { (it % 251).toByte() }
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/stream") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-"
            val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
            val body = if (start >= totalBytes) ByteArray(0) else payload.copyOfRange(start.toInt(), totalBytes)
            exchange.responseHeaders.add("Content-Range", "bytes $start-${totalBytes - 1}/$totalBytes")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        httpServer = server
        serverPort = server.address.port
        return serverPort
    }

    @After
    fun stopServer() {
        httpServer?.stop(0)
    }

    @Test
    fun `interrupted download resumes from the written offset and completes`() = runBlocking {
        val port = startFakeCdn(totalBytes = 16 * 1024)

        // Explicit per-test DB name: no collision with the app-wide singleton.
        val db = Room.databaseBuilder(context, WearsicDatabase::class.java, "resume_test_db")
            .allowMainThreadQueries()
            .build()
        val repository = WearsicDownloadRepository(context, db.downloadDao())
        val manager = WearsicDownloadManager(context, repository)
        try {
            val id = "resume_test_1"
            val dir = manager.getDownloadDir()

            // Robolectric's StatFs reports 0 free bytes by default — register a
            // big disk for the download dir so the storage guard passes
            // (blocks of 4KB: 8192 blocks ≈ 32MB free).
            ShadowStatFs.registerStats(dir, 8192, 8192, 8192)

            // Simulate a previously interrupted download: first 4KB on disk.
            val partFile = File(dir, "$id.m4a.part")
            partFile.parentFile?.mkdirs()
            partFile.writeBytes(ByteArray(4096))

            val track = Track(
                id = id,
                title = "Resume Song",
                artist = "Artist",
                album = "Album",
                durationMs = 200_000L,
                mediaUri = "http://127.0.0.1:$port/stream/$id",
            )

            manager.startDownload(track)
            // Poll until the Room row reports COMPLETED (manager runs on IO).
            var entity = repository.getDownloadedTrack(id)
            var waited = 0
            while (entity == null && waited < 15_000) {
                Thread.sleep(100)
                waited += 100
                entity = repository.getDownloadedTrack(id)
            }
            assertNotNull(entity)

            val finished = File(entity!!.mediaUri)
            assertEquals((16 * 1024).toLong(), finished.length())

            // Atomic completion: the .part file is gone.
            assertTrue("part file must be renamed away", !partFile.exists())

            // deleteDownload cleans up asynchronously on the manager's scope —
            // poll briefly rather than racing it.
            manager.deleteDownload(id)
            var gone = repository.getDownloadedTrack(id) == null
            var deleteWaited = 0
            while (!gone && deleteWaited < 5_000) {
                Thread.sleep(100)
                deleteWaited += 100
                gone = repository.getDownloadedTrack(id) == null
            }
            assertTrue("deleted download must disappear from the repository", gone)
        } finally {
            // release() cancels the manager scope but does not join it. Let any
            // in-flight repository write (progress / markCancelled / delete)
            // land before closing the DB — otherwise a late write can surface
            // as an uncaught exception in the NEXT test class and flake CI.
            manager.release()
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            db.close()
            context.getDatabasePath("resume_test_db").delete()
        }
    }
}
