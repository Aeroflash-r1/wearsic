package com.wearsic.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal in-memory token-bucket rate limiter for the expensive
 * `/api/stream/{videoId}` endpoint (extraction + CDN traffic + possible
 * ffmpeg CPU). Personal-server scale: per client key (API key or IP), bounded
 * map with idle-identity eviction so it can never become a memory leak.
 *
 * Not a distributed rate limiter — deliberately boring.
 */
class RateLimiter(
    /** Sustained refill rate per minute per bucket. */
    private val permitsPerMinute: Double,
    /** Bucket depth; short bursts above the sustained rate are allowed. */
    private val burstCapacity: Int = (permitsPerMinute * 2).toInt().coerceAtLeast(4),
    /** Buckets idle for longer than this are evicted lazily (nanoseconds). */
    private val idleEvictNanos: Long = 10L * 60 * 1_000_000_000L,
    /** Hard cap on tracked buckets; the oldest-idle is dropped beyond this. */
    private val maxTrackedKeys: Int = 512,
    clock: () -> Long = System::nanoTime,
) {
    private class Bucket(val createdAtNanos: Long, @Volatile var lastRefillNanos: Long, @Volatile var tokens: Double)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val lastEviction = AtomicLong(clock())
    private val clockFn = clock

    /** Returns true when the request is allowed; false when rate-limited. */
    fun tryAcquire(key: String, nowNanos: Long = clockFn()): Boolean {
        evictIfNeeded(nowNanos)

        val refillPerNano = permitsPerMinute / 60_000_000_000.0
        val capacity = burstCapacity.toDouble()

        // computeIfAbsent keeps bucket creation atomic under contention.
        val bucket = buckets.computeIfAbsent(key) { Bucket(nowNanos, nowNanos, capacity) }

        synchronized(bucket) {
            val elapsed = (nowNanos - bucket.lastRefillNanos).coerceAtLeast(0)
            if (elapsed > 0) {
                bucket.tokens = (bucket.tokens + elapsed * refillPerNano).coerceAtMost(capacity)
                bucket.lastRefillNanos = nowNanos
            }
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    /** Current number of tracked buckets (exposed for tests). */
    val trackedKeys: Int get() = buckets.size

    private fun evictIfNeeded(nowNanos: Long) {
        val last = lastEviction.get()
        // Try to become the evictor at most once per idle window.
        if (nowNanos - last < idleEvictNanos || !lastEviction.compareAndSet(last, nowNanos)) return

        val idleCutoff = nowNanos - idleEvictNanos
        buckets.entries.removeIf { it.value.lastRefillNanos < idleCutoff }

        // Still over the hard cap? Drop the oldest-idle entries.
        if (buckets.size > maxTrackedKeys) {
            buckets.entries
                .sortedBy { it.value.lastRefillNanos }
                .take(buckets.size - maxTrackedKeys)
                .forEach { buckets.remove(it.key) }
        }
    }
}
