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
import kotlin.test.assertTrue

/**
 * The release notes preview travels with every staged update: an auto-update
 * must never be a black box merely because nobody is watching a terminal.
 * These tests pin the preview shaping (badges/link junk dropped, headings
 * unhashed, hard length cap) and the discovery path that captures them.
 */
class EngineUpdaterNotesTest {

    // ---------------- previewReleaseNotes (pure) ----------------

    @Test
    fun `badges and link definitions are dropped`() {
        val body = """
            [![CI](https://badge.example/w.svg)](https://example.com/ci)
            [Pull requests]: https://example.com

            ### First render of the notes
            Real release notes live here.
        """.trimIndent()
        val preview = EngineUpdater.previewReleaseNotes(body)!!
        assertTrue(preview.contains("First render of the notes"), preview)
        assertTrue(preview.contains("Real release notes"), preview)
        assertTrue(!preview.contains("badge.example"), "badge URL must not leak into the preview")
        assertTrue(!preview.contains("[Pull requests]:"), "link definitions must not leak")
        assertTrue(!preview.startsWith("#"), "headings are unhashed, not dropped")
        assertTrue(
            preview.startsWith("First render of the notes"),
            "leading heading text stays, without the # hashes",
        )
    }

    @Test
    fun `long bodies are capped with an ellipsis`() {
        val body = (1..40).joinToString("\n") { "line $it of the notes" }
        val preview = EngineUpdater.previewReleaseNotes(body)!!
        assertTrue(preview.length <= 401, "400 chars + ellipsis, was ${preview.length}")
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `empty and decoration-only bodies preview to null`() {
        assertNull(EngineUpdater.previewReleaseNotes(null))
        assertNull(EngineUpdater.previewReleaseNotes(""))
        assertNull(
            EngineUpdater.previewReleaseNotes(
                "[![Badge](https://x/b.svg)](https://y)\n\n  \n"
            ),
            "a body with nothing readable is 'no notes'",
        )
    }

    // ---------------- discovery captures the notes ----------------

    @TempDir
    lateinit var dir: File

    private fun releaseJson(tag: String, version: String, bodyField: String): String =
        """
            {
              "tag_name": "$tag",
              "prerelease": false,
              "draft": false,
              "body": "$bodyField",
              "assets": [
                { "name": "wearsic-server-termux-$version.zip",
                  "browser_download_url": "https://example.invalid/wearsic-server-termux-$version.zip" },
                { "name": "wearsic-server-termux-$version.zip.sha256",
                  "browser_download_url": "https://example.invalid/wearsic-server-termux-$version.zip.sha256" }
              ]
            }
        """.trimIndent()

    /** Serves a GitHub-shaped releases list over local HTTP (offline). */
    private fun serveReleases(body: String): Pair<String, AutoCloseable> {
        val server = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing { get("/releases") { call.respondText(body, ContentType.Application.Json) } }
        }.start(wait = false)
        val port = runBlocking { server.resolvedConnectors().first().port }
        return "http://127.0.0.1:$port/releases" to AutoCloseable { server.stop(500, 1_000) }
    }

    private fun updaterOn(url: String): EngineUpdater = EngineUpdater(
        client = HttpClient(CIO),
        stateDir = dir,
        releasesApiUrl = url,
        minZipBytes = 1,
        runningVersion = "1.0.0",
    )

    @Test
    fun `checkForUpdate captures the target release notes`() {
        // \\n in the Kotlin string is the two characters ("\", "n") that JSON
        // unescapes to a newline — exactly how GitHub's API ships bodies.
        val (url, close) = serveReleases(
            "[${releaseJson("v9.9.9", "9.9.9", "# Wearsic Engine v9.9.9\\n\\nExtraction fallback hardening.")}]"
        )
        try {
            val updater = updaterOn(url)
            val update = runBlocking { updater.checkForUpdate() }
            assertEquals("9.9.9", update?.version)
            assertEquals("9.9.9", updater.latestKnownVersion)
            val notes = updater.latestReleaseNotes
            assertTrue(
                notes?.contains("Extraction fallback hardening") == true,
                "notes must carry the body text, got: $notes",
            )
            assertTrue(notes?.startsWith("Wearsic Engine") == true, "headings are unhashed")
            assertEquals(
                notes, update?.notes,
                "the RemoteUpdate handed to downloadAndStage carries the same notes",
            )
        } finally {
            close.close()
        }
    }

    @Test
    fun `a release with no body yields no notes`() {
        val (url, close) = serveReleases("[${releaseJson("v9.9.9", "9.9.9", "")}]")
        try {
            val updater = updaterOn(url)
            runBlocking { updater.checkForUpdate() }
            assertNull(updater.latestReleaseNotes)
        } finally {
            close.close()
        }
    }

    @Test
    fun `notes fall back to the newest installable release when up to date`() {
        // Running version IS the newest: no update, but the notes preview
        // should still describe "what you're on / what you'd move to".
        val (url, close) = serveReleases(
            "[${releaseJson("v1.0.0", "1.0.0", "The latest stable story.")}]"
        )
        try {
            val updater = updaterOn(url)
            val update = runBlocking { updater.checkForUpdate() }
            assertNull(update, "no newer release is offered")
            assertEquals(
                "The latest stable story.",
                updater.latestReleaseNotes,
                "notes still come from the newest installable release",
            )
        } finally {
            close.close()
        }
    }
}
