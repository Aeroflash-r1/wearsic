package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    /** 60 permits/minute = 1/sec; 5-bucket burst. */
    private fun limiter(clock: () -> Long) = RateLimiter(
        permitsPerMinute = 60.0,
        burstCapacity = 5,
        idleEvictNanos = 1_000_000_000L, // 1s in nanos
        clock = clock,
    )

    @Test
    fun `allows burst above sustained rate`() {
        var now = 0L
        val rl = limiter { now }

        val results = (1..5).map { rl.tryAcquire("client") }
        assertTrue(results.all { it }, "full burst must be allowed (results=$results)")
    }

    @Test
    fun `rejects when burst is exhausted and not yet refilled`() {
        var now = 0L
        val rl = RateLimiter(
            permitsPerMinute = 60.0,
            burstCapacity = 5,
            idleEvictNanos = 100_000_000_000L, // 100s: no eviction during the test
            clock = { now },
        )

        repeat(5) { assertTrue(rl.tryAcquire("client")) }
        now += 100_000_000L // +100ms -> only 0.1 token refilled
        assertFalse(rl.tryAcquire("client"), "6th request inside the burst window must be limited")
    }

    @Test
    fun `refills over time at the sustained rate`() {
        var now = 0L
        // Idle window (100s) far exceeds the refill timescale here, so no
        // bucket eviction interferes with the refill arithmetic.
        val rl = RateLimiter(
            permitsPerMinute = 60.0,
            burstCapacity = 5,
            idleEvictNanos = 100_000_000_000L,
            clock = { now },
        )

        repeat(5) { rl.tryAcquire("client") }
        now += 3_000_000_000L // +3s -> 3 tokens refilled (0 -> 3 of 5)
        assertTrue(rl.tryAcquire("client"))
        assertTrue(rl.tryAcquire("client"))
        assertTrue(rl.tryAcquire("client"))
        now += 100_000_000L // +100ms -> only 0.1 more token
        assertFalse(rl.tryAcquire("client"), "bucket must be drained again after refilled tokens are consumed")
    }

    @Test
    fun `keys are independent`() {
        var now = 0L
        val rl = RateLimiter(
            permitsPerMinute = 60.0,
            burstCapacity = 5,
            idleEvictNanos = 100_000_000_000L, // 100s: no eviction during the test
            clock = { now },
        )

        repeat(5) { rl.tryAcquire("client-a") }
        now += 100_000_000L
        assertTrue(rl.tryAcquire("client-b"), "exhausting one key must not affect another")
        assertFalse(rl.tryAcquire("client-a"))
    }

    @Test
    fun `idle buckets are evicted - map stays bounded`() {
        var now = 0L
        val rl = RateLimiter(
            permitsPerMinute = 60.0,
            burstCapacity = 5,
            idleEvictNanos = 1_000_000_000L, // 1s in nanos: sweeps every acquire past the first second
            clock = { now },
        )

        // 300 distinct clients across ~10 minutes of simulated time; every
        // acquire after t=1s triggers a sweep that drops all idle buckets.
        for (minute in 0..9) {
            now = minute * 60_000_000_000L
            for (i in 1..30) {
                rl.tryAcquire("client-$minute-$i")
            }
        }
        // Only the current minute's buckets (plus in-flight stragglers) remain.
        assertTrue(rl.trackedKeys <= 45, "idle buckets must be evicted (tracked=${rl.trackedKeys})")
    }

    @Test
    fun `zero permits per minute never allows`() {
        var now = 0L
        val rl = RateLimiter(permitsPerMinute = 0.0, burstCapacity = 0, clock = { now })
        assertFalse(rl.tryAcquire("k"))
    }
}
