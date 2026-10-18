package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

/**
 * LIVE dual-path smoke — the gate the Extractor Hotfix workflow trusts:
 *
 *   WEARSIC_LIVE_SMOKE=1 ./gradlew :wearsic-server:test \
 *       --tests "com.wearsic.server.LiveExtractorSmokeTest"
 *
 * Extracts an effectively-permanent video ("Me at the zoo", the same one the
 * production canary uses) through BOTH engines and pulls real bytes from each
 * resolved CDN URL:
 *
 *  1. NewPipeExtractor — the component a pin bump changes. This is the only
 *     check that can tell "the new pin fixes YouTube" from "the new pin
 *     merely compiles"; unit tests cannot reach the live service.
 *  2. The production resolver — lean player path first, extractor fallback.
 *     Proves the whole resolve→CDN chain, not just one library.
 *
 * Skipped unless WEARSIC_LIVE_SMOKE=1 (talks to YouTube), mirroring
 * [LiveCdnProbeTest]'s WEARSIC_LIVE_PROBE gate. A CI datacenter IP can hit
 * YouTube bot-walls here even when the pin is fine — the hotfix workflow
 * therefore treats a failure as blocking-with-override, not as truth, and
 * can arm WEARSIC_YOUTUBE_COOKIE (repo secret YT_COOKIE) so the smoke runs
 * cookie-backed from those IPs.
 */
class LiveExtractorSmokeTest {

    /** Enough proof that the CDN serves THIS resolved link to THIS network. */
    private val minBytes = 64L * 1024

    private fun pullBytes(url: String, wantBytes: Long): Long = runBlocking {
        var total = 0L
        val client = HttpClient(CIO)
        try {
            client.fetchClosedChunks(url, 0, wantBytes - 1, maxRefreshes = 0) { _, n -> total += n }
        } finally {
            client.close()
        }
        total
    }

    @Test
    fun `NewPipe extraction resolves and streams bytes`() {
        assumeTrue(
            System.getenv("WEARSIC_LIVE_SMOKE") == "1",
            "live smoke — set WEARSIC_LIVE_SMOKE=1 to run",
        )
        NewPipe.init(NewPipeDownloader(NewPipeDownloader.buildClient()))
        val extractor = ServiceList.YouTube.getStreamExtractor(YoutubeGateway.CANARY_URL)
        if (extractor is YoutubeStreamExtractor) YoutubeStreamExtractor.setFetchIosClient(true)
        try {
            extractor.fetchPage()
            val best = extractor.audioStreams.orEmpty()
                .filter { it.isUrl && it.url != null }
                .minByOrNull { kotlin.math.abs(it.averageBitrate - 128) }
            assertNotNull(
                best,
                "NewPipeExtractor found no URL-only audio stream — YouTube changed " +
                    "something the pinned extractor cannot handle",
            )
            val bytes = pullBytes(best!!.url!!, 256 * 1024)
            assertTrue(
                bytes >= minBytes,
                "NewPipeExtractor resolved a URL but only $bytes bytes streamed " +
                    "(min $minBytes) — the CDN refused the resolved link",
            )
        } finally {
            if (extractor is YoutubeStreamExtractor) YoutubeStreamExtractor.setFetchIosClient(false)
        }
    }

    @Test
    fun `production resolver resolves and streams bytes`() {
        assumeTrue(
            System.getenv("WEARSIC_LIVE_SMOKE") == "1",
            "live smoke — set WEARSIC_LIVE_SMOKE=1 to run",
        )
        val canaryId = YoutubeGateway.CANARY_URL.substringAfter("v=")
        val gateway = YoutubeGateway()
        val target = runBlocking { gateway.streamTarget(canaryId) }
        assertNotNull(
            target,
            "production resolver returned no stream target — lean player path " +
                "AND NewPipe extraction both failed",
        )
        val bytes = pullBytes(target!!.url, 256 * 1024)
        assertTrue(
            bytes >= minBytes,
            "production resolver returned a URL but only $bytes bytes streamed " +
                "(min $minBytes) — the CDN refused the resolved link",
        )
    }

    companion object {
        /**
         * Mirror a server boot: the WEARSIC_YOUTUBE_COOKIE env var (when set)
         * becomes the session cookie [NewPipeDownloader] attaches to requests —
         * the same mechanism `wearsic cookies` uses on a running server.
         */
        @JvmStatic
        @BeforeAll
        fun armSessionCookie() {
            val raw = System.getenv("WEARSIC_YOUTUBE_COOKIE")
            YoutubeSession.cookie = raw
            // Printed to both streams: whichever one Gradle surfaces for this
            // run, the cookie state is never a mystery in CI logs. Lengths
            // only — never the value.
            val line = "live smoke: cookie-backed=${YoutubeSession.hasCookie()}" +
                if (raw == null) " (env not set)" else
                    " (raw-len=${raw.length}, effective-len=${YoutubeSession.cookie?.length ?: 0})"
            println(line)
            System.err.println(line)
        }
    }
}
