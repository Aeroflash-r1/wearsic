package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

/**
 * Mid-stream throttle recovery: a CDN that 403s partway must not truncate
 * the song — the fetcher re-resolves and resumes at the same byte.
 */
class RangedUpstreamTest {

    /** Origin that serves [bytes] but 403s any range starting past [cutoff]. */
    private fun withFlakyOrigin(bytes: ByteArray, cutoff: Long, block: (String) -> Unit) {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/audio") {
                    val (start, end) = parseRangeHeader(call.request.headers[HttpHeaders.Range])
                    if (start >= cutoff) {
                        call.respond(HttpStatusCode.Forbidden, "throttled")
                        return@get
                    }
                    val last = minOf(end ?: (bytes.size - 1).toLong(), bytes.size - 1L)
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$last/${bytes.size}")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("audio/mp4"),
                        status = HttpStatusCode.PartialContent
                    ) {
                        writeFully(bytes, start.toInt(), (last - start + 1).toInt())
                    }
                }
                // Fresh URL after a re-resolve: always serves.
                get("/audio-fresh") {
                    val (start, end) = parseRangeHeader(call.request.headers[HttpHeaders.Range])
                    val last = minOf(end ?: (bytes.size - 1).toLong(), bytes.size - 1L)
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$last/${bytes.size}")
                    call.respondBytesWriter(
                        contentType = ContentType.parse("audio/mp4"),
                        status = HttpStatusCode.PartialContent
                    ) {
                        writeFully(bytes, start.toInt(), (last - start + 1).toInt())
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

    @Test
    fun `mid-stream 403 resumes from a fresh url instead of truncating`() {
        val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        withFlakyOrigin(bytes, cutoff = 1024L * 1024L) { base ->
            val received = runBlocking {
                val out = mutableListOf<Byte>()
                val client = HttpClient(CIO)
                try {
                    client.fetchClosedChunks(
                        "$base/audio", 0, null,
                        refreshUrl = { "$base/audio-fresh" },
                    ) { buf, n -> repeat(n) { out.add(buf[it]) } }
                } finally {
                    client.close()
                }
                out.toByteArray()
            }
            assertContentEquals(bytes, received, "stream truncated at the throttle point")
        }
    }

    @Test
    fun `first-chunk refusal surfaces as refused`() {
        val bytes = ByteArray(1024) { 1 }
        withFlakyOrigin(bytes, cutoff = 0L) { base ->
            val outcome = runBlocking {
                val client = HttpClient(CIO)
                try {
                    client.fetchClosedChunks("$base/audio", 0, null) { _, _ -> }
                } finally {
                    client.close()
                }
            }
            assertTrue(outcome is ChunkedUpstream.Refused, "expected Refused, got $outcome")
            assertEquals(403, (outcome as ChunkedUpstream.Refused).status)
        }
    }

    @Test
    fun `refusals are counted for throttle visibility`() {
        val before = UpstreamStats.rangeRefusals.get()
        val bytes = ByteArray(1024) { 1 }
        withFlakyOrigin(bytes, cutoff = 0L) { base ->
            runBlocking {
                val client = HttpClient(CIO)
                try {
                    client.fetchClosedChunks("$base/audio", 0, null) { _, _ -> }
                } finally {
                    client.close()
                }
            }
        }
        assertTrue(
            UpstreamStats.rangeRefusals.get() > before,
            "refused chunk must increment the throttle counter"
        )
    }

    @Test
    fun `clean stream completes without refresh`() {
        val bytes = ByteArray(257 * 1024) { (it % 251).toByte() }
        withFlakyOrigin(bytes, cutoff = Long.MAX_VALUE) { base ->
            var refreshes = 0
            val received = runBlocking {
                val out = mutableListOf<Byte>()
                val client = HttpClient(CIO)
                try {
                    client.fetchClosedChunks(
                        "$base/audio", 0, null,
                        refreshUrl = { refreshes++; "$base/audio" },
                    ) { buf, n -> repeat(n) { out.add(buf[it]) } }
                } finally {
                    client.close()
                }
                out.toByteArray()
            }
            assertContentEquals(bytes, received)
            assertEquals(0, refreshes, "no refresh should fire on a clean stream")
        }
    }
}
