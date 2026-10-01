package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Production-hardening matrix for the auto-updater's security guarantees:
 *
 *  - authenticity: a release without a published SHA-256 is NEVER installed;
 *    a mismatching or malformed checksum rejects the package before staging,
 *  - extraction hardening: zip-slip, absolute/backslash/drive-letter paths,
 *    duplicate entries and unexpected files all abort staging,
 *  - package shape: only a complete engine (bin + lib + run-termux.sh) stages,
 *  - loop protection: staging the same version is bounded by the attempt
 *    budget, so stage → apply → rollback cycles cannot run forever.
 */
class EngineUpdaterSecurityTest {

    // ---------------- helpers ----------------

    private fun makeZip(dest: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                if (content.isNotEmpty()) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
    }

    /**
     * Minimal STORED zip writer that ALLOWS duplicate entry names (ZipOutputStream
     * refuses to create them). ZIP integers are LITTLE-endian.
     */
    private fun rawZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Long) {
            u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt())
        }
        val offsets = IntArray(entries.size)
        entries.forEachIndexed { i, (name, data) ->
            offsets[i] = out.size()
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            u32(0x04034b50)        // local file header signature
            u16(20)                // version needed
            u16(0)                 // flags
            u16(0)                 // method: STORED
            u16(0); u16(0)         // time/date
            u32(crc)
            u32(data.size.toLong())
            u32(data.size.toLong())
            u16(nameBytes.size)
            u16(0)                 // extra len
            out.write(nameBytes)
            out.write(data)
        }
        val cdStart = out.size()
        entries.forEachIndexed { i, (name, data) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value
            u32(0x02014b50)        // central directory signature
            u16(20); u16(20)
            u16(0); u16(0)
            u16(0); u16(0)
            u32(crc)
            u32(data.size.toLong())
            u32(data.size.toLong())
            u16(nameBytes.size)
            u16(0); u16(0)         // extra/comment lens
            u16(0); u16(0)         // disk/attrs
            u32(0)                 // external attrs
            u32(offsets[i].toLong())
            out.write(nameBytes)
        }
        val cdSize = out.size() - cdStart
        u32(0x06054b50)            // EOCD
        u16(0); u16(0)
        u16(entries.size); u16(entries.size)
        u32(cdSize.toLong())
        u32(cdStart.toLong())
        u16(0)
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves the update zip and its published checksum over local HTTP. */
    private fun serve(zipBytes: ByteArray, sha256Text: String): Pair<Int, AutoCloseable> {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/engine.zip") { call.respondBytes(zipBytes, ContentType.parse("application/zip")) }
                get("/engine.zip.sha256") { call.respondText(sha256Text, ContentType.Text.Plain) }
            }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        return port to AutoCloseable { server.stop(500, 1_000) }
    }

    private fun updater(stateDir: File) = EngineUpdater(
        client = HttpClient(CIO),
        stateDir = stateDir,
        releasesApiUrl = "http://127.0.0.1:1/unused",
        minZipBytes = 1,
        runningVersion = "1.7.0",
    )

    private fun goodZipBytes(): ByteArray {
        val zip = File.createTempFile("wearsic-good", ".zip")
        zip.deleteOnExit()
        makeZip(
            zip,
            mapOf(
                "wearsic-server/" to "",
                "wearsic-server/bin/wearsic-server" to "#!/bin/sh\necho engine\n",
                "wearsic-server/lib/wearsic-server-1.8.0.jar" to "jarbytes",
                "wearsic-server/run-termux.sh" to "#!/bin/sh\n",
            )
        )
        return zip.readBytes()
    }

    // ---------------- authenticity ----------------

    @Test
    fun `staging refuses a release without a published checksum`(@TempDir tmp: File) = runBlocking {
        val bytes = goodZipBytes()
        val (port, closer) = serve(bytes, sha256Text = "${sha256Hex(bytes)}  engine.zip\n")
        try {
            val u = updater(File(tmp, "state"))
            // No sha256Url on the RemoteUpdate: must be refused outright.
            assertNull(u.downloadAndStage(RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip")))
            assertFalse(u.hasStagedUpdate())
            assertTrue(u.lastCheckError!!.contains("checksum"), u.lastCheckError)
        } finally {
            closer.close()
        }
    }

    @Test
    fun `checksum mismatch rejects the package and stages nothing`(@TempDir tmp: File) = runBlocking {
        val bytes = goodZipBytes()
        val wrong = "0".repeat(64)
        val (port, closer) = serve(bytes, sha256Text = "$wrong  engine.zip\n")
        try {
            val u = updater(File(tmp, "state"))
            assertNull(
                u.downloadAndStage(
                    RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
                )
            )
            assertFalse(u.hasStagedUpdate())
            assertTrue(u.lastCheckError!!.contains("checksum mismatch"), u.lastCheckError)
            // The rejected download must not linger on disk.
            assertFalse(File(tmp, "state/update.zip.tmp").exists())
        } finally {
            closer.close()
        }
    }

    @Test
    fun `malformed checksum asset is rejected`(@TempDir tmp: File) = runBlocking {
        val bytes = goodZipBytes()
        val (port, closer) = serve(bytes, sha256Text = "this is not a hash at all\n")
        try {
            val u = updater(File(tmp, "state"))
            assertNull(
                u.downloadAndStage(
                    RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
                )
            )
            assertFalse(u.hasStagedUpdate())
            assertTrue(u.lastCheckError!!.contains("malformed"), u.lastCheckError)
        } finally {
            closer.close()
        }
    }

    @Test
    fun `matching checksum stages and records verified hash and attempt`(@TempDir tmp: File) = runBlocking {
        val bytes = goodZipBytes()
        val sha = sha256Hex(bytes)
        val (port, closer) = serve(bytes, sha256Text = "$sha  wearsic-server-termux-v1.8.0.zip\n")
        try {
            val u = updater(File(tmp, "state"))
            val staged = u.downloadAndStage(
                RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
            )
            assertEquals("1.8.0", staged)
            assertTrue(u.hasStagedUpdate())
            val state = u.stagedState()
            assertEquals(sha, state!!.sha256)
            assertEquals(1, state.updateAttempt)
            assertEquals(1, u.attemptsFor("1.8.0"))
        } finally {
            closer.close()
        }
    }

    @Test
    fun `repeated staging of one version is bounded by the attempt budget`(@TempDir tmp: File) = runBlocking {
        val bytes = goodZipBytes()
        val sha = sha256Hex(bytes)
        val (port, closer) = serve(bytes, sha256Text = "$sha  engine.zip\n")
        try {
            val u = updater(File(tmp, "state"))
            val remote = RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
            // MAX_UPDATE_ATTEMPTS successful stages are allowed…
            repeat(EngineUpdater.MAX_UPDATE_ATTEMPTS) { i ->
                assertEquals("1.8.0", u.downloadAndStage(remote), "attempt ${i + 1} should stage")
            }
            // …afterwards the loop protection refuses forever (manual update
            // required) — this is what stops stage → apply → rollback loops.
            assertNull(u.downloadAndStage(remote))
            assertTrue(u.lastCheckError!!.contains("loop protection"), u.lastCheckError)
            assertEquals(EngineUpdater.MAX_UPDATE_ATTEMPTS, u.attemptsFor("1.8.0"))
        } finally {
            closer.close()
        }
    }

    @Test
    fun `rollback record written by the supervisor is surfaced`(@TempDir tmp: File) {
        val stateDir = File(tmp, "state").apply { mkdirs() }
        File(stateDir, "rollback.json")
            .writeText("""{"version":"1.8.0","reason":"new-engine-failed-health-validation","atMillis":123}""")
        val u = updater(stateDir)
        val info = u.rollbackInfo()
        assertNotNull(info)
        assertEquals("1.8.0", info.version)
        assertEquals("new-engine-failed-health-validation", info.reason)
        assertEquals(123L, info.atMillis)
    }

    // ---------------- extraction hardening ----------------

    @Test
    fun `zip slip entry aborts extraction`(@TempDir tmp: File) {
        val zip = File(tmp, "slip.zip")
        makeZip(
            zip,
            mapOf(
                "wearsic-server/bin/wearsic-server" to "ok",
                "../../evil.sh" to "pwned",
            )
        )
        val err = runCatching { EngineUpdater.safeExtract(zip, File(tmp, "out")) }.exceptionOrNull()
        assertNotNull(err, "zip-slip entry must abort extraction")
    }

    @Test
    fun `absolute backslash and drive-letter entries are rejected`(@TempDir tmp: File) {
        for (name in listOf("/etc/passwd", "win\\evil.bat", "C:/windows/evil.dll")) {
            val zip = File(tmp, "bad-${name.hashCode()}.zip")
            makeZip(zip, mapOf("wearsic-server/bin/wearsic-server" to "ok", name to "x"))
            val err = runCatching { EngineUpdater.safeExtract(zip, File(tmp, "out-${name.hashCode()}")) }.exceptionOrNull()
            assertNotNull(err, "entry '$name' must abort extraction")
        }
    }

    @Test
    fun `duplicate zip entries are rejected`(@TempDir tmp: File) {
        val bytes = rawZipBytes(
            listOf(
                "wearsic-server/bin/wearsic-server" to "first".toByteArray(),
                "wearsic-server/bin/wearsic-server" to "second".toByteArray(),
            )
        )
        val zip = File(tmp, "dup.zip").apply { writeBytes(bytes) }
        val err = runCatching { EngineUpdater.safeExtract(zip, File(tmp, "out")) }.exceptionOrNull()
        assertNotNull(err, "duplicate entries must abort extraction")
    }

    @Test
    fun `unexpected top-level entries abort extraction`(@TempDir tmp: File) {
        val zip = File(tmp, "extra.zip")
        makeZip(
            zip,
            mapOf(
                "wearsic-server/bin/wearsic-server" to "ok",
                "wearsic-server/lib/a.jar" to "jar",
                "wearsic-server/run-termux.sh" to "sh",
                "wearsic-server/evil-mystery-file.sh" to "not part of a release",
            )
        )
        val err = runCatching { EngineUpdater.safeExtract(zip, File(tmp, "out")) }.exceptionOrNull()
        assertNotNull(err, "unexpected files must abort extraction")
    }

    // ---------------- package shape ----------------

    @Test
    fun `incomplete packages never stage`(@TempDir tmp: File) = runBlocking {
        // No run-termux.sh, and an empty lib/ — both must be refused even
        // though the zip itself is valid and correctly checksummed.
        val zip = File(tmp, "incomplete.zip")
        makeZip(
            zip,
            mapOf(
                "wearsic-server/" to "",
                "wearsic-server/bin/wearsic-server" to "engine",
                "wearsic-server/lib/" to "",
            )
        )
        val bytes = zip.readBytes()
        val (port, closer) = serve(bytes, sha256Text = "${sha256Hex(bytes)}  engine.zip\n")
        try {
            val u = updater(File(tmp, "state"))
            assertNull(
                u.downloadAndStage(
                    RemoteUpdate("1.8.0", "http://127.0.0.1:$port/engine.zip", "http://127.0.0.1:$port/engine.zip.sha256")
                )
            )
            assertFalse(u.hasStagedUpdate(), "an incomplete package must never reach the supervisor")
        } finally {
            closer.close()
        }
    }
}
