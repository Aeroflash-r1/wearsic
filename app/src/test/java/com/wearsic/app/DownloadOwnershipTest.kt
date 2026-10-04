package com.wearsic.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicDownloadEntity
import com.wearsic.app.media.download.WearsicDownloadManager
import com.wearsic.app.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * One-physical-file-per-track storage architecture tests.
 *
 * Coverage for the ownership rules:
 *  - AUTO and MANUAL share the SAME file path (never two copies);
 *  - AUTO -> MANUAL is a metadata-only promotion (no second download);
 *  - MANUAL is never downgraded by an AUTO request;
 *  - concurrent requests for one track produce ONE HTTP download;
 *  - a MANUAL request during an AUTO download upgrades the SAME job;
 *  - eviction / "clear auto" only ever delete AUTO files;
 *  - interrupted (killed-process) rows are cleaned up on the next start;
 *  - storage accounting counts each physical file exactly once.
 *
 * Fully offline: bytes are served by a local com.sun.net.httpserver that
 * honors Range requests and counts how many times each track is fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadOwnershipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: WearsicDatabase
    private lateinit var repository: WearsicDownloadRepository
    private lateinit var manager: WearsicDownloadManager
    private lateinit var dbName: String
    private var cdn: FakeCdn? = null

    @Before
    fun setUp() {
        dbName = "ownership_test_${System.nanoTime()}"
        db = Room.databaseBuilder(context, WearsicDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()
        repository = WearsicDownloadRepository(context, db.downloadDao())
        manager = WearsicDownloadManager(context, repository)
        // Robolectric's StatFs reports 0 free bytes by default — register a
        // big disk (4KB blocks x 8192 ≈ 32MB) so the storage guard passes.
        val dir = manager.getDownloadDir()
        ShadowStatFs.registerStats(dir, 8192, 8192, 8192)
    }

    @After
    fun tearDown() {
        cdn?.stop()
        manager.release()
        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        db.close()
        context.deleteDatabase(dbName)
    }

    // ---------------------------- helpers ----------------------------

    private fun fileFor(trackId: String): File {
        val sanitized = trackId.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(manager.getDownloadDir(), "$sanitized.m4a")
    }

    private fun track(id: String) = Track(
        id = id,
        title = "Song $id",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        mediaUri = "http://127.0.0.1:${cdn!!.port}/stream/$id",
    )

    /** Starts the fake CDN; returns after the server is accepting requests. */
    private fun startCdn(
        totalBytes: Int = 32 * 1024,
        delayBeforeMs: Long = 0,
        delayMidBodyMs: Long = 0
    ): FakeCdn {
        return FakeCdn(totalBytes, delayBeforeMs, delayMidBodyMs).also { cdn = it; it.start() }
    }

    private suspend fun entity(id: String): WearsicDownloadEntity? = repository.getDownloadEntity(id)

    /** Polls until the row is COMPLETED (downloads run on the manager's IO scope). */
    private fun awaitCompleted(id: String, timeoutMs: Long = 15_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            val e = runBlocking { entity(id) }
            if (e?.downloadState == DownloadState.COMPLETED.name && fileFor(id).isFile) return
            Thread.sleep(50)
            waited += 50
        }
        val e = runBlocking { entity(id) }
        throw AssertionError("track $id never completed (state=${e?.downloadState}, file=${fileFor(id).exists()})")
    }

    /**
     * Waits until the download is observably in flight (DOWNLOADING) or has
     * already completed — both are legitimate starting points for the manual
     * upgrade, which converges to MANUAL either way.
     */
    private fun awaitDownloadingOrCompleted(id: String, timeoutMs: Long = 10_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            val e = runBlocking { entity(id) }
            if (e?.downloadState == DownloadState.DOWNLOADING.name ||
                e?.downloadState == DownloadState.COMPLETED.name
            ) return
            Thread.sleep(10)
            waited += 10
        }
        throw AssertionError("track $id never became DOWNLOADING or COMPLETED")
    }

    private fun awaitRowGone(id: String, timeoutMs: Long = 10_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            if (runBlocking { entity(id) } == null) return
            Thread.sleep(25)
            waited += 25
        }
        throw AssertionError("row $id was never deleted")
    }

    private fun awaitFlagged(id: String, timeoutMs: Long = 10_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            if (runBlocking { entity(id) }?.pendingDeletion == true) return
            Thread.sleep(25)
            waited += 25
        }
        throw AssertionError("row $id never got the persisted pendingDeletion flag")
    }

    // -------------------- 1 + 2: fresh downloads --------------------

    @Test
    fun `manual download of a new song yields one MANUAL file`() = runBlocking {
        startCdn()
        manager.startDownload(track("song_a"))
        awaitCompleted("song_a")

        val e = entity("song_a")!!
        assertEquals(DownloadState.COMPLETED.name, e.downloadState)
        assertEquals(false, e.autoCached)
        assertTrue("exactly one physical file", fileFor("song_a").isFile)
        assertEquals(1, cdn!!.hits("song_a"))
    }

    @Test
    fun `auto-cache download of a new song yields one AUTO file`() = runBlocking {
        startCdn()
        manager.startDownload(track("song_b"), autoCached = true)
        awaitCompleted("song_b")

        val e = entity("song_b")!!
        assertEquals(true, e.autoCached)
        assertTrue(fileFor("song_b").isFile)
        assertEquals(1, cdn!!.hits("song_b"))
    }

    // ----------------- 3: AUTO -> MANUAL promotion -------------------

    @Test
    fun `download on an auto-cached song promotes it without a second download`() = runBlocking {
        startCdn()
        val t = track("song_promo")
        manager.startDownload(t, autoCached = true)
        awaitCompleted("song_promo")

        val before = entity("song_promo")!!
        val pathBefore = before.localFilePath
        val sizeBefore = fileFor("song_promo").length()
        assertEquals(true, before.autoCached)

        // User presses Download on the auto-cached song.
        manager.startDownload(t)
        awaitRowFlipsManual("song_promo")

        val after = entity("song_promo")!!
        assertEquals("ownership becomes MANUAL", false, after.autoCached)
        assertEquals("path unchanged — same physical file", pathBefore, after.localFilePath)
        assertEquals("file untouched", sizeBefore, fileFor("song_promo").length())
        assertEquals("no second network download", 1, cdn!!.hits("song_promo"))
        assertTrue(fileFor("song_promo").isFile)
    }

    private fun awaitRowFlipsManual(id: String, timeoutMs: Long = 10_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            val e = runBlocking { entity(id) }
            if (e != null && !e.autoCached && e.downloadState == DownloadState.COMPLETED.name) return
            Thread.sleep(25)
            waited += 25
        }
        throw AssertionError("row $id never became MANUAL")
    }

    @Test
    fun `promoted MANUAL track survives clear-auto and is not evictable`() = runBlocking {
        startCdn()
        val t = track("song_keep")
        manager.startDownload(t, autoCached = true)
        awaitCompleted("song_keep")
        manager.startDownload(t) // promote
        awaitRowFlipsManual("song_keep")

        // Aggressive eviction (cap 0 = clear every AUTO song) must skip it.
        manager.clearAutoCachedDownloads()
        Thread.sleep(600)

        val e = entity("song_keep")
        assertNotNull("promoted track must survive clear-auto", e)
        assertEquals(false, e!!.autoCached)
        assertTrue(fileFor("song_keep").isFile)
    }

    // ---------------- 4 + 5: MANUAL is never downgraded ---------------

    @Test
    fun `auto-cache request on a MANUAL file never downgrades it`() = runBlocking {
        startCdn()
        val t = track("song_manual")
        manager.startDownload(t) // MANUAL
        awaitCompleted("song_manual")

        // AutoCache decides this song "should" be saved too.
        manager.startDownload(t, autoCached = true)
        Thread.sleep(600)

        val e = entity("song_manual")!!
        assertEquals("manual ownership is stronger than auto-cache", false, e.autoCached)
        assertEquals("no re-download, no duplicate", 1, cdn!!.hits("song_manual"))
        assertTrue(fileFor("song_manual").isFile)
    }

    @Test
    fun `manual request on an already-manual track does nothing`() = runBlocking {
        startCdn()
        val t = track("song_double")
        manager.startDownload(t)
        awaitCompleted("song_double")
        val hitsBefore = cdn!!.hits("song_double")

        manager.startDownload(t)
        Thread.sleep(600)

        val e = entity("song_double")!!
        assertEquals(false, e.autoCached)
        assertEquals("no duplicate download", hitsBefore, cdn!!.hits("song_double"))
        // Exactly one physical file on disk for this track.
        val matches = manager.getDownloadDir().listFiles { f -> f.name.startsWith("song_double") } ?: emptyArray()
        assertEquals(1, matches.size)
    }

    // ------------------- 6 + 7: single job per track ------------------

    @Test
    fun `auto-cache while the same track is downloading stays one job`() = runBlocking {
        startCdn(delayBeforeMs = 900)
        val t = track("song_onejob")
        manager.startDownload(t, autoCached = true)
        manager.startDownload(t, autoCached = true) // duplicate request
        manager.startDownload(t, autoCached = true) // from another UI surface
        awaitCompleted("song_onejob")

        assertEquals("three requests -> ONE http download", 1, cdn!!.hits("song_onejob"))
        assertTrue(fileFor("song_onejob").isFile)
    }

    @Test
    fun `manual request while an AUTO job is downloading upgrades that job`() = runBlocking {
        // Stall MID-body so the row sits in DOWNLOADING for a while: the test
        // must catch the download in flight, not before it starts. Payload is
        // larger than the first stalled chunk so bytes keep flowing after it.
        startCdn(totalBytes = 512 * 1024, delayMidBodyMs = 1500)
        val t = track("song_upgrade")
        manager.startDownload(t, autoCached = true)
        // Wait until the AUTO download is in flight OR already done — if the
        // transfer raced past the DOWNLOADING window, the completed-AUTO path
        // still flips to MANUAL without a second download (same assertions).
        awaitDownloadingOrCompleted("song_upgrade")

        // User presses Download mid-auto-cache: the SAME job must be upgraded
        // (or, if it just finished, the completed AUTO row must be promoted).
        // The promotion is a metadata write on the manager's scope, so poll
        // for the deterministic final state (COMPLETED + MANUAL).
        manager.startDownload(t)
        awaitRowFlipsManual("song_upgrade")

        assertEquals("still exactly one http download", 1, cdn!!.hits("song_upgrade"))
        assertTrue(fileFor("song_upgrade").isFile)
    }

    // ----------------------- 8: AUTO eviction -------------------------

    @Test
    fun `eviction removes oldest AUTO only and keeps MANUAL`() = runBlocking {
        startCdn()
        manager.maxAutoCachedTracks = 2

        // A MANUAL download first — it must never be touched by auto eviction.
        val manual = track("song_manual_safe")
        manager.startDownload(manual)
        awaitCompleted("song_manual_safe")

        // Three AUTO downloads, oldest first (spaced so createdAt orders).
        val autoIds = listOf("song_ev_1", "song_ev_2", "song_ev_3")
        for (id in autoIds) {
            manager.startDownload(track(id), autoCached = true)
            awaitCompleted(id)
            Thread.sleep(5) // distinct createdAt millis -> deterministic order
        }

        // 3 auto with cap 2 -> the OLDEST (song_ev_1) is evicted.
        awaitRowGone("song_ev_1")
        assertTrue("oldest AUTO file deleted", !fileFor("song_ev_1").exists())
        assertTrue(entity("song_ev_2")!!.autoCached)
        assertTrue(fileFor("song_ev_2").isFile)
        assertTrue(entity("song_ev_3")!!.autoCached)
        assertTrue(fileFor("song_ev_3").isFile)

        // The MANUAL download survived untouched.
        val m = entity("song_manual_safe")!!
        assertEquals(false, m.autoCached)
        assertTrue(fileFor("song_manual_safe").isFile)
    }

    // --------------------- 9: clear auto-saved ------------------------

    @Test
    fun `clear auto-saved removes only AUTO rows and files`() = runBlocking {
        startCdn()
        // Sequential: the manager runs at most 2 downloads concurrently, so
        // start + finish each before requesting the next.
        val auto1 = track("song_ca_1")
        manager.startDownload(auto1, autoCached = true)
        awaitCompleted("song_ca_1")
        val auto2 = track("song_ca_2")
        manager.startDownload(auto2, autoCached = true)
        awaitCompleted("song_ca_2")
        val manualT = track("song_ca_manual")
        manager.startDownload(manualT)
        awaitCompleted("song_ca_manual")

        manager.clearAutoCachedDownloads()
        awaitRowGone("song_ca_1")
        awaitRowGone("song_ca_2")

        assertTrue(!fileFor("song_ca_1").exists())
        assertTrue(!fileFor("song_ca_2").exists())
        val m = entity("song_ca_manual")
        assertNotNull(m)
        assertEquals(false, m!!.autoCached)
        assertTrue("manual file preserved", fileFor("song_ca_manual").isFile)
    }

    // ---------------- 10: interrupted download cleanup ----------------

    @Test
    fun `rows left downloading by a killed process are cleaned on restart`() = runBlocking {
        // Simulate a crash: a QUEUED/DOWNLOADING row and orphaned .part bytes,
        // but no live job (this manager has not started anything yet). The URL
        // is never fetched — the row is cleaned before any download starts.
        val crashed = Track(
            id = "song_crashed",
            title = "Crashed",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            mediaUri = "http://127.0.0.1:1/never/fetched",
        )
        val dir = manager.getDownloadDir()
        repository.recordQueued(crashed, fileFor("song_crashed").absolutePath)
        repository.updateProgress("song_crashed", 40, 4096L)
        val part = File(dir, "song_crashed.m4a.part")
        part.writeBytes(ByteArray(4096))
        assertEquals(DownloadState.DOWNLOADING.name, entity("song_crashed")!!.downloadState)

        // An app restart runs startup cleanup against the SAME repository; a
        // fresh manager performs it. (Called explicitly here — in production
        // the ViewModel invokes it once after constructing the manager.)
        val manager2 = WearsicDownloadManager(context, repository)
        try {
            manager2.cleanupInterruptedDownloads()
            assertNull(entity("song_crashed"))
            assertTrue("orphan .part cleaned", !part.exists())
        } finally {
            manager2.release()
            Thread.sleep(200)
        }
    }

    // --------------- 11: storage accounting (exact once) ---------------

    @Test
    fun `storage breakdown counts each physical file exactly once`() = runBlocking {
        startCdn(totalBytes = 24 * 1024)
        val autoT = track("song_acct_auto")
        manager.startDownload(autoT, autoCached = true)
        awaitCompleted("song_acct_auto")

        val manualT = track("song_acct_manual")
        manager.startDownload(manualT)
        awaitCompleted("song_acct_manual")

        val breakdown = repository.computeLocalStorageBreakdown()
        assertEquals(1, breakdown.autoCount)
        assertEquals(1, breakdown.manualCount)
        assertEquals("auto bytes = real file size", fileFor("song_acct_auto").length(), breakdown.autoBytes)
        assertEquals("manual bytes = real file size", fileFor("song_acct_manual").length(), breakdown.manualBytes)
        // No double counting: total == sum of the two buckets.
        assertEquals(
            fileFor("song_acct_auto").length() + fileFor("song_acct_manual").length(),
            breakdown.autoBytes + breakdown.manualBytes
        )
    }

    // ---------------- 12/13: offline resolution / playback -------------

    @Test
    fun `no local file means network playback is used`() = runBlocking {
        startCdn()
        // Track never downloaded.
        assertNull(repository.getDownloadedTrack("song_online"))

        // Row says COMPLETED but its file is gone -> must NOT resolve offline.
        val ghost = track("song_ghost")
        repository.recordQueued(ghost, fileFor("song_ghost").absolutePath)
        repository.markCompleted("song_ghost", 1234L)
        assertNull("missing file must fall back to streaming", repository.getDownloadedTrack("song_ghost"))
    }

    @Test
    fun `AUTO and MANUAL completed files both resolve for offline playback`() = runBlocking {
        startCdn()
        val autoT = track("song_play_auto")
        val manualT = track("song_play_manual")
        manager.startDownload(autoT, autoCached = true)
        manager.startDownload(manualT)
        awaitCompleted("song_play_auto")
        awaitCompleted("song_play_manual")

        val auto = repository.getDownloadedTrack("song_play_auto")!!
        assertEquals(fileFor("song_play_auto").absolutePath, auto.mediaUri)
        assertEquals("song_play_auto", auto.id)

        val manual = repository.getDownloadedTrack("song_play_manual")!!
        assertEquals(fileFor("song_play_manual").absolutePath, manual.mediaUri)
        assertEquals("song_play_manual", manual.id)
    }

    // ------------ A/B: playback-safe Clear auto-saved -------------------

    @Test
    fun `clear auto-saved defers the playing AUTO file and deletes it once released`() = runBlocking {
        startCdn()
        val playing = track("song_pl_a")
        val other = track("song_pl_b")
        val manualT = track("song_pl_manual")
        manager.startDownload(playing, autoCached = true)
        awaitCompleted("song_pl_a")
        manager.startDownload(other, autoCached = true)
        awaitCompleted("song_pl_b")
        manager.startDownload(manualT)
        awaitCompleted("song_pl_manual")

        // Song A is the file ExoPlayer currently has open.
        manager.isTrackProtected = { it == "song_pl_a" }
        manager.clearAutoCachedDownloads()

        // Every OTHER AUTO file is deleted immediately...
        awaitRowGone("song_pl_b")
        assertTrue(!fileFor("song_pl_b").exists())
        // ...the playing AUTO survives temporarily (still AUTO, still there,
        // with its deferred-deletion intent PERSISTED on the row so process
        // death cannot lose it)...
        awaitFlagged("song_pl_a")
        val aWhilePlaying = entity("song_pl_a")!!
        assertEquals(true, aWhilePlaying.autoCached)
        assertTrue(fileFor("song_pl_a").isFile)
        // ...and MANUAL is untouched throughout (never flagged).
        val m = entity("song_pl_manual")!!
        assertEquals(false, m.autoCached)
        assertEquals(false, m.pendingDeletion)
        assertTrue(fileFor("song_pl_manual").isFile)

        // Playback releases the file (stop / leave the track).
        manager.isTrackProtected = { false }
        manager.flushPendingDeletions()
        awaitRowGone("song_pl_a")
        assertTrue("deferred file removed after release", !fileFor("song_pl_a").exists())
        // MANUAL still untouched after the deferred delete ran.
        assertNotNull(entity("song_pl_manual"))
        assertTrue(fileFor("song_pl_manual").isFile)
    }

    // ------ B/C/E: deferred deletion survives process death + restart -----

    @Test
    fun `clear auto-saved then process death - restart deterministically removes the deferred AUTO`() = runBlocking {
        startCdn()
        val playing = track("song_death_1")
        val other = track("song_death_2")
        val manualT = track("song_death_manual")
        manager.startDownload(playing, autoCached = true)
        awaitCompleted("song_death_1")
        manager.startDownload(other, autoCached = true)
        awaitCompleted("song_death_2")
        manager.startDownload(manualT)
        awaitCompleted("song_death_manual")

        // Clear auto-saved while song_1 is playing: song_2 dies now, song_1
        // is deferred WITH its intent persisted in the database.
        manager.isTrackProtected = { it == "song_death_1" }
        manager.clearAutoCachedDownloads()
        awaitRowGone("song_death_2")
        awaitFlagged("song_death_1")
        assertTrue(fileFor("song_death_1").isFile)

        // "Process death": a brand-new manager over the same store. Nothing
        // is playing (its protection predicate is off), so startup
        // reconciliation must discover the persisted flag and delete the file.
        val manager2 = WearsicDownloadManager(context, repository)
        try {
            manager2.cleanupInterruptedDownloads()
            manager2.trimAutoCache()
            manager2.flushPendingDeletions()
            awaitRowGone("song_death_1")
            assertTrue(!fileFor("song_death_1").exists())

            // Repeated reconciliation is idempotent: runs again change nothing
            // and never touch the MANUAL download.
            manager2.cleanupInterruptedDownloads()
            manager2.trimAutoCache()
            manager2.flushPendingDeletions()
            val m = entity("song_death_manual")
            assertNotNull(m)
            assertEquals(false, m!!.autoCached)
            assertTrue(fileFor("song_death_manual").isFile)
        } finally {
            manager2.release()
            Thread.sleep(200)
        }
    }

    @Test
    fun `restart while the file is still in use keeps it deferred`() = runBlocking {
        startCdn()
        val t = track("song_keep_def")
        manager.startDownload(t, autoCached = true)
        awaitCompleted("song_keep_def")

        // Defer deletion of the playing AUTO (as a recreated VM would find it:
        // the media session is STILL playing this local file).
        manager.isTrackProtected = { it == "song_keep_def" }
        manager.deleteDownload("song_keep_def")
        awaitFlagged("song_keep_def")
        assertTrue(fileFor("song_keep_def").isFile)

        // "Restart" while the file is genuinely still in use: a fresh manager
        // whose protection predicate reflects the live session. Flushing (and
        // repeating it — idempotent) must NOT delete the file.
        val manager2 = WearsicDownloadManager(context, repository)
        try {
            manager2.isTrackProtected = { it == "song_keep_def" }
            repeat(3) {
                manager2.cleanupInterruptedDownloads()
                manager2.trimAutoCache()
                manager2.flushPendingDeletions()
            }
            Thread.sleep(200)
            val kept = entity("song_keep_def")
            assertNotNull("in-use file must never be deleted", kept)
            assertEquals(true, kept!!.autoCached)
            assertEquals(true, kept.pendingDeletion)
            assertTrue(fileFor("song_keep_def").isFile)

            // Playback releases it -> deferred deletion finally runs.
            manager2.isTrackProtected = { false }
            manager2.flushPendingDeletions()
            awaitRowGone("song_keep_def")
            assertTrue(!fileFor("song_keep_def").exists())
        } finally {
            manager2.release()
            Thread.sleep(200)
        }
    }

    // ------ D: MANUAL promotion during a deferred deletion ---------------

    @Test
    fun `manual promotion during a deferred deletion keeps the file MANUAL`() = runBlocking {
        startCdn()
        val t = track("song_prom_defer")
        manager.startDownload(t, autoCached = true)
        awaitCompleted("song_prom_defer")

        // Defer deletion (the AUTO is flagged). The file is NOT in use here —
        // the dangerous case is a flush racing the promotion.
        manager.isTrackProtected = { it == "song_prom_defer" }
        manager.deleteDownload("song_prom_defer")
        awaitFlagged("song_prom_defer")
        manager.isTrackProtected = { false }

        // Press Download (promotion intent recorded synchronously) and flush
        // the deferred deletion in the same tick: MANUAL must win.
        manager.startDownload(t)
        manager.flushPendingDeletions()

        awaitRowFlipsManual("song_prom_defer")
        Thread.sleep(300)
        val e = entity("song_prom_defer")
        assertNotNull("promoted file must survive the deferred deletion", e)
        assertEquals(false, e!!.autoCached)
        assertEquals("deletion intent cleared on promotion", false, e.pendingDeletion)
        assertTrue(fileFor("song_prom_defer").isFile)

        // And the promoted MANUAL survives a subsequent "restart" reconcile.
        val manager2 = WearsicDownloadManager(context, repository)
        try {
            manager2.cleanupInterruptedDownloads()
            manager2.trimAutoCache()
            manager2.flushPendingDeletions()
            Thread.sleep(200)
            val afterRestart = entity("song_prom_defer")
            assertNotNull(afterRestart)
            assertEquals(false, afterRestart!!.autoCached)
            assertTrue(fileFor("song_prom_defer").isFile)
        } finally {
            manager2.release()
            Thread.sleep(200)
        }
    }

    // ------- C: changing away from the playing AUTO releases it ---------

    @Test
    fun `switching to another song makes the deferred AUTO eligible`() = runBlocking {
        startCdn()
        val first = track("song_sw_1")
        val second = track("song_sw_2")
        val third = track("song_sw_3")
        for (t in listOf(first, second, third)) {
            manager.startDownload(t, autoCached = true)
            awaitCompleted(t.id)
        }

        // "song_sw_1" is playing; clear-auto defers it and deletes the rest.
        manager.isTrackProtected = { it == "song_sw_1" }
        manager.clearAutoCachedDownloads()
        awaitRowGone("song_sw_2")
        awaitRowGone("song_sw_3")
        assertNotNull(entity("song_sw_1"))
        assertTrue(fileFor("song_sw_1").isFile)

        // User changes track: "song_sw_2" would be the new local file, so
        // "song_sw_1" is no longer in use.
        manager.isTrackProtected = { it == "song_sw_2" }
        manager.flushPendingDeletions()
        awaitRowGone("song_sw_1")
        assertTrue("released file deleted", !fileFor("song_sw_1").exists())
    }

    // ---------- D/E: promotion always beats concurrent eviction ---------

    @Test
    fun `manual promotion racing auto eviction keeps the file as MANUAL`() = runBlocking {
        startCdn()
        val older = track("song_prom_ev_1")
        val newer = track("song_prom_ev_2")
        manager.startDownload(older, autoCached = true)
        awaitCompleted("song_prom_ev_1")
        manager.startDownload(newer, autoCached = true)
        awaitCompleted("song_prom_ev_2")

        // Lower the cap AND request a manual download of the OLDEST auto in
        // the same tick: the promotion intent is recorded synchronously, so
        // the eviction pass must not delete the file out from under it.
        manager.maxAutoCachedTracks = 1
        manager.startDownload(older) // MANUAL request (async promotion write)
        manager.trimAutoCache()      // racing eviction pass

        awaitRowFlipsManual("song_prom_ev_1")
        Thread.sleep(300) // let any racing eviction settle
        val promoted = entity("song_prom_ev_1")
        assertNotNull("promoted file must survive eviction", promoted)
        assertEquals(false, promoted!!.autoCached)
        assertTrue(fileFor("song_prom_ev_1").isFile)
        // The in-cap AUTO remains.
        assertEquals(true, entity("song_prom_ev_2")!!.autoCached)
        assertTrue(fileFor("song_prom_ev_2").isFile)
    }

    @Test
    fun `clear auto-saved during AUTO to MANUAL promotion leaves MANUAL`() = runBlocking {
        startCdn()
        val t = track("song_clr_prom")
        manager.startDownload(t, autoCached = true)
        awaitCompleted("song_clr_prom")

        // Press Download and Clear-auto back to back: MANUAL must win and the
        // physical file must survive.
        manager.startDownload(t)          // promote request (intent recorded)
        manager.clearAutoCachedDownloads() // racing bulk delete

        awaitRowFlipsManual("song_clr_prom")
        Thread.sleep(300)
        val e = entity("song_clr_prom")
        assertNotNull(e)
        assertEquals(false, e!!.autoCached)
        assertTrue(fileFor("song_clr_prom").isFile)
    }

    // ------------------ F: legacy CANCELLED rows -----------------------

    @Test
    fun `startup reconciliation rescues or removes CANCELLED rows safely`() = runBlocking {
        val dir = manager.getDownloadDir()

        // (a) A VALID completed MANUAL download that must never be touched.
        startCdn()
        val validManual = track("song_ok_manual")
        manager.startDownload(validManual)
        awaitCompleted("song_ok_manual")
        // (b) A valid completed AUTO download that must never be touched.
        val validAuto = track("song_ok_auto")
        manager.startDownload(validAuto, autoCached = true)
        awaitCompleted("song_ok_auto")

        // (c) Dead CANCELLED row: no physical file -> row + leftovers removed.
        val deadCancelled = Track(
            id = "song_dead_cancelled", title = "Dead", artist = "A", album = "B",
            durationMs = 1000L, mediaUri = "http://127.0.0.1:1/never",
        )
        repository.recordQueued(deadCancelled, fileFor("song_dead_cancelled").absolutePath)
        db.downloadDao().updateState("song_dead_cancelled", DownloadState.CANCELLED.name)
        File(dir, "song_dead_cancelled.m4a.part").writeBytes(ByteArray(2048))

        // (d) Rescue-able CANCELLED row: a FULL file exists under it (the old
        //     cancel/complete race) -> must become COMPLETED, media preserved.
        val rescuedManual = track("song_rescue_m")
        repository.recordQueued(rescuedManual, fileFor("song_rescue_m").absolutePath)
        fileFor("song_rescue_m").writeBytes(ByteArray(8192) { 1 })
        db.downloadDao().updateState("song_rescue_m", DownloadState.CANCELLED.name)
        val rescuedAuto = track("song_rescue_a")
        repository.recordQueued(rescuedAuto, fileFor("song_rescue_a").absolutePath, autoCached = true)
        fileFor("song_rescue_a").writeBytes(ByteArray(4096) { 2 })
        db.downloadDao().updateState("song_rescue_a", DownloadState.CANCELLED.name)

        // Run reconciliation (twice: it must be idempotent).
        manager.cleanupInterruptedDownloads()
        manager.cleanupInterruptedDownloads()

        // Dead row gone, part cleaned.
        assertNull(entity("song_dead_cancelled"))
        assertTrue(!File(dir, "song_dead_cancelled.m4a.part").exists())
        // Rescue: rows completed with their OWNERSHIP preserved, files intact.
        val rm = entity("song_rescue_m")!!
        assertEquals(DownloadState.COMPLETED.name, rm.downloadState)
        assertEquals(false, rm.autoCached)
        assertTrue(fileFor("song_rescue_m").isFile)
        val ra = entity("song_rescue_a")!!
        assertEquals(DownloadState.COMPLETED.name, ra.downloadState)
        assertEquals("AUTO ownership preserved", true, ra.autoCached)
        assertTrue(fileFor("song_rescue_a").isFile)
        // Valid AUTO + MANUAL downloads untouched.
        assertEquals(false, entity("song_ok_manual")!!.autoCached)
        assertTrue(fileFor("song_ok_manual").isFile)
        assertEquals(true, entity("song_ok_auto")!!.autoCached)
        assertTrue(fileFor("song_ok_auto").isFile)
    }

    // ----------- G: restart reconciliation with deferred state ----------

    @Test
    fun `restart reconciles over-cap AUTO while keeping MANUAL`() = runBlocking {
        startCdn()
        val auto1 = track("song_rst_1")
        val auto2 = track("song_rst_2")
        val manualT = track("song_rst_manual")
        manager.startDownload(auto1, autoCached = true)
        awaitCompleted("song_rst_1")
        manager.startDownload(auto2, autoCached = true)
        awaitCompleted("song_rst_2")
        manager.startDownload(manualT)
        awaitCompleted("song_rst_manual")

        // Pre-restart world: cap lowered to 1 while the OLDEST auto was
        // playing, so the trim pass deferred it (row kept, deletion pending in
        // memory) — this is the exact state a Clear-auto/trim leaves behind
        // with a protected file. The pending set is in-memory and lost on
        // restart, so startup reconciliation must re-apply the cap.
        manager.maxAutoCachedTracks = 1
        manager.isTrackProtected = { it == "song_rst_1" }
        manager.trimAutoCache()
        Thread.sleep(300)
        // song_rst_1 (oldest) was protected -> row still present, still AUTO.
        assertEquals(true, entity("song_rst_1")!!.autoCached)
        assertTrue(fileFor("song_rst_1").isFile)
        // song_rst_2 (newest) is within the cap -> kept.
        assertEquals(true, entity("song_rst_2")!!.autoCached)
        assertTrue(fileFor("song_rst_2").isFile)

        // "Restart": a fresh manager reconciles. Nothing is playing anymore,
        // so the over-cap oldest AUTO is finally removed; the in-cap AUTO and
        // every MANUAL download survive.
        val manager2 = WearsicDownloadManager(context, repository)
        try {
            manager2.maxAutoCachedTracks = 1
            manager2.cleanupInterruptedDownloads()
            manager2.trimAutoCache()
            manager2.flushPendingDeletions()

            awaitRowGone("song_rst_1")
            assertTrue(!fileFor("song_rst_1").exists())
            // Newest AUTO is within the cap -> kept as AUTO.
            val kept = entity("song_rst_2")
            assertNotNull(kept)
            assertEquals(true, kept!!.autoCached)
            assertTrue(fileFor("song_rst_2").isFile)
            // No valid MANUAL was deleted.
            val m = entity("song_rst_manual")
            assertNotNull(m)
            assertEquals(false, m!!.autoCached)
            assertTrue(fileFor("song_rst_manual").isFile)
        } finally {
            manager2.release()
            Thread.sleep(200)
        }
    }

    // --------------------------- fake CDN ------------------------------

    private class FakeCdn(
        private val totalBytes: Int,
        private val delayBeforeMs: Long,
        private val delayMidBodyMs: Long
    ) {
        private val hitsById = ConcurrentHashMap<String, Int>()
        private lateinit var server: HttpServer
        val port: Int get() = server.address.port

        fun hits(id: String): Int = hitsById[id] ?: 0

        fun start() {
            val payload = ByteArray(totalBytes) { (it % 251).toByte() }
            server = HttpServer.create(InetSocketAddress(0), 0)
            server.createContext("/stream") { exchange ->
                val path = exchange.requestURI.path
                val id = path.substringAfterLast('/')
                hitsById.merge(id, 1, Int::plus)
                if (delayBeforeMs > 0) Thread.sleep(delayBeforeMs)

                val range = exchange.requestHeaders.getFirst("Range") ?: "bytes=0-"
                val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                val body = if (start >= totalBytes) ByteArray(0) else payload.copyOfRange(start.toInt(), totalBytes)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${totalBytes - 1}/$totalBytes")
                exchange.sendResponseHeaders(206, if (body.isEmpty()) -1L else body.size.toLong())
                exchange.responseBody.use { out ->
                    if (body.isNotEmpty() && delayMidBodyMs > 0) {
                        // Send the first chunk, then stall mid-transfer so the
                        // client is observably still DOWNLOADING.
                        val firstChunk = 32 * 1024
                        out.write(body, 0, minOf(firstChunk, body.size))
                        out.flush()
                        Thread.sleep(delayMidBodyMs)
                        if (body.size > firstChunk) out.write(body, firstChunk, body.size - firstChunk)
                    } else {
                        out.write(body)
                    }
                }
            }
            server.start()
        }

        fun stop() {
            if (::server.isInitialized) server.stop(0)
        }
    }
}
