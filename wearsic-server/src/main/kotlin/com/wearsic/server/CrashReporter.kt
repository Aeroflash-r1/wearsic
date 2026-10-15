package com.wearsic.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Self-hosted crash capture. The JVM's default uncaught-exception handler
 * only prints to stderr — on a headless phone that scrolls away and the
 * failure is invisible forever. This records every uncaught throwable as a
 * small JSON file (cap [maxFiles], oldest pruned) so `wearsic doctor` and
 * `/health` can report that crashes happened, and the exact stack survives
 * restarts for diagnosis. No network, no third party, no PII beyond what
 * the stack itself contains (no API keys — those never appear in stacks).
 */
class CrashReporter(
    crashDir: File,
    private val maxFiles: Int = 20,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(CrashReporter::class.java)
    private val dir = crashDir

    @Serializable
    data class CrashInfo(
        val atMillis: Long,
        val thread: String,
        val type: String,
        val message: String?,
        val topFrames: List<String>,
    )

    private val json = Json { encodeDefaults = true }

    /**
     * Installs as the JVM default handler, CHAINING to the previous one so
     * normal crash behavior (non-zero exit, supervisor restart) is unchanged
     * — capture is purely additive.
     */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(thread, throwable) }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Exception) {
            }
        }
    }

    /** Records one crash; returns the file written (or null when disk fails). */
    fun record(thread: Thread, throwable: Throwable): File? {
        return try {
            dir.mkdirs()
            val info = CrashInfo(
                atMillis = clock(),
                thread = thread.name,
                type = throwable.javaClass.name,
                message = throwable.message?.take(500),
                topFrames = throwable.stackTrace.take(25).map { it.toString() },
            )
            val file = File(dir, "crash-${info.atMillis}.json")
            file.writeText(json.encodeToString(info))
            prune()
            logger.warn("Recorded crash {} in {}", file.name, dir)
            file
        } catch (e: Exception) {
            logger.warn("Could not record crash: {}", e.message)
            null
        }
    }

    fun crashCount(): Int = try {
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.size ?: 0
    } catch (_: Exception) {
        0
    }

    fun lastCrash(): CrashInfo? = try {
        dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.maxByOrNull { it.name }
            ?.readText()
            ?.let { json.decodeFromString<CrashInfo>(it) }
    } catch (_: Exception) {
        null
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedBy { it.name } ?: return
        if (files.size > maxFiles) {
            files.take(files.size - maxFiles).forEach { runCatching { it.delete() } }
        }
    }
}
