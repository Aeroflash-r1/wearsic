package com.wearsic.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Deduplicates concurrent suspend calls by key: the first caller computes,
 * simultaneous callers for the same key await the SAME [Deferred] result, and
 * the mapping is always removed afterwards (success, failure, or cancellation)
 * via try/finally — so the map cannot grow unboundedly over a long-running
 * server (the failure mode of a classic "key -> Mutex" map, which keeps one
 * entry per key the server has EVER seen).
 *
 * No lock is held while a computation runs, so unrelated keys never block
 * each other. Known trade-off: if the winning caller is cancelled, awaiting
 * losers see the cancellation too and their own callers retry naturally.
 *
 * V is intentionally unbounded (nullable allowed): a computation may decide
 * its outcome is "nothing" (e.g. a timed-out extraction), and joiners must
 * observe that same outcome instead of re-running the work.
 */
class SingleFlight<K : Any, V> {

    private val inFlight = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        // Fast path: join an in-flight computation without allocating anything.
        inFlight[key]?.let { return it.await() }

        // The computing coroutine must be a SUPERVISED child of the caller's
        // job: it inherits the caller's dispatcher and cancellation (so
        // cancelling the caller cancels the work), but its FAILURE must not
        // tear down the caller's scope — the failure is delivered to awaiting
        // callers via deferred.await() instead. The supervisor job is created
        // in COMPLETE state after the deferred finishes: it exists only to
        // decouple failure propagation while the computation runs, so no test
        // scheduler is left with dangling active children.
        val callerContext = currentCoroutineContext()
        val supervisor = SupervisorJob(callerContext[Job])
        val deferred = CoroutineScope(
            supervisor + callerContext.minusKey(Job)
        ).async(start = CoroutineStart.LAZY) { block() }
        deferred.invokeOnCompletion { supervisor.complete() }

        val winner = inFlight.putIfAbsent(key, deferred)
        if (winner != null) {
            // Lazy start is essential: a losing candidate must NEVER execute
            // extraction, even on a multi-threaded dispatcher.
            deferred.cancel()
            return winner.await()
        }

        try {
            return deferred.await()
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /** Number of operations currently in flight (exposed for tests). */
    val size: Int get() = inFlight.size

    /** True while a computation for [key] is in flight (exposed for tests). */
    fun contains(key: K): Boolean = inFlight[key]?.isActive == true
}
