package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream

/** A newer engine release discovered on GitHub. */
data class RemoteUpdate(val version: String, val zipUrl: String)

/**
 * What [SelfHealOrchestrator] needs from the update pipeline. An interface so
 * tests can fake the whole pipeline without network or filesystem.
 */
interface EngineUpdateController {
    fun hasStagedUpdate(): Boolean
    suspend fun checkForUpdate(): RemoteUpdate?
    suspend fun downloadAndStage(update: RemoteUpdate): String?
}

/**
 * Self-healing engine updater.
 *
 * YouTube changes their site/API regularly, which breaks the embedded
 * extraction engine (NewPipeExtractor) — the server process stays "healthy"
 * while every search/extract dies. The real fix is shipping a NEW build, so
 * this component:
 *
 *  1. checks this project's GitHub Releases for a newer
 *     `wearsic-server-termux-vX.Y.Z.zip`,
 *  2. downloads it to the state dir and verifies the zip is complete
 *     (central-directory walk — catches truncated downloads),
 *  3. extracts it into `state/staging/` with zip-slip protection,
 *  4. writes `state/update.json` for the supervisor.
 *
 * The JVM process never replaces its own running jar. Instead
 * [run-termux.sh] detects the staged update on exit/restart, swaps
 * `bin/` + `lib/` atomically (keeping the old version as `.bak` for
 * rollback), and boots the new engine. If the new build fails to become
 * healthy, the supervisor's existing crash handling plus the `.bak` copy
 * give a one-command rollback path.
 */
