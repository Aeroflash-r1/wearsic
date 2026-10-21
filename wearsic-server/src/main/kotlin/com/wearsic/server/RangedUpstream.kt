package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/**
 * Closed-range chunk size for upstream CDN fetches.
 *
 * WHY THIS EXISTS: YouTube's CDN answers open-ended ranges (`bytes=N-`,
 * no end byte) with 403 from throttled networks while closed ranges
 * (`bytes=N-M`) return 206 — verified live: `bytes=0-`/`100-`/giant-end
 * all 403, `bytes=0-100000`/`100-100000` all 206. So every upstream fetch
 * is a bounded chunk; callers stitch chunks into one continuous stream.
 * 1 MB keeps per-chunk overhead to one RTT while staying far under any
 * CDN response-size tripwire.
 */
internal const val UPSTREAM_CHUNK_BYTES: Long = 1024L * 1024L

/**
 * Throttle visibility: every upstream range refusal (throttle 403s, dead
 * 404/410s) increments [rangeRefusals]. Surfaced in /health so `doctor`
 * can tell "YouTube is throttling this network" apart from "engine broken"
 * — the remedies differ (cookie/wait vs update).
 */
internal object UpstreamStats {
    val rangeRefusals = java.util.concurrent.atomic.AtomicInteger()
}

private val chunkLogger = org.slf4j.LoggerFactory.getLogger("RangedUpstream")

/**
 * STREAMING CLIENT TIMEOUTS (audio bodies):
 * Ktor's per-request `timeout { }` requires the HttpTimeout plugin to be
 * installed on the client, so instead the streaming client carries them as
 * CLIENT-level config (see Application.streamClient):
 *   requestTimeout = INFINITE — a song streamed through one open-ended
 *     request routinely runs 30s+; the old 20s limit killed the upstream
 *     mid-song, the watch's buffer drained, and playback stopped exactly
 *     at "halfway". Streaming is governed by the INACTIVITY watchdog
 *     (socketTimeout) instead: bytes may trickle for minutes, a dead
 *     connection is noticed within 60s.
 *   connectTimeout 10s / socketTimeout 60s.
 */

internal sealed interface ChunkedUpstream {
    data object Done : ChunkedUpstream

    /** Upstream refused the FIRST chunk — caller maps to self-heal/error. */
    data class Refused(val status: Int) : ChunkedUpstream
}

/**
 * Mid-stream upstream death that a resume/fresh-URL could not recover
 * within the retry budget. Thrown (not returned) precisely so the proxy's
 * already-committed response is ABORTED instead of being closed as a clean
 * end-of-body: OkHttp/data-source layers treat abrupt connection death as
 * an error and ExoPlayer retries the remaining range with a fresh request,
 * whereas a clean EOF reads as "song finished" at byte N — the exact
 * "stalls halfway, silently" bug.
 */
internal class UpstreamTruncatedException(
    val atByte: Long,
    cause: Throwable? = null,
) : java.io.IOException("upstream truncated un-recoverably at byte $atByte", cause)
internal fun parseRangeHeader(header: String?, availableTotal: Long? = null): Pair<Long, Long?> {
    if (header.isNullOrBlank()) return 0L to null
    val trimmed = header.trim()
    val m = Regex("bytes=(\\d+)-(\\d*)").find(trimmed)
    if (m == null) {
        // RFC 7233 suffix form: "bytes=-N" = the LAST N bytes. Resolvable
        // only with a known total; callers without one keep the historical
        // (0, null) whole-file behavior until they re-parse with a total
        // (AudioProxy does exactly that after the length probe).
        Regex("bytes=-(\\d+)").find(trimmed)?.let { suffix ->
            val n = suffix.groupValues[1].toLongOrNull() ?: return 0L to null
            val total = availableTotal
            if (total == null || total <= 0L || n <= 0L) return 0L to null
            val start = (total - n).coerceIn(0L, total - 1L)
            return start to (total - 1L).coerceAtLeast(start)
        }
        return 0L to null
    }
    val start = m.groupValues[1].toLongOrNull() ?: 0L
    val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
    return start to end
}

