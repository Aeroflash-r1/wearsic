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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Wall-clock cap for one background CDN warm probe. A cold edge can take
 * seconds to answer; anything longer is left to the real stream's own
 * (client-level) timeouts.
 */
private const val WARM_UPSTREAM_TIMEOUT_MS: Long = 12_000L

/**
 * Upper bound on bytes a warm probe will read. A host honoring
 * `Range: bytes=0-0` answers one byte; a hop that ignores the range and
 * answers 200 must not push a whole song through a probe.
 */
private const val WARM_UPSTREAM_DRAIN_CAP = 64 * 1024

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

    /** Hosts the fire-and-forget prefetch warm probes (never joined). */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget CDN warm for a PREFETCHED target. The tap that follows
     * (usually seconds later) then finds the host's connection pooled and the
     * CDN edge path hot. Live-measured: a cold first byte cost 3.8s while the
     * very same URL answered in 157ms once warm — that gap is the "tap feels
     * slow" complaint. One ranged byte per probe; any failure is ignored (the
     * stream path has its own rotation/self-healing).
     */
    fun warmUpstream(videoId: String, url: String) {
        if (!url.startsWith("https://")) return
        warmScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                withTimeoutOrNull(WARM_UPSTREAM_TIMEOUT_MS) {
                    client.prepareGet(url) {
                        header(HttpHeaders.Range, "bytes=0-0")
                    }.execute { response ->
                        if (response.status.value !in 200..299) return@execute
                        val channel = response.bodyAsChannel()
                        var drained = 0L
                        val buf = ByteArray(8 * 1024)
                        while (drained < WARM_UPSTREAM_DRAIN_CAP) {
                            val n = channel.readAvailable(buf)
                            if (n < 0) break
                            if (n == 0) {
                                channel.awaitContent()
                                continue
                            }
                            drained += n
                        }
                    }
                }
                logger.info(
                    "Upstream warm {}: {}ms", videoId, System.currentTimeMillis() - startedAt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: a failed warm changes nothing.
            }
        }
    }

    suspend fun handle(call: ApplicationCall, videoId: String) {
        val resolveStartedAt = System.currentTimeMillis()
        val target = try {
            extractor.streamTarget(videoId)
        } catch (e: Exception) {
            // Extraction blew up (most commonly YouTube's bot-wall LOGIN_REQUIRED
            // when no cookie is set). The v1 server answered 503 with an
            // actionable message here — match that contract instead of leaking
            // a 500 + stack trace to the watch.
            logger.warn("Audio extraction failed for {}: {}", videoId, e.message)
            // The old blanket text blamed authentication for EVERY extraction
            // failure. This call fails for many reasons — bot-wall without a
            // cookie, network outage, InnerTube change, a simply non-playable
            // video — and "add your cookie" advice only helps the first one.
            // Tell the truth; keep the cookie guidance for auth-shaped errors.
            val message = e.message.orEmpty()
            val maybeAuthSignal =
                listOf("login", "sign in", "auth", "bot", "age", "visitor", "consent")
                    .any { message.contains(it, ignoreCase = true) }
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse(
                    if (maybeAuthSignal) {
                        "YouTube requires authentication for audio extraction. " +
                            "Add your YouTube cookie in the app Settings."
                    } else {
                        "Audio extraction failed for this track. It may be unavailable " +
                            "or the server could not reach YouTube — check server logs " +
                            "and try again."
                    }
                )
            )
            return
        }
        val resolveMs = System.currentTimeMillis() - resolveStartedAt
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("No playable audio stream found for $videoId"))
            return
        }

        // Extractor rotation tickets are warmed ONLY when the target's client
        // family is wall-prone (not VISIONOS — live-verified wall-free).
        // Warming on every play put a heavy NewPipe extraction right next to
        // the playback download on a phone, and the watcher's warm-up
        // requests (`Range: bytes=0-0`, fired per tap AND per upcoming track)
        // made sure it happened on every single tap — the "slow to start
        // playing" regression. Wall-prone targets still get their safety net
        // (deduped per video per 45 min, delayed a few seconds).
        if (target.needsExtractorWarm) {
            (extractor as? YoutubeGateway)?.warmExtractorTickets(videoId)
        }

        val rangeHeader = call.request.header(HttpHeaders.Range)

        // Songs without a native AAC stream (WebM/Opus or Vorbis) are converted
        // to AAC-LC on the server so the watch always gets hardware-decodable
        // audio. When YouTube already offers AAC (audio/mp4 — the common case),
        // pass it through untouched with zero CPU cost.
        if (transcoder.needsTranscode(target.mimeType)) {
            val refreshForTranscode: suspend () -> String? = {
                // Mid-pump rotation: same-encoding tickets only (byte offsets
                // encode a timeline). Incompatible → null → clean abort.
                (extractor as? YoutubeGateway)
                    ?.invalidateStreamTarget(videoId, target.itag, target.mimeType, requireCompatible = true)
                    ?.url
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
                            ErrorResponse(upstreamUnavailableText(result.status))
                        )
                        return
                    }
                    // Dead URL on the transcode path: same self-heal as the
                    // passthrough path — one fresh re-resolve, then stream it.
                    val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag, target.mimeType)
                    if (fresh != null) {
                        logger.info("Self-healed stale transcode URL for {}", videoId)
                        when (val retry = transcoder.handle(call, fresh.url, rangeHeader, refreshForTranscode)) {
                            is Transcoder.TranscodeResult.UpstreamError -> call.respond(
                                HttpStatusCode.BadGateway,
                                ErrorResponse(upstreamUnavailableText(retry.status))
                            )
                            else -> Unit // Streamed / Unavailable / Aborted already responded
                        }
                        return
                    }
                    // Re-resolve failed — surface the ORIGINAL upstream error,
                    // not a generic message, so logs stay actionable.
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse(upstreamUnavailableText(result.status))
                    )
                    return
                }
            }
        }

        // Passthrough path. proxyTarget no longer commits error responses
        // itself: it returns the outcome so we can retry once on dead URLs
        // BEFORE anything is sent (double-respond would throw).
        when (val outcome = proxyTarget(call, target, rangeHeader, videoId, resolveMs)) {
            is ProxyOutcome.Streamed -> return
            is ProxyOutcome.Aborted -> return
            is ProxyOutcome.UpstreamError -> {
                logger.info("Upstream refused {} with HTTP {} (first attempt)", videoId, outcome.status)
                if (outcome.status !in DEAD_URL_STATUSES) {
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ErrorResponse(upstreamUnavailableText(outcome.status))
                    )
                    return
                }
                // Dead-URL self-heal: 403/404/410 mean the cached CDN URL expired
                // or was revoked upstream (it can die well inside the 1h cache
                // TTL). Drop the stale entry, re-resolve once, and stream the
                // fresh URL — the watch gets audio instead of an error.
                val fresh = (extractor as? YoutubeGateway)?.invalidateStreamTarget(videoId, target.itag, target.mimeType)
                if (fresh != null) {
                    logger.info("Self-healed stale stream URL for {}", videoId)
                    when (val retry = streamFresh(call, fresh, rangeHeader, videoId)) {
                        is ProxyOutcome.UpstreamError -> call.respond(
                            HttpStatusCode.BadGateway,
                            ErrorResponse(upstreamUnavailableText(retry.status))
                        )
                        else -> Unit
                    }
                    return
                }
                logger.warn("Re-resolve after dead URL failed for {}", videoId)
                call.respond(
                    HttpStatusCode.BadGateway,
                    ErrorResponse(upstreamUnavailableText(outcome.status))
                )
            }
        }
    }

    private sealed interface ProxyOutcome {
        data object Streamed : ProxyOutcome
        data object Aborted : ProxyOutcome
        data class UpstreamError(val status: Int) : ProxyOutcome
    }

    /** Maps the probe layer's status-0 sentinel ("connect/IO failed before
     *  any HTTP status arrived") to honest client-facing text instead of the
     *  nonsensical "(HTTP 0)" the watch's error surface used to display. */
    private fun upstreamUnavailableText(status: Int): String =
        if (status == 0) "Upstream audio unavailable (connection failed)"
        else "Upstream audio unavailable (HTTP $status)"

    /** Streams one already-resolved [fresh] target (transcode-aware). */
    private suspend fun streamFresh(
        call: ApplicationCall,
        fresh: StreamTarget,
        rangeHeader: String?,
        videoId: String,
    ): ProxyOutcome {
        if (transcoder.needsTranscode(fresh.mimeType)) {
            val refreshForTranscode: suspend () -> String? = {
                (extractor as? YoutubeGateway)
                    ?.invalidateStreamTarget(videoId, fresh.itag, fresh.mimeType, requireCompatible = true)
                    ?.url
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
     *
     * Every response logs one compact timing line (resolve wait, upstream
     * open, first byte, bytes, total) — the "slow to play" hunt needed
     * exactly these numbers and guessing from request durations alone
     * proved wrong.
     */
    private suspend fun proxyTarget(
        call: ApplicationCall,
        target: StreamTarget,
        rangeHeader: String?,
        videoId: String,
        resolveMs: Long = 0L,
    ): ProxyOutcome {
        val startedAt = System.currentTimeMillis()
        var openMs = 0L
        var firstByteMs = -1L
        var written = 0L
        return try {
            val hasRangeHeader = !rangeHeader.isNullOrBlank()
            // First parse: `start` anchors the 1-byte length probe below.
            // Suffix ranges ("bytes=-N") probe byte 0 — their true window is
            // resolved in the second parse once the total is known.
            var (start, end) = parseRangeHeader(rangeHeader)
            // The upstream total. Lean targets carry it from the player
            // response (contentLength) — re-using it skips the old 1-byte
            // validating probe: a full CDN round trip before the first byte
            // of EVERY request, including the watch app's per-tap warm-up
            // requests. Unknown length (extractor targets) probes as before.
            // A dead URL is no longer caught by the probe for lean targets:
            // the open request surfaces the refusal and the chunked fallback
            // immediately self-heals through URL rotation.
            val (probeStatus, total) = if (target.contentLength > 0) {
                HttpStatusCode.PartialContent.value to target.contentLength
            } else {
                client.probeUpstreamLength(target.url, start)
            }
            if (probeStatus != HttpStatusCode.PartialContent.value || total == null) {
                return ProxyOutcome.UpstreamError(probeStatus)
            }
            // Second parse with the authoritative total: resolves RFC 7233
            // suffix ranges ("bytes=-N" = the LAST N bytes). Without a total,
            // a suffix request fell through as (0, null) and previously got
            // the WHOLE file from byte 0 wrongly presented as a 206.
            parseRangeHeader(rangeHeader, availableTotal = total).let { (s, e) ->
                start = s
                end = e
            }
            val lastByte = minOf(end ?: Long.MAX_VALUE, total - 1)
            if (start > lastByte) {
                call.respond(
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    ErrorResponse("Range start is beyond the content length")
                )
                return ProxyOutcome.Aborted // responded; nothing more to do
            }
            // RFC 7233: a plain GET (no Range header) must be answered 200
            // with the full representation; only a Range request earns a 206
            // + Content-Range. (Previously every response was a 206 with a
            // bytes-0 Content-Range even unsolicited.)
            if (hasRangeHeader) {
                call.response.header(HttpHeaders.ContentRange, "bytes $start-$lastByte/$total")
            }
            call.response.header(HttpHeaders.AcceptRanges, "bytes")

            // The client's declared window. The open pump runs to the natural
            // EOF — never stop it early. A write-clamped early exit was
            // live-tested and left tiny-window requests (the app's `bytes=0-0`
            // warm-ups) hanging with the socket silent; the watch app closes
            // its warm-up body right after the headers, so the response tears
            // down on the client's disconnect instead.
            val expectedRemaining = lastByte - start + 1
            val countWrite: (Int) -> Unit = { n ->
                written += n
                if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - startedAt
            }

            call.respondBytesWriter(
                contentType = io.ktor.http.ContentType.parse(target.mimeType),
                status = if (hasRangeHeader) HttpStatusCode.PartialContent else HttpStatusCode.OK
            ) {
                // Every failure in here tears the connection down (see
                // [abortOnFailure]): an abandoned writer would leave the
                // socket open and the watch would sit out its read timeout.
                abortOnFailure {
                    // Prefer ONE open-ended stream (the old behavior): a single
                    // request means a single throttle decision and zero chunk
                    // seams. Only when the network refuses it do we fall back
                    // to stitched closed-range chunks — resuming EXACTLY at the
                    // last byte already written, never replaying a prefix into
                    // a live response (a replay would corrupt the byte window
                    // the watch already accepted and stall/blur the stream).
                    val openStarted = System.currentTimeMillis()
                    val (openDone, openWritten) = tryOpenStream(target.url, start, onBytes = countWrite)
                    openMs = System.currentTimeMillis() - openStarted
                    // `openDone` alone is NOT enough: the CDN can END the open
                    // stream at its per-URL transfer budget (~1 MB, measured)
                    // with a graceful EOF — ending the response there would tell
                    // the watch the song finished at that byte (the reported
                    // "stalls at 1:04"). Anything short of the probed length
                    // continues through rotating closed-range chunks instead.
                    if (!openDone || openWritten < expectedRemaining) {
                        val outcome = client.fetchClosedChunks(
                            target.url, start + openWritten, lastByte, blockBytes = chunkBytes,
                            maxRefreshes = 8,
                            emit = { buf, n -> writeFully(buf, 0, n); countWrite(n) },
                            refreshUrl = {
                                // Mid-stream rotation: same-encoding tickets only
                                // (byte offsets encode a timeline). When the pool
                                // is dry the video gets wall-marked and the next
                                // request resolves via n-fixed extractor URLs.
                                (extractor as? YoutubeGateway)
                                    ?.invalidateStreamTarget(videoId, target.itag, target.mimeType, requireCompatible = true)
                                    ?.url
                            },
                        )
                        if (outcome is ChunkedUpstream.Refused) {
                            // No rotation left mid-stream: ABORT (never a clean
                            // end) so ExoPlayer retries the remaining range.
                            throw UpstreamTruncatedException(start + openWritten)
                        }
                    }
                }
            }
            logger.info(
                "Stream {} done: rng={} resolve={}ms upstream={}ms firstByte={}ms bytes={} total={}ms",
                videoId, rangeHeader ?: "full", resolveMs, openMs, maxOf(firstByteMs, 0L),
                written, System.currentTimeMillis() - startedAt,
            )
            ProxyOutcome.Streamed
        } catch (e: Exception) {
            // Client disconnected/skipped mid-stream, or upstream failed —
            // not worth a scary 500, this happens routinely on track skips.
            logger.info(
                "Stream proxy ended early: {} (rng={} bytes={} after={}ms)",
                e.message, rangeHeader ?: "full", written, System.currentTimeMillis() - startedAt,
            )
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
     *
     * A bounded client window (the watch app's `bytes=0-0` warm-ups) is NOT
     * clamped here: the pump runs to the natural EOF and the client's own
     * disconnect ends it. A write-clamped early exit was live-tested and
     * left tiny-window requests hanging with the socket silent — never
     * abandon an upstream body mid-transfer inside Ktor's writer.
     */
    private suspend fun io.ktor.utils.io.ByteWriteChannel.tryOpenStream(
        url: String,
        start: Long,
        onBytes: (Int) -> Unit = {},
    ): Pair<Boolean, Long> {
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
                    onBytes(n)
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
