package com.wearsic.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the read-path rules that the watch's OkHttp disk cache and the
 * Tailscale Funnel speed-up depend on:
 *  - only read endpoints carry Cache-Control, with the documented windows;
 *  - only successful (2xx) responses do — a transient 404 (e.g. a failed
 *    playlist resolve) must not reach the watch as a cached failure;
 *  - large JSON reads are gzipped when the client accepts gzip; small bodies
 *    and non-accepting clients stay byte-identical.
 */
class ApiCacheAndCompressionTest {

    private class FakeGateway : YoutubeMetadataClient {
        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> = emptyList()
        override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    private fun withServer(block: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val dbFile = Files.createTempFile("wearsic-cache-test", ".db").toFile().apply { deleteOnExit() }
        val database = Database(dbFile.absolutePath)
        val gateway = FakeGateway()
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
        block(database)
    }

    // ---------------- Cache-Control policy ----------------

    @Test
    fun `content reads advertise the five minute window`() = withServer {
        assertEquals(
            "private, max-age=300",
            client.get("/api/search?q=test").headers[HttpHeaders.CacheControl],
        )
        assertEquals(
            "private, max-age=300",
            client.get("/api/suggestions?q=te").headers[HttpHeaders.CacheControl],
        )
        assertEquals(
            "private, max-age=300",
            client.get("/api/search/albums?q=test").headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `user-state reads advertise the short window`() = withServer {
        assertEquals(
            "private, max-age=5",
            client.get("/api/favorites").headers[HttpHeaders.CacheControl],
        )
        assertEquals(
            "private, max-age=5",
            client.get("/api/playlists").headers[HttpHeaders.CacheControl],
        )
    }

    @Test
    fun `unlisted paths carry no directive`() = withServer {
        assertNull(client.get("/api/config/youtube-cookie").headers[HttpHeaders.CacheControl])

        val created = client.post("/api/favorites") {
            contentType(ContentType.Application.Json)
            setBody("""{"videoId":"newvid","title":"T","uploader":"A"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        assertNull(created.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `failed reads are never marked cacheable`() = withServer {
        // Missing playlist -> 404 on a path that IS cacheable when it succeeds.
        val missing = client.get("/api/playlists/nope")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertNull(missing.headers[HttpHeaders.CacheControl])

        // Unresolvable album URL -> failure status, no directive.
        val unresolvable = client.get(
            "/api/playlist?url=https%3A%2F%2Fmusic.youtube.com%2Fplaylist%3Flist%3DPLtest"
        )
        assertTrue(
            unresolvable.status.value >= 400,
            "expected a failure status, was ${unresolvable.status}",
        )
        assertNull(unresolvable.headers[HttpHeaders.CacheControl])
    }

    // ---------------- gzip ----------------

    @Test
    fun `large json reads gzip when accepted`() = withServer { database ->
        repeat(30) { i ->
            database.addFavorite(
                TrackDto(
                    videoId = "video$i",
                    title = "A reasonably long song title number $i",
                    uploader = "An uploader name that also costs bytes $i",
                )
            )
        }

        val plain = client.get("/api/favorites") { header(HttpHeaders.AcceptEncoding, "identity") }
        val gzipped = client.get("/api/favorites") { header(HttpHeaders.AcceptEncoding, "gzip") }

        assertNull(plain.headers[HttpHeaders.ContentEncoding])
        assertEquals("gzip", gzipped.headers[HttpHeaders.ContentEncoding])

        val plainBytes = plain.readBytes()
        val gzipBytes = gzipped.readBytes()
        assertTrue(
            plainBytes.size > 512,
            "seeded list must exceed the 512-byte compression floor (was ${plainBytes.size})",
        )
        assertTrue(
            gzipBytes.size < plainBytes.size,
            "gzip must shrink the payload (plain=${plainBytes.size}, gzip=${gzipBytes.size})",
        )
        // gzip magic numbers: the body really is a gzip stream.
        assertEquals(0x1f, gzipBytes[0].toInt() and 0xff)
        assertEquals(0x8b, gzipBytes[1].toInt() and 0xff)
    }

    @Test
    fun `small json reads stay uncompressed`() = withServer {
        val resp = client.get("/api/favorites") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertNull(resp.headers[HttpHeaders.ContentEncoding])
        assertEquals("[]", resp.bodyAsText())
    }
}
