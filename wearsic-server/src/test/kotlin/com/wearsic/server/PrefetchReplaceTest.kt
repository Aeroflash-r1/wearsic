package com.wearsic.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The warm for the NEWEST results must actually run.
 *
 * `prefetch` used to `return` when a previous prefetch was still active, so a
 * search issued while an earlier warm was running (normal while typing, or
 * when opening a second list right behind the first) never warmed its own
 * rows: the visible results stayed cold and the tap paid a full extraction.
 * The fix cancels the stale warm and starts the new one.
 *
 * This is pinned with a gate-blocked fake extractor: with the old
 * early-return, the second id is never attempted and the wait times out.
 */
class PrefetchReplaceTest {

    @Test
    fun `a newer prefetch replaces an in-flight warm`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = Collections.synchronizedList(mutableListOf<String>())

        val youtube = object : YoutubeMetadataClient {
            override suspend fun search(query: String): List<TrackDto> = emptyList()
            override suspend fun streamTarget(videoId: String): StreamTarget? {
                started.add(videoId)
                gate.await()
                return StreamTarget(url = "https://cdn.example/$videoId", mimeType = "audio/mp4")
            }
            override suspend fun suggestions(prefix: String): List<String> = emptyList()
            override suspend fun related(videoId: String): List<TrackDto> = emptyList()
            override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
            override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
        }

        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = youtube,
        )

        try {
            orchestrator.prefetchVideoIds(listOf("first"))
            awaitStarted(started, "first")

            // The first warm is still blocked on the gate here.
            orchestrator.prefetchVideoIds(listOf("second"))
            awaitStarted(started, "second")
        } finally {
            gate.complete(Unit)
            orchestrator.shutdown()
        }
    }

    private suspend fun awaitStarted(started: List<String>, id: String) {
        withTimeout(5_000) {
            while (id !in started) delay(10)
        }
        assertTrue(id in started)
    }
}