/**
 * Runs a response-body producer so that ANY failure TEARS THE CONNECTION
 * DOWN instead of abandoning the body.
 *
 * Why: a producer exception inside `respondBytesWriter` propagates to the
 * caller, but the writer channel itself is left open — the socket then
 * stays up with no further bytes and no end-of-body, so the watch waits out
 * its own read timeout (~30-60s) before retrying, and the UI reads that as
 * exactly the "stalls mid-song" bug. Closing the channel WITH the cause
 * makes the engine drop the connection immediately: the data source errors
 * at once and ExoPlayer retries the remaining range with a fresh request.
 * A clean close is NOT an option either — that reads as "song finished".
 */
internal suspend fun ByteWriteChannel.abortOnFailure(block: suspend ByteWriteChannel.() -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        runCatching { close(t) }
        throw t
    }
}

/** `"bytes 0-0/3449447"` → 3449447. Null when absent/unparseable. */
internal fun parseContentRangeTotal(contentRange: String?): Long? {
    if (contentRange.isNullOrBlank()) return null
    return Regex("/(\\d+)\\s*$").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
}

/**
 * 1-byte closed probe: validates a CDN URL and learns its total length
 * without committing to a full fetch. Returns (status, total?) — total is
 * non-null only on 206 with a parseable Content-Range.
 */
internal suspend fun HttpClient.probeUpstreamLength(url: String, atByte: Long): Pair<Int, Long?> {
    var status = 0
    var total: Long? = null
    try {
        prepareGet(url) {
            header(HttpHeaders.Range, "bytes=$atByte-$atByte")
        }.execute { resp ->
            status = resp.status.value
            if (resp.status == HttpStatusCode.PartialContent) {
                total = parseContentRangeTotal(resp.headers[HttpHeaders.ContentRange])
            }
        }
    } catch (_: Exception) {
    }
    return status to total
}

/**
 * Streams [startByte]..[endByteInclusive] (null = to EOF) through [emit]
 * using only CLOSED upstream ranges. Returns [ChunkedUpstream.Done] after
 * a clean EOF, [ChunkedUpstream.Refused] when the FIRST chunk is refused.
 * A mid-stream refusal re-resolves via [refreshUrl] and RESUMES at the same
 * byte (at most [maxRefreshes] times) instead of truncating the song.
 * Throws when [emit] throws (caller gone) so callers map it to aborted.
 */
