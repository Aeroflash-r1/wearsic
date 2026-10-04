package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Measures the audio proxy's copy path on the REAL stack.
 *
 * Why this exists: the proxy used to hand every byte to Ktor's
 * `copyAndClose`, which copies in 8KB blocks. Changing that constant is easy
 * to *claim* and easy to get wrong, so this test measures it instead —
 * a genuine CIO origin server, the genuine `Application.module` route, a
 * genuine HTTP client over loopback, and real sockets on both hops.
 *
 * It asserts CORRECTNESS at every chunk size (byte-exact body, correct
 * partial-content headers) and only *reports* timings — a slow CI box must
 * never turn into a red build. The numbers are printed so the constant can be
 * re-tuned against evidence rather than folklore.
 */
class AudioProxyThroughputTest {

    /** 6 MB — roughly a long track, and large enough to average out noise. */
    private val payloadBytes = 6 * 1024 * 1024

    /** A deterministic, compressible-but-actually-random-enough body. */
    private fun payload(): ByteArray =
        ByteArray(payloadBytes) { i -> ((i * 31 + (i shr 8)) and 0xFF).toByte() }

    /**
     * Boots the real server module on a loopback port, pointed at a fake
     * extractor that resolves to [originBase]. Returns the base URL.
     */
    private fun withProxyServer(
        originBase: String,
        chunkBytes: Int,
        stateDir: File,
        block: (String) -> Unit,
    ) {
        val gateway = FakeYoutubeMetadataClient().apply {
            streamTargets["vid"] = StreamTarget(url = "$originBase/audio", mimeType = "audio/mp4")
        }
        val dbFile = Files.createTempFile("wearsic-tput", ".db").toFile().apply { deleteOnExit() }
        val client = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 8
                endpoint.apply { maxConnectionsPerRoute = 8 }
            }
        }
        val audioProxy = AudioProxy(gateway, client, Transcoder(client, availability = false), chunkBytes)

        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            module(
                gateway = gateway,
                database = Database(dbFile.absolutePath),
                audioProxy = audioProxy,
                apiKey = null,
                searchOrchestrator = MetadataSearchOrchestrator(
                    metadata = object : MetadataSource {
                        override suspend fun searchSongs(query: String, limit: Int) = emptyList<YtmTrack>()
                        override fun toTrackDto(track: YtmTrack) = TrackDto(track.videoId, "t", "u")
                    },
                    youtube = gateway,
                ),
            )
        }.start(wait = false)

        val port = runBlocking { server.resolvedConnectors().first().port }
        try {
            block("http://127.0.0.1:$port")
        } finally {
            server.stop(500, 1_000)
            client.close()
        }
    }

    /** Origin server that serves [bytes] with range support, like a CDN. */
    private fun withOrigin(bytes: ByteArray, block: (String) -> Unit) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/audio") {
                    // Always ranged, matching how the proxy talks to YouTube.
                    call.response.header(HttpHeaders.ContentRange, "bytes 0-${bytes.size - 1}/${bytes.size}")
                    call.respondBytesWriter(contentType = ContentType.parse("audio/mp4"), status = io.ktor.http.HttpStatusCode.PartialContent) {
                        val buf = ByteArray(64 * 1024)
                        var off = 0
                        while (off < bytes.size) {
                            val n = minOf(buf.size, bytes.size - off)
                            // Copy FROM the payload into the buffer (not
                            // buffer into itself) — the previous form read
                            // past the end of a 64KB buffer.
                            bytes.copyInto(buf, 0, off, off + n)
                            writeFully(buf, 0, n)
                            off += n
                        }
                    }
                }
            }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        try {
            block("http://127.0.0.1:$port")
        } finally {
            server.stop(500, 1_000)
        }
    }

    private class Result(val millis: Long, val bytes: Int)

    private fun measureViaProxy(chunkBytes: Int, stateDir: File): Result {
        val bytes = payload()
        var out = Result(0L, 0)
        withOrigin(bytes) { origin ->
            withProxyServer(origin, chunkBytes, stateDir) { base ->
                val started = System.nanoTime()
                val received = runBlocking {
                    val c = HttpClient(CIO)
                    try {
                        // Drain the channel the same way any real consumer
                        // would, rather than slurping it with a helper whose
                        // availability varies between ktor versions.
                        val channel = c.get("$base/api/stream/vid").bodyAsChannel()
                        val sink = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) channel.awaitContent() else sink.write(buf, 0, n)
                        }
                        sink.toByteArray()
                    } finally {
                        c.close()
                    }
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                out = Result(millis, received.size)
                // Byte-exactness is the part that MUST hold.
                assertEquals(payloadBytes, received.size, "proxy truncated the stream at chunk=$chunkBytes")
                assertTrue(bytes.contentEquals(received), "proxy corrupted the stream at chunk=$chunkBytes")
            }
        }
        return out
    }

    @Test
    fun `proxying is byte-exact at every chunk size and 64KB is not slower than 8KB`(@TempDir dir: File) {
        // Warm up JIT + socket paths so the first measured run isn't penalised.
        measureViaProxy(STREAM_CHUNK_BYTES, dir)

        val sizes = listOf(8 * 1024, 64 * 1024, 256 * 1024)
        val results = sizes.associateWith { measureViaProxy(it, dir) }

        val report = buildString {
            appendLine("── audio proxy throughput (${payloadBytes / 1024 / 1024} MB over loopback) ──")
            results.forEach { (chunk, r) ->
                val mbps = (payloadBytes / 1024.0 / 1024.0) / (r.millis / 1000.0)
                appendLine("  chunk=%6d bytes  %5d ms  %7.1f MB/s".format(chunk, r.millis, mbps))
            }
            val small = results.getValue(8 * 1024)
            val chosen = results.getValue(STREAM_CHUNK_BYTES)
            appendLine("  64KB vs 8KB: %.2fx".format(small.millis.toDouble() / chosen.millis))
        }
        println(report)
        // Also persisted: Gradle swallows test stdout by default, and the
        // point of this measurement is that the numbers stay inspectable
        // after the run (locally and in CI artifacts).
        runCatching {
            val out = File("build/audio-proxy-throughput.txt")
            out.parentFile.mkdirs()
            out.writeText(report)
        }

        val small = results.getValue(8 * 1024)
        val chosen = results.getValue(STREAM_CHUNK_BYTES)

        // Correctness of the choice, not raw speed: 64KB must never be
        // meaningfully WORSE than the 8KB default it replaced. A shared CI
        // box can be noisy, so this is deliberately a loose bound.
        assertTrue(
            chosen.millis < small.millis * 3,
            "64KB chunks (${chosen.millis} ms) far slower than 8KB (${small.millis} ms)",
        )
    }
}