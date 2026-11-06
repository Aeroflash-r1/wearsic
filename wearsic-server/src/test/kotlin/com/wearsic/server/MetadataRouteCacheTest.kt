package com.wearsic.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the route-level metadata TTL caches (TtlLoaderCache) on
 * /api/suggestions, /api/related and /api/search/albums:
 *
 *  - repeating an identical read within its window costs ONE upstream call,
 *  - a different key always re-loads,
 *  - a FAILED load is never cached — the next caller retries.
 *
 * These endpoints are the watch's cheapest-to-repeat, most
 * upstream-costly-to-recompute reads (Radio re-open, typing pauses, album
 * re-browses), and each upstream call is a live request someone else on the
 * network pays for too.
 */
class MetadataRouteCacheTest {

    private class CountingGateway : YoutubeMetadataClient {
        val suggestionsCalls = AtomicInteger()
        val relatedCalls = AtomicInteger()
        val albumCalls = AtomicInteger()
        var failRelated = false

        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> {
            suggestionsCalls.incrementAndGet()
            return listOf("suggestion one", "suggestion two")
        }

        override suspend fun related(videoId: String): List<TrackDto> {
            // Count FIRST so a failed fetch is still an upstream attempt —
            // the failure path in the route must be able to prove "the
            // failing call happened and the retry happened too".
            relatedCalls.incrementAndGet()
            if (failRelated) error("upstream related fetch failed")
            return listOf(TrackDto(videoId = "related01", title = "Radio row", uploader = "Artist"))
        }

        override suspend fun searchAlbums(query: String): List<AlbumDto> {
            albumCalls.incrementAndGet()
            return listOf(AlbumDto(id = "https://music.youtube.com/playlist?list=PL1", name = "Album", uploader = "Artist"))
        }

        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    /** Gateway under test, assigned by [withServer] before each test. */
    private lateinit var gateway: CountingGateway

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        gateway = CountingGateway()
        val dbFile = Files.createTempFile("wearsic-metadata-cache-test", ".db").toFile().apply { deleteOnExit() }
        val database = Database(dbFile.absolutePath)
        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = gateway,
        )
        val transcoder = Transcoder(
            client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO),
            availability = false,
        )
        val audioProxy = AudioProxy(gateway, io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO), transcoder)
        application {
            module(gateway, database, audioProxy, null, orchestrator, transcoder)
        }
        block()
    }

    // ---------------- suggestions ----------------

    @Test
    fun `repeat suggestions within the window cost one upstream call`() = withServer {
        val first = client.get("/api/suggestions?q=hello")
        assertEquals(HttpStatusCode.OK, first.status)
        val second = client.get("/api/suggestions?q=hello")
        assertEquals(first.bodyAsText(), second.bodyAsText(), "the cached answer must be byte-identical")
        assertEquals(1, gateway.suggestionsCalls.get())
    }

    @Test
    fun `a different prefix reloads suggestions`() = withServer {
        client.get("/api/suggestions?q=hello")
        client.get("/api/suggestions?q=hello%20there")
        assertEquals(2, gateway.suggestionsCalls.get())
    }

    @Test
    fun `whitespace normalization shares one suggestions entry`() = withServer {
        client.get("/api/suggestions?q=hello")
        client.get("/api/suggestions?q=%20hello%20")
        assertEquals(1, gateway.suggestionsCalls.get(), "the cache key is the trimmed query")
    }

    // ---------------- related (radio) ----------------

    @Test
    fun `repeat radio reads within the window cost one upstream call`() = withServer {
        val first = client.get("/api/related/songid01")
        assertEquals(HttpStatusCode.OK, first.status)
        val second = client.get("/api/related/songid01")
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, gateway.relatedCalls.get())
    }

    @Test
    fun `a failing related fetch is never cached`() = withServer {
        gateway.failRelated = true
        val failing = client.get("/api/related/songid01")
        assertEquals(HttpStatusCode.InternalServerError, failing.status)
        gateway.failRelated = false
        val retry = client.get("/api/related/songid01")
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(
            2, gateway.relatedCalls.get(),
            "every failure must be retried by the next caller",
        )
    }
}
