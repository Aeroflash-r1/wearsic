package com.wearsic.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api/prewarm/{videoId}` contract.
 *
 * This endpoint exists because the watch's old warm-up requested
 * `/api/stream/{id}` with `Range: bytes=0-0`, and the proxy deliberately runs
 * tiny windows to EOF — so every tap relayed a WHOLE song over Tailscale
 * Funnel, racing the real playback request. These tests pin the replacement:
 * resolve (and cache) the target, answer with a tiny JSON body, never send
 * audio, and never consume the stream rate limiter's permits.
 */
class PrewarmEndpointTest {

    private fun withServer(
        apiKey: String? = null,
        gateway: FakeYoutubeMetadataClient = FakeYoutubeMetadataClient(),
        block: suspend ApplicationTestBuilder.(FakeYoutubeMetadataClient) -> Unit,
    ) = testApplication {
        val dbFile = Files.createTempFile("wearsic-prewarm", ".db").toFile().apply { deleteOnExit() }
        val database = Database(dbFile.absolutePath)
        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = gateway,
        )
        val client = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        val transcoder = Transcoder(client, availability = false)
        val audioProxy = AudioProxy(gateway, client, transcoder)

        application {
            module(
                gateway = gateway,
                database = database,
                audioProxy = audioProxy,
                apiKey = apiKey,
                searchOrchestrator = orchestrator,
                transcoder = transcoder,
            )
        }
        block(gateway)
    }

    @Test
    fun `prewarm resolves the target and answers with a tiny JSON body`() {
        val gateway = FakeYoutubeMetadataClient().apply {
            streamTargets["vid"] = StreamTarget("https://cdn.example/audio", "audio/mp4")
        }
        withServer(gateway = gateway) { gw ->
            val resp = client.get("/api/prewarm/vid")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("\"ready\":true"), body)
            assertTrue(body.length < 100, "prewarm must not carry audio or bulk data: $body")
            assertEquals(listOf("vid"), gw.streamTargetCalls)
        }
    }

    @Test
    fun `prewarm of an unresolvable id is a 404 JSON error`() {
        withServer { _ ->
            val resp = client.get("/api/prewarm/missing")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = resp.bodyAsText()
            assertTrue(body.contains("error"), body)
            assertTrue(!body.contains("audio/"), "error body must not be audio: $body")
        }
    }

    @Test
    fun `prewarm is protected by the API key like every other api route`() {
        val gateway = FakeYoutubeMetadataClient().apply {
            streamTargets["vid"] = StreamTarget("https://cdn.example/audio", "audio/mp4")
        }
        withServer(apiKey = "secret", gateway = gateway) { gw ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/prewarm/vid").status)
            val ok = client.get("/api/prewarm/vid") { header("X-Wearsic-Key", "secret") }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(listOf("vid"), gw.streamTargetCalls, "a rejected request must not resolve anything")
        }
    }

    @Test
    fun `prewarm does not spend the stream rate limiter's permits`() {
        // Stream burst capacity is 12. If warm-ups shared that bucket, the
        // playback request below would be rate-limited (503) instead of
        // reaching the resolver (404 for an unknown id).
        withServer { _ ->
            repeat(20) { client.get("/api/prewarm/missing") }
            val stream = client.get("/api/stream/missing")
            assertEquals(HttpStatusCode.NotFound, stream.status)
        }
    }
}
