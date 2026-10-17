package com.wearsic.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * CONNECTION-ABORT CONTRACT: when a stream producer fails mid-body, the
 * server must TEAR THE SOCKET DOWN — promptly, and without a chunked-stream
 * terminator.
 *
 * Why this is its own test: a body that is merely abandoned (producer
 * throws, writer channel left open) keeps the connection up with no bytes
 * and no end-of-body, so the watch waits out its own read timeout before it
 * can retry — the user-visible "stall". And an ABORT that closed the stream
 * cleanly instead would read as "song finished" at the wrong byte. A raw
 * socket is used so no HTTP client convenience can mask either failure.
 */
class AbortedStreamTest {

    @Test
    fun `failed producer kills the connection without a clean end`() {
        val payload = ByteArray(256 * 1024) // all-zero bytes: no accidental CRLF sequences
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/abort") {
                    call.respondBytesWriter(
                        contentType = ContentType.parse("audio/mp4"),
                        status = HttpStatusCode.PartialContent,
                    ) {
                        abortOnFailure {
                            writeFully(payload, 0, 64 * 1024)
                            throw UpstreamTruncatedException(64L * 1024)
                        }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = runBlocking { server.resolvedConnectors().first().port }
            var elapsedMs = 0L
            val acc = ByteArrayOutputStream()
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 8_000
                val began = System.currentTimeMillis()
                socket.getOutputStream().apply {
                    write(
                        (
                            "GET /abort HTTP/1.1\r\n" +
                                "Host: 127.0.0.1\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    flush()
                }
                val buf = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = socket.getInputStream().read(buf)
                        if (n < 0) break // EOF: connection closed by the server
                        acc.write(buf, 0, n)
                    }
                } catch (timeout: SocketTimeoutException) {
                    throw AssertionError(
                        "connection hung after the producer failed " +
                            "(no EOF within 8s) — abort must close the socket"
                    )
                } catch (reset: java.io.IOException) {
                    // Connection reset mid-body is the other valid abort shape.
                }
                elapsedMs = System.currentTimeMillis() - began
            }
            val text = String(acc.toByteArray(), StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("206"), "expected the 206 status line, got: ${text.take(60)}")
            assertTrue(elapsedMs < 8_000, "abort took ${elapsedMs}ms — should be immediate")
            assertTrue(
                !text.contains("\r\n0\r\n\r\n"),
                "truncated body must NOT carry the chunked end-of-stream terminator " +
                    "(a clean end reads as 'song finished' on the watch)",
            )
        } finally {
            server.stop(200, 1_000)
        }
    }
}
