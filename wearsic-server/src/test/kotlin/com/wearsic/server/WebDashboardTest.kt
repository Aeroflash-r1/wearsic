package com.wearsic.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine status dashboard at `/` — the page any browser that lands on
 * the engine's address renders. Guards:
 *
 *  - it is public (no API key) and secret-free,
 *  - it answers text/html with a no-store directive (never a stale page),
 *  - the first paint carries REAL data: the `__WEARSIC_BOOT__` token is
 *    replaced by an embedded /health + /diag snapshot per request,
 *  - the page never leaks credentials or machinery it must not show.
 */
class WebDashboardTest {

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val dbFile = Files.createTempFile("wearsic-dashboard-test", ".db").toFile().apply { deleteOnExit() }
        val database = Database(dbFile.absolutePath)
        val gateway = object : YoutubeMetadataClient {
            override suspend fun search(query: String): List<TrackDto> = emptyList()
            override suspend fun streamTarget(videoId: String): StreamTarget? = null
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

    @Test
    fun `the dashboard is a real page with the live boot snapshot`() = withServer {
        val response = client.get("/")
        assertEquals(200, response.status.value)
        assertTrue(
            response.contentType()?.toString()?.startsWith("text/html") == true,
            "the dashboard must render as HTML, got ${response.contentType()}",
        )
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])

        val html = response.bodyAsText()
        assertTrue(html.contains("<title>Wearsic Engine</title>"), "the page is the engine dashboard")
        assertFalse(
            html.contains("__WEARSIC_BOOT__"),
            "the boot token must be replaced by real JSON on every request",
        )
        assertTrue(
            html.contains("\"version\":\"${ServerVersion.VERSION}\""),
            "the embedded snapshot carries the engine version",
        )
        assertTrue(
            html.contains("\"diag\":{"),
            "the embedded snapshot carries the diag payload for first paint",
        )
        // No secrets: there is nothing to leak when no key is configured, but
        // the page must also never echo its own source template.
        assertFalse(html.contains("WEARSIC_API_KEY"))
    }

    @Test
    fun `two requests embed two different payloads - never cached`() = withServer {
        // LatencyMetrics is process-wide: its count field advances between
        // the two requests, so the two inline snapshots must genuinely
        // differ — proving the dashboard is rebuilt per request, not served
        // from a cached page.
        LatencyMetrics.reset()
        LatencyMetrics.record("dashboard.test", 1)
        val first = client.get("/").bodyAsText()
        LatencyMetrics.record("dashboard.test", 2)
        val second = client.get("/").bodyAsText()
        assertTrue(
            first.contains("\"dashboard.test\":{\"count\":1"),
            "first paint reflects the state at request time: $first",
        )
        assertTrue(
            second.contains("\"dashboard.test\":{\"count\":2"),
            "second paint reflects the newer state: $second",
        )
    }
}
