package com.example

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny, dependency-free startup journal + crash hook.
 *
 * Device-only freezes/crashes during cold start (the "stuck on the opening
 * screen, then the app closes itself" reports) cannot be reproduced by
 * Robolectric — the media-session/binder layer is stubbed out in tests. This
 * tracker gives the app ground truth:
 *
 *  - every process appends phase lines to `filesDir/wearsic_diagnostics/startup.log`
 *    (app-start, ui-ready, controller-connected, startup-reconcile-done, ...);
 *  - an uncaught-exception hook (chained to the platform handler) appends the
 *    crashing thread + full stack to `crash.log`;
 *  - if the PREVIOUS process logged app-start but never reached ui-ready, it
 *    died during cold start, so this process boots in RECOVERY MODE: the eager
 *    media-session connect is deferred until the first playback action and the
 *    UI can never again be held hostage by a wedged session.
 *
 * All state lives in files under the app's own filesDir — no permissions, no
 * new dependencies, idempotent, safe to run in tests (Robolectric sandboxes
 * filesDir per test).
 */
object StartupDiagnostics {

    private const val MAX_JOURNAL_LINES = 80

    /** How long the ANR watchdog stays armed after process start. */
    private const val WATCHDOG_WINDOW_MS = 60_000L

    /**
     * All journal writes go through this single daemon thread. The journal is
     * written from the MAIN thread during cold start (Application.onCreate,
     * Activity.onCreate, ViewModel init, first frame), so doing the disk I/O
     * inline used to block the very code path the splash screen waits on —
     * one of the direct causes of "stuck on the opening screen".
     */
    private val writeExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Wearsic-Diagnostics").apply { isDaemon = true }
        }

    @Volatile
    private var appStartRealtimeMs = 0L

    /**
     * True when the previous process died before its first frame rendered.
     * Set synchronously in [onApplicationCreate], i.e. before any ViewModel is
     * constructed, so startup code can read it from the main thread safely.
     */
    @Volatile
    var recoveryMode: Boolean = false
        private set

    /** Called first thing from [WearsicApp.onCreate]. */
    fun onApplicationCreate(context: Context) {
        appStartRealtimeMs = System.currentTimeMillis()
        val previous = readJournal(context)
        // A previous process that logged app-start but never ui-ready died
        // during cold start (crash or ANR kill before the first frame).
        recoveryMode = previous.any { it.startsWith("app-start") } &&
            previous.none { it.startsWith("ui-ready") }
        installCrashHandler(context)
        append(context, "app-start pid=${android.os.Process.myPid()}")
        startMainThreadWatchdog(context)
    }

    /**
     * Called once the first composition of the UI has been committed.
     *
     * Written SYNCHRONOUSLY (it happens exactly once, and it is the single
     * line recovery-mode detection depends on): if the process were killed
     * immediately after the first frame with this queued, the next launch
     * would wrongly believe the previous start died.
     */
    fun markUiReady(context: Context) {
        append(context, "ui-ready +${elapsedMs()}ms", synchronous = true)
    }

    /** Appends a phase marker (thread-safe, cheap). */
    fun log(context: Context, phase: String) {
        append(context, "$phase +${elapsedMs()}ms")
    }

    /**
     * One-line summary for the Settings screen (read on a background thread).
     * When the main thread was caught stuck (ANR) or a crash was logged, the
     * FIRST app frame of the captured stack is surfaced — that is the exact
     * place the cold start (or wake) froze, no adb required.
     */
    fun lastStartupSummary(context: Context): String {
        val lines = readJournal(context)
        if (lines.isEmpty()) return ""
        val last = lines.lastOrNull() ?: ""
        val crashLog = File(diagnosticsDir(context), "crash.log")
            .takeIf { it.isFile && it.length() > 0L }
            ?.readText()
            .orEmpty()
        val isAnr = crashLog.contains("ANR-WATCHDOG")
        val head = crashLog.lineSequence()
            .firstOrNull { it.startsWith("at com.example.") }
            ?.removePrefix("at ")
            ?.substringBefore("(")
            ?.take(120)
            ?: crashLog.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("at ") }?.take(120)
            ?: ""
        // Healthy starts show NOTHING — the line exists to diagnose failures,
        // not to decorate every Settings screen. It appears only when the
        // previous startup died (recovery mode) or a crash/ANR was captured.
        if (!recoveryMode && head.isBlank()) return ""
        return buildString {
            if (recoveryMode) append("Recovered: previous startup crashed. ")
            append("Last: $last")
            if (head.isNotBlank()) {
                append(if (isAnr) " | Main thread stuck in: " else " | Crash in: ")
                append(head)
            }
        }
    }

    private fun elapsedMs(): Long =
        if (appStartRealtimeMs == 0L) 0L else System.currentTimeMillis() - appStartRealtimeMs

    private fun appendCrashBlock(context: Context, block: String) {
        try {
            synchronized(this) {
                val dir = diagnosticsDir(context)
                val file = File(dir, "crash.log")
                file.appendText(block)
                if (file.length() > 64 * 1024) {
                    val text = file.readText()
                    file.writeText(text.takeLast(32 * 1024))
                }
            }
        } catch (_: Exception) {
        }
    }

    @Volatile
    private var watchdogStarted = false

    private fun diagnosticsDir(context: Context): File =
        File(context.filesDir, "wearsic_diagnostics").apply { if (!exists()) mkdirs() }

    /** Non-blocking by default: queues [line] for the diagnostics thread. */
    private fun append(context: Context, line: String, synchronous: Boolean = false) {
        val appContext = context.applicationContext
        if (synchronous) {
            writeLine(appContext, line)
            return
        }
        try {
            writeExecutor.execute { writeLine(appContext, line) }
        } catch (_: Exception) {
            // Diagnostics must never take the app down.
        }
    }

    /**
     * Append-only write (never rewrites the file in the common case) so the
     * cost stays O(1) instead of O(journal). Compacts only when it grows past
     * a small bound.
     */
    private fun writeLine(context: Context, line: String) {
        try {
            synchronized(this) {
                val file = File(diagnosticsDir(context), "startup.log")
                file.appendText(line + "\n")
                if (file.length() > 16 * 1024) {
                    val kept = file.readText()
                        .lines()
                        .filter { it.isNotBlank() }
                        .takeLast(MAX_JOURNAL_LINES)
                        .joinToString("\n")
                    file.writeText(kept + "\n")
                }
            }
        } catch (_: Exception) {
            // Diagnostics must never take the app down.
        }
    }

    private fun readJournal(context: Context): List<String> = try {
        val file = File(diagnosticsDir(context), "startup.log")
        if (file.isFile) file.readLines() else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Background main-thread heartbeater: every 2s it posts a no-op to the
     * main looper and waits up to 6s. If the main thread fails to answer, it
     * is stuck (ANR in progress) and its full stack trace is appended to
     * crash.log — the device-only freezes ("stuck on the opening screen, app
     * closes itself") leave zero in-process trace otherwise, because an ANR
     * does not throw an exception. Daemon thread; skipped under Robolectric.
     */
    private fun startMainThreadWatchdog(context: Context) {
        if (watchdogStarted) return
        if (android.os.Build.FINGERPRINT == "robolectric" ||
            android.os.Build.HARDWARE == "robolectric"
        ) {
            return
        }
        watchdogStarted = true
        val appContext = context.applicationContext
        val mainLooper = android.os.Looper.getMainLooper()
        val mainHandler = android.os.Handler(mainLooper)
        val thread = Thread({
            // Bounded: the watchdog only needs to cover cold start. Leaving it
            // pinging the main looper forever woke the CPU every 2s for the
            // whole session — measurable battery drain for no benefit.
            val deadline = System.currentTimeMillis() + WATCHDOG_WINDOW_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(3000L)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    mainHandler.post { latch.countDown() }
                    if (!latch.await(6L, java.util.concurrent.TimeUnit.SECONDS)) {
                        val sw = StringWriter()
                        sw.append("=== ANR-WATCHDOG ")
                            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                            .append(" main thread blocked >6s ===\n")
                        for (frame in mainLooper.thread.stackTrace) {
                            sw.append("    at ").append(frame.toString()).append('\n')
                        }
                        appendCrashBlock(appContext, sw.toString())
                    }
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                    // Watchdog must never take the app down.
                }
            }
        }, "Wearsic-ANR-Watchdog")
        thread.isDaemon = true
        thread.start()
    }

    private fun installCrashHandler(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                synchronized(this) {
                    val dir = diagnosticsDir(context)
                    val file = File(dir, "crash.log")
                    file.appendText(
                        "=== $stamp [${thread.name}] ${throwable.javaClass.name}: " +
                            "${throwable.message}\n$sw\n"
                    )
                    if (file.length() > 64 * 1024) {
                        val text = file.readText()
                        file.writeText(text.takeLast(32 * 1024))
                    }
                }
            } catch (_: Exception) {
                // Never interfere with the real crash path.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
