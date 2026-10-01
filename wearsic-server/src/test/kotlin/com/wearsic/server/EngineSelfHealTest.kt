package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EngineSelfHealTest {

    // ---------------- Version comparison ----------------

    @Test
    fun `version comparison is strict semver-ish`() {
        assertTrue(EngineUpdater.isNewerVersion("1.7.0", "1.6.0"))
        assertTrue(EngineUpdater.isNewerVersion("1.10.0", "1.9.9"))
        assertTrue(EngineUpdater.isNewerVersion("2.0.0", "1.99.99"))
        assertTrue(EngineUpdater.isNewerVersion("v1.7.0", "1.6.0"))
        assertFalse(EngineUpdater.isNewerVersion("1.6.0", "1.6.0"))
        assertFalse(EngineUpdater.isNewerVersion("1.5.9", "1.6.0"))
        assertFalse(EngineUpdater.isNewerVersion("1.6", "1.6.0"))
    }

    // ---------------- Health meter ----------------

    @Test
    fun `health meter tracks success failure and streaks`() {
        val meter = ExtractionHealthMeter()
        assertEquals(0, meter.failureRatePercent())

        meter.recordSuccess()
        meter.recordSuccess()
        assertEquals(0, meter.failureRatePercent())
        assertEquals(0, meter.consecutiveFailures())

        meter.recordFailure("boom")
        meter.recordFailure("boom2")
        assertEquals(2, meter.failureCount)
        assertEquals(2, meter.consecutiveFailures())
        assertEquals("boom2", meter.lastError)
        // 2 failures / 2 attempts (success rate 0% in the whole window).
        assertEquals(50, meter.failureRatePercent())

        meter.recordSuccess()
        assertEquals(0, meter.consecutiveFailures())
        assertTrue(meter.lastSuccessAtMillis > 0)
    }

    // ---------------- Canary ----------------

    @Test
    fun `canary caches result within interval`() = runTest {
        var now = 1_000L
        var healthy = true
        var calls = 0
        val canary = ExtractionCanary(probe = { calls++; healthy }, clock = { now })

        assertTrue(canary.maybeProbe(minIntervalMs = 1_000))
        assertEquals(1, calls)

        now = 1_500L
        healthy = false
        // Cached — no new probe inside the interval.
        assertTrue(canary.maybeProbe(minIntervalMs = 1_000))
        assertEquals(1, calls)

        now = 2_500L
        // Interval elapsed -> re-probe, now unhealthy.
        assertFalse(canary.maybeProbe(minIntervalMs = 1_000))
        assertEquals(2, calls)
        assertEquals(false, canary.lastProbeHealthy)
    }

    @Test
    fun `canary treats thrown probe as unhealthy`() = runTest {
        val canary = ExtractionCanary(probe = { error("engine exploded") })
        assertFalse(canary.maybeProbe(minIntervalMs = 0))
    }

    // ---------------- Zip integrity + zip-slip ----------------

    private fun makeZip(dest: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                if (content.isNotEmpty()) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `zip verification accepts a complete archive`(@TempDir tmp: File) {
        val zip = File(tmp, "ok.zip")
        makeZip(zip, mapOf("wearsic-server/bin/wearsic-server" to "x", "wearsic-server/lib/a.jar" to "y"))
        EngineUpdater.verifyZipIntegrity(zip) // must not throw
    }

    @Test
    fun `zip verification rejects a truncated archive`(@TempDir tmp: File) {
        val zip = File(tmp, "cut.zip")
        makeZip(zip, mapOf("wearsic-server/bin/wearsic-server" to "x", "wearsic-server/lib/a.jar" to "y"))
        val full = zip.readBytes()
        // Chop the tail: kills the central directory / last entries.
        zip.writeBytes(full.copyOfRange(0, full.size - 40))
        val err = runCatching { EngineUpdater.verifyZipIntegrity(zip) }.exceptionOrNull()
        assertNotNull(err, "truncated zip must fail verification")
    }

    @Test
    fun `zip verification rejects a non-zip file`(@TempDir tmp: File) {
        val junk = File(tmp, "junk.zip")
        junk.writeBytes(ByteArray(2048) { (it % 7).toByte() })
        assertNotNull(runCatching { EngineUpdater.verifyZipIntegrity(junk) }.exceptionOrNull())
    }

    @Test
    fun `safeExtract extracts package and reports root`(@TempDir tmp: File) {
        val zip = File(tmp, "pkg.zip")
        makeZip(
            zip,
            mapOf(
                "wearsic-server/bin/wearsic-server" to "script",
                "wearsic-server/lib/engine.jar" to "jar",
                "wearsic-server/run-termux.sh" to "sh",
            )
        )
        val dest = File(tmp, "out")
        val root = EngineUpdater.safeExtract(zip, dest)
        assertEquals("wearsic-server", root)
        assertTrue(File(dest, "wearsic-server/bin/wearsic-server").isFile)
        assertTrue(File(dest, "wearsic-server/lib/engine.jar").isFile)
    }

    // ---------------- Staging pipeline (real flow, local HTTP) ----------------

    /** Boots a tiny local HTTP server; returns (port, closer). */
    private fun serve(
        releasesJson: String? = null,
        zipBytes: ByteArray? = null,
        sha256Text: String? = null,
    ): Pair<Int, AutoCloseable> {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/releases") { call.respondText(releasesJson!!, ContentType.Application.Json) }
                get("/engine.zip") { call.respondBytes(zipBytes!!, ContentType.parse("application/zip")) }
                get("/engine.zip.sha256") { call.respondText(sha256Text!!, ContentType.Text.Plain) }
            }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        return port to AutoCloseable { server.stop(500, 1_000) }
    }

    @Test
    fun `checkForUpdate picks the newest newer release`(@TempDir tmp: File) = runTest {
        // Releases now carry their published .zip.sha256 checksum assets —
        // without one, auto-update refuses to even offer the release.
        val releasesJson = """
        [
          {"tag_name":"v1.6.0","assets":[{"name":"wearsic-server-termux-v1.6.0.zip","browser_download_url":"http://x/1.6.0.zip"},{"name":"wearsic-server-termux-v1.6.0.zip.sha256","browser_download_url":"http://x/1.6.0.zip.sha256"}]},
          {"tag_name":"v1.8.1","assets":[{"name":"wearsic-server-termux-v1.8.1.zip","browser_download_url":"http://x/1.8.1.zip"},{"name":"wearsic-server-termux-v1.8.1.zip.sha256","browser_download_url":"http://x/1.8.1.zip.sha256"}]},
          {"tag_name":"v1.9.0","assets":[{"name":"Wearsic-v1.9.0.apk","browser_download_url":"http://x/nope"}]}
        ]
        """.trimIndent()
        val (port, closer) = serve(releasesJson = releasesJson)
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = tmp,
                releasesApiUrl = "http://127.0.0.1:$port/releases",
                minZipBytes = 1,
                runningVersion = "1.7.0",
            )
            val update = updater.checkForUpdate()
            assertNotNull(update)
            assertEquals("1.8.1", update!!.version)
            assertEquals("http://x/1.8.1.zip", update.zipUrl)
            assertEquals("http://x/1.8.1.zip.sha256", update.sha256Url)
            assertEquals("1.8.1", updater.latestKnownVersion)
            assertNull(updater.lastCheckError)
        } finally {
            closer.close()
        }
    }

    @Test
    fun `checkForUpdate returns null when already current`(@TempDir tmp: File) = runTest {
        val releasesJson = """
        [{"tag_name":"v1.7.0","assets":[{"name":"wearsic-server-termux-v1.7.0.zip","browser_download_url":"http://x/1.7.0.zip"}]}]
        """.trimIndent()
        val (port, closer) = serve(releasesJson = releasesJson)
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = tmp,
                releasesApiUrl = "http://127.0.0.1:$port/releases",
                minZipBytes = 1,
                runningVersion = "1.7.0",
            )
            assertNull(updater.checkForUpdate())
        } finally {
            closer.close()
        }
    }

    @Test
    fun `downloadAndStage rejects corrupt zips and stages good ones`(@TempDir tmp: File) = runTest {
        // Build a REAL release-shaped zip served over local HTTP.
        val goodZip = File(tmp, "good.zip")
        ZipOutputStream(FileOutputStream(goodZip)).use { zos ->
            zos.putNextEntry(ZipEntry("wearsic-server/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("wearsic-server/bin/wearsic-server"))
            zos.write("#!/bin/sh\necho engine\n".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("wearsic-server/lib/wearsic-server-1.8.0.jar"))
            zos.write("jarbytes".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("wearsic-server/run-termux.sh"))
            zos.write("#!/bin/sh\n".toByteArray())
            zos.closeEntry()
        }
        val goodBytes = goodZip.readBytes()
        val goodSha = EngineUpdater.sha256Of(goodZip)
        // Corrupt variant: chop the tail (central directory gone).
        val badBytes = goodBytes.copyOfRange(0, goodBytes.size - 60)
        val badZip = File(tmp, "bad.zip").apply { writeBytes(badBytes) }
        val badSha = EngineUpdater.sha256Of(badZip)

        val (port, closer) = serve(zipBytes = goodBytes, sha256Text = "$goodSha  good.zip")
        // Second server: the corrupt payload with ITS OWN (valid) checksum —
        // so this exercises the structural verification, not the checksum.
        val (port2, closer2) = serve(zipBytes = badBytes, sha256Text = "$badSha  bad.zip")
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = File(tmp, "state"),
                releasesApiUrl = "http://127.0.0.1:$port/unused",
                minZipBytes = 10,
                runningVersion = "1.7.0",
            )

            // Corrupt download must be rejected and stage nothing.
            assertNull(
                updater.downloadAndStage(
                    RemoteUpdate("1.8.0", "http://127.0.0.1:$port2/engine.zip", "http://127.0.0.1:$port2/engine.zip.sha256")
                )
            )
            assertFalse(updater.hasStagedUpdate())
            assertNotNull(updater.lastCheckError)

            // Good download stages and writes the supervisor state file.
            val staged = updater.downloadAndStage(
                RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
            )
            assertEquals("1.8.0", staged)
            assertTrue(updater.hasStagedUpdate())
            val state = updater.stagedState()
            assertEquals("1.8.0", state!!.version)
            assertEquals("1.7.0", state.previousVersion)
            assertEquals("staged", state.status)
            assertEquals(goodSha, state.sha256)
            assertTrue(File(updater.stageDir, "wearsic-server/bin/wearsic-server").isFile)
        } finally {
            closer.close()
            closer2.close()
        }
    }

    // ---------------- Orchestrator ----------------

    private class FakeController(
        var staged: Boolean = false,
        var remote: RemoteUpdate? = null,
        var stageResult: String? = null,
    ) : EngineUpdateController {
        var checkCalls = 0
        var stageCalls = 0
        override fun hasStagedUpdate() = staged
        override suspend fun checkForUpdate(): RemoteUpdate? { checkCalls++; return remote }
        override suspend fun downloadAndStage(update: RemoteUpdate): String? {
            stageCalls++
            // Mirror the real controller: a successful stage becomes visible
            // to hasStagedUpdate() (that's how the discovery channel stops).
            if (stageResult != null) staged = true
            return stageResult
        }
    }

    private fun orchestrator(
        meter: ExtractionHealthMeter,
        canary: ExtractionCanary,
        controller: EngineUpdateController,
        autoUpdate: Boolean = true,
    ) = SelfHealOrchestrator(
        healthMeter = meter,
        canary = canary,
        updater = controller,
        stateDir = File("/tmp/wearsic-test-unused"),
        autoUpdate = autoUpdate,
        scope = CoroutineScope(CoroutineName("test-unused")),
        restartAction = {}, // tests must never exit the JVM
    )

    @Test
    fun `orchestrator does not stage when canary says engine is healthy`() = runTest {
        val meter = ExtractionHealthMeter()
        val canary = ExtractionCanary(probe = { true }) // engine actually fine
        val controller = FakeController()

        // 6 consecutive failures, but canary passes -> one dead video, NOT a
        // broken engine: no restart, and the discovery check finds nothing
        // newer anyway (remote = null).
        repeat(6) { meter.recordFailure("per-video") }
        orchestrator(meter, canary, controller).tick()
        assertEquals(0, controller.stageCalls)
        assertTrue(canary.lastProbeHealthy == true)
    }

    @Test
    fun `orchestrator stages update on confirmed engine breakage`() = runTest {
        val meter = ExtractionHealthMeter()
        val canary = ExtractionCanary(probe = { false }) // canary fails too
        val controller = FakeController(remote = RemoteUpdate("1.8.0", "http://x/zip"), stageResult = "1.8.0")

        repeat(6) { meter.recordFailure("search: everything dies") }
        orchestrator(meter, canary, controller).tick()
        // checkForUpdate is called once by onEngineBroken; the discovery
        // channel is skipped because an update was just staged.
        assertEquals(1, controller.checkCalls)
        assertEquals(1, controller.stageCalls)
    }

    @Test
    fun `orchestrator skips update when one is already staged`() = runTest {
        val meter = ExtractionHealthMeter()
        val canary = ExtractionCanary(probe = { false })
        val controller = FakeController(staged = true)

        repeat(6) { meter.recordFailure("search: everything dies") }
        orchestrator(meter, canary, controller).tick()
        // Restart path taken; the discovery check never runs.
        assertEquals(0, controller.checkCalls)
        assertEquals(0, controller.stageCalls)
    }

    @Test
    fun `orchestrator discovery channel checks when healthy and nothing staged`() = runTest {
        val meter = ExtractionHealthMeter()
        val canary = ExtractionCanary(probe = { true })
        val controller = FakeController(remote = RemoteUpdate("1.8.0", "http://x/zip"), stageResult = "1.8.0")

        meter.recordSuccess()
        orchestrator(meter, canary, controller).tick()
        // Healthy path still runs the periodic discovery check.
        assertEquals(1, controller.checkCalls)
        assertEquals(1, controller.stageCalls)
    }
}
