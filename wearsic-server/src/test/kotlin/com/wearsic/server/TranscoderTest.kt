package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get as routeGet
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the server-side AAC transcode pipeline with a FAKE ffmpeg that
 * simply copies stdin → stdout (a pass-through "transcode"). This validates
 * the process plumbing, the CDN→ffmpeg stdin pump, byte-offset skipping (used
 * by ExoPlayer seeks and the app's download resume) and response metadata —
 * without needing a real ffmpeg or YouTube access in CI.
 *
 * Each test constructs its own [Transcoder] instance: no static mutable
 * state is touched, so test order cannot affect results (each test also gets
 * a fresh fake-ffmpeg script and upstream server).
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class TranscoderTest {

    /** 1 MB of deterministic bytes standing in for a source audio stream. */
    private val sourceBytes: ByteArray = ByteArray(1024 * 1024) { (it % 251).toByte() }

    private fun fakeFfmpeg(): File {
        val script = File.createTempFile("fake-ffmpeg", ".sh").apply {
            setExecutable(true)
            deleteOnExit()
            // Escaped string (not a raw template) so \$1 reaches bash intact.
            writeText(
                "#!/bin/bash\n" +
                    "if [ \"\$1\" = \"-version\" ]; then exit 0; fi\n" +
                    "exec cat\n"
            )
        }
        return script
    }

    /** A tiny local HTTP server standing in for the YouTube CDN. */
    private suspend fun startUpstream(): ApplicationEngine {
        val engine = embeddedServer(ServerCIO, port = 0) {
            routing {
                routeGet("/source") {
                    val range = call.request.headers[HttpHeaders.Range]
                    if (range == null) {
                        // No range → 200 full body, like a real CDN.
                        call.respondBytesWriter(
                            contentType = ContentType.parse("audio/webm"),
                            status = HttpStatusCode.OK
                        ) {
                            writeFully(sourceBytes)
                        }
                        return@routeGet
                    }
                    // Honor closed ranges exactly (bytes s-e/size slice) —
                    // the transcode pump only ever sends closed ranges.
                    val (start, end) = parseRangeHeader(range)
                    if (start > sourceBytes.size) {
                        call.respond(HttpStatusCode.RequestedRangeNotSatisfiable, "range too large")
                        return@routeGet
                    }
                    val last = minOf(end ?: (sourceBytes.size - 1).toLong(), (sourceBytes.size - 1).toLong())
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes $start-$last/${sourceBytes.size}"
                    )
                    call.respondBytesWriter(
                        contentType = ContentType.parse("audio/webm"),
                        status = HttpStatusCode.PartialContent
                    ) {
                        writeFully(sourceBytes, start.toInt(), (last - start + 1).toInt())
                    }
                }

                // Upstream that always fails, for the 4xx-propagation test.
                routeGet("/broken") {
                    call.respond(HttpStatusCode.Forbidden, "cdn says no")
                }
            }
        }
        engine.start(wait = false)
        return engine
    }

    @Test
    fun `webm stream is routed through ffmpeg and bytes are served`() = runBlocking {
        val fake = fakeFfmpeg()
        val transcoder = Transcoder(HttpClient(CIO), ffmpegBinary = fake.absolutePath, availability = true)
        assertTrue(transcoder.available, "fake ffmpeg must be detected")

        val upstream = startUpstream()
        val port = upstream.resolvedConnectors().first().port
        val upstreamUrl = "http://127.0.0.1:$port/source"

        try {
            testApplication {
                application {
                    routing {
                        routeGet("/stream") {
                            transcoder.handle(call, upstreamUrl, call.request.headers[HttpHeaders.Range])
                        }
                    }
                }

                // No range → full stream, 200, AAC content type. (`client` here
                // is testApplication's in-process test client — no real sockets.)
                val full = client.get("/stream")
                assertEquals(HttpStatusCode.OK, full.status)
                assertEquals("audio/aac", full.headers[HttpHeaders.ContentType])
                val fullBody = full.body<ByteArray>()
                assertEquals(sourceBytes.size, fullBody.size)
                assertTrue(fullBody.contentEquals(sourceBytes), "full transcode body must equal source")

                // Byte offset → 206, Content-Range, body starts at the offset
                // (ExoPlayer seeks / download resume on transcoded songs).
                val partial = client.get("/stream") {
                    header(HttpHeaders.Range, "bytes=5000-")
                }
                assertEquals(HttpStatusCode.PartialContent, partial.status)
                assertEquals("bytes 5000-*", partial.headers[HttpHeaders.ContentRange])
                val partialBody = partial.body<ByteArray>()
                assertEquals(sourceBytes.size - 5000, partialBody.size)
                assertTrue(
                    partialBody.contentEquals(sourceBytes.copyOfRange(5000, sourceBytes.size)),
                    "partial body must start at the requested byte offset"
                )
            }
        } finally {
            upstream.stop(100, 500)
        }
    }

    @Test
    fun `second identical transcode is served from disk with the origin dead`() = runBlocking {
        val fake = fakeFfmpeg()
        val cacheDir = Files.createTempDirectory("wearsic-tcache").toFile()
        val transcoder = Transcoder(
            HttpClient(CIO), ffmpegBinary = fake.absolutePath,
            availability = true, cacheDir = cacheDir,
        )
        val upstream = startUpstream()
        val port = upstream.resolvedConnectors().first().port
        val upstreamUrl = "http://127.0.0.1:$port/source"

        try {
            testApplication {
                application {
                    routing {
                        routeGet("/stream") {
                            transcoder.handle(
                                call, upstreamUrl,
                                call.request.headers[HttpHeaders.Range], cacheKey = "vid123",
                            )
                        }
                    }
                }

                // First request transcodes (fake ffmpeg echoes stdin) and
                // must leave exactly one cached .aac behind.
                val first = client.get("/stream")
                assertEquals(HttpStatusCode.OK, first.status)
                assertTrue(first.body<ByteArray>().contentEquals(sourceBytes))
                val cached = cacheDir.listFiles { f -> f.extension == "aac" }
                assertEquals(1, cached?.size, "expected one cached transcode")

                // Kill the origin entirely: the song must still stream,
                // proving the bytes came from disk, not the network.
                upstream.stop(100, 500)
                val second = client.get("/stream")
                assertEquals(HttpStatusCode.OK, second.status)
                assertTrue(
                    second.body<ByteArray>().contentEquals(sourceBytes),
                    "cached transcode must equal the source bytes",
                )
            }
        } finally {
            runCatching { upstream.stop(100, 500) }
        }
    }

    @Test
    fun `missing ffmpeg yields actionable 503`() = runBlocking {        // Injected unavailable instance — no global state mutated, so this
        // test is independent of the test above regardless of execution order.
        val transcoder = Transcoder(HttpClient(CIO), ffmpegBinary = "/nonexistent/ffmpeg", availability = false)
        assertFalse(transcoder.available)

        try {
            testApplication {
                application {
                    // Mirror the real server so ErrorResponse serializes to JSON.
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                    }
                    routing {
                        routeGet("/stream") {
                            transcoder.handle(call, "http://127.0.0.1:1/unused", call.request.headers[HttpHeaders.Range])
                        }
                    }
                }
                val resp = client.get("/stream")
                assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
                val body = resp.bodyAsText()
                assertTrue(body.contains("ffmpeg"), "503 must tell the user to install ffmpeg: $body")
            }
        } finally {
            // nothing to clean up
        }
    }

    @Test
    fun `needsTranscode is mime-driven`() {
        val transcoder = Transcoder(HttpClient(CIO), availability = false)
        assertTrue(transcoder.needsTranscode("audio/webm"), "Opus/WebM needs conversion")
        assertTrue(transcoder.needsTranscode("audio/ogg"), "Vorbis needs conversion")
        assertFalse(transcoder.needsTranscode("audio/mp4"), "AAC/mp4 is passed through")
        // Verified behavior: the check is a substring match on "mp4". YouTube
        // only ever ships AAC as audio/mp4, which passes through; a hypothetical
        // x-m4a MIME does not contain "mp4" and would be (harmlessly) re-encoded.
        assertTrue(transcoder.needsTranscode("audio/x-m4a"), "non-YouTube m4a MIME is conservatively transcoded")
    }

    @Test
    fun `upstream 4xx surfaces as 502 instead of garbage audio`() = runBlocking {
        val fake = fakeFfmpeg()
        val transcoder = Transcoder(HttpClient(CIO), ffmpegBinary = fake.absolutePath, availability = true)

        val upstream = startUpstream()
        val port = upstream.resolvedConnectors().first().port

        try {
            testApplication {
                application {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                    }
                    routing {
                        routeGet("/stream") {
                            when (val r = transcoder.handle(call, "http://127.0.0.1:$port/broken", null)) {
                                is Transcoder.TranscodeResult.UpstreamError -> call.respond(
                                    HttpStatusCode.BadGateway,
                                    ErrorResponse("Upstream audio unavailable (HTTP ${r.status})")
                                )
                                else -> Unit
                            }
                        }
                    }
                }
                val resp = client.get("/stream")
                assertEquals(HttpStatusCode.BadGateway, resp.status, "body: ${resp.bodyAsText()}")
                assertTrue(resp.bodyAsText().contains("Upstream audio unavailable"))
            }
        } finally {
            upstream.stop(100, 500)
        }
    }

    @Test
    fun `detectBinary returns false for a nonexistent binary`() {
        assertFalse(Transcoder.detectBinary("/nonexistent/ffmpeg-path"))
        // The real production binary is probed but never mutates instance state.
        Transcoder.detectBinary("ffmpeg") // must not throw regardless of host
    }
}
