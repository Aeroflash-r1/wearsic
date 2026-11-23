package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The proxy must not pay a CDN round trip it already has the answer to.
 *
 * When a target carries `contentLength` (the lean player response, or the
 * extractor's ItagItem after this change), `proxyTarget` skips
 * `probeUpstreamLength` entirely: that probe is a full `Range: bytes=0-0`
 * request to the CDN whose only job is to learn the size, and it sits BEFORE
 * the first audio byte of every request whose length is unknown.
 *
 * This test counts real requests at the origin over the real CIO stack and
 * asserts 1 request with a known length vs 2 without one, then verifies the
 * body is byte-exact in both cases.
 */
class AudioProxyLengthProbeTest {

    private val payload = ByteArray(128 * 1024) { i -> ((i * 17 + (i shr 7)) and 0xFF).toByte() }

    private fun withOrigin(block: (String, AtomicInteger) -> Unit) {
        val requests = AtomicInteger()
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/audio") {
                    requests.incrementAndGet()
                    val (start, end) = parseRangeHeader(call.request.headers[HttpHeaders.Range])
                    val last = minOf(end ?: (payload.size - 1).toLong(), (payload.size - 1).toLong())
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$last/${payload.size}")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("audio/mp4"),
                        status = HttpStatusCode.PartialContent,
                    ) {
                        var off = start.toInt()
                        val stop = (last + 1).toInt()
                        while (off < stop) {
                            val n = minOf(32 * 1024, stop - off)
                            writeFully(payload, off, n)
                            off += n
                        }
                    }
                }
            }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        try {
            block("http://127.0.0.1:$port", requests)
        } finally {
            server.stop(200, 1_000)
        }
    }

    private fun streamOnce(
        origin: String,
        targetLength: Long,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val gateway = FakeYoutubeMetadataClient().apply {
            streamTargets["vid"] = StreamTarget(
                url = "$origin/audio",
                mimeType = "audio/mp4",
                contentLength = targetLength,
            )
        }
        val dbFile = Files.createTempFile("wearsic-probe", ".db").toFile().apply { deleteOnExit() }
        val client = HttpClient(CIO)
        val transcoder = Transcoder(client, availability = false)
        val audioProxy = AudioProxy(gateway, client, transcoder)
        val orchestrator = MetadataSearchOrchestrator(
            metadata = object : MetadataSource {
                override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
            },
            youtube = gateway,
        )
        application {
            module(gateway, Database(dbFile.absolutePath), audioProxy, null, orchestrator, transcoder)
        }
        block()
    }

    /** Drains the whole response through the test client; returns the bytes. */
    private suspend fun ApplicationTestBuilder.drainStream(): ByteArray {
        val channel = client.get("/api/stream/vid").bodyAsChannel()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = channel.readAvailable(buf)
            if (n < 0) break
            if (n == 0) channel.awaitContent() else out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `a known content length skips the probe`() {
        withOrigin { origin, requests ->
            streamOnce(origin, payload.size.toLong()) {
                val received = drainStream()
                assertEquals(payload.size, received.size, "proxy truncated the stream")
                assertTrue(payload.contentEquals(received), "proxy corrupted the stream")
                assertEquals(
                    1, requests.get(),
                    "a target with a known length must reach the origin exactly once",
                )
            }
        }
    }

    @Test
    fun `an unknown content length still pays the probe`() {
        withOrigin { origin, requests ->
            streamOnce(origin, 0L) {
                val received = drainStream()
                assertEquals(payload.size, received.size, "proxy truncated the stream")
                assertTrue(payload.contentEquals(received), "proxy corrupted the stream")
                assertTrue(
                    requests.get() >= 2,
                    "an unknown length needs the probe request plus the stream (saw ${requests.get()})",
                )
            }
        }
    }
}
