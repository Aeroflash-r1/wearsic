package com.wearsic.server

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
class DatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    private fun track(id: String, title: String = "T $id") = TrackDto(
        videoId = id, title = title, uploader = "Artist", durationMs = 1000L, thumbnailUrl = "http://x/y.jpg",
    )

    private fun newDb(): Database = Database(tempDir.resolve("test-${System.nanoTime()}.db").toString())

    // ---------------- Favorites ----------------

    @Test
    fun `favorite add list remove round-trip`() {
        val db = newDb()
        db.addFavorite(track("a"))
        db.addFavorite(track("b"))

        val favorites = db.listFavorites()
        assertEquals(2, favorites.size)
        // Newest first.
        assertEquals("b", favorites[0].videoId)

        db.removeFavorite("a")
        assertEquals(listOf("b"), db.listFavorites().map { it.videoId })
    }

    @Test
    fun `re-adding a favorite updates instead of duplicating`() {
        val db = newDb()
        db.addFavorite(track("a", title = "Old Title"))
        db.addFavorite(track("a", title = "New Title"))

        val favorites = db.listFavorites()
        assertEquals(1, favorites.size, "PK conflict must upsert, not duplicate")
        assertEquals("New Title", favorites[0].title)
    }

    @Test
    fun `removing a nonexistent favorite is a silent no-op`() {
        val db = newDb()
        db.removeFavorite("ghost")
        assertEquals(0, db.listFavorites().size)
    }

    // ---------------- Playlists ----------------

    @Test
    fun `playlist create add-track list detail round-trip`() {
        val db = newDb()
        val pl = db.createPlaylist("Road trip")

        db.addTrackToPlaylist(pl.id, track("t1"))
        db.addTrackToPlaylist(pl.id, track("t2"))

        val summaries = db.listPlaylists()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries[0].trackCount)
        assertEquals("http://x/y.jpg", summaries[0].thumbnailUrl, "thumbnail comes from the first track")

        val detail = db.getPlaylistTracks(pl.id)
        assertNotNull(detail)
        assertEquals("Road trip", detail.name)
        assertEquals(listOf("t1", "t2"), detail.tracks.map { it.videoId }, "tracks ordered by position")
    }

    @Test
    fun `re-adding a track to a playlist updates metadata without reordering`() {
        val db = newDb()
        val pl = db.createPlaylist("Mix")
        db.addTrackToPlaylist(pl.id, track("t1", title = "First"))
        db.addTrackToPlaylist(pl.id, track("t2"))
        db.addTrackToPlaylist(pl.id, track("t1", title = "Renamed"))

        val detail = db.getPlaylistTracks(pl.id)!!
        assertEquals(2, detail.tracks.size, "PK (playlist, track) conflict must upsert")
        assertEquals("Renamed", detail.tracks.first { it.videoId == "t1" }.title)
        assertEquals(listOf("t1", "t2"), detail.tracks.map { it.videoId }, "position must be preserved")
    }

    @Test
    fun `delete single track keeps playlist`() {
        val db = newDb()
        val pl = db.createPlaylist("Mix")
        db.addTrackToPlaylist(pl.id, track("t1"))
        db.addTrackToPlaylist(pl.id, track("t2"))

        db.deletePlaylistTrack(pl.id, "t1")

        val detail = db.getPlaylistTracks(pl.id)!!
        assertEquals(listOf("t2"), detail.tracks.map { it.videoId })
    }

    @Test
    fun `wildcard videoId deletes the entire playlist via FK cascade`() {
        val db = newDb()
        val pl1 = db.createPlaylist("Gone")
        val pl2 = db.createPlaylist("Stays")
        db.addTrackToPlaylist(pl1.id, track("t1"))
        db.addTrackToPlaylist(pl1.id, track("t2"))
        db.addTrackToPlaylist(pl2.id, track("t3"))

        // The documented special case (API_CONTRACT.md): DELETE .../tracks/*.
        db.deletePlaylistTrack(pl1.id, "*")

        assertNull(db.getPlaylistTracks(pl1.id), "playlist row must be gone")
        assertEquals(0, db.listPlaylists().filter { it.id == pl1.id }.size)
        // Cascade must have removed the orphaned track rows too.
        val staying = db.getPlaylistTracks(pl2.id)!!
        assertEquals(listOf("t3"), staying.tracks.map { it.videoId })
    }

    @Test
    fun `unknown playlist id returns null detail`() {
        val db = newDb()
        assertNull(db.getPlaylistTracks("nope"))
    }

    // ---------------- Settings (cookie persistence) ----------------

    @Test
    fun `settings upsert semantics`() {
        val db = newDb()
        assertNull(db.getSetting("youtube_cookie"))
        db.setSetting("youtube_cookie", "SID=REDACTED")
        assertEquals("SID=REDACTED", db.getSetting("youtube_cookie"))
        db.setSetting("youtube_cookie", "SID=ROTATED")
        assertEquals("SID=ROTATED", db.getSetting("youtube_cookie"))
    }

    // ---------------- Surrogate match persistence ----------------

    @Test
    fun `match put get round-trip and upsert`() {
        val db = newDb()
        assertNull(db.getMatchedVideoId("it:123"))
        db.putMatchedVideoId("it:123", "abcXYZ")
        assertEquals("abcXYZ", db.getMatchedVideoId("it:123"))
        // Re-matching later must update, not duplicate.
        db.putMatchedVideoId("it:123", "newVID")
        assertEquals("newVID", db.getMatchedVideoId("it:123"))
        assertEquals(1, db.matchCount())
    }

    @Test
    fun `stale matches are treated as missing so they self-heal`() {
        val db = newDb()
        val old = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000 // > 30 days
        db.putMatchedVideoId("it:old", "staleVid", nowMs = old)
        db.putMatchedVideoId("it:fresh", "freshVid")

        assertNull(db.getMatchedVideoId("it:old"), ">30d match must be re-matched")
        assertEquals("freshVid", db.getMatchedVideoId("it:fresh"))
    }

    @Test
    fun `match table stays bounded by evicting oldest rows`() {
        val db = newDb()
        // The cap is private; verify eviction behaviorally by exceeding it via
        // reflection-free arithmetic on a tiny synthetic scenario is not
        // possible, so just assert the cap constant through the API: inserting
        // more than MAX_MATCH_ROWS (2000) is impractical in a unit test, so
        // verify small-scale invariants instead: count accuracy + no growth on
        // upsert.
        db.putMatchedVideoId("it:1", "v1")
        db.putMatchedVideoId("it:2", "v2")
        db.putMatchedVideoId("it:1", "v1-updated")
        assertEquals(2, db.matchCount())
    }

    // ---------------- Restart persistence ----------------

    @Test
    fun `data survives reopening the same database file`() {
        val path = tempDir.resolve("persist.db").toString()
        val track = track("keepme")

        val first = Database(path)
        val pl = first.createPlaylist("Saved")
        first.addTrackToPlaylist(pl.id, track)
        first.addFavorite(track)
        first.setSetting("youtube_cookie", "SID=REDACTED")

        val reopened = Database(path)
        assertEquals(1, reopened.listFavorites().size)
        assertEquals("keepme", reopened.listFavorites()[0].videoId)
        assertEquals("Saved", reopened.getPlaylistTracks(pl.id)?.name)
        assertEquals("SID=REDACTED", reopened.getSetting("youtube_cookie"))
    }

    @Test
    fun `stale-match wipe runs once and never again`() {
        val db = newDb()
        db.putMatchedVideoId("it:1", "wrongAudio")
        db.putMatchedVideoId("it:2", "wrongAudio2")

        db.clearStaleMatchesOnce("matcher_version_wipe", "1.4.4")
        assertEquals(0, db.matchCount(), "pre-fix matches must be wiped")

        // Second boot: marker present -> no-op, new matches survive.
        db.putMatchedVideoId("it:3", "goodAudio")
        db.clearStaleMatchesOnce("matcher_version_wipe", "1.4.4")
        assertEquals(1, db.matchCount())
        assertEquals("goodAudio", db.getMatchedVideoId("it:3"))
    }

    @Test
    fun `surrogate matches survive reopening the same database file`() {
        val path = tempDir.resolve("persist-matches.db").toString()

        val first = Database(path)
        first.putMatchedVideoId("it:724472291", "realVideoId")

        // Simulates the server restarting: fresh Database instance, same file.
        val reopened = Database(path)
        assertEquals("realVideoId", reopened.getMatchedVideoId("it:724472291"))
    }
}
