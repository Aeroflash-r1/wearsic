package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.slf4j.LoggerFactory

/**
 * Replaces the original raw java.net.HttpURLConnection-based downloader.
 * That implementation opened a fresh connection per request with no HTTP/2
 * support and weak connection reuse — every extraction call risked a full
 * TCP+TLS handshake. This version reuses Ktor's CIO engine connection pool
 * (shared with the rest of the server) for real keep-alive across requests
 * to the same YouTube hosts.
 */
/**
 * Process-wide cap on concurrent NewPipeExtractor HTTP calls.
 *
 * Every extractor path funnels through [NewPipeDownloader.execute] — search,
 * related, albums, playlist, the canary probe and stream resolution. Only
 * stream resolution was ever bounded (by its own slot semaphore); the rest
 * could run unbounded in parallel — and /api/recommendations launches one
 * `related()` per seed, up to six at once. On a phone that means six
 * simultaneous YouTube fetches and six parked threads, which is also the
 * fastest way to get the server IP bot-walled.
 *
 * 4 is sized for the CPU (extraction is parse-bound, not network-bound) and
 * leaves room for a foreground resolve to proceed while a search runs.
 */
internal object NewPipeConcurrency {
    internal const val MAX_CONCURRENT = 4

    private val slots = java.util.concurrent.Semaphore(MAX_CONCURRENT)

    fun acquire() = slots.acquire()

    fun release() = slots.release()
}

class NewPipeDownloader(private val client: HttpClient) : Downloader() {

    private val logger = LoggerFactory.getLogger(NewPipeDownloader::class.java)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        fun buildClient(): HttpClient = HttpClient(CIO) {
            engine {
                maxConnectionsCount = 20
                endpoint.apply {
                    // CIO multiplexes requests over each route automatically
                    // (HTTP/1.1 pipelining semantics) — there is no separate
                    // pipelining toggle on EndpointConfig in Ktor 2.x.
                    maxConnectionsPerRoute = 10
                    keepAliveTime = 30_000
                    connectAttempts = 2
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 12_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
            expectSuccess = false // NewPipe wants raw responses, even 4xx/5xx — it decides what's fatal
        }
    }

    override fun execute(request: NPRequest): NPResponse = runBlocking {
        // Bounded here, at the single choke point every extraction goes
        // through, rather than in each caller (see NewPipeConcurrency).
        NewPipeConcurrency.acquire()
        try {
            val httpResponse = client.request(request.url()) {
                method = HttpMethod.parse(request.httpMethod())

                request.headers().forEach { (name, values) ->
                    values.forEach { value -> header(name, value) }
                }

                // Bot-challenge workaround: attach the authenticated session
                // cookie if one is set (WEARSIC_YOUTUBE_COOKIE or runtime-set
                // via API), unless the caller already supplied their own
                // Cookie header.
                if (request.headers()[HttpHeaders.Cookie].isNullOrEmpty()) {
                    YoutubeSession.cookie?.let { header(HttpHeaders.Cookie, it) }
                }
                if (request.headers()[HttpHeaders.UserAgent].isNullOrEmpty()) {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }

                request.dataToSend()?.let { setBody(it) }
            }

            val body = httpResponse.bodyAsText()
            val responseHeaders = httpResponse.headers.entries().associate { (k, v) -> k to v }

            return@runBlocking NPResponse(
                httpResponse.status.value,
                httpResponse.status.description,
                responseHeaders,
                body,
                httpResponse.call.request.url.toString(),
            )
        } finally {
            NewPipeConcurrency.release()
        }
    }
}
