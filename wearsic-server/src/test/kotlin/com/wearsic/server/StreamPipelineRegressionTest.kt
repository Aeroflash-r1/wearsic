package com.wearsic.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the two changes that decide tap-to-play latency:
 *
 *  1. background warm-ups must NEVER enter the extractor (they used to hold the
 *     global extraction mutex for ~10s while a user's tap queued behind them —
 *     the "it just sits there, then 404s" mode), and
 *  2. a resolved stream target must survive a restart (it used to live only in
 *     a 64-entry in-memory map, so every engine update, reboot or lmkd kill
 *     cold-started every song in the library).
 */
class StreamPipelineRegressionTest {

    /** Records the `allowExtraction` decision every resolve was made with. */
    private class RecordingGateway : YoutubeMetadataClient {
        val flags = Collections.synchronizedList(mutableListOf<Boolean>())

        override suspend fun search(query: String): List<TrackDto> = emptyList()

        override suspend fun streamTarget(videoId: String): StreamTarget? {
            // The one-argument form means "the caller permits extraction".
            flags.add(true)
            return null
        }

        override suspend fun streamTarget(videoId: String, allowExtraction: Boolean): StreamTarget? {
            flags.add(allowExtraction)
            return null
        }

        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> = emptyList()
        override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    @Test
    fun `background prefetch never enters the extractor`() = runBlocking {
        val gateway = RecordingGateway()
        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = gateway,
        )
        try {
            orchestrator.prefetchVideoIds(listOf("vid00000001", "vid00000002"))
            withTimeout(5_000) {
                while (gateway.flags.isEmpty()) delay(10)
            }
        } finally {
            orchestrator.shutdown()
        }
        assertTrue(gateway.flags.isNotEmpty(), "prefetch must actually have resolved something")
        assertTrue(
            gateway.flags.all { !it },
            "prefetch asked for extraction: ${gateway.flags} — background work must stay lean-only",
        )
    }

    @Test
    fun `a resolved target survives a restart`() {
        val file = Files.createTempFile("wearsic-store", ".db").toFile().apply { deleteOnExit() }
        val entry = CachedStreamTarget(
            StreamTarget(
                url = "https://cdn.example/audio?id=1",
                mimeType = "audio/mp4",
                itag = 140,
                contentLength = 3_449_447L,
                needsExtractorWarm = false,
            ),
            expiresAtMillis = System.currentTimeMillis() + 60_000,
        )

        Database(file.absolutePath).apply {
            putStreamTarget("vid00000001", entry)
            close()
        }

        // A new process: a new Database over the same file.
        val reopened = Database(file.absolutePath)
        try {
            val restored = reopened.getStreamTarget("vid00000001")
            assertEquals(entry.target.url, restored?.target?.url)
            assertEquals(entry.target.mimeType, restored?.target?.mimeType)
            assertEquals(entry.target.itag, restored?.target?.itag)
            assertEquals(entry.target.contentLength, restored?.target?.contentLength)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `cached statements survive repeated reads`() {
        // Regression: the durable reads used PreparedStatement.use { }, which
        // closes the statement. Because statements are cached and reused, the
        // SECOND read of any video threw "statement is closed" — the first tap
        // in a process worked and every later one silently fell back to a full
        // resolve. Read twice to pin the reuse.
        val file = Files.createTempFile("wearsic-store", ".db").toFile().apply { deleteOnExit() }
        val db = Database(file.absolutePath)
        try {
            db.putStreamTarget(
                "vid00000003",
                CachedStreamTarget(
                    StreamTarget(url = "https://cdn.example/y", mimeType = "audio/mp4", itag = 251),
                    expiresAtMillis = System.currentTimeMillis() + 60_000,
                ),
            )
            assertEquals("https://cdn.example/y", db.getStreamTarget("vid00000003")?.target?.url)
            assertEquals("https://cdn.example/y", db.getStreamTarget("vid00000003")?.target?.url)

            db.putWallThrottled("vid00000003", 4_000_000_000L)
            assertEquals(4_000_000_000L, db.getWallThrottledUntil("vid00000003"))
            assertEquals(4_000_000_000L, db.getWallThrottledUntil("vid00000003"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `expired targets are pruned instead of served`() {
        val file = Files.createTempFile("wearsic-store", ".db").toFile().apply { deleteOnExit() }
        val db = Database(file.absolutePath)
        try {
            db.putStreamTarget(
                "vid00000009",
                CachedStreamTarget(
                    StreamTarget(url = "https://cdn.example/x", mimeType = "audio/mp4"),
                    expiresAtMillis = 1L,
                ),
            )
            db.pruneStreamState(System.currentTimeMillis())
            assertEquals(null, db.getStreamTarget("vid00000009"))
        } finally {
            db.close()
        }
    }
}
