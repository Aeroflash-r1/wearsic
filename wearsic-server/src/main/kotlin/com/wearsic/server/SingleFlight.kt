package com.wearsic.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared computation with reference-counted interest.
 *
 * A cancelled caller no longer cancels work that another caller still needs.
 * When the last interested caller leaves, the shared computation is cancelled.
 * This is important for stream extraction because foreground playback may join
 * a background prefetch for the same video.
 */
class SingleFlight<K : Any, V> {

    private data class Entry<V>(
        val deferred: Deferred<V>,
        val interests: AtomicInteger,
    )

    private val inFlight = ConcurrentHashMap<K, Entry<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val callerContext = currentCoroutineContext()
        val existing = inFlight[key]

        val entry = if (existing != null) {
            existing.interests.incrementAndGet()
            existing
        } else {
            val supervisor = SupervisorJob()
            val deferred = CoroutineScope(
                supervisor + callerContext.minusKey(Job)
            ).async(start = CoroutineStart.LAZY) {
                try {
                    block()
                } finally {
                    supervisor.complete()
                }
            }
            val candidate = Entry(deferred, AtomicInteger(1))
            val winner = inFlight.putIfAbsent(key, candidate)
            if (winner != null) {
                deferred.cancel()
                winner.interests.incrementAndGet()
                winner
            } else {
                candidate
            }
        }

        entry.deferred.start()

        try {
            return entry.deferred.await()
        } finally {
            if (entry.interests.decrementAndGet() <= 0) {
                inFlight.remove(key, entry)
                entry.deferred.cancel()
            }
        }
    }

    val size: Int get() = inFlight.size

    fun contains(key: K): Boolean = inFlight[key]?.deferred?.isActive == true

    fun interests(key: K): Int = inFlight[key]?.interests?.get() ?: 0
}
