package com.wearsic.app.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the retry policy added to [WearsicHttp].
 *
 * The error model always classified 429/503 and refused connections as
 * retryable (`WearsicError.isRetryable`), but nothing acted on it, so one
 * dropped handshake over Tailscale Funnel reached the watch as an error
 * screen even though the next attempt would have worked.
 *
 * The rules this test locks down:
 *  - a GET that meets 503 is retried and can still succeed;
 *  - terminal statuses (401) are answered immediately, never retried;
 *  - POST is NEVER replayed, because the server may already have applied it.
 */
class WearsicRetryTest {

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private var statusFor: (Int) -> Int = { 200 }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status = statusFor(hits.incrementAndGet())
            val body = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun url() = "http://127.0.0.1:${server.address.port}/api/thing"

    @Test
    fun `a 503 is retried and the next successful answer is used`() {
        statusFor = { attempt -> if (attempt <= 2) 503 else 200 }

        WearsicHttp.client.newCall(
            Request.Builder().url(url()).get().build()
        ).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"ok":true}""", response.body?.string())
        }

        assertEquals("first try plus two retries", 3, hits.get())
    }

    @Test
    fun `a 401 is terminal and not retried`() {
        statusFor = { 401 }

        WearsicHttp.client.newCall(
            Request.Builder().url(url()).get().build()
        ).execute().use { response ->
            assertEquals(401, response.code)
        }

        assertEquals("a wrong API key must fail fast", 1, hits.get())
    }

    @Test
    fun `a POST is never replayed`() {
        statusFor = { 503 }

        WearsicHttp.client.newCall(
            Request.Builder().url(url()).post("""{"name":"mix"}""".toRequestBody()).build()
        ).execute().use { response ->
            assertEquals(503, response.code)
        }

        assertEquals("mutations are never duplicated", 1, hits.get())
    }
}
