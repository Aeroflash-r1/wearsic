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

internal sealed interface ChunkedUpstream {
    data object Done : ChunkedUpstream

    /** Upstream refused the FIRST chunk — caller maps to self-heal/error. */
    data class Refused(val status: Int) : ChunkedUpstream
}

/** `"bytes=N-"` / `"bytes=N-M"` → start + inclusive end (null = open). */
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
 * A refusal mid-stream ends the stream where it stands (throttle windows
 * pass; the next tap re-resolves). Throws when [emit] throws (caller gone)
 * so callers map it to their aborted outcome.
 */
internal suspend fun HttpClient.fetchClosedChunks(
    url: String,
    startByte: Long,
    endByteInclusive: Long?,
    blockBytes: Int = 64 * 1024,
    emit: suspend (ByteArray, Int) -> Unit,
): ChunkedUpstream {
    require(blockBytes > 0) { "blockBytes must be positive" }
    var pos = startByte.coerceAtLeast(0L)
    while (endByteInclusive == null || pos <= endByteInclusive) {
        val chunkEnd = minOf(pos + UPSTREAM_CHUNK_BYTES - 1, endByteInclusive ?: Long.MAX_VALUE)
        val asked = chunkEnd - pos + 1
        var status = 0
        var got = 0L
        prepareGet(url) {
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
            // for the caller's self-heal; mid-stream = end what we have.
            if (pos == startByte.coerceAtLeast(0L) && got == 0L) {
                return ChunkedUpstream.Refused(status)
            }
            break
        }
        pos += got
        if (got < asked) break // short read = EOF
    }
    return ChunkedUpstream.Done
}