internal suspend fun HttpClient.fetchClosedChunks(
    url: String,
    startByte: Long,
    endByteInclusive: Long?,
    blockBytes: Int = 64 * 1024,
    refreshUrl: (suspend () -> String?)? = null,
    maxRefreshes: Int = 2,
    emit: suspend (ByteArray, Int) -> Unit,
): ChunkedUpstream {
    require(blockBytes > 0) { "blockBytes must be positive" }
    var currentUrl = url
    var pos = startByte.coerceAtLeast(0L)
    var refreshes = 0
    var zeroStreak = 0
    var socketRetries = 2
    while (endByteInclusive == null || pos <= endByteInclusive) {
        val chunkEnd = minOf(pos + UPSTREAM_CHUNK_BYTES - 1, endByteInclusive ?: Long.MAX_VALUE)
        var status = 0
        var got = 0L
        try {
            prepareGet(currentUrl) {
                header(HttpHeaders.Range, "bytes=$pos-$chunkEnd")
            }.execute { resp ->                status = resp.status.value
                if (resp.status != HttpStatusCode.PartialContent) return@execute
                val channel = resp.bodyAsChannel()
                val buf = ByteArray(blockBytes)
                while (true) {
                    val n = channel.readAvailable(buf)
                    if (n < 0) break
                    if (n == 0) {
                        channel.awaitContent()
                        continue
                    }
                    emit(buf, n)
                    got += n
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Mid-chunk network death (socket timeout / reset). Bytes up to
            // (pos + got) are already with the caller. Reconnect at that
            // exact byte before declaring the stream truncated — a CDN
            // throttle usually resolves on reconnect or a fresh URL.
            if (socketRetries > 0) {
                socketRetries--
                val fresh = if (refreshUrl != null && refreshes < maxRefreshes) {
                    refreshes++
                    runCatching { refreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
                } else currentUrl
                if (fresh != null && fresh != currentUrl) currentUrl = fresh
                pos += got
                chunkLogger.info("Upstream connection died mid-chunk at byte {}, reconnecting ({} retries left)", pos, socketRetries)
                kotlinx.coroutines.delay(200)
                continue
            }
            throw UpstreamTruncatedException(pos + got, e)
        }
        if (status == HttpStatusCode.RequestedRangeNotSatisfiable.value) {
            // 416 usually means past-EOF (clean end) — but a RESUMED range
            // (pos <= known end) answered 416 means the CDN lost the body
            // or is lying about state; treating it as EOF would declare the
            // song complete at the cutoff. Re-resolve + retry, then treat
            // like a mid-stream refusal (aborted connection, never a fake
            // clean end).
            val suspicious416 = endByteInclusive != null && pos <= endByteInclusive
            if (suspicious416) {
                val fresh = if (refreshUrl != null && refreshes < maxRefreshes) {
                    refreshes++
                    runCatching { refreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
                } else null
                if (fresh != null && fresh != currentUrl) {
                    chunkLogger.info("416 on live range at byte {} — resuming with a fresh URL", pos)
                    currentUrl = fresh
                    continue
                }
                if (socketRetries > 0) {
                    socketRetries--
                    chunkLogger.info("416 on live range at byte {} — retrying same range", pos)
                    kotlinx.coroutines.delay(200)
                    continue
                }
                throw UpstreamTruncatedException(pos + got)
            }
            break // genuinely past EOF: clean end
        }
        if (status != HttpStatusCode.PartialContent.value) {
            UpstreamStats.rangeRefusals.incrementAndGet()
            // First chunk refused. A refresh may STILL rescue a mid-response
            // fallback — the caller's self-heal only applies pre-commit —
            // so rotate once here before surfacing Refused.
            if (pos == startByte.coerceAtLeast(0L) && got == 0L) {
                val fresh = if (refreshUrl != null && refreshes < maxRefreshes) {
                    refreshes++
                    runCatching { refreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
                } else null
                if (fresh != null && fresh != currentUrl) {
                    chunkLogger.info("Upstream refused first chunk ({}), retrying with a rotated URL", status)
                    currentUrl = fresh
                    continue
                }
                return ChunkedUpstream.Refused(status)
            }
            // Mid-stream refusal (throttle 403): refresh the URL and RESUME
            // at the same byte — the client's song continues instead of
            // dying halfway. Bounded: beyond the budget the stream becomes
            // an ABORTED connection, never a fake clean EOF.
            val fresh = if (refreshUrl != null && refreshes < maxRefreshes) {
                refreshes++
                runCatching { refreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
            } else null
            if (fresh != null && fresh != currentUrl) {
                chunkLogger.info("Upstream refused mid-stream ({}), resuming at byte {} with a fresh URL", status, pos)
                currentUrl = fresh
                continue
            }
            chunkLogger.info("Upstream refused mid-stream ({}) at byte {}, out of resume budget", status, pos)
            throw UpstreamTruncatedException(pos + got)
        }
        pos += got
        // Zero-byte 206s in a row mean either the origin ran dry (streaming
        // to an unknown end) or a wedged connection (a concrete end is still
        // owed). Without this the loop re-requests the same range forever.
        if (got == 0L) {
            zeroStreak++
            if (zeroStreak >= 3) {
                if (endByteInclusive != null && pos <= endByteInclusive) {
                    // Bytes are still OWED: a lying/wedged origin, NOT EOF.
                    // Abort so the data source errors and retries instead of
                    // the song "finishing" at this byte.
                    throw UpstreamTruncatedException(pos)
                }
                break
            }
        } else {
            zeroStreak = 0
        }
    }
    return ChunkedUpstream.Done
}
