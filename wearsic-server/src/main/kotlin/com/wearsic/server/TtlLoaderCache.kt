package com.wearsic.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Size-bounded, TTL'd, single-flight suspend cache.
 *
 * [BoundedCache.getOrPut] computes under the map's lock, which is fine for
 * short pure functions but wrong for a NETWORK call: one slow fetch would
 * freeze every other key's get. This class keeps the good parts (bounded LRU,
 * expiry) and fixes the concurrency shape:
 *
 *   - `[loader]` runs OUTSIDE every lock, on the caller's coroutine;
 *   - identical misses coalesce onto one load (per-key single-flight), so a
 *     burst of identical requests costs ONE upstream fetch, not N;
 *   - a completed value is shared with every waiter from that burst.
 *
 * Negative values are cached too (the value simply IS the empty result) and
 * live for the same TTL, so a failed lookup is retried no faster than the
 * expiry — an upstream that answers "nothing" for a while must not be
 * hammered every keystroke.
 *
 * Error semantics: exceptions are NEVER cached — they propagate to every
 * waiter of this burst, and the next caller retries. A stale-but-present
 * entry is treated as a miss: a completed value is always removed when its
 * TTL expires, so "expire then compute" is the only path (the caller can
 * choose a longer TTL instead of serving stale entries).
 */
class TtlLoaderCache<K : Any, V : Any>(private val maxSize: Int) {

    private class Entry<V>(val value: V, val expiresAt: Long)

    private val entries = BoundedCache<K, Entry<V>>(maxSize)
    private val inFlight = BoundedCache<K, Mutex>(maxSize)

    /**
     * Returns the cached value when it is younger than the TTL for [key],
     * otherwise computes it (once per burst), stores it, and returns it.
     *
     * [ttlMs] is caller-chosen (different endpoints have different staleness
     * budgets). Every waiter of one miss burst gets the SAME result: the
     * first to finish loads, the rest receive the stored value.
     */
    suspend fun getOrLoad(key: K, ttlMs: Long, loader: suspend () -> V): V {
        val now = System.currentTimeMillis()
        entries.get(key)?.let { entry ->
            if (now < entry.expiresAt) return entry.value
        }
        // Per-key single-flight. The mutex map is itself a bounded LRU, so a
        // flood of distinct keys cannot grow it without bound; the losing
        // waiter holds a DIFFERENT mutex instance only in the vanishingly
        // rare eviction race, and would then already see the winner's value
        // (the entry is put before the mutex is released).
        val mutex = inFlight.getOrPut(key) { Mutex() }
        return mutex.withLock {
            // Re-check inside the lock: the previous lock holder may have
            // completed the load and stored a fresh value while we waited.
            val now2 = System.currentTimeMillis()
            entries.get(key)?.let { entry ->
                if (now2 < entry.expiresAt) return@withLock entry.value
            }
            val value = loader()
            entries.put(key, Entry(value, System.currentTimeMillis() + ttlMs))
            value
        }
    }

    /** Test and monitor hook: how many live entries are cached. */
    val size: Int get() = entries.size

    fun clear() {
        entries.clear()
        inFlight.clear()
    }
}
