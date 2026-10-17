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
import kotlinx.coroutines.CancellationException
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
 *
 * Verified by AudioProxyThroughputTest, which streams a real body through the
 * real CIO stack at several chunk sizes.
 */
internal const val STREAM_CHUNK_BYTES = 64 * 1024

class AudioProxy(
    private val extractor: YoutubeMetadataClient,
    private val client: HttpClient,
    val transcoder: Transcoder = Transcoder(client),
    /**
     * Copy block size for every audio byte forwarded to the watch.
     * Injectable so the throughput test can measure the real difference
     * instead of the size being an unverified constant.
     */
    internal val chunkBytes: Int = STREAM_CHUNK_BYTES,
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
            val refreshForTranscode: suspend () -> String? = {
                (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag)?.url
            }
            when (val result = transcoder.handle(call, target.url, rangeHeader, refreshForTranscode, videoId)) {
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
                    val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag)
                    if (fresh != null) {
                        logger.info("Self-healed stale transcode URL for {}", videoId)
                        when (val retry = transcoder.handle(call, fresh.url, rangeHeader, refreshForTranscode)) {
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
        when (val outcome = proxyTarget(call, target, rangeHeader, videoId)) {
            is ProxyOutcome.Streamed -> return
            is ProxyOutcome.Aborted -> return
            is ProxyOutcome.UpstreamError -> {
                logger.info("Upstream refused {} with HTTP {} (first attempt)", videoId, outcome.status)
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
                val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag)
                if (fresh != null) {
                    logger.info("Self-healed stale stream URL for {}", videoId)
                    when (val retry = streamFresh(call, fresh, rangeHeader, videoId)) {
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
        videoId: String,
    ): ProxyOutcome {
        if (transcoder.needsTranscode(fresh.mimeType)) {
            val refreshForTranscode: suspend () -> String? = {
                (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, fresh.itag)?.url
            }
            return when (val r = transcoder.handle(call, fresh.url, rangeHeader, refreshForTranscode, videoId)) {
                is Transcoder.TranscodeResult.Streamed -> ProxyOutcome.Streamed
                is Transcoder.TranscodeResult.Aborted -> ProxyOutcome.Aborted
                is Transcoder.TranscodeResult.Unavailable -> ProxyOutcome.Streamed // 503 already sent
                is Transcoder.TranscodeResult.UpstreamError -> ProxyOutcome.UpstreamError(r.status)
            }
        }
        return proxyTarget(call, fresh, rangeHeader, videoId)
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
        videoId: String,
    ): ProxyOutcome {
        return try {
            val (start, end) = parseRangeHeader(rangeHeader)
            // Validate the URL + learn its total with a 1-byte CLOSED probe.
            // Open-ended ranges 403 from throttled networks, so the probe
            // (and every chunk below) carries an explicit end byte.
            val (probeStatus, total) = client.probeUpstreamLength(target.url, start)
            if (probeStatus != HttpStatusCode.PartialContent.value || total == null) {
                return ProxyOutcome.UpstreamError(probeStatus)
            }
            val lastByte = minOf(end ?: Long.MAX_VALUE, total - 1)
            if (start > lastByte) {
                call.respond(
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    ErrorResponse("Range start is beyond the content length")
                )
                return ProxyOutcome.Aborted // responded; nothing more to do
            }
            call.response.header(HttpHeaders.ContentRange, "bytes $start-$lastByte/$total")
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            call.respondBytesWriter(
                contentType = io.ktor.http.ContentType.parse(target.mimeType),
                status = HttpStatusCode.PartialContent
            ) {
                // Prefer ONE open-ended stream (the old behavior): a single
                // request means a single throttle decision and zero chunk
                // seams. Only when the network refuses it do we fall back
                // to stitched closed-range chunks — resuming EXACTLY at the
                // last byte already written, never replaying a prefix into
                // a live response (a replay would corrupt the byte window
                // the watch already accepted and stall/blur the stream).
                val (openDone, openWritten) = tryOpenStream(target.url, start)
                if (!openDone) {
                    client.fetchClosedChunks(
                        target.url, start + openWritten, lastByte, blockBytes = chunkBytes,
                        emit = { buf, n -> writeFully(buf, 0, n) },
                        refreshUrl = {
                            (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag)?.url
                        },
                    )
                }
            }
            ProxyOutcome.Streamed
        } catch (e: Exception) {
            // Client disconnected/skipped mid-stream, or upstream failed —
            // not worth a scary 500, this happens routinely on track skips.
            logger.info("Stream proxy ended early: {}", e.message)
            ProxyOutcome.Aborted
        }
    }

    /**
     * Single open-ended upstream fetch (`bytes=start-`, the pre-chunking
     * behavior). Returns (completed, bytesWritten):
     *   completed  — true when the WHOLE remainder streamed cleanly to EOF.
     *   bytesWritten — bytes actually flushed to the watch; an upstream
     *     connection can die mid-song, and the caller must resume the
     *     fallback at exactly that byte (a restart from `start` would
     *     replay already-delivered bytes and corrupt the response).
     * The upstream request carries streaming timeouts: an idle stall is
     * noticed in ≤60s, while a slow-but-alive stream is NEVER killed at an
     * arbitrary total elapsed time (that was the old fixed 20s cap).
     * Throws when the caller goes away (mapped to Aborted above).
     */
    private suspend fun io.ktor.utils.io.ByteWriteChannel.tryOpenStream(url: String, start: Long): Pair<Boolean, Long> {
        var written = 0L
        return try {
            var ok = false
            client.prepareGet(url) {
                header(HttpHeaders.Range, "bytes=$start-")
                // Timeout policy is CLIENT-level on Application.streamClient
                // (per-request `timeout {}` demands the plugin; test clients
                // run bare). See AudioProxy doc header.
            }.execute { upstream ->
                // `start > 0` bytes mean the response body MUST begin at
                // that byte. A 200 answer starts at byte 0 (range ignored):
                // accepting it here would inject the song's opening bytes
                // into the declared window — byte corruption. Closed chunks
                // (which re-send the exact range) are the safe fallback.
                if (upstream.status != HttpStatusCode.PartialContent &&
                    (start > 0 || upstream.status != HttpStatusCode.OK)
                ) {
                    return@execute
                }
                val channel = upstream.bodyAsChannel()
                val buf = ByteArray(chunkBytes)
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n < 0) break
                    if (n == 0) {
                        channel.awaitContent()
                        continue
                    }
                    writeFully(buf, 0, n)
                    written += n
                }
                ok = true
            }
            ok to written
        } catch (e: Exception) {
            // Caller gone (cancellation) must propagate as Aborted, not
            // silently fall back to chunks on a dead call.
            if (e is CancellationException) throw e
            false to written
        }
    }
}
