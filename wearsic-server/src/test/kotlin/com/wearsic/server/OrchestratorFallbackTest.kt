package com.wearsic.server

import kotlinx.coroutines.delay
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
        // A full page of STRONG matches (every query token present) is the
        // fast path: plain YouTube is never consulted.
        val metadata = FakeMetadataSource().apply {
            tracks = (1..20).map { track("vid$it", "Crowded House Song $it") }
        }
        val youtube = FakeYoutubeMetadataClient()
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)

        val results = orchestrator.search("crowded house")

        assertEquals(10, results.size)
        assertEquals("vid1", results[0].videoId, "results must carry real videoIds")
        assertEquals(0, orchestrator.youtubeFallbackCount, "strong YTM results must not trigger the YouTube fallback")
        assertTrue(youtube.searchCalls.isEmpty())
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

    @Test
    fun `weak YTM page consults plain YouTube and surfaces the exact upload`() = runTest {
        // The real-world miss: YTM returns a full page of tangential tracks
        // (weak token overlap) — e.g. unrelated "Mary Jane" covers — while
        // the exact upload the user wants exists on plain YouTube. The
        // orchestrator must consult the YouTube fallback and merge its hit.
        val metadata = object : MetadataSource {
            val calls = mutableListOf<String>()
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
                calls.add(query)
                return (1..limit).map { YtmTrack(videoId = "weak$it", title = "Some Other Track $it", artist = "Various Artists", durationMs = 200_000L) }
            }
            override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
                videoId = track.videoId,
                title = track.title ?: "Unknown",
                uploader = track.artist ?: "Unknown",
                durationMs = track.durationMs ?: 0L,
            )
        }
        val youtube = FakeYoutubeMetadataClient().apply {
            results = listOf(
                TrackDto(videoId = "ytTarget", title = "Mary Jane", uploader = "Lil Tjay"),
                TrackDto(videoId = "yt2", title = "Mary Jane 2", uploader = "Pop Smoke"),
            )
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)
        try {
            val results = orchestrator.search("Lil Tjay x Pop Smoke - Mary Jane (Unreleased Audio)")
            assertTrue(results.any { it.videoId == "ytTarget" }, "the exact upload must surface: ${results.map { it.videoId }}")
            assertTrue(orchestrator.youtubeFallbackCount >= 1, "YouTube fallback must have been consulted")
            assertTrue(youtube.searchCalls.any { it.equals("lil tjay x pop smoke - mary jane (unreleased audio)", ignoreCase = true) }, "exact query must be sent to YouTube: ${youtube.searchCalls}")
        } finally { orchestrator.shutdown() }
    }

    @Test
    fun `slow general consult never delays the first page`() = runTest {
        // Even when the general-YouTube rescue is slow, the first page must
        // answer within the tight budget — it must not wait out the slow
        // fetch. The YTM results come back; the slow consult contributes
        // nothing.
        val metadata = object : MetadataSource {
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> =
                (1..limit).map { YtmTrack(videoId = "w$it", title = "Some Other Track $it", artist = "Various Artists", durationMs = 200_000L) }
            override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
                videoId = track.videoId,
                title = track.title ?: "Unknown",
                uploader = track.artist ?: "Unknown",
                durationMs = track.durationMs ?: 0L,
            )
        }
        val youtube = object : YoutubeMetadataClient by FakeYoutubeMetadataClient() {
            override suspend fun searchGeneral(query: String, limit: Int): List<TrackDto> {
                delay(20_000)
                return listOf(TrackDto(videoId = "slowYt", title = "Slow find", uploader = "u"))
            }
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)
        try {
            val results = orchestrator.search("weak query")
            assertTrue(results.isNotEmpty(), "first page must still return YTM results")
            assertTrue(results.none { it.videoId == "slowYt" }, "timed-out general consult must not contribute")
            assertEquals(0, orchestrator.youtubeFallbackCount)
        } finally { orchestrator.shutdown() }
    }

    @Test
    fun `zero results everywhere triggers relaxed-query retry`() = runTest {
        // The metadata source only answers the relaxed variant; the exact
        // query (with parentheticals) finds nothing. The orchestrator must
        // retry the stripped query and surface the song.
        val metadata = object : MetadataSource {
            val calls = mutableListOf<String>()
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
                calls.add(query)
                return if (query.equals("weather with you", ignoreCase = true)) listOf(
                    YtmTrack(videoId = "relaxed1", title = "Weather with You", artist = "Crowded House", durationMs = 200_000L)
                ) else emptyList()
            }
            override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
                videoId = track.videoId,
                title = track.title ?: "Unknown",
                uploader = track.artist ?: "Unknown",
                durationMs = track.durationMs ?: 0L,
            )
        }
        val youtube = FakeYoutubeMetadataClient() // returns nothing
        val orchestrator = MetadataSearchOrchestrator(metadata, youtube)
        try {
            val results = orchestrator.search("Weather with You (Official Video)")
            assertEquals(1, results.size)
            assertEquals("relaxed1", results[0].videoId)
            assertTrue(metadata.calls.any { it.equals("weather with you", ignoreCase = true) }, "relaxed variant must be tried: ${metadata.calls}")
        } finally { orchestrator.shutdown() }
    }

    @Test
    fun `relaxed variant surfaces a missed song even when exact page is full of weak matches`() = runTest {
        // The metadata source only answers the relaxed variant ("mary jane");
        // the full exact query (artist separator + parentheticals) finds weak
        // tracks. The merge must include the strong relaxed hit.
        val metadata = object : MetadataSource {
            val calls = mutableListOf<String>()
            override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
                calls.add(query)
                return if (query.equals("mary jane", ignoreCase = true)) {
                    listOf(YtmTrack(videoId = "target1", title = "Mary Jane", artist = "Lil Tjay", durationMs = 150_000L))
                } else {
                    (1..limit).map { YtmTrack(videoId = "weak$it", title = "Some Other Track $it", artist = "Various Artists", durationMs = 200_000L) }
                }
            }
            override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
                videoId = track.videoId,
                title = track.title ?: "Unknown",
                uploader = track.artist ?: "Unknown",
                durationMs = track.durationMs ?: 0L,
            )
        }
        val orchestrator = MetadataSearchOrchestrator(metadata, FakeYoutubeMetadataClient())
        try {
            val results = orchestrator.search("Lil Tjay x Pop Smoke - Mary Jane (Unreleased Audio)")
            assertTrue(results.any { it.videoId == "target1" }, "the missed song must surface: ${results.map { it.videoId }}")
            assertTrue(metadata.calls.any { it.equals("mary jane", ignoreCase = true) }, "stripped artist variant must be tried: ${metadata.calls}")
        } finally { orchestrator.shutdown() }
    }

    /**
     * The real YTMusicService filters unusable entries (null title/artist)
     * BEFORE returning, so partially populated entries never reach the
     * orchestrator. Any non-empty list = usable.
     */
    @Test
    fun `weak-but-present YTM results still consult plain YouTube and merge`() = runTest {
        // YTM returns two usable entries for this query, but they share no
        // query tokens (weak page) — the orchestrator still asks plain YouTube
        // (which has the real upload) and merges without dropping the YTM row.
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

        assertEquals(listOf("a", "b", "yt9"), results.map { it.videoId })
        assertEquals(1, orchestrator.youtubeFallbackCount)
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
