package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

/**
 * LIVE probe (network-dependent, run manually):
 *   WEARSIC_LIVE_PROBE=1 ./gradlew :wearsic-server:test --tests "com.wearsic.server.LiveCdnProbeTest"
 *
 * Answers the question the stall fix hangs on: can bytes be pulled PAST
 * 1 MB through (a) the lean target the production resolver picks, and
 * (b) a NewPipe-extracted URL? Lean targets died at exactly 1,048,576
 * bytes on throttled networks (the user-visible "stalls at 1:04") until
 * the resolver moved to the VISIONOS client + session visitor id — this
 * probe re-checks the live behaviour after any resolver change.
 *
 * Not tagged as part of the normal suite: it talks to YouTube.
 */
class LiveCdnProbeTest {

    private val videoId = "K_8yRH2KPVo" // the user's problem track

    private fun pull(url: String, wantBytes: Long): String = runBlocking {
        var total = 0L
        val client = HttpClient(CIO)
        try {
            val outcome = client.fetchClosedChunks(url, 0, wantBytes - 1, maxRefreshes = 0) { _, n -> total += n }
            "outcome=$outcome bytes=$total"
        } catch (e: Exception) {
            "EXCEPTION=${e.javaClass.simpleName}: ${e.message?.take(90)} bytes=$total"
        } finally {
            client.close()
        }
    }

    private fun nParam(url: String): String {
        val m = Regex("[?&]n=([^&]+)").find(url)
        return m?.groupValues?.get(1)?.take(14) ?: "<none>"
    }

    @Test
    fun `probe lean vs extractor url past 1MB`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("WEARSIC_LIVE_PROBE") == "1",
            "live network probe — set WEARSIC_LIVE_PROBE=1 to run",
        )
        println("=== PROBE video=$videoId ===")

        // --- (a) LEAN path, exactly as production resolves ---
        runCatching {
            val gateway = YoutubeGateway()
            val target = runBlocking { gateway.streamTarget(videoId) }
            println("LEAN target: itag=${target?.itag} mime=${target?.mimeType} n=${target?.url?.let { nParam(it) }}")
            println("LEAN URL: ${target?.url}")
            if (target != null) {
                println("LEAN pull 3MB: " + pull(target.url, 3L * 1024 * 1024))
            } else {
                println("LEAN target: null")
            }
        }.onFailure { println("LEAN probe failed: ${it.message}") }

        // --- (b) EXTRACTOR path (n-deobfuscated) ---
        runCatching {
            NewPipe.init(NewPipeDownloader(NewPipeDownloader.buildClient()))
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            if (extractor is YoutubeStreamExtractor) YoutubeStreamExtractor.setFetchIosClient(true)
            try {
                extractor.fetchPage()
                val streams = extractor.audioStreams.orEmpty().filter { it.isUrl }
                println("EXTRACTOR streams: ${streams.size}")
                streams.forEach { s ->
                    println("  itag=${runCatching { s.itagItem?.id }.getOrNull()} " +
                        "br=${s.averageBitrate} mime=${s.format?.mimeType} n=${nParam(s.url ?: "")}")
                }
                val best = streams.filter { it.format?.mimeType?.contains("mp4", true) == true }
                    .ifEmpty { streams }
                    .minByOrNull { kotlin.math.abs(it.averageBitrate - 128) }
                if (best != null) {
                    println("EXTRACTOR URL: ${best.url}")
                    best.url?.let { u -> println("EXTRACTOR pull 3MB: " + pull(u, 3L * 1024 * 1024)) }
                }
            } finally {
                if (extractor is YoutubeStreamExtractor) YoutubeStreamExtractor.setFetchIosClient(false)
            }
        }.onFailure { println("EXTRACTOR probe failed: ${it.message}") }
    }
}
