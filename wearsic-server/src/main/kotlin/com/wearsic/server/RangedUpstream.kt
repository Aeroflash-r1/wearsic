package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable

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

private val chunkLogger = org.slf4j.LoggerFactory.getLogger("RangedUpstream")

internal sealed interface ChunkedUpstream {
    data object Done : ChunkedUpstream

    /** Upstream refused the FIRST chunk — caller maps to self-heal/error. */
    data class Refused(val status: Int) : ChunkedUpstream
}
internal fun parseRangeHeader(header: String?): Pair<Long, Long?> {
    if (header.isNullOrBlank()) return 0L to null
    val m = Regex("bytes=(\\d+)-(\\d*)").find(header.trim()) ?: return 0L to null
    val start = m.groupValues[1].toLongOrNull() ?: 0L
    val end = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLongOrNull()
    return start to end
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
    while (endByteInclusive == null || pos <= endByteInclusive) {
        val chunkEnd = minOf(pos + UPSTREAM_CHUNK_BYTES - 1, endByteInclusive ?: Long.MAX_VALUE)
        var status = 0
        var got = 0L
        prepareGet(currentUrl) {
            header(HttpHeaders.Range, "bytes=$pos-$chunkEnd")
        }.execute { resp ->
            status = resp.status.value
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
        if (status == HttpStatusCode.RequestedRangeNotSatisfiable.value) break // past EOF: clean end
        if (status != HttpStatusCode.PartialContent.value) {
            // Refused. First chunk (nothing emitted yet) = dead URL signal
            // for the caller's self-heal.
            if (pos == startByte.coerceAtLeast(0L) && got == 0L) {
                return ChunkedUpstream.Refused(status)
            }
            // Mid-stream refusal (throttle 403): refresh the URL and RESUME
            // at the same byte — the client's song continues instead of
            // dying halfway. Bounded, then end what we have.
            val fresh = if (refreshUrl != null && refreshes < maxRefreshes) {
                refreshes++
                runCatching { refreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
            } else null
            if (fresh != null && fresh != currentUrl) {
                chunkLogger.info("Upstream refused mid-stream ({}), resuming at byte {} with a fresh URL", status, pos)
                currentUrl = fresh
                continue
            }
            chunkLogger.info("Upstream refused mid-stream ({}) at byte {}, ending stream", status, pos)
            break
        }
        pos += got
        // Zero-byte 206s in a row mean a wedged connection, not EOF (EOF
        // arrives as 416 or a short read followed by 416). Without this the
        // loop re-requests the same range forever.
        if (got == 0L) {
            zeroStreak++
            if (zeroStreak >= 3) break
        } else {
            zeroStreak = 0
        }
    }
    return ChunkedUpstream.Done
}
