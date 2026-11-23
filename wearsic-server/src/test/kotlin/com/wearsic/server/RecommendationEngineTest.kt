package com.wearsic.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
 * Tier A+B recommendations: multi-seed related fetch + taste-profile scoring
 * + TTL cache. Unit tests cover the pure engine; an HTTP test covers the
 * route contract (auth, 400 on no seeds, return shape, cache hit).
 */
class RecommendationEngineTest {

    /** Fake gateway with deterministic per-seed related lists + call count. */
    private class FakeGateway : YoutubeMetadataClient {
        val relatedCalls = java.util.concurrent.atomic.AtomicInteger(0)

        private val catalog = mapOf(
            "seed1" to listOf(
                TrackDto("a1", "Alpha One", "ArtistA"),
                TrackDto("a2", "Alpha Two", "ArtistA"),
                TrackDto("b1", "Beta One", "ArtistB"),
            ),
            "seed2" to listOf(
                TrackDto("a1", "Alpha One", "ArtistA"), // duplicate across seeds
                TrackDto("c1", "Gamma One", "ArtistC"),
            ),
        )

        override suspend fun search(query: String): List<TrackDto> = emptyList()
        override suspend fun streamTarget(videoId: String): StreamTarget? = null
        override suspend fun suggestions(prefix: String): List<String> = emptyList()
        override suspend fun related(videoId: String): List<TrackDto> {
            relatedCalls.incrementAndGet()
            return catalog[videoId] ?: emptyList()
        }
        override suspend fun searchAlbums(query: String): List<AlbumDto> = emptyList()
        override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = null
    }

    // ---------------- Engine (pure logic) ----------------

    @Test
    fun `engine merges seeds dedupes and ranks by taste`() = runBlocking {
        val gateway = FakeGateway()
        val engine = RecommendationEngine(gateway)

        val result = engine.build(
            RecommendationEngine.Seeds(
                trackIds = listOf("seed1", "seed2"),
                artists = setOf("artistb"),
                exclude = emptySet(),
            )
        )

        // a1 appears in both seeds -> deduped. b1 (ArtistB = the user's top
        // artist) must rank first thanks to the taste boost.
        assertEquals(listOf("b1", "a1", "a2", "c1"), result.map { it.videoId })
        assertEquals("b1", result.first().videoId)
        assertEquals(2, gateway.relatedCalls.get())
    }

    @Test
    fun `engine excludes known tracks`() = runBlocking {
        val engine = RecommendationEngine(FakeGateway())
        val result = engine.build(
            RecommendationEngine.Seeds(
                trackIds = listOf("seed1"),
                exclude = setOf("a1", "b1"),
            )
        )
        assertEquals(listOf("a2"), result.map { it.videoId })
    }

    @Test
    fun `engine returns empty without seeds`() = runBlocking {
        val engine = RecommendationEngine(FakeGateway())
        assertTrue(engine.build(RecommendationEngine.Seeds(emptyList())).isEmpty())
    }

    // ---------------- HTTP route ----------------

    private fun withServer(
        gateway: FakeGateway = FakeGateway(),
        block: suspend ApplicationTestBuilder.(FakeGateway) -> Unit,
    ) = testApplication {
        val dbFile = Files.createTempFile("wearsic-rec-test", ".db").toFile().apply { deleteOnExit() }
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
            module(gateway, database, audioProxy, "secret", orchestrator, transcoder)
        }
        block(gateway)
    }

    @Test
    fun `recommendations route returns merged scored results and dedupes`() =
        withServer { _ ->
            val resp = client.post("/api/recommendations") {
                header("X-Wearsic-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"seedTrackIds":["seed1","seed2"],"topArtists":["artistb"],"excludeTrackIds":[]}"""
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val results = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["results"]!!.jsonArray
            val ids = results.map { it.jsonObject["videoId"]!!.jsonPrimitive.content }
            assertEquals(listOf("b1", "a1", "a2", "c1"), ids)
        }

    @Test
    fun `recommendations route rejects missing seeds`() = withServer { _ ->
        val resp = client.post("/api/recommendations") {
            header("X-Wearsic-Key", "secret")
            contentType(ContentType.Application.Json)
            setBody("""{"seedTrackIds":[],"topArtists":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `recommendations require auth`() = withServer { _ ->
        val resp = client.post("/api/recommendations") {
            header("X-Wearsic-Key", "wrong")
            contentType(ContentType.Application.Json)
            setBody("""{"seedTrackIds":["seed1"],"topArtists":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `recommendations are cached within the ttl`() = withServer { gateway ->
        val body = """{"seedTrackIds":["seed1"],"topArtists":[],"excludeTrackIds":[]}"""
        repeat(2) {
            val resp = client.post("/api/recommendations") {
                header("X-Wearsic-Key", "secret")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
        // Second identical request must be a cache hit: related fetched once.
        assertEquals(1, gateway.relatedCalls.get())
    }

    private fun runBlocking(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
