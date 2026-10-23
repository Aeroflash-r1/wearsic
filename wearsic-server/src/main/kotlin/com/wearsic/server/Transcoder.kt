package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondFile
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-the-fly Opus/Vorbis -> AAC-LC converter for songs YouTube doesn't offer
 * in AAC (rare — most videos ship itag 140 AAC-LC, which is passed through
 * untouched with zero CPU cost). The watch's SoC decodes AAC in hardware, so
 * converting the rare WebM-only song on the server keeps EVERY stream
 * hardware-decodable and battery-cheap on the watch.
 *
 * The transcode runs only when the resolved stream's MIME type is not
 * audio/mp4. It pipes the YouTube CDN bytes into an ffmpeg process and streams
 * the ADTS AAC output straight to the client — no temp files, no download
 * latency beyond the encoder's initial buffer.
 */
class Transcoder(
    private val client: HttpClient,
    private val ffmpegBinary: String = "ffmpeg",
    /**
     * Per-instance probe result — injectable so tests never mutate global
     * state and remain order-independent. Defaults to a one-shot probe of
     * [ffmpegBinary] at construction time (same behavior the static
     * `detect()` used to provide at server startup).
     */
    availability: Boolean = detectBinary(ffmpegBinary),
    /**
     * Completed transcodes are stored here (one .aac per cache key) so the
     * ~15 range requests ExoPlayer makes per song share ONE ffmpeg run
     * instead of each spawning its own full re-transcode — the actual cause
     * of mid-song stalls on transcoded tracks. Null disables caching
     * (tests). Bounded LRU, crash-safe (temp + atomic rename).
     */
    private val cacheDir: File? = null,
    private val maxCacheBytes: Long = 200L * 1024L * 1024L,
    /**
     * Throttle for re-probing a MISSING binary (see [ensureAvailability]).
     * 0 disables the throttle (tests).
     */
    private val recheckIntervalMs: Long = RECHECK_INTERVAL_MS,
) {

    private val logger = LoggerFactory.getLogger(Transcoder::class.java)

    /**
     * Latched once found. Starts from the construction-time probe but can
     * flip to true later via [ensureAvailability] when `ffmpeg` appears.
     */
    @Volatile
    private var availabilityState: Boolean = availability

    /** Last missing-binary probe attempt (throttles re-probing). */
    private val lastProbeMs = AtomicLong(System.currentTimeMillis())

    /** True when this instance's `ffmpeg` binary has been found. */
    val available: Boolean get() = availabilityState

    /**
     * Re-probes a MISSING binary (throttled + single-flight) so a
     * transiently failed boot probe — or an `ffmpeg` installed while the
     * server is already running — heals WITHOUT a restart. Before this,
     * one slow 5s probe at boot condemned every non-AAC song to a
     * "ffmpeg isn't installed" 503 for the whole process lifetime, and a
     * background install never took effect until the next restart.
     */
    suspend fun ensureAvailability(): Boolean {
        if (availabilityState) return true
        val now = System.currentTimeMillis()
        val last = lastProbeMs.get()
        if (now - last < recheckIntervalMs) return false
        // Lost the CAS: another request is already probing — report current state.
        if (!lastProbeMs.compareAndSet(last, now)) return false
        val found = withContext(Dispatchers.IO) { detectBinary(ffmpegBinary) }
        if (found) {
            availabilityState = true
            logger.info("ffmpeg found — server-side conversion is now ON (no restart needed)")
        }
        return found
    }

    companion object {
        /**
         * Instance-global transcode cap shared by every Transcoder created
         * with the default construction (production creates exactly one).
         * Declared here so repeated instantiations in tests cannot multiply
         * concurrent ffmpeg processes.
         */
        private val sharedSlots = Semaphore(2)

        /** Throttle for re-probing a missing ffmpeg while the server stays up. */
        internal const val RECHECK_INTERVAL_MS = 30_000L

        /** Probe [binary] without mutating any global state. */
        fun detectBinary(binary: String = "ffmpeg"): Boolean = runCatching {
            val p = ProcessBuilder(binary, "-version")
                .redirectErrorStream(true)
                .start()
            val exited = p.waitFor(5, TimeUnit.SECONDS)
            p.destroy()
            exited && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** WebM/Ogg (Opus/Vorbis) needs conversion; MP4 (AAC-LC) is already ideal. */
    fun needsTranscode(mimeType: String): Boolean =
        !mimeType.contains("mp4", ignoreCase = true)

    // ---------------- Transcode file cache ----------------

    /** In-flight transcodes by cache key (dedupes ExoPlayer's parallel ranges). */
    private val ongoingTranscodes = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private fun cacheFileFor(cacheKey: String): File? {
        val dir = cacheDir ?: return null
        val safe = cacheKey.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
        if (safe.isBlank()) return null
        return File(dir, "$safe.aac")
    }

    /** Serves a COMPLETED cached transcode (ranges handled by Ktor). */
    private suspend fun serveCached(call: ApplicationCall, file: File): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        return try {
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            call.respondFile(file)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Evicts oldest files until the cache fits [maxCacheBytes]. */
    private fun evictCache() {
        val dir = cacheDir ?: return
        try {
            // A SIGKILL/OOM mid-transcode orphans its .aac.part file: the
            // in-flight cleanup never ran, and eviction below deliberately
            // ignores .part (an ACTIVE transcode must not be deleted).
            // Unbounded across crashes — so reclaim any .part older than an
            // hour: no live ffmpeg survives that long (the pipeline has
            // hard timeouts far below it).
            val now = System.currentTimeMillis()
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".part") && now - it.lastModified() > 3600_000L }
                ?.forEach { runCatching { it.delete() } }
            var total = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
                ?.sumOf { it.length() } ?: 0L
            if (total <= maxCacheBytes) return
            dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") }
                ?.sortedBy { it.lastModified() }
                ?.forEach {
                    if (total <= maxCacheBytes) return@forEach
                    total -= it.length()
                    runCatching { it.delete() }
                }
        } catch (_: Exception) {
        }
    }

    /**
     * Transcode result — returned WITHOUT committing an error response for
     * upstream failures so the caller ([AudioProxy]) can still self-heal
     * (invalidate + retry once) before anything is sent to the watch.
     * Committing a 502 first would make the retry a double-respond.
     */
    sealed interface TranscodeResult {
        data object Streamed : TranscodeResult
        data class UpstreamError(val status: Int) : TranscodeResult
        data object Unavailable : TranscodeResult
        data object Aborted : TranscodeResult
    }

    suspend fun handle(
        call: ApplicationCall,
        sourceUrl: String,
        rangeHeader: String?,
        /**
         * Fresh-URL supplier for mid-stream throttle refusals (see
         * fetchClosedChunks): resume the ffmpeg feed from a new URL
         * instead of truncating the song. Null = end the feed where
         * it stands (callers without a resolver, e.g. tests).
         */
        refreshUrl: (suspend () -> String?)? = null,
        /**
         * Cache identity (normally the videoId): completed transcodes are
         * stored and re-served from disk, so ExoPlayer's ~15 range requests
         * per song share ONE ffmpeg run. Null disables caching (tests).
         */
        cacheKey: String? = null,
    ): TranscodeResult {
        if (!ensureAvailability()) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("This song needs server-side AAC conversion, but ffmpeg isn't installed. In Termux run: pkg install ffmpeg")
            )
            return TranscodeResult.Unavailable
        }

        // Cache hit (or a concurrent transcode that just finished): serve
        // the file — ranges handled by Ktor, zero ffmpeg cost.
        val cacheFile = cacheKey?.let { cacheFileFor(it) }
        if (cacheFile != null) {
            // Another request is transcoding this right now: wait for it
            // (bounded), then serve the file instead of starting a second
            // ffmpeg for the same bytes.
            ongoingTranscodes[cacheKey]?.let { inFlight ->
                val done = withTimeoutOrNull(5 * 60 * 1000L) { inFlight.await() } ?: false
                if (done && serveCached(call, cacheFile)) return TranscodeResult.Streamed
            }
            if (serveCached(call, cacheFile)) return TranscodeResult.Streamed
        }

        // Streaming conversion honors byte offsets by discarding that much
        // output (ADTS bytes are continuous), so ExoPlayer seeks and the app's
        // download-resume both stay correct even on transcoded songs.
        val startByte = parseRangeStart(rangeHeader)

        // Holds an upstream failure status so the caller can self-heal.
        // Null = streamed OK (or client aborted mid-stream — routine, no retry).
        var upstreamFailure: Int? = null
        var aborted = false

        sharedSlots.withPermit {
            // Winner of the sliver race (see the pre-check above): only the
            // primary worker writes the cache file — a loser that slipped
            // through transcodes uncached rather than corrupting the part.
            var primaryWorker: CompletableDeferred<Boolean>? = null
            var cacheWriter: java.io.BufferedOutputStream? = null
            var cachePart: File? = null
            if (cacheFile != null && cacheKey != null) {
                val mine = CompletableDeferred<Boolean>()
                if (ongoingTranscodes.putIfAbsent(cacheKey, mine) == null) {
                    primaryWorker = mine
                    cachePart = File(cacheFile.parentFile, "${cacheFile.name}.part")
                    cacheWriter = runCatching {
                        cacheFile.parentFile?.mkdirs()
                        cachePart!!.outputStream().buffered()
                    }.getOrNull()
                }
            }
            // True only when the upstream feed ran to a clean EOF (not a
            // refusal, not an abort): the file is complete and cacheable.
            // Written by the pump coroutine, read after pump.join().
            val upstreamDone = java.util.concurrent.atomic.AtomicBoolean(false)
            var process: Process? = null
            try {
                process = ProcessBuilder(
                    ffmpegBinary, "-hide_banner", "-loglevel", "error",
                    "-i", "pipe:0",
                    "-vn", "-c:a", "aac", "-b:a", "128k", "-f", "adts", "pipe:1"
                ).start()
                val active = process
                val stdin: OutputStream = active.outputStream
                val stdout: InputStream = active.inputStream

                // Validate the CDN URL with a 1-byte CLOSED probe before
                // committing a response (open ranges 403 — see RangedUpstream).
                // The probe ALSO yields the source's total length: the feed
                // is bounded at that byte, so any pre-EOF 416/connection-end
                // is provably mid-song and never dressed up as a clean end.
                val (probeStatus, probeTotal) = client.probeUpstreamLength(sourceUrl, 0)
                if (probeStatus != HttpStatusCode.PartialContent.value || probeTotal == null) {
                    // Don't respond here — let AudioProxy retry with a
                    // fresh URL first (dead-URL self-heal). It sends the
                    // final 502 only if the retry also fails.
                    upstreamFailure = probeStatus
                    return@withPermit
                }

                val status = if (startByte > 0) {
                    HttpStatusCode.PartialContent
                } else {
                    HttpStatusCode.OK
                }
                if (startByte > 0) {
                    // KNOWN DEBT, deliberate: RFC 7233 has no legal encoding
                    // for "content begins at byte N, total unknown", and the
                    // transcoded AAC size cannot be known until ffmpeg winds
                    // down. We answer 206 with `bytes N-*/*` (strictly, the
                    // "unsatisfied-range" form may only be `bytes *` with a
                    // real total) rather than downgrade to 200 — a 200 here
                    // tells the client the body starts at byte 0, which
                    // corrupts byte accounting WORSE than a header the client
                    // ignores. ExoPlayer treats the 206 as "range honored
                    // from N" and reads the body accordingly; revisit if a
                    // client appears that rejects it.
                    call.response.header(HttpHeaders.ContentRange, "bytes $startByte-*/*")
                }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                call.respondBytesWriter(
                    contentType = ContentType.parse("audio/aac"),
                    status = status
                ) {
                    abortOnFailure {
                        coroutineScope {
                            // Drain ffmpeg's stderr to nowhere on its own thread.
                            // A full OS pipe buffer would otherwise wedge ffmpeg
                            // forever while it holds a global transcode permit.
                            // (Never redirectErrorStream into stdout — that pipe
                            // carries the AAC bytes.)
                            val stderrDrain = launch(Dispatchers.IO) {
                                runCatching {
                                    active.errorStream.copyTo(OutputStream.nullOutputStream())
                                }
                            }
    
                            // Pump CDN bytes into ffmpeg's stdin in closed-range
                            // chunks (open upstream ranges 403 — see RangedUpstream).
                            // Bounded at the probed total: a pre-EOF connection
                            // death can then never masquerade as a clean feed end.
                            val pump = launch(Dispatchers.IO) {
                                try {
                                    val outcome: ChunkedUpstream = client.fetchClosedChunks(
                                        sourceUrl, 0, (probeTotal ?: 0L) - 1,
                                        refreshUrl = refreshUrl,
                                        maxRefreshes = 8,
                                    ) { buf: ByteArray, n: Int ->
                                        stdin.write(buf, 0, n)
                                    }
                                    when (outcome) {
                                        is ChunkedUpstream.Done -> upstreamDone.set(true)
                                        is ChunkedUpstream.Refused -> Unit
                                    }
                                } catch (e: Exception) {
                                    // Upstream died; ffmpeg sees EOF and flushes what it has.
                                } finally {
                                    runCatching { stdin.close() }
                                }
                            }
    
                            // Read ffmpeg stdout, discard `startByte` bytes, stream the rest.
                            val buf = ByteArray(64 * 1024)
                            var skipped = 0L
                            while (skipped < startByte) {
                                val n = withContext(Dispatchers.IO) { stdout.read(buf) }
                                if (n < 0) break
                                runCatching { cacheWriter?.write(buf, 0, n) }
                                skipped += n
                                if (skipped > startByte) {
                                    // Chunk straddles the offset: keep the tail.
                                    val tail = buf.copyOfRange(
                                        (n - (skipped - startByte)).toInt(),
                                        n
                                    )
                                    writeFully(tail)
                                }
                            }
                            while (true) {
                                val n = withContext(Dispatchers.IO) { stdout.read(buf) }
                                if (n < 0) break
                                runCatching { cacheWriter?.write(buf, 0, n) }
                                writeFully(buf, 0, n)
                            }
                            pump.join()
                            stderrDrain.join()
                            // IMPORTANT: served songs must ALWAYS be whole or dead.
                            // An upstream feed that died mid-song (refusal/drop)
                            // leaves ffmpeg's flushed prefix as a shorter-than-song
                            // output; ending the response cleanly would make the
                            // watch believe the song ENDED here (the reported
                            // "stalls halfway, silently"). Abort the connection
                            // instead: OkHttp sees unexpected EOF, ExoPlayer
                            // retries the remaining range with a fresh request.
                            if (!upstreamDone.get()) throw UpstreamTruncatedException(startByte)
                        }
                    }
                }
            } catch (e: Exception) {
                // Client disconnected/skipped, or ffmpeg failed — routine on skips.
                logger.info("Transcode proxy ended early: {}", e.message)
                aborted = true
            } finally {
                // destroy() alone can leave a wedged ffmpeg as a zombie:
                // escalate and reap so permits/threads never leak.
                runCatching { process?.destroy() }
                try {
                    if (process != null && process!!.isAlive) {
                        process!!.destroyForcibly()
                        process!!.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                }
                // Publish the cache file ONLY on a provably complete run:
                // clean upstream EOF + no abort + no upstream failure.
                // Anything else leaves a partial file that must never pass
                // as a full song.
                runCatching { cacheWriter?.close() }
                if (primaryWorker != null && cacheFile != null && cachePart != null) {
                    var ok = false
                    try {
                        if (!aborted && upstreamFailure == null && upstreamDone.get() &&
                            cachePart.isFile && cachePart.length() > 0
                        ) {
                            Files.move(
                                cachePart.toPath(), cacheFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING
                            )
                            evictCache()
                            ok = true
                        } else {
                            runCatching { cachePart.delete() }
                        }
                    } catch (_: Exception) {
                        runCatching { cachePart.delete() }
                    }
                    primaryWorker.complete(ok)
                    ongoingTranscodes.remove(cacheKey, primaryWorker)
                }
            }
        }

        return when {
            upstreamFailure != null -> TranscodeResult.UpstreamError(upstreamFailure!!)
            aborted -> TranscodeResult.Aborted
            else -> TranscodeResult.Streamed
        }
    }

        /** Parses "bytes=N-..." → N (0 when absent/malformed). Suffix ranges
         *  like "bytes=-500" are treated as 0 (serve from the start) — for a
         *  live transcode the total is unknowable, so the suffix window can
         *  never be resolved; serving the whole song is the least-wrong
         *  answer and keeps the response byte-consistent. */
    private fun parseRangeStart(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0L
        val m = Regex("bytes=(\\d+)-").find(rangeHeader) ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }
}