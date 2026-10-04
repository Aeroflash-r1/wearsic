package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A GitHub PRE-RELEASE must never be auto-installed.
 *
 * This matters because the two readers of GitHub releases behave differently:
 *  - `releases/latest` (used by the Termux CLI's `wearsic update`) already
 *    EXCLUDES pre-releases — GitHub does the filtering.
 *  - `releases?per_page=N` (used by EngineUpdater) INCLUDES them.
 *
 * So the server, which reads the list, has to filter `prerelease`/`draft`
 * itself. Without this filter, tagging v1.6.0 as a pre-release would still
 * auto-install v1.6.0 onto every existing phone within the check interval —
 * making the pre-release flag cosmetic and breaking the point of a beta.
 */
class EngineUpdaterPrereleaseTest {

    private fun releaseJson(tag: String, version: String, prerelease: Boolean, draft: Boolean): String {
        val base = "http://127.0.0.1/assets/$version"
        return """
            {
              "tag_name": "$tag",
              "prerelease": $prerelease,
              "draft": $draft,
              "assets": [
                { "name": "wearsic-server-termux-$version.zip",
                  "browser_download_url": "$base/wearsic-server-termux-$version.zip" },
                { "name": "wearsic-server-termux-$version.zip.sha256",
                  "browser_download_url": "$base/wearsic-server-termux-$version.zip.sha256" }
              ]
            }
        """.trimIndent()
    }

    /** Serves a GitHub-shaped releases list over local HTTP. */
    private fun serveReleases(body: String): Pair<String, AutoCloseable> {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing { get("/releases") { call.respondText(body, ContentType.Application.Json) } }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port/releases" to AutoCloseable { server.stop(500, 1_000) }
    }

    @Test
    fun newerPreReleaseIsSkipped_andTheLastStableIsOffered(@TempDir dir: File) {
        // GitHub returns newest first: the v1.6.0 beta sits above v1.5.5.
        val (url, close) = serveReleases(
            "[${releaseJson("v1.6.0", "1.6.0", prerelease = true, draft = false)}," +
                releaseJson("v1.5.5", "1.5.5", prerelease = false, draft = false) + "]"
        )
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = dir,
                releasesApiUrl = url,
                minZipBytes = 1,
                runningVersion = "1.5.0",
            )
            val update = runBlocking { updater.checkForUpdate() }
            assertEquals(
                "1.5.5",
                update?.version,
                "The beta v1.6.0 must be ignored; the newest STABLE release is v1.5.5",
            )
            assertEquals(
                "1.5.5",
                updater.latestKnownVersion,
                "latestKnownVersion must never report a pre-release as the newest build",
            )
        } finally {
            close.close()
        }
    }

    @Test
    fun draftReleaseIsAlsoSkipped(@TempDir dir: File) {
        val (url, close) = serveReleases(
            "[${releaseJson("v1.7.0", "1.7.0", prerelease = false, draft = true)}," +
                releaseJson("v1.5.5", "1.5.5", prerelease = false, draft = false) + "]"
        )
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = dir,
                releasesApiUrl = url,
                minZipBytes = 1,
                runningVersion = "1.5.0",
            )
            assertEquals("1.5.5", runBlocking { updater.checkForUpdate() }?.version)
        } finally {
            close.close()
        }
    }

    @Test
    fun aPreReleaseOnlyListOffersNothing(@TempDir dir: File) {
        // Running on the newest stable already: the beta must not look like
        // an available "update" to a user who is up to date.
        val (url, close) = serveReleases(
            "[${releaseJson("v1.6.0", "1.6.0", prerelease = true, draft = false)}," +
                releaseJson("v1.5.5", "1.5.5", prerelease = false, draft = false) + "]"
        )
        try {
            val updater = EngineUpdater(
                client = HttpClient(CIO),
                stateDir = dir,
                releasesApiUrl = url,
                minZipBytes = 1,
                runningVersion = "1.5.5",
            )
            assertNull(runBlocking { updater.checkForUpdate() }, "Up-to-date stable user must not be pushed onto the beta")
        } finally {
            close.close()
        }
    }
}