package com.wearsic.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the player-disc album resolution: the two-step lookup (pin the exact
 * song row by title+artist, then resolve its album name to a playable album
 * URL) and the guards that stop it opening the WRONG album. All offline —
 * fake MetadataSource + fake YoutubeMetadataClient, mirroring how
 * ApiIntegrationTest wires them.
 */
class AlbumResolveTest {

    private fun row(videoId: String, title: String, artist: String, album: String? = null) =
        YtmTrack(videoId = videoId, title = title, artist = artist, album = album)

    private fun album(id: String, name: String, uploader: String) =
        AlbumDto(id = id, name = name, uploader = uploader)

    /** Minimal orchestrator for the pure helpers (no real lookups). */
    private fun orch() = MetadataSearchOrchestrator(
        metadata = FakeMetadata(emptyList()),
        youtube = FakeGateway(emptyList()),
    )

    // ---------------- bestAlbumRow ----------------

    @Test
    fun `bestAlbumRow picks the exact title and artist`() {
        val rows = listOf(
            row("aaa", "Smells Like Teen Spirit", "Nirvana"),
            row("bbb", "Smells Like Teen Spirit", "Nirvana", album = "Nevermind"),
        )
        val picked = orch().bestAlbumRow(rows, "Smells Like Teen Spirit", "Nirvana")
        assertEquals("bbb", picked?.videoId, "the album-bearing row must win")
        assertEquals("Nevermind", picked?.album)
    }

    @Test
    fun `bestAlbumRow rejects a matching title by a different artist`() {
        val rows = listOf(
            row("ccc", "Imagine", "John Lennon", album = "Imagine"),
            row("ddd", "Imagine", "Ariana Grande", album = "Imagine"),
        )
        // Query artist = "Ariana Grande" — only the exact-artist row qualifies.
        val picked = orch().bestAlbumRow(rows, "Imagine", "Ariana Grande")
        assertEquals("ddd", picked?.videoId)
    }

    @Test
    fun `bestAlbumRow rejects a row without any album name`() {
        val rows = listOf(row("eee", "Enter Sandman", "Metallica"))
        assertNull(orch().bestAlbumRow(rows, "Enter Sandman", "Metallica"))
    }

    @Test
    fun `bestAlbumRow rejects an exact title by a different artist even with an album`() {
        val rows = listOf(
            row("g1", "Imagine", "John Lennon", album = "Imagine"),
        )
        // The query artist (Ariana Grande) is not the row's artist: the row
        // must never win, no matter how exact the title and album are.
        assertNull(orch().bestAlbumRow(rows, "Imagine", "Ariana Grande"))
    }

    @Test
    fun `bestAlbumRow returns null on no match`() {
        val rows = listOf(row("fff", "Something Else", "Someone", album = "X"))
        assertNull(orch().bestAlbumRow(rows, "Totally Different", "Artist"))
    }

    // ---------------- bestAlbumMatch ----------------

    @Test
    fun `bestAlbumMatch prefers the album whose uploader matches the artist`() {
        val albums = listOf(
            album("https://playlist/1", "Nevermind", "Various Artists"),
            album("https://playlist/2", "Nevermind", "Nirvana"),
        )
        val picked = orch().bestAlbumMatch("Nevermind", albums, "Nirvana")
        assertEquals("https://playlist/2", picked?.id)
    }

    @Test
    fun `bestAlbumMatch falls back to the top-ranked name match when artist is unknown`() {
        val albums = listOf(
            album("https://playlist/1", "Nevermind", "Various Artists"),
            album("https://playlist/2", "Nevermind", "Nirvana"),
        )
        val picked = orch().bestAlbumMatch("Nevermind", albums, "")
        assertEquals("https://playlist/1", picked?.id, "first in ranked order when no artist hint")
    }

    @Test
    fun `bestAlbumMatch returns null on empty album list`() {
        assertNull(orch().bestAlbumMatch("Nevermind", emptyList(), "Nirvana"))
    }

    // ---------------- resolveAlbum (two-step, offline) ----------------

    private class FakeMetadata(
        private val rows: List<YtmTrack>,
    ) : MetadataSource {
        override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> = rows
        override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, track.title ?: "", track.artist ?: "")
    }

    private class FakeGateway(
        private val albums: List<AlbumDto>,
    ) : YoutubeMetadataClient {
        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> = emptyList()
        override suspend fun searchAlbums(query: String): List<AlbumDto> = albums
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    @Test
    fun `resolveAlbum resolves a video to its album`() = runBlocking {
        val orchestrator = MetadataSearchOrchestrator(
            metadata = FakeMetadata(
                listOf(row("abc123abc12", "Come As You Are", "Nirvana", album = "Nevermind")),
            ),
            youtube = FakeGateway(
                listOf(album("https://playlist/nevermind", "Nevermind", "Nirvana")),
            ),
        )
        val album = orchestrator.resolveAlbum("abc123abc12", "Come As You Are", "Nirvana")
        assertEquals("https://playlist/nevermind", album?.id)
        assertEquals("Nevermind", album?.name)
    }

    @Test
    fun `resolveAlbum returns null when the song row has no album`() = runBlocking {
        val orchestrator = MetadataSearchOrchestrator(
            metadata = FakeMetadata(listOf(row("abc123abc12", "Come As You Are", "Nirvana"))),
            youtube = FakeGateway(emptyList()),
        )
        assertNull(orchestrator.resolveAlbum("abc123abc12", "Come As You Are", "Nirvana"))
    }

    @Test
    fun `resolveAlbum returns null when the album list cannot confirm the artist`() = runBlocking {
        val orchestrator = MetadataSearchOrchestrator(
            metadata = FakeMetadata(
                listOf(row("abc123abc12", "Come As You Are", "Nirvana", album = "Nevermind")),
            ),
            youtube = FakeGateway(
                listOf(album("https://playlist/other", "Nevermind", "Totally Different Artist")),
            ),
        )
        // Same-named album by a different artist must NOT be returned.
        assertNull(orchestrator.resolveAlbum("abc123abc12", "Come As You Are", "Nirvana"))
    }

    @Test
    fun `resolveAlbum rejects an invalid video id`() = runBlocking {
        val orchestrator = MetadataSearchOrchestrator(
            metadata = FakeMetadata(emptyList()),
            youtube = FakeGateway(emptyList()),
        )
        assertNull(orchestrator.resolveAlbum("not-a-video-id", "x", "y"))
    }

    @Test
    fun `resolveAlbum caches positive results`() = runBlocking {
        var searches = 0
        val metadata = object : MetadataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
                searches++
                return listOf(row("abc123abc12", "Come As You Are", "Nirvana", album = "Nevermind"))
            }
            override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "", "")
        }
        val orchestrator = MetadataSearchOrchestrator(
            metadata = metadata,
            youtube = FakeGateway(listOf(album("https://playlist/nevermind", "Nevermind", "Nirvana"))),
        )
        assertEquals("https://playlist/nevermind", orchestrator.resolveAlbum("abc123abc12", "Come As You Are", "Nirvana")?.id)
        assertEquals("https://playlist/nevermind", orchestrator.resolveAlbum("abc123abc12", "Come As You Are", "Nirvana")?.id)
        assertEquals(1, searches, "second call must be served from the cache")
        assertTrue(true)
    }
}
