package com.wearsic.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorFallbackTest {

    private class FakeMetadataSource : MetadataSource {
        var tracks: List<YtmTrack> = emptyList()
        val searchCalls: MutableList<String> = mutableListOf()

        override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
            searchCalls.add(query)
            return tracks
        }

        override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
            videoId = track.videoId,
            title = track.title ?: "Unknown",
            uploader = track.artist ?: "Unknown",
            durationMs = track.durationMs ?: 0L,
            thumbnailUrl = track.thumbnailUrl,
        )
    }

    private fun track(id: String, name: String = "Song $id") = YtmTrack(
        videoId = id,
        title = name,
        artist = "Artist $id",
        durationMs = 200_000L,
    )

    @Test
    fun `YTM results are returned directly - YouTube fallback NOT called`() = runTest {
        val metadata = FakeMetadataSource().apply { tracks = listOf(track("vid1"), track("vid2")) }
        val youtube = FakeYoutubeMetadataClient()
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        val results = orchestrator.search("crowded house")

        assertEquals(2, results.size)
        assertEquals("vid1", results[0].videoId, "results must carry real videoIds")
        assertEquals(0, orchestrator.youtubeFallbackCount, "usable YTM results must not count as YouTube fallbacks")
        assertEquals(listOf("crowded house"), metadata.searchCalls)
    }

    @Test
    fun `empty YTM results fall back to YouTube search`() = runTest {
        val metadata = FakeMetadataSource().apply { tracks = emptyList() }
        val youtube = FakeYoutubeMetadataClient().apply {
            results = listOf(TrackDto(videoId = "yt1", title = "YT Song", uploader = "Chan"))
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        val results = orchestrator.search("obscure song")

        assertEquals(1, results.size)
        assertEquals("yt1", results[0].videoId)
        assertEquals(1, orchestrator.youtubeFallbackCount)
        assertEquals("obscure song", youtube.searchCalls.single())
    }

    /**
     * The real YTMusicService filters unusable entries (null title/artist)
     * BEFORE returning, so partially populated entries never reach the
     * orchestrator. Any non-empty list = usable.
     */
    @Test
    fun `non-empty YTM response is treated as usable regardless of entry quality`() = runTest {
        val metadata = FakeMetadataSource().apply {
            tracks = listOf(
                YtmTrack(videoId = "a", title = null, artist = "Artist"),
                YtmTrack(videoId = "b", title = "Name", artist = null),
            )
        }
        val youtube = FakeYoutubeMetadataClient().apply {
            results = listOf(TrackDto(videoId = "yt9", title = "t", uploader = "u"))
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        val results = orchestrator.search("broken metadata")

        assertEquals(2, results.size, "non-empty YTM list must be returned as-is (no fallback)")
        assertEquals(0, orchestrator.youtubeFallbackCount)
    }

    @Test
    fun `real YouTube ids pass through unchanged`() = runTest {
        val metadata = FakeMetadataSource()
        val youtube = FakeYoutubeMetadataClient()
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        assertEquals("dQw4w9WgXcQ", orchestrator.resolveStreamVideoId("dQw4w9WgXcQ"))
        assertEquals("NZ3Ck43m_ZY", orchestrator.resolveStreamVideoId("NZ3Ck43m_ZY"))
        assertEquals(0, orchestrator.youtubeFallbackCount)
    }

    @Test
    fun `unknown legacy surrogate id resolves to null`() = runTest {
        val metadata = FakeMetadataSource()
        val youtube = FakeYoutubeMetadataClient()
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        assertEquals(null, orchestrator.resolveStreamVideoId("it:999999"))
    }

    @Test
    fun `metadata exception falls back without hiding cancellation`() = runTest {
        val metadata = object : MetadataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> = error("upstream unavailable")
            override fun toTrackDto(track: YtmTrack): TrackDto = error("unused")
        }
        val youtube = FakeYoutubeMetadataClient().apply {
            results = listOf(TrackDto("fallback", "Song", "Artist"))
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)
        try {
            assertEquals("fallback", orchestrator.search("  my   song  ").single().videoId)
            assertEquals("my song", youtube.searchCalls.single())
            assertTrue(orchestrator.search(" ").isEmpty())
        } finally { orchestrator.shutdown() }
    }

    @Test
    fun `duplicate and blank ids do not reach the watch`() = runTest {
        val metadata = FakeMetadataSource().apply {
            tracks = listOf(track("same"), track("same"), track(""))
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, FakeYoutubeMetadataClient())
        try {
            assertEquals(listOf("same"), orchestrator.search("music").map { it.videoId })
        } finally { orchestrator.shutdown() }
    }

    @Test
    fun `metadata cancellation is propagated instead of triggering fallback`() = runTest {
        val metadata = object : MetadataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> = throw kotlinx.coroutines.CancellationException("cancelled")
            override fun toTrackDto(track: YtmTrack): TrackDto = error("unused")
        }
        val youtube = FakeYoutubeMetadataClient()
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)
        try {
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { orchestrator.search("music") }
            assertTrue(youtube.searchCalls.isEmpty())
        } finally { orchestrator.shutdown() }
    }

    // ---------------- Legacy match store (pre-1.5 `it:` ids) ----------------

    private class InMemoryMatchStore : MetadataSearchOrchestrator.MatchPersistence {
        val map = mutableMapOf<String, String>()
        override fun getMatchedVideoId(surrogateId: String): String? = map[surrogateId]
    }

    @Test
    fun `legacy persisted match resolves without hitting YouTube`() = runTest {
        val metadata = FakeMetadataSource()
        val youtube = FakeYoutubeMetadataClient()
        val store = InMemoryMatchStore().apply { map["it:77"] = "realVideo" }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube, persistentMatches = store)

        assertEquals("realVideo", orchestrator.resolveStreamVideoId("it:77"))
        assertTrue(youtube.searchCalls.isEmpty(), "legacy replay must not touch YouTube")
    }

    @Test
    fun `persistence failure degrades to null, never throws`() = runTest {
        val metadata = FakeMetadataSource()
        val youtube = FakeYoutubeMetadataClient()
        val broken = object : MetadataSearchOrchestrator.MatchPersistence {
            override fun getMatchedVideoId(surrogateId: String): String? =
                throw IllegalStateException("db gone")
        }

        val orchestrator = MetadataSearchOrchestrator(metadata, youtube, persistentMatches = broken)
        assertEquals(null, orchestrator.resolveStreamVideoId("it:77"), "broken store must degrade to null")
    }
}
