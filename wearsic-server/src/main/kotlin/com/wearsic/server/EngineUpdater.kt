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

/**
 * A newer engine release discovered on GitHub. [sha256Url] is the release's
 * published checksum asset (`.zip.sha256`) — auto-update NEVER installs a
 * release that does not ship one.
 */
data class RemoteUpdate(
    val version: String,
    val zipUrl: String,
    val sha256Url: String? = null,
)

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
    private data class GithubRelease(
        val tag_name: String? = null,
        val assets: List<GithubAsset> = emptyList(),
        // GitHub's releases LIST includes pre-releases and drafts; only
        // releases/latest excludes them. Because this class reads the list,
        // it MUST honour both flags itself or a pre-release would quietly
        // auto-install onto every user's phone. The Termux CLI does not need
        // this: it reads releases/latest, which GitHub already filters.
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    )

    /** Supervisor-facing state written to `state/update.json`. */
    @Serializable
    data class UpdateState(
        val status: String, // "staged"
        val version: String,
        val previousVersion: String? = null,
        val stagedAtMillis: Long = 0,
        /** SHA-256 the staged zip was verified against (hex, lowercase). */
        val sha256: String? = null,
        /** How many times this target version has been staged (loop guard). */
        val updateAttempt: Int = 1,
    )

    /**
     * Rollback record written by the Termux supervisor when it restores the
     * previous known-good engine (interrupted swap, or the new engine failed
     * startup/health validation). Surfaced in /health and /ready.
     */
    @Serializable
    data class RollbackInfo(
        val version: String? = null,
        val reason: String? = null,
        val atMillis: Long = 0,
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
    private val attemptsFile: File get() = File(stateDir, "update-attempts.json")
    private val rollbackFile: File get() = File(stateDir, "rollback.json")
    val stageDir: File get() = File(stateDir, "staging")

    /**
     * How many times [version] has been staged by this updater. Persisted
     * OUTSIDE the staged state (survives supervisor cleanup) so a
     * stage → apply → rollback → stage loop can never run forever.
     */
    fun attemptsFor(version: String): Int = readAttempts()[version] ?: 0

    /** Attempt count for the currently staged (or latest known) version. */
    fun currentAttemptCount(): Int {
        val version = stagedState()?.version ?: latestKnownVersion ?: return 0
        return readAttempts()[version] ?: 0
    }

    /** Supervisor-recorded rollback info, if a rollback ever happened. */
    fun rollbackInfo(): RollbackInfo? = try {
        if (rollbackFile.isFile) json.decodeFromString<RollbackInfo>(rollbackFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun readAttempts(): Map<String, Int> = try {
        if (attemptsFile.isFile) json.decodeFromString(attemptsFile.readText()) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun recordAttempt(version: String) {
        val all = readAttempts().toMutableMap()
        all[version] = (all[version] ?: 0) + 1
        // Bound the map so months of updates cannot grow it forever.
        val bounded = all.entries.sortedByDescending { it.value }.take(16)
        attemptsFile.writeText(json.encodeToString(bounded.associate { it.key to it.value }))
    }

    override fun hasStagedUpdate(): Boolean = readState()?.status == "staged"
    fun stagedState(): UpdateState? = readState()

    /**
     * Looks for a newer release. Returns [RemoteUpdate] when a strictly newer
     * version with a Termux ZIP asset AND its published `.zip.sha256`
     * checksum exists, null otherwise (up to date, any error, or a release
     * that ships no checksum — unverified auto-update is never offered).
     */
    override suspend fun checkForUpdate(): RemoteUpdate? = try {
        val body = client.get(releasesApiUrl) {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "wearsic-server/$runningVersion")
        }.bodyAsText()
        val releases = json.decodeFromString<List<GithubRelease>>(body)
        var skippedUnsigned: String? = null
        // Only real, published, stable releases are installable. Anything
        // else (a draft, or a pre-release like the v1.6 betas) is skipped
        // entirely: users stay on the last stable build until the release is
        // marked final.
        val installable = releases.filterNot { it.prerelease || it.draft }
        val update = installable.asSequence()
            .mapNotNull { rel ->
                val tag = rel.tag_name ?: return@mapNotNull null
                val version = tag.removePrefix("v")
                val asset = rel.assets.firstOrNull {
                    val n = it.name ?: return@firstOrNull false
                    n.startsWith(ZIP_ASSET_PREFIX) && n.endsWith(".zip") && !n.endsWith(".sha256")
                }
                val url = asset?.browser_download_url ?: return@mapNotNull null
                // Authenticity: the release MUST publish the zip's SHA-256.
                // A release without it is never auto-installed (see class
                // docs — old servers keep running and update manually).
                val checksumUrl = rel.assets
                    .firstOrNull { it.name == asset.name + ".sha256" }
                    ?.browser_download_url
                if (checksumUrl == null) {
                    if (isNewerVersion(version, runningVersion) && skippedUnsigned == null) {
                        skippedUnsigned = "v$version"
                    }
                    return@mapNotNull null
                }
                RemoteUpdate(version = version, zipUrl = url, sha256Url = checksumUrl)
            }
            .firstOrNull { isNewerVersion(it.version, runningVersion) }

        lastCheckAtMillis = clock()
        lastCheckError = skippedUnsigned?.let {
            "release $it publishes no .sha256 checksum — refusing unverified auto-update"
        }
        latestKnownVersion = update?.version
            ?: installable.firstNotNullOfOrNull { it.tag_name?.removePrefix("v") }

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
     * Downloads, VERIFIES and stages the given release ZIP. Returns the
     * staged version on success, null on any failure (everything is cleaned
     * up; the running server is never touched).
     *
     * Verification is mandatory and in this order:
     *  1. the release must publish a `.zip.sha256` checksum ([RemoteUpdate.sha256Url]),
     *  2. the downloaded bytes must match it exactly (SHA-256),
     *  3. the zip must pass structural integrity (central-directory walk),
     *  4. extraction is zip-slip-safe with a strict entry allowlist,
     *  5. the extracted package must contain bin/wearsic-server, a non-empty
     *     lib/ and run-termux.sh before anything is staged.
     *
     * A modified, truncated, unsigned or malformed package is rejected
     * BEFORE staging — the running engine is never at risk.
     */
    override suspend fun downloadAndStage(update: RemoteUpdate): String? = try {
        stateDir.mkdirs()

        // --- loop protection: a stage → apply → rollback cycle must not run
        // forever. Attempts persist across supervisor cleanups. ---
        val attempt = attemptsFor(update.version) + 1
        if (attempt > MAX_UPDATE_ATTEMPTS) {
            error(
                "update/rollback loop protection: v${update.version} already attempted " +
                    "${attempt - 1} times — update manually (wearsic-server-termux zip + install)"
            )
        }

        // --- authenticity: no checksum, no update. Never "proceed anyway". ---
        val checksumUrl = update.sha256Url
            ?: error("release v${update.version} publishes no .sha256 checksum — refusing unverified update")

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

        // --- SHA-256 authenticity: published hash vs downloaded bytes ---
        val published = parseSha256(
            client.get(checksumUrl) {
                header(HttpHeaders.UserAgent, "wearsic-server/$runningVersion")
            }.bodyAsText()
        ) ?: error("checksum asset is malformed (no SHA-256 found)")
        val actual = sha256Of(tmpZip)
        if (!actual.equals(published, ignoreCase = true)) {
            error("checksum mismatch: published ${published.take(12)}…, got ${actual.take(12)}…")
        }

        // --- verify: a truncated download must NEVER replace the engine ---
        verifyZipIntegrity(tmpZip)

        // --- stage: wipe previous staging, extract safely + strictly ---
        stageDir.deleteRecursively()
        stageDir.mkdirs()
        val root = safeExtract(tmpZip, stageDir)
        val pkgDir = if (root != null) File(stageDir, root) else stageDir
        validateStagedPackage(pkgDir)

        // --- hand off to the supervisor ---
        val state = UpdateState(
            status = "staged",
            version = update.version,
            previousVersion = runningVersion,
            stagedAtMillis = clock(),
            sha256 = actual,
            updateAttempt = attempt,
        )
        stateFile.writeText(json.encodeToString(state))
        recordAttempt(update.version)
        finalZip.delete()
        tmpZip.delete()
        println(
            "[update] Engine v${update.version} staged (attempt $attempt, sha256 verified) " +
                "— supervisor will apply it on next restart"
        )
        update.version
    } catch (e: Exception) {
        lastCheckError = ("stage failed: " + (e.message ?: e.javaClass.simpleName)).take(200)
        println("[update] ERROR staging engine update: ${e.message}")
        // Never leave a rejected download behind.
        runCatching { File(stateDir, "update.zip.tmp").delete() }
        null
    }

    /**
     * The staged package must look like a real engine release before the
     * supervisor is allowed to swap it in. A valid ZIP alone is not enough.
     */
    private fun validateStagedPackage(pkgDir: File) {
        require(File(pkgDir, "bin/wearsic-server").isFile) {
            "Staged zip missing bin/wearsic-server"
        }
        val libDir = File(pkgDir, "lib")
        require(libDir.isDirectory && (libDir.listFiles()?.isNotEmpty() == true)) {
            "Staged zip missing lib/ contents"
        }
        require(File(pkgDir, "run-termux.sh").isFile) {
            "Staged zip missing run-termux.sh"
        }
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

        /**
         * Stage → apply → rollback attempts allowed per target version.
         * After this many, auto-update refuses and a manual update is
         * required — this is what stops infinite update/rollback loops.
         */
        internal const val MAX_UPDATE_ATTEMPTS = 3

        /**
         * The ONLY top-level names allowed inside a release package (below
         * the package root folder). Anything else — executables, scripts,
         * unexpected config — aborts staging before the supervisor can see
         * the package.
         */
        internal val ALLOWED_TOP_LEVEL = setOf(
            "bin", "lib", "run-termux.sh", "wearsic", "install.sh",
            "README.md", "SETUP.md", ".env.example",
        )

        /** Extracts the first 64-hex token from a `.sha256` checksum file. */
        fun parseSha256(text: String): String? =
            Regex("[a-fA-F0-9]{64}").find(text)?.value?.lowercase()

        /** Streaming SHA-256 of [file], lowercase hex. */
        fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

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
         * Zip-slip-safe, STRICT extraction:
         *
         *  - every entry name must be a clean relative POSIX path (no `..`,
         *    no `.` segments, no absolute paths, no backslashes / Windows
         *    drive letters, no NULs),
         *  - duplicate entry names abort the extraction (zip parser
         *    confusion attacks rely on repeats),
         *  - the resolved output must stay inside [destDir] (canonicalized),
         *  - every entry must live under the single package root and its
         *    top-level name must be in [ALLOWED_TOP_LEVEL] — unexpected
         *    files abort staging entirely.
         *
         * Returns the root folder name found inside the zip (packages
         * extract as `wearsic-server/...`), or null when the zip has files
         * at its root.
         */
        fun safeExtract(zip: File, destDir: File): String? {
            destDir.mkdirs()
            val destCanon = destDir.canonicalPath + File.separator
            var rootFolder: String? = null
            val seen = HashSet<String>()
            ZipInputStream(BufferedInputStream(zip.inputStream().buffered())).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    validateEntryName(name)
                    if (!seen.add(name)) error("zip: duplicate entry: $name")

                    val out = File(destDir, name)
                    if (!out.canonicalPath.startsWith(destCanon)) {
                        error("zip: entry escapes target dir: $name")
                    }

                    // Package-root + top-level allowlist enforcement.
                    val trimmed = name.trimEnd('/')
                    val top = trimmed.substringBefore('/')
                    val rest = if (trimmed.contains('/')) trimmed.substringAfter('/') else ""
                    if (rootFolder == null && rest.isNotEmpty() &&
                        rest.substringBefore('/') in ALLOWED_TOP_LEVEL
                    ) {
                        rootFolder = top
                    }
                    when {
                        // The package-root directory entry itself ("wearsic-server/").
                        rest.isEmpty() && entry.isDirectory && rootFolder == null -> Unit
                        // Stray FILE at the package root level of a rooted zip.
                        top == rootFolder && rest.isEmpty() && !entry.isDirectory ->
                            error("zip: unexpected entry in release package: $name")
                        top == rootFolder && rest.isNotEmpty() -> {
                            if (rest.substringBefore('/') !in ALLOWED_TOP_LEVEL) {
                                error("zip: unexpected entry in release package: $name")
                            }
                        }
                        // Rootless layout: the top-level name itself must be allowed.
                        rootFolder == null -> {
                            if (top !in ALLOWED_TOP_LEVEL) {
                                error("zip: unexpected entry in release package: $name")
                            }
                        }
                        else -> error("zip: entry outside the package root: $name")
                    }

                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
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

        /** Rejects every path shape that has ever been used to escape a zip. */
        private fun validateEntryName(name: String) {
            if (name.isBlank()) error("zip: blank entry name")
            if (name.any { it.code == 0 }) error("zip: NUL byte in entry name")
            if (name.startsWith("/") || name.startsWith("\\")) {
                error("zip: absolute entry path: $name")
            }
            if (name.contains('\\')) error("zip: backslash in entry path: $name")
            if (Regex("^[A-Za-z]:").containsMatchIn(name)) {
                error("zip: drive-letter entry path: $name")
            }
            // A trailing slash is a directory marker; every other segment must
            // be a clean single name (no empty, no dot, no dot-dot).
            val segments = name.removeSuffix("/").split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                error("zip: path traversal in entry: $name")
            }
        }
    }
}
