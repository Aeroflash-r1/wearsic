package com.wearsic.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.WearsicRecentRepository
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * REGRESSION — "Recent song plays the WRONG audio".
 *
 * Recents follow a two-layer model:
 *
 *  - DATABASE: one row per stable recording identity (a real YouTube videoId
 *    since the 1.5 YTM migration). Two different recordings that share a
 *    title+artist keep separate rows — nothing is collapsed or lost.
 *  - VISIBLE LIST: one row per SONG NAME (title+artist), showing the most
 *    recently played recording of that song — so replaying a song never
 *    stacks near-identical rows on the Recents screen.
 *
 * The invariant this test enforces is that whatever row IS visible resolves
 * the EXACT recording stored on it — never a title re-search:
 *
 *     visible row id (videoId)
 *         = track/MediaItem id
 *         = stream URL segment (mediaUri ends in /api/stream/{videoId})
 *         = local file id, when one exists for THAT id
 *
 * If this test fails, Recent identity was rebuilt from display metadata
 * (title/artist) instead of the exact stored id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RecentPlaybackIdentityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: WearsicDatabase
    private lateinit var recents: WearsicRecentRepository
    private lateinit var downloads: WearsicDownloadRepository

    /** Same title AND same artist on purpose — the recordings must stay distinct. */
    private fun recording(id: String) = Track(
        id = id,
        title = "Test Song",
        artist = "Same Artist",
        album = "Album",
        durationMs = 180_000L,
        mediaUri = "https://server.example/api/stream/$id",
    )

    /** Exactly how playback picks a source: local file for the id first, else
     *  the row's own stream URL. NEVER a title search. */
    private suspend fun resolveForPlayback(recentRow: Track): Track =
        downloads.getDownloadedTrack(recentRow.id) ?: recentRow

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, WearsicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recents = WearsicRecentRepository(context, recentTrackDao = db.recentTrackDao())
        downloads = WearsicDownloadRepository(context, downloadDao = db.downloadDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun visibleList(): List<Track> = runBlocking { recents.recentTracksFlow.first() }
    private fun storedRowCount(): Int = runBlocking { db.recentTrackDao().countRows() }

    // A + B + F: same title/artist, different videoIds. The DB keeps both
    // recordings; the visible list shows ONE row (the latest play, BBB), and
    // that row resolves BBB's own stream — never AAA's, never a title search.
    @Test
    fun `same-title recordings stay stored and the visible row resolves its own id`() = runBlocking {
        recents.recordPlayed(recording("AAA"))
        recents.recordPlayed(recording("BBB"))

        // Identity layer: neither recording was collapsed or lost.
        assertEquals("both recordings stored", 2, storedRowCount())

        // Display layer: one row per song, showing the latest play (BBB).
        val rows = visibleList()
        assertEquals("one visible row per song: " + rows.map { it.id }, 1, rows.size)
        assertEquals("BBB", rows[0].id)
        assertEquals("BBB", rows[0].mediaUri.substringAfterLast('/'))
        // Clicking the visible row resolves BBB's exact recording.
        assertEquals("BBB", resolveForPlayback(rows[0]).id)
    }

    // C: AAA -> BBB -> AAA replay: AAA is visible again and resolves AAA —
    // replaying a recent song always replays the exact recording.
    @Test
    fun `replaying AAA after BBB always replays recording AAA`() = runBlocking {
        recents.recordPlayed(recording("AAA"))
        recents.recordPlayed(recording("BBB"))
        recents.recordPlayed(recording("AAA")) // AAA again

        assertEquals("both recordings still stored", 2, storedRowCount())

        // Top of Recents is AAA again, still pointing at AAA's stream.
        val rows = visibleList()
        assertEquals(1, rows.size)
        assertEquals("AAA", rows[0].id)
        assertEquals("https://server.example/api/stream/AAA", rows[0].mediaUri)
        assertEquals("AAA", resolveForPlayback(rows[0]).id)
    }

    // D: a local AUTO file for AAA must only ever be used when AAA is the
    // visible row. When BBB is the latest play it resolves BBB's own stream,
    // even though an AAA file exists on disk.
    @Test
    fun `local AUTO file is used only for its own visible recording`() = runBlocking {
        recents.recordPlayed(recording("AAA"))
        recents.recordPlayed(recording("BBB")) // BBB latest -> BBB visible
        seedCompletedFile("AAA", autoCached = true)

        // BBB is visible and unaffected by AAA's local file: BBB's stream.
        val bRow = visibleList().single()
        assertEquals("BBB", bRow.id)
        val resolvedB = resolveForPlayback(bRow)
        assertEquals("BBB", resolvedB.id)
        assertEquals("https://server.example/api/stream/BBB", resolvedB.mediaUri)

        // Replay AAA -> AAA visible -> AAA's exact local file is played.
        recents.recordPlayed(recording("AAA"))
        val aRow = visibleList().single()
        assertEquals("AAA", aRow.id)
        val resolvedA = resolveForPlayback(aRow)
        assertEquals("AAA", resolvedA.id)
        assertTrue("plays the local AUTO file", resolvedA.mediaUri.startsWith("/"))
        assertEquals("AAA.m4a", resolvedA.mediaUri.substringAfterLast('/'))
        assertTrue(File(resolvedA.mediaUri).isFile)
    }

    // E: symmetric — a local MANUAL file for BBB is used only when BBB is the
    // visible recording; AAA's row still resolves AAA's own stream.
    @Test
    fun `local MANUAL file is used only for its own visible recording`() = runBlocking {
        recents.recordPlayed(recording("AAA"))
        recents.recordPlayed(recording("BBB")) // BBB latest -> BBB visible
        seedCompletedFile("BBB", autoCached = false)

        val bRow = visibleList().single()
        assertEquals("BBB", bRow.id)
        val resolvedB = resolveForPlayback(bRow)
        assertEquals("BBB", resolvedB.id)
        assertTrue("plays the local MANUAL file", resolvedB.mediaUri.startsWith("/"))
        assertEquals("BBB.m4a", resolvedB.mediaUri.substringAfterLast('/'))
        assertTrue(File(resolvedB.mediaUri).isFile)

        // Replay AAA -> AAA visible -> AAA resolves its own stream.
        recents.recordPlayed(recording("AAA"))
        val aRow = visibleList().single()
        assertEquals("AAA", aRow.id)
        val resolvedA = resolveForPlayback(aRow)
        assertEquals("AAA", resolvedA.id)
        assertEquals("https://server.example/api/stream/AAA", resolvedA.mediaUri)
    }

    // G: identity survives an app restart (fresh repository over the same
    // store): rows are retained and the visible row keeps its exact id.
    @Test
    fun `exact ids survive restart and still resolve their own recordings`() = runBlocking {
        recents.recordPlayed(recording("AAA"))
        recents.recordPlayed(recording("BBB"))
        recents.recordPlayed(recording("AAA")) // AAA latest before the restart

        // "Restart": brand-new repository instances over the same database.
        val recentsAfterRestart = WearsicRecentRepository(context, recentTrackDao = db.recentTrackDao())
        val downloadsAfterRestart = WearsicDownloadRepository(context, downloadDao = db.downloadDao())

        // No rows were lost; the visible row is AAA with its exact stream URL.
        assertEquals(2, storedRowCount())
        val rows = recentsAfterRestart.recentTracksFlow.first()
        assertEquals(1, rows.size)
        assertEquals("AAA", rows[0].id)
        assertEquals("https://server.example/api/stream/AAA", rows[0].mediaUri)
        // Clicking after restart resolves the exact id (no title search).
        assertEquals("AAA", (downloadsAfterRestart.getDownloadedTrack(rows[0].id) ?: rows[0]).id)
    }

    private fun seedCompletedFile(id: String, autoCached: Boolean) {
        val dir = File(context.filesDir, "wearsic_downloads").apply { mkdirs() }
        val file = File(dir, "$id.m4a")
        file.writeBytes(ByteArray(1024) { 7 })
        runBlocking {
            downloads.recordQueued(recording(id), file.absolutePath, autoCached)
            downloads.markCompleted(id, file.length(), autoCached)
        }
    }
}
