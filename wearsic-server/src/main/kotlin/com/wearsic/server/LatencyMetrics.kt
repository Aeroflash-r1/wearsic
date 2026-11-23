package com.wearsic.server

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * One metric's recent distribution.
 *
 * Deliberately counts only — no reservoir sampling, no percentiles library.
 * The server is a phone: a fixed ring of the last [RING] samples per metric
 * costs a few KB and answers the only question that matters here ("is the tap
 * slow because of YouTube, or because of us?").
 */
@Serializable
data class LatencyStatsDto(
    val count: Long = 0,
    val p50Ms: Long = 0,
    val p90Ms: Long = 0,
    val p99Ms: Long = 0,
    val maxMs: Long = 0,
)

/**
 * Process-wide latency histograms for the request hot paths.
 *
 * WHY THIS EXISTS: every performance decision in this server used to be made
 * from single log lines, guessed from request durations, or from a benchmark
 * that measured the wrong thing. The proxy already prints
 * `resolve= upstream= firstByte=` per stream; that is evidence for ONE
 * request. This turns the same measurements into p50/p90/p99 over the last
 * 256 of each, so `/health` answers "is it slow right now?" and `wearsic
 * doctor` can tell a cold CDN apart from a jammed extraction queue.
 *
 * Metric names are dotted and stable (see the ones recorded across the code):
 *   search.total, search.ytm, search.general
 *   resolve.total, resolve.lean, resolve.extract, resolve.extract.wait
 *   related.lean, related.extract
 *   stream.firstByte, stream.openRefused, transcode.wait
 */
object LatencyMetrics {

    /** Samples kept per metric. 256 is enough for a p99 that stops jumping. */
    internal const val RING = 256

    /** Hard cap on distinct metric names (typos must not leak memory). */
    private const val MAX_SERIES = 48

    private class Series {
        val samples = LongArray(RING)
        var total = 0L
        var index = 0
        var max = 0L
    }

    private val series = ConcurrentHashMap<String, Series>()

    fun record(name: String, millis: Long) {
        if (series.size >= MAX_SERIES && !series.containsKey(name)) return
        val s = series.computeIfAbsent(name) { Series() }
        val v = millis.coerceAtLeast(0L)
        synchronized(s) {
            s.samples[s.index] = v
            s.index = (s.index + 1) % RING
            s.total++
            if (v > s.max) s.max = v
        }
    }

    /** Times [block] and records it under [name]. Exceptions are recorded too. */
    suspend fun <T> time(name: String, block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        try {
            return block()
        } finally {
            record(name, System.currentTimeMillis() - started)
        }
    }

    fun snapshot(): Map<String, LatencyStatsDto> {
        val out = LinkedHashMap<String, LatencyStatsDto>()
        for (name in series.keys.sorted()) {
            val s = series[name] ?: continue
            out[name] = synchronized(s) {
                // Before the ring wraps, samples 0..total-1 are the valid ones;
                // after it wraps the whole array is valid.
                val n = if (s.total >= RING) RING else s.total.toInt()
                val ordered = s.samples.copyOf(n).also { it.sort() }
                fun pct(p: Double): Long =
                    if (ordered.isEmpty()) 0L else ordered[((ordered.size - 1) * p).toInt()]
                LatencyStatsDto(
                    count = s.total,
                    p50Ms = pct(0.50),
                    p90Ms = pct(0.90),
                    p99Ms = pct(0.99),
                    maxMs = s.max,
                )
            }
        }
        return out
    }

    /** Idempotent: used by tests that share this process-wide object. */
    fun reset() {
        series.clear()
    }
}
