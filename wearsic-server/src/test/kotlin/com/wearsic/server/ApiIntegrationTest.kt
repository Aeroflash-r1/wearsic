package com.wearsic.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline integration tests over the real routing stack: exercises the health
 * endpoint, the auth interceptor, StatusPages error mapping, the stream rate
 * limiter, and the wildcard playlist-deletion contract — with a fake
 * YoutubeGateway so nothing touches the network.
 */
class ApiIntegrationTest {

    private class FakeGateway : YoutubeMetadataClient {
        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> = emptyList()
        override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    private fun withServer(
        apiKey: String? = null,
        healthMeter: ExtractionHealthMeter? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val dbFile = Files.createTempFile("wearsic-test", ".db").toFile().apply { deleteOnExit() }
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
            // module() installs ContentNegotiation itself — do not duplicate it here.
            module(gateway, database, audioProxy, apiKey, orchestrator, transcoder, healthMeter)
        }
        block()
    }

    // ---------------- Readiness ----------------

    @Test
    fun `ready endpoint is public and contains no secrets`() = withServer(apiKey = "secret") {
        val resp = client.get("/ready")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"ready\":true"), body)
        assertTrue(body.contains("\"database\":true"), body)
        assertTrue(body.contains("\"extractor\":true"), body)
        assertTrue(body.contains("\"engineVersion\":"), body)
        // The API key must never appear in any observability output.
        assertTrue(!body.contains("secret"), body)
    }

    @Test
    fun `ready endpoint does not require the api key`() = withServer(apiKey = "secret") {
        // Monitoring probes (like /health) stay usable without auth.
        assertEquals(HttpStatusCode.OK, client.get("/ready").status)
    }

    @Test
    fun `health exposes extraction timestamps and never leaks the key`() =
        withServer(apiKey = "secret", healthMeter = ExtractionHealthMeter().apply { recordSuccess() }) {
            val body = client.get("/health").bodyAsText()
            assertTrue(body.contains("\"lastSuccessAtMillis\":"), body)
            assertTrue(body.contains("\"lastFailureAtMillis\":"), body)
            assertTrue(body.contains("\"extraction\":{"), body)
            assertTrue(!body.contains("secret"), body)
        }

    // ---------------- Health ----------------

    @Test
    fun `health is public and reports version`() = withServer(apiKey = "secret") {
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        assertEquals(ServerVersion.VERSION, body["version"]!!.jsonPrimitive.content)
    }

    // ---------------- Auth ----------------

    @Test
    fun `api rejects missing key with 401 and JSON error`() = withServer(apiKey = "secret") {
        val resp = client.get("/api/favorites")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertTrue(resp.bodyAsText().contains("API key"))
    }

    @Test
    fun `api rejects wrong key with 401`() = withServer(apiKey = "secret") {
        val resp = client.get("/api/favorites") { header("X-Wearsic-Key", "wrong") }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `api accepts correct key`() = withServer(apiKey = "secret") {
        val resp = client.get("/api/favorites") { header("X-Wearsic-Key", "secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("[]", resp.bodyAsText())
    }

    @Test
    fun `api accepts bearer token`() = withServer(apiKey = "secret") {
        val resp = client.get("/api/favorites") { header(HttpHeaders.Authorization, "Bearer secret") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals("[]", resp.bodyAsText())
    }

    @Test
    fun `open server works without any key`() = withServer(apiKey = null) {
        val resp = client.get("/api/favorites")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    // ---------------- Error mapping ----------------

    @Test
    fun `malformed JSON body returns 400 JSON error not 500`() = withServer {
        val resp = client.post("/api/favorites") {
            contentType(ContentType.Application.Json)
            setBody("{ not valid json")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status, "body: ${resp.bodyAsText()}")
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body.containsKey("error"), "error body must be JSON, got: ${resp.bodyAsText()}")
    }

    @Test
    fun `unknown route returns 404 JSON error`() = withServer {
        val resp = client.get("/api/definitely-not-a-route")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains("error"), resp.bodyAsText())
    }

    @Test
    fun `stream for unresolvable id returns a 404 error response`() = withServer {
        val resp = client.get("/api/stream/nonexistent0")
        assertEquals(HttpStatusCode.NotFound, resp.status)
        // The route's own ErrorResponse takes precedence; the StatusPages
        // fallback text ("No such endpoint") is acceptable when the framework
        // short-circuits, so only the status is asserted strictly here.
        assertTrue(resp.bodyAsText().isNotEmpty(), "404 must carry a body")
    }

    // ---------------- Rate limiting ----------------

    @Test
    fun `stream endpoint rate-limits after the burst is exhausted`() = withServer {
        var limited = false
        // Burst capacity is 12; a few extra requests beyond that must trip it.
        repeat(20) { i ->
            val resp = client.get("/api/stream/video-$i")
            if (resp.status == HttpStatusCode.ServiceUnavailable) limited = true
        }
        assertTrue(limited, "expected at least one 503 rate-limit response after 20 rapid stream requests")
    }

    // ---------------- Favorites / playlists over HTTP ----------------

    @Test
    fun `favorite POST-GET-DELETE round trip over HTTP`() = withServer {
        val post = client.post("/api/favorites") {
            contentType(ContentType.Application.Json)
            header("X-Wearsic-Key", "")
            setBody("""{"videoId":"v1","title":"T","uploader":"A","durationMs":1000}""")
        }
        assertEquals(HttpStatusCode.Created, post.status)

        val list = client.get("/api/favorites")
        assertEquals(1, Json.parseToJsonElement(list.bodyAsText()).jsonArray.size)

        val del = client.delete("/api/favorites/v1")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals("[]", client.get("/api/favorites").bodyAsText())
    }

    @Test
    fun `DELETE playlist tracks with wildcard removes the whole playlist`() = withServer {
        val created = client.post("/api/playlists") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Temp"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/playlists/$id/tracks") {
            contentType(ContentType.Application.Json)
            setBody("""{"videoId":"v1","title":"T","uploader":"A","durationMs":1}""")
        }

        val del = client.delete("/api/playlists/$id/tracks/*")
        assertEquals(HttpStatusCode.NoContent, del.status)
        assertEquals("[]", client.get("/api/playlists").bodyAsText(), "playlist must be gone via wildcard deletion")
    }

    @Test
    fun `playlist without url parameter returns 400`() = withServer {
        val resp = client.get("/api/playlist")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ---------------- Cookie config (must never echo the value) ----------------

    @Test
    fun `cookie config endpoints never expose the cookie value`() = withServer {
        val set = client.post("/api/config/youtube-cookie") {
            contentType(ContentType.Application.Json)
            setBody("""{"cookie":"SID=REDACTED; HSID=REDACTED"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        val body = set.bodyAsText()
        assertTrue(body.contains("\"hasCookie\":true"), body)
        assertTrue(!body.contains("SID"), "cookie value must never be echoed: $body")

        val status = client.get("/api/config/youtube-cookie").bodyAsText()
        assertTrue(!status.contains("SID"), "cookie value must never be echoed: $status")
    }
}
