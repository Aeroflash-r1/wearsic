package com.wearsic.server

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks extraction success/failure so the server can TELL when the engine
 * (NewPipeExtractor) is being broken by YouTube changes — the failure mode
 * where the process looks healthy but every search/extract dies.
 *
 * All counters are process-lifetime monotonic; [failureRate] reads the whole
 * window. Per-endpoint breakdowns are logged by the gateway on failures.
 */
class ExtractionHealthMeter {

    private val successes = AtomicInteger(0)
    private val failures = AtomicInteger(0)
    private val lastSuccessAt = AtomicLong(0)
    private val lastFailureAt = AtomicLong(0)
    private val lastErrorMessage = AtomicReference<String?>(null)
    private val consecutiveFailures = AtomicInteger(0)

    /** Called after ANY metadata/extractor operation completes. */
    fun recordSuccess() {
        successes.incrementAndGet()
        lastSuccessAt.set(System.currentTimeMillis())
        consecutiveFailures.set(0)
    }

    fun recordFailure(message: String?) {
        failures.incrementAndGet()
        lastFailureAt.set(System.currentTimeMillis())
        lastErrorMessage.set(message?.take(200))
        consecutiveFailures.incrementAndGet()
    }

    val successCount: Int get() = successes.get()
    val failureCount: Int get() = failures.get()
    val lastSuccessAtMillis: Long get() = lastSuccessAt.get()
    val lastFailureAtMillis: Long get() = lastFailureAt.get()
    val lastError: String? get() = lastErrorMessage.get()

    /**
     * Rolling "is the engine sick" signal: failures in the recent window as a
     * percentage of all attempts, and the current consecutive-failure streak.
     */
    fun failureRatePercent(): Int {
        val s = successes.get()
        val f = failures.get()
        val total = s + f
        if (total == 0) return 0
        return (f * 100 / total)
    }

    fun consecutiveFailures(): Int = consecutiveFailures.get()
}

/**
 * Canary watchdog: after repeated extraction failures, probe a video that is
 * effectively permanent on YouTube ("Me at the zoo", jNQXAC9IVRw — first
 * upload ever, never removed). Two very different situations produce the same
 * user-visible symptom ("nothing plays"):
 *
 *  - one dead video / bad network  -> canary EXTRACTS fine -> engine healthy
 *  - YouTube changed their site and broke NewPipeExtractor (or the server IP
 *    got bot-walled) -> canary fails too -> ENGINE IS BROKEN
 *
 * The result is surfaced in /health (`extraction.canaryHealthy`), logged with
 * actionable remediation, and is the trigger signal for [EngineUpdater].
 */
class ExtractionCanary(
    private val probe: suspend () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Short lock-free state holder; watchdog runs on a single coroutine. */
    @Volatile var lastProbeHealthy: Boolean? = null
        private set
    @Volatile var lastProbeAtMillis: Long = 0
        private set

    /** Serializes probes: concurrent callers must not double-probe. */
    private val probeMutex = Mutex()

    /**
     * Runs the canary at most once per [minIntervalMs]. Returns true when the
     * engine is healthy (or no probe ran — cached result stands).
     */
    suspend fun maybeProbe(minIntervalMs: Long): Boolean = probeMutex.withLock {
        val now = clock()
        val cached = lastProbeHealthy
        if (cached != null && now - lastProbeAtMillis < minIntervalMs) return cached
        val healthy = try {
            probe()
        } catch (_: Exception) {
            false
        }
        lastProbeHealthy = healthy
        lastProbeAtMillis = now
        healthy
    }
}
