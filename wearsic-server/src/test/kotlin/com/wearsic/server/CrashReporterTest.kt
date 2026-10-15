package com.wearsic.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CrashReporterTest {

    @TempDir
    lateinit var tmp: Path

    private fun reporter(maxFiles: Int = 20): CrashReporter =
        CrashReporter(File(tmp.toFile(), "crashes"), maxFiles = maxFiles)

    @Test
    fun `empty dir reports zero crashes`() {
        val r = reporter()
        assertEquals(0, r.crashCount())
        assertNull(r.lastCrash())
    }

    @Test
    fun `record writes a parseable file and updates count`() {
        val r = reporter()
        val file = r.record(Thread.currentThread(), RuntimeException("boom-test"))
        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertEquals(1, r.crashCount())
        val last = r.lastCrash()
        assertNotNull(last)
        assertEquals("java.lang.RuntimeException", last!!.type)
        assertEquals("boom-test", last.message)
        assertEquals(Thread.currentThread().name, last.thread)
        assertTrue(last.topFrames.isNotEmpty())
    }

    @Test
    fun `prune keeps only the newest maxFiles`() {
        var clock = 1000L
        val r = CrashReporter(File(tmp.toFile(), "crashes"), maxFiles = 3) { clock }
        repeat(5) {
            clock += 1000L
            r.record(Thread.currentThread(), IllegalStateException("n$it"))
        }
        assertEquals(3, r.crashCount())
        assertEquals("n4", r.lastCrash()?.message)
    }

    @Test
    fun `corrupt files are ignored, not fatal`() {
        val dir = File(tmp.toFile(), "crashes").apply { mkdirs() }
        File(dir, "crash-abc.json").writeText("not json{{{")
        val r = CrashReporter(dir)
        assertEquals(1, r.crashCount())
        assertNull(r.lastCrash())
    }

    @Test
    fun `install chains to the previous handler`() {
        val seen = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { t, _ -> seen.add(t.name) }
        Thread.setDefaultUncaughtExceptionHandler(previous)
        try {
            reporter().install()
            val t = Thread.currentThread()
            Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(t, RuntimeException("chain"))
            assertEquals(listOf(t.name), seen)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