class EngineUpdater(
    private val client: HttpClient,
    private val stateDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Injectable for tests: a local server can stand in for GitHub. */
    private val releasesApiUrl: String = RELEASES_API,
    /** Injectable for tests: real packages are ~30MB, tests use tiny zips. */
    private val minZipBytes: Long = MIN_ZIP_BYTES,
    private val runningVersion: String = ServerVersion.VERSION,
) : EngineUpdateController {
    @Serializable
    private data class GithubAsset(val name: String? = null, val browser_download_url: String? = null)

    @Serializable
    private data class GithubRelease(val tag_name: String? = null, val assets: List<GithubAsset> = emptyList())

    /** Supervisor-facing state written to `state/update.json`. */
    @Serializable
    data class UpdateState(
        val status: String, // "staged"
        val version: String,
        val previousVersion: String? = null,
        val stagedAtMillis: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // Observability for /health.
    @Volatile var lastCheckAtMillis: Long = 0
        private set
    @Volatile var lastCheckError: String? = null
        private set
    @Volatile var latestKnownVersion: String? = null
        private set

    private val stateFile: File get() = File(stateDir, "update.json")
    val stageDir: File get() = File(stateDir, "staging")

    override fun hasStagedUpdate(): Boolean = readState()?.status == "staged"
    fun stagedState(): UpdateState? = readState()

    /**
     * Looks for a newer release. Returns [RemoteUpdate] when a strictly newer
     * version with a Termux ZIP asset exists, null otherwise (up to date, or
     * any error — which is logged into [lastCheckError] for /health).
     */
    override suspend fun checkForUpdate(): RemoteUpdate? = try {
        val body = client.get(releasesApiUrl) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "wearsic-server/$runningVersion")
        }.bodyAsText()
        val releases = json.decodeFromString<List<GithubRelease>>(body)
        val update = releases.asSequence()
            .mapNotNull { rel ->
                val tag = rel.tag_name ?: return@mapNotNull null
                val asset = rel.assets.firstOrNull { it.name?.startsWith(ZIP_ASSET_PREFIX) == true }
                val url = asset?.browser_download_url ?: return@mapNotNull null
                RemoteUpdate(version = tag.removePrefix("v"), zipUrl = url)
            }
            .firstOrNull { isNewerVersion(it.version, runningVersion) }

        lastCheckAtMillis = clock()
        lastCheckError = null
        latestKnownVersion = update?.version
            ?: releases.firstNotNullOfOrNull { it.tag_name?.removePrefix("v") }

        if (update != null) {
            println("[update] New engine available: v${update.version} (running v$runningVersion)")
        }
        update
    } catch (e: Exception) {
        lastCheckAtMillis = clock()
        lastCheckError = (e.message ?: e.javaClass.simpleName).take(160)
        null
    }

    /**
     * Downloads and stages the given release ZIP. Returns the staged version
     * on success, null on any failure (everything is cleaned up; the running
     * server is never touched).
     */
    override suspend fun downloadAndStage(update: RemoteUpdate): String? = try {
        stateDir.mkdirs()
        val tmpZip = File(stateDir, "update.zip.tmp")
        val finalZip = File(stateDir, "update.zip")

        // --- download (streamed, no size surprises) ---
        val bytes = client.prepareGet(update.zipUrl) {
            header(HttpHeaders.UserAgent, "wearsic-server/$runningVersion")
        }.execute { resp ->
            if (resp.status.value != 200) error("HTTP ${resp.status.value} downloading update")
            val channel = resp.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            tmpZip.outputStream().use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n == -1) break
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
            tmpZip.length()
        }
        if (bytes < minZipBytes) error("Downloaded zip too small ($bytes bytes)")

        // --- verify: a truncated download must NEVER replace the engine ---
        verifyZipIntegrity(tmpZip)

        // --- stage: wipe previous staging, extract safely ---
        stageDir.deleteRecursively()
        stageDir.mkdirs()
        val root = safeExtract(tmpZip, stageDir)
        val pkgDir = if (root != null) File(stageDir, root) else stageDir
        require(File(pkgDir, "bin/wearsic-server").isFile) {
            "Staged zip missing bin/wearsic-server"
        }

        // --- hand off to the supervisor ---
        val state = UpdateState(
            status = "staged",
            version = update.version,
            previousVersion = runningVersion,
            stagedAtMillis = clock(),
        )
        stateFile.writeText(json.encodeToString(state))
        finalZip.delete()
        tmpZip.delete()
        println("[update] Engine v${update.version} staged — supervisor will apply it on next restart")
        update.version
    } catch (e: Exception) {
        lastCheckError = ("stage failed: " + (e.message ?: e.javaClass.simpleName)).take(200)
        println("[update] ERROR staging engine update: ${e.message}")
        null
    }

    private fun readState(): UpdateState? = try {
        if (stateFile.isFile) json.decodeFromString<UpdateState>(stateFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Releases of this project, newest first. */
        private const val RELEASES_API =
            "https://api.github.com/repos/Aeroflash-r1/wearsic/releases?per_page=10"
        private const val ZIP_ASSET_PREFIX = "wearsic-server-termux-"
        private const val MIN_ZIP_BYTES = 1_000_000L // real packages are ~30MB

        /** True when [candidate] is strictly newer than [current] (semver-ish). */
        fun isNewerVersion(candidate: String, current: String): Boolean {
            fun parse(v: String): List<Int> =
                v.removePrefix("v").split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val a = parse(candidate)
            val b = parse(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /**
         * Verifies [zip] is a complete archive by walking its central
         * directory: EOCD present, every entry's header + data inside the
         * file. Throws [IllegalStateException] on any truncation/corruption.
         *
         * Streams through the file with [RandomAccessFile] instead of
         * loading it whole — real packages are ~30MB and the updater runs
         * on a phone, where a full in-memory copy is a pointless heap
         * spike for a one-shot check.
         */
        fun verifyZipIntegrity(zip: File) {
            java.io.RandomAccessFile(zip, "r").use { raf ->
                val size = raf.length()
                if (size < 22) error("zip: file too small to be a zip archive")

                // End Of Central Directory: scan the last 64KB+22 (bounded
                // window, never the whole archive) for its signature.
                val tailSize = minOf(size, 65_536L + 22L).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(size - tailSize)
                raf.readFully(tail)
                var eocdIndex = -1
                var i = tailSize - 22
                while (i >= 0) {
                    if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                        tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
                    ) {
                        eocdIndex = i
                        break
                    }
                    i--
                }
                if (eocdIndex < 0) error("zip: missing End Of Central Directory (truncated download?)")
                val eocd = size - tailSize + eocdIndex

                val scratch2 = ByteArray(2)
                fun u16(off: Long): Int {
                    raf.seek(off)
                    raf.readFully(scratch2)
                    return (scratch2[0].toInt() and 0xFF) or ((scratch2[1].toInt() and 0xFF) shl 8)
                }
                fun u32(off: Long): Long =
                    (u16(off).toLong() and 0xFFFF) or ((u16(off + 2).toLong() and 0xFFFF) shl 16)

                val entryCount = u16(eocd + 10)
                val cdSize = u32(eocd + 12)
                val cdOffset = u32(eocd + 16)
                if (cdOffset + cdSize > size) error("zip: central directory beyond end of file")

                var pos = cdOffset
                repeat(entryCount) {
                    if (pos + 46 > size || u32(pos) != 0x02014b50L) error("zip: corrupt central directory entry")
                    val compressedSize = u32(pos + 20)
                    val nameLen = u16(pos + 28).toLong()
                    val extraLen = u16(pos + 30).toLong()
                    val commentLen = u16(pos + 32).toLong()
                    val localOffset = u32(pos + 42)
                    val entryEnd = pos + 46 + nameLen + extraLen + commentLen
                    if (entryEnd > size) error("zip: central directory entry overruns file")

                    // Local header must exist with its data fully inside the file.
                    if (localOffset + 30 > size || u32(localOffset) != 0x04034b50L) {
                        error("zip: missing/corrupt local header (truncated download?)")
                    }
                    val localNameLen = u16(localOffset + 26).toLong()
                    val localExtraLen = u16(localOffset + 28).toLong()
                    val dataStart = localOffset + 30 + localNameLen + localExtraLen
                    if (dataStart + compressedSize > size) {
                        error("zip: entry data beyond end of file (truncated download?)")
                    }
                    pos = entryEnd
                }
                if (entryCount == 0) error("zip: archive has no entries")
            }
        }

        /**
         * Zip-slip-safe extraction: entries are resolved against [destDir] and
         * any path that escapes it aborts the extraction. Returns the root
         * folder name found inside the zip (packages extract as
         * `wearsic-server/...`), or null when the zip has files at its root.
         */
        fun safeExtract(zip: File, destDir: File): String? {
            destDir.mkdirs()
            val destCanon = destDir.canonicalPath + File.separator
            var rootFolder: String? = null
            ZipInputStream(BufferedInputStream(zip.inputStream().buffered())).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destCanon)) {
                        error("zip: entry escapes target dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        // Remember the package root ("wearsic-server/").
                        val top = entry.name.trimEnd('/').substringBefore('/')
                        if (entry.name.count { it == '/' } >= 1 && rootFolder == null) rootFolder = top
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        if (rootFolder == null && entry.name.contains('/')) {
                            rootFolder = entry.name.substringBefore('/')
                        }
                    }
                    zis.closeEntry()
                }
            }
            // Keep the packaged launcher executable through Java's unzip.
            val pkgRoot = rootFolder?.let { File(destDir, it) } ?: destDir
            File(pkgRoot, "bin/wearsic-server").setExecutable(true, false)
            File(pkgRoot, "run-termux.sh").setExecutable(true, false)
            return rootFolder
        }
    }
}
