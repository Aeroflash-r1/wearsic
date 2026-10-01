package com.wearsic.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Wires the self-healing layers together and runs their background loops:
 *
 *  - watches the [ExtractionHealthMeter]; on sustained failure calls the
 *    canary to decide "one bad video" vs "YouTube broke the engine",
 *  - when the engine is declared broken AND no staged update is pending,
 *    asks [EngineUpdater] for a newer release and stages it, then restarts
 *    the process so the supervisor can apply it,
 *  - regardless of health, checks GitHub for a newer engine release on a
 *    slower cadence (optional discovery channel).
 *
 * Auto-restart policy is deliberately conservative: the updater only pulls
 * the trigger when the canary CONFIRMS the engine is broken (not on flaky
 * networks), and it never updates while a staged update is already waiting
 * to be applied.
 */
class SelfHealOrchestrator(
    private val healthMeter: ExtractionHealthMeter,
    private val canary: ExtractionCanary,
    private val updater: EngineUpdateController,
    private val stateDir: java.io.File,
    private val autoUpdate: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Injectable for tests: the real action exits the process. */
    private val restartAction: () -> Unit = ::scheduleProcessExit,
) {
    companion object {
        /**
         * Consecutive failed extractions before the canary decides. Public
         * because /ready uses the SAME threshold to report "extractor not
         * ready" — one definition of "engine broken", not two.
         */
        const val FAILURE_TRIGGER = 6

        /** Minimum gap between engine-broken restart attempts. */
        private const val RETRY_AFTER_RESTART_MS = 10 * 60 * 1000L

        /**
         * Health-check cadence. The engine can break at ANY moment
         * (YouTube ships site changes continuously), so the watchdog
         * must look often — checking only every few hours would leave
         * the server silently dead for that long.
         */
        private const val HEALTH_CHECK_INTERVAL_MS = 60 * 1000L

        /** Periodic update discovery cadence (much slower than health). */
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        /**
         * Real restart: short grace period for in-flight responses, then exit
         * 0 so the supervisor re-launches and applies the staged update.
         */
        fun scheduleProcessExit() {
            try {
                Runtime.getRuntime().addShutdownHook(
                    Thread {
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                        }
                    }
                )
            } catch (_: Exception) {
            }
            Thread {
                try {
                    Thread.sleep(3_000)
                } catch (_: InterruptedException) {
                }
                exitProcess(0)
            }.apply { isDaemon = true }.start()
        }
    }

    @Volatile var lastCanaryHealthy: Boolean? = null
        private set
    @Volatile var lastEngineBrokenAtMillis: Long = 0
        private set

    private val restarting = AtomicReference<String?>(null)

    fun start() {
        scope.launch {
            // First check quickly after boot (catch a broken engine early),
            // then settle into the health cadence. Update DISCOVERY
            // still runs only every few hours (time-gated in tick()).
            delay(60_000)
            while (isActive) {
                tick()
                delay(HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    /** Last update-discovery run; touched only by the single loop coroutine. */
    private var lastUpdateCheckMillis: Long = 0L

    internal suspend fun tick() {
        // 1) Engine-broken path: sustained failures + failing canary.
        //    Runs on the FAST cadence so a broken engine is noticed
        //    within minutes, not hours.
        val broken = healthMeter.consecutiveFailures() >= FAILURE_TRIGGER
        if (broken) {
            val canaryHealthy = canary.maybeProbe(minIntervalMs = 5 * 60 * 1000L)
            lastCanaryHealthy = canaryHealthy
            if (!canaryHealthy) {
                onEngineBroken()
            }
        }

        // 2) Periodic discovery channel: cheap GitHub check, but only
        //    once per slow cadence even though tick() runs every minute.
        val now = System.currentTimeMillis()
        if (autoUpdate && !updater.hasStagedUpdate()
            && now - lastUpdateCheckMillis >= UPDATE_CHECK_INTERVAL_MS
        ) {
            lastUpdateCheckMillis = now
            updater.checkForUpdate()?.let { remote ->
                updater.downloadAndStage(remote)
            }
        }
    }

    private suspend fun onEngineBroken() {
        val now = System.currentTimeMillis()
        if (now - lastEngineBrokenAtMillis < RETRY_AFTER_RESTART_MS) return
        lastEngineBrokenAtMillis = now

        if (updater.hasStagedUpdate()) {
            println("[selfheal] Engine broken and an update is ALREADY staged — restarting to apply it")
            restartSelf()
            return
        }

        if (!autoUpdate) {
            println(
                "[selfheal] Engine appears BROKEN (canary failed). " +
                    "Auto-update is disabled (WEARSIC_AUTO_UPDATE=0) — " +
                    "download the latest wearsic-server-termux zip and restart to fix."
            )
            return
        }

        println("[selfheal] Engine appears BROKEN — searching for a newer engine release...")
        val remote = updater.checkForUpdate()
        if (remote == null) {
            println(
                "[selfheal] No newer release found; already on v${ServerVersion.VERSION}. " +
                    "If YouTube just changed something, a fix needs a new release upstream. " +
                    "A YouTube cookie (POST /api/config/youtube-cookie) can also bypass bot-wall failures."
            )
            return
        }
        if (updater.downloadAndStage(remote) != null) {
            restartSelf()
        }
    }

    /**
     * Graceful exit so run-termux.sh re-launches a fresh JVM that applies the
     * staged update. Guarded against double-fires.
     */
    private fun restartSelf() {
        if (!restarting.compareAndSet(null, "restart")) return
        println("[selfheal] Restarting in 3s to apply the updated engine (supervisor will swap it in)...")
        restartAction()
    }
}
