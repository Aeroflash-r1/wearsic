package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
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
            try {
                extractor.fetchPage()
            } catch (e: SignInConfirmNotBotException) {
                // The question the hotfix gate hinges on, embedded where Gradle
                // always prints it: lengths only — never the cookie value.
                fail(
                    "YouTube bot-walled the NewPipe fetch — ${cookieState()}. " +
                        "effective-len=0 means the cookie never reached this test JVM; " +
                        "a non-zero effective-len means it was attached and YouTube " +
                        "challenged this IP anyway.",
                    e,
                )
            }
            val best = extractor.audioStreams.orEmpty()
                .filter { it.isUrl && it.url != null }
                .minByOrNull { kotlin.math.abs(it.averageBitrate - 128) }
            assertNotNull(
                best,
                "NewPipeExtractor found no URL-only audio stream — YouTube changed " +
                    "something the pinned extractor cannot handle (${cookieState()})",
            )
            val bytes = pullBytes(best!!.url!!, 256 * 1024)
            assertTrue(
                bytes >= minBytes,
                "NewPipeExtractor resolved a URL but only $bytes bytes streamed " +
                    "(min $minBytes) — the CDN refused the resolved link (${cookieState()})",
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
                "AND NewPipe extraction both failed (${cookieState()})",
        )
        val bytes = pullBytes(target!!.url, 256 * 1024)
        assertTrue(
            bytes >= minBytes,
            "production resolver returned a URL but only $bytes bytes streamed " +
                "(min $minBytes) — the CDN refused the resolved link (${cookieState()})",
        )
    }

    companion object {
        /**
         * Two delivery channels: the env var (local runs, and step env when a
         * fresh daemon passes it through) and the file the hotfix workflow
         * writes next to the module's build output (immune to any environment
         * caching of a reused Gradle daemon). Env wins when non-blank.
         */
        @JvmStatic
        @BeforeAll
        fun armSessionCookie() {
            val env = System.getenv("WEARSIC_YOUTUBE_COOKIE")?.takeIf { it.isNotBlank() }
            YoutubeSession.cookie = env ?: File("build/yt-cookie.txt").readIfPresent()
            println("live smoke: ${cookieState()}")
            System.err.println("live smoke: ${cookieState()}")
        }

        private fun File.readIfPresent(): String? =
            takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

        /**
         * Cookie state as a log-safe string — lengths only, never the value.
         * Embedded in every failure message so a bot-wall can never hide
         * which channel delivered (or failed to deliver) the cookie.
         */
        fun cookieState(): String {
            val envLen = System.getenv("WEARSIC_YOUTUBE_COOKIE")?.length
            val fileLen = File("build/yt-cookie.txt").readIfPresent()?.length
            return "cookie-backed=${YoutubeSession.hasCookie()} " +
                "(env-len=${envLen ?: "none"}, file-len=${fileLen ?: "none"}, " +
                "effective-len=${YoutubeSession.cookie?.length ?: 0})"
        }
    }
}
