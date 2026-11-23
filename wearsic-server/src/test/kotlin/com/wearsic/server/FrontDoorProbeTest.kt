package com.wearsic.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The front-door probe is the deterministic endpoint `wearsic cloudflare
 * verify` fetches THROUGH the permanent workers.dev address. It has to
 * exercise exactly the parts of playback that a Worker proxy can break:
 * authentication, Range forwarding, 206 + Content-Range, and a byte-exact
 * streamed body. These tests pin that contract at the engine.
 */
class FrontDoorProbeTest {

    private companion object {
        const val KEY = "testkey"
        const val SIZE = 64 * 1024
    }

    private class FakeGateway : YoutubeMetadataClient {
        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> = emptyList()
        override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val dbFile = Files.createTempFile("wearsic-probe-test", ".db").toFile().apply { deleteOnExit() }
        val database = Database(dbFile.absolutePath)
        val gateway = FakeGateway()
        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = gateway,
        )
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        val transcoder = Transcoder(client = http, availability = false)
        val audioProxy = AudioProxy(gateway, http, transcoder)

        application {
            module(gateway, database, audioProxy, KEY, orchestrator, transcoder)
        }
        block()
    }

    @Test
    fun `the probe requires the API key`() = withServer {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/frontdoor/probe").status)
    }

    @Test
    fun `a full probe is deterministic and advertises byte ranges`() = withServer {
        val res = client.get("/api/frontdoor/probe") {
            header("X-Wearsic-Key", KEY)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("bytes", res.headers[HttpHeaders.AcceptRanges])
        assertEquals("no-store", res.headers[HttpHeaders.CacheControl])
        assertEquals(SIZE, res.headers[HttpHeaders.ContentLength]?.toInt())
        val body = res.readBytes()
        assertEquals(SIZE, body.size)
        assertEquals(7.toByte(), body[0])
        assertEquals(((10 * 31 + 7) and 0xFF).toByte(), body[10])
    }

    @Test
    fun `a bounded range returns 206 with exact bytes`() = withServer {
        val res = client.get("/api/frontdoor/probe") {
            header("X-Wearsic-Key", KEY)
            header(HttpHeaders.Range, "bytes=10-19")
        }
        assertEquals(HttpStatusCode.PartialContent, res.status)
        assertEquals("bytes 10-19/$SIZE", res.headers[HttpHeaders.ContentRange])
        assertEquals("10", res.headers[HttpHeaders.ContentLength])
        assertEquals("bytes", res.headers[HttpHeaders.AcceptRanges])
        val body = res.readBytes()
        assertEquals(10, body.size)
        assertEquals(((10 * 31 + 7) and 0xFF).toByte(), body[0])
        assertEquals(((19 * 31 + 7) and 0xFF).toByte(), body[9])
    }

    @Test
    fun `an open-ended range returns the tail`() = withServer {
        val res = client.get("/api/frontdoor/probe") {
            header("X-Wearsic-Key", KEY)
            header(HttpHeaders.Range, "bytes=${SIZE - 6}-")
        }
        assertEquals(HttpStatusCode.PartialContent, res.status)
        assertEquals("bytes ${SIZE - 6}-${SIZE - 1}/$SIZE", res.headers[HttpHeaders.ContentRange])
        assertEquals(6, res.readBytes().size)
    }

    @Test
    fun `an unsatisfiable range is 416 with the total size`() = withServer {
        val res = client.get("/api/frontdoor/probe") {
            header("X-Wearsic-Key", KEY)
            header(HttpHeaders.Range, "bytes=${SIZE + 10}-")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, res.status)
        assertEquals("bytes */$SIZE", res.headers[HttpHeaders.ContentRange])
    }

    @Test
    fun `multiple ranges are rejected rather than mis-served`() = withServer {
        val res = client.get("/api/frontdoor/probe") {
            header("X-Wearsic-Key", KEY)
            header(HttpHeaders.Range, "bytes=0-1,4-5")
        }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, res.status)
        assertTrue(res.headers[HttpHeaders.ContentRange]?.contains("/$SIZE") == true)
    }
}
