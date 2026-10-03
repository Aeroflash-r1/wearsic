package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import org.slf4j.LoggerFactory

/**
 * CDN statuses that mean "this RESOLVED url is dead" — the target was cached
 * but YouTube revoked/expired it before the TTL ran out. Trigger self-heal.
 */
private val DEAD_URL_STATUSES = setOf(403, 404, 410)

/**
 * Size of one upstream -> watch copy. Bigger than Ktor's 8KB default so a
 * phone-hosted proxy isn't dominated by per-chunk overhead; small enough to
 * stay cheap to allocate per request.
 */
private const val STREAM_CHUNK_BYTES = 64 * 1024

class AudioProxy(
    private val extractor: YoutubeMetadataClient,
    private val client: HttpClient,
    val transcoder: Transcoder = Transcoder(client),
) {

    private val logger = LoggerFactory.getLogger(AudioProxy::class.java)

    suspend fun handle(call: ApplicationCall, videoId: String) {
        val target = try {
            extractor.streamTarget(videoId)
        } catch (e: Exception) {
            // Extraction blew up (most commonly YouTube's bot-wall LOGIN_REQUIRED
            // when no cookie is set). The v1 server answered 503 with an
            // actionable message here — match that contract instead of leaking
            // a 500 + stack trace to the watch.
            logger.warn("Audio extraction failed for {}: {}", videoId, e.message)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    "YouTube requires authentication for audio extraction. Add your YouTube cookie in the app Settings."
                )
            )
            return
        }
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("No playable audio stream found for $videoId"))
            return
        }

        val rangeHeader = call.request.header(HttpHeaders.Range)

        // Songs without a native AAC stream (WebM/Opus or Vorbis) are converted
        // to AAC-LC on the server so the watch always gets hardware-decodable
        // audio. When YouTube already offers AAC (audio/mp4 — the common case),
        // pass it through untouched with zero CPU cost.
        if (transcoder.needsTranscode(target.mimeType)) {
            when (val result = transcoder.handle(call, target.url, rangeHeader)) {
                is Transcoder.TranscodeResult.Streamed,
                is Transcoder.TranscodeResult.Unavailable,
                is Transcoder.TranscodeResult.Aborted,
                -> return // response already committed (audio, 503, or client gone)
                is Transcoder.TranscodeResult.UpstreamError -> {
                    if (result.status !in DEAD_URL_STATUSES) {
                        call.respond(
                            HttpStatusCode.BadGateway,
                            ErrorResponse("Upstream audio unavailable (HTTP ${result.status})")
                        )
                        return
                    }
                    // Dead URL on the transcode path: same self-heal as the
                    // passthrough path — one fresh re-resolve, then stream it.
                    val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId)
                    if (fresh != null) {
                        logger.info("Self-healed stale transcode URL for {}", videoId)
                        when (val retry = transcoder.handle(call, fresh.url, rangeHeader)) {
                            is Transcoder.TranscodeResult.UpstreamError -> call.respond(
                                HttpStatusCode.BadGateway,
                                ErrorResponse("Upstream audio unavailable (HTTP ${retry.status})")
                            )
                            else -> Unit // Streamed / Unavailable / Aborted already responded
                        }
                        return
                    }
                    // Re-resolve failed — surface the ORIGINAL upstream error,
                    // not a generic message, so logs stay actionable.
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse("Upstream audio unavailable (HTTP ${result.status})")
                    )
                    return
                }
            }
        }

        // Passthrough path. proxyTarget no longer commits error responses
        // itself: it returns the outcome so we can retry once on dead URLs
        // BEFORE anything is sent (double-respond would throw).
        when (val outcome = proxyTarget(call, target, rangeHeader)) {
            is ProxyOutcome.Streamed -> return
            is ProxyOutcome.Aborted -> return
            is ProxyOutcome.UpstreamError -> {
                if (outcome.status !in DEAD_URL_STATUSES) {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse("Upstream audio unavailable (HTTP ${outcome.status})")
                    )
                    return
                }
                // Dead-URL self-heal: 403/404/410 mean the cached CDN URL expired
                // or was revoked upstream (it can die well inside the 1h cache
                // TTL). Drop the stale entry, re-resolve once, and stream the
                // fresh URL — the watch gets audio instead of an error.
                val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId)
                if (fresh != null) {
                    logger.info("Self-healed stale stream URL for {}", videoId)
                    when (val retry = streamFresh(call, fresh, rangeHeader)) {
                        is ProxyOutcome.UpstreamError -> call.respond(
                            HttpStatusCode.BadGateway,
                            ErrorResponse("Upstream audio unavailable (HTTP ${retry.status})")
                        )
                        else -> Unit
                    }
                    return
                }
                logger.warn("Re-resolve after dead URL failed for {}", videoId)
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse("Upstream audio unavailable (HTTP ${outcome.status})")
                )
            }
        }
    }

    private sealed interface ProxyOutcome {
        data object Streamed : ProxyOutcome
        data object Aborted : ProxyOutcome
        data class UpstreamError(val status: Int) : ProxyOutcome
    }

    /** Streams one already-resolved [fresh] target (transcode-aware). */
    private suspend fun streamFresh(
        call: ApplicationCall,
        fresh: StreamTarget,
        rangeHeader: String?,
    ): ProxyOutcome {
        if (transcoder.needsTranscode(fresh.mimeType)) {
            return when (val r = transcoder.handle(call, fresh.url, rangeHeader)) {
                is Transcoder.TranscodeResult.Streamed -> ProxyOutcome.Streamed
                is Transcoder.TranscodeResult.Aborted -> ProxyOutcome.Aborted
                is Transcoder.TranscodeResult.Unavailable -> ProxyOutcome.Streamed // 503 already sent
                is Transcoder.TranscodeResult.UpstreamError -> ProxyOutcome.UpstreamError(r.status)
            }
        }
        return proxyTarget(call, fresh, rangeHeader)
    }

    /**
     * Streams one resolved [target] to the watch. Never commits an error
     * response itself — returns [ProxyOutcome.UpstreamError] so the caller
     * can self-heal first. Never throws for client aborts.
     */
    private suspend fun proxyTarget(
        call: ApplicationCall,
        target: StreamTarget,
        rangeHeader: String?,
    ): ProxyOutcome {
        return try {
            client.prepareGet(target.url) {
                // Always ask upstream for a byte range: YouTube's CDN throttles
                // non-range (200) audio responses and closes them after ~256KB,
                // which made the first play of a song stall after ~15s of audio.
                // Ranged requests stream the whole file at full speed. Forward the
                // client's Range when present, otherwise request from byte 0.
                header(HttpHeaders.Range, rangeHeader ?: "bytes=0-")
            }.execute { upstream ->
                // A 4xx/5xx from the CDN must surface as a real error — but
                // NOT committed here: the caller retries once on dead URLs
                // before sending anything (double-respond would throw).
                if (upstream.status.value >= 400) {
                    return@execute ProxyOutcome.UpstreamError(upstream.status.value)
                }
                upstream.headers[HttpHeaders.ContentRange]?.let { call.response.header(HttpHeaders.ContentRange, it) }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                val status = if (upstream.status == HttpStatusCode.PartialContent) {
                    HttpStatusCode.PartialContent
                } else {
                    HttpStatusCode.OK
                }

                call.respondBytesWriter(contentType = io.ktor.http.ContentType.parse(target.mimeType), status = status) {
                    // Pump in 64KB chunks instead of Ktor's default 8KB
                    // copyAndClose. This is the hot path for EVERY byte of
                    // every song: 8KB writes mean 8x more socket writes,
                    // 8x more channel hand-offs and 8x more CIO event-loop
                    // wake-ups per megabyte, all on a phone CPU in Termux
                    // that has a handful of big cores to share with ffmpeg
                    // and NewPipe. 64KB also matches what ExoPlayer's
                    // OkHttpDataSource reads per chunk on the watch, so the
                    // two ends now work in comparable blocks.
                    val source = upstream.bodyAsChannel()
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        val read = source.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        if (read > 0) {
                            writeFully(buffer, 0, read)
                        } else {
                            // Channel has no bytes right now but isn't closed:
                            // suspend until the CDN delivers more instead of
                            // spinning on a 0-length read.
                            source.awaitContent()
                        }
                    }
                    // The surrounding `execute { }` closes the upstream
                    // response when this block returns, so a skipped track
                    // releases its CDN socket here without an explicit cancel.
                }
                ProxyOutcome.Streamed
            }
        } catch (e: Exception) {
            // Client disconnected/skipped mid-stream, or upstream failed —
            // not worth a scary 500, this happens routinely on track skips.
            logger.info("Stream proxy ended early: {}", e.message)
            ProxyOutcome.Aborted
        }
    }
}
