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
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

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
) {

    private val logger = LoggerFactory.getLogger(Transcoder::class.java)

    /** True when this instance's `ffmpeg` binary was found. */
    val available: Boolean = availability

    companion object {
        /**
         * Instance-global transcode cap shared by every Transcoder created
         * with the default construction (production creates exactly one).
         * Declared here so repeated instantiations in tests cannot multiply
         * concurrent ffmpeg processes.
         */
        private val sharedSlots = Semaphore(2)

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

    suspend fun handle(call: ApplicationCall, sourceUrl: String, rangeHeader: String?): TranscodeResult {
        if (!available) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ErrorResponse("This song needs server-side AAC conversion, but ffmpeg isn't installed. In Termux run: pkg install ffmpeg")
            )
            return TranscodeResult.Unavailable
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
                val (probeStatus, _) = client.probeUpstreamLength(sourceUrl, 0)
                if (probeStatus != HttpStatusCode.PartialContent.value) {
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
                    call.response.header(HttpHeaders.ContentRange, "bytes $startByte-*")
                }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                call.respondBytesWriter(
                    contentType = ContentType.parse("audio/aac"),
                    status = status
                ) {
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
                        val pump = launch(Dispatchers.IO) {
                            try {
                                val outcome: ChunkedUpstream = client.fetchClosedChunks(
                                    sourceUrl, 0, null
                                ) { buf: ByteArray, n: Int ->
                                    stdin.write(buf, 0, n)
                                }
                                when (outcome) {
                                    is ChunkedUpstream.Refused,
                                    is ChunkedUpstream.Done -> Unit
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
                            writeFully(buf, 0, n)
                        }
                        pump.join()
                        stderrDrain.join()
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
            }
        }

        return when {
            upstreamFailure != null -> TranscodeResult.UpstreamError(upstreamFailure!!)
            aborted -> TranscodeResult.Aborted
            else -> TranscodeResult.Streamed
        }
    }

    /** Parses "bytes=N-..." → N (0 when absent/malformed). Suffix ranges like
     *  "bytes=-500" are treated as 0 (serve from the start). */
    private fun parseRangeStart(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0L
        val m = Regex("bytes=(\\d+)-").find(rangeHeader) ?: return 0L
        return m.groupValues[1].toLongOrNull() ?: 0L
    }
}