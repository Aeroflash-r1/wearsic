package com.wearsic.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the lyrics service: provider adapter shaping (synced vs plain vs
 * unavailable), cache behavior, and key normalization. All offline — the
 * provider is a fake.
 */
class LyricsServiceTest {

    private class FakeProvider(
        var result: LyricsFetchResult = LyricsFetchResult.NotAvailable,
        var calls: Int = 0,
    ) : LyricsProvider {
        override suspend fun fetch(query: LyricsQuery): LyricsFetchResult {
            calls++
            return result
        }
    }

    private fun query(title: String = "Come As You Are", artist: String = "Nirvana") =
        LyricsQuery(videoId = "abc123abc12", title = title, artist = artist, durationMs = 240_000L)

    @Test
    fun `synced provider result is shaped into timed lines`() = runBlocking {
        val provider = FakeProvider(
            LyricsFetchResult.Synced("[00:01.00]hi\n[00:02.00]there\n", "lrclib"),
        )
        val service = LyricsService(provider)
        val resp = service.lyrics(query())
        assertEquals("synced", resp.mode)
        assertEquals("lrclib", resp.source)
        assertEquals(2, resp.lines.size)
        assertEquals(1_000L, resp.lines[0].timeMs)
        assertEquals("hi", resp.lines[0].text)
        assertNull(resp.plainText)
    }

    @Test
    fun `synced provider result that fails to parse falls back to unavailable`() = runBlocking {
        val provider = FakeProvider(
            LyricsFetchResult.Synced("no time tags at all", "lrclib"),
        )
        val resp = LyricsService(provider).lyrics(query())
        assertEquals("unavailable", resp.mode)
        assertTrue(resp.lines.isEmpty())
    }

    @Test
    fun `plain provider result is shaped into untimed paragraphs`() = runBlocking {
        val provider = FakeProvider(
            LyricsFetchResult.Plain("Line one\nLine two\n\nLine three", "lrclib"),
        )
        val resp = LyricsService(provider).lyrics(query())
        assertEquals("plain", resp.mode)
        assertEquals(3, resp.lines.size)
        assertTrue(resp.lines.all { it.timeMs == null })
        assertEquals("Line one", resp.lines[0].text)
        assertEquals("Line one\nLine two\n\nLine three", resp.plainText)
    }

    @Test
    fun `unavailable is returned when the provider says so`() = runBlocking {
        val provider = FakeProvider(LyricsFetchResult.NotAvailable)
        val resp = LyricsService(provider).lyrics(query())
        assertEquals("unavailable", resp.mode)
        assertTrue(resp.lines.isEmpty())
    }

    @Test
    fun `provider exceptions degrade to unavailable, never throw`() = runBlocking {
        val provider = object : LyricsProvider {
            override suspend fun fetch(query: LyricsQuery): LyricsFetchResult =
                throw RuntimeException("provider down")
        }
        val resp = LyricsService(provider).lyrics(query())
        assertEquals("unavailable", resp.mode)
    }

    @Test
    fun `hits are cached and repeat calls do not hit the provider`() = runBlocking {
        val provider = FakeProvider(
            LyricsFetchResult.Synced("[00:01.00]hi\n", "lrclib"),
        )
        val service = LyricsService(provider)
        assertEquals("synced", service.lyrics(query()).mode)
        assertEquals("synced", service.lyrics(query()).mode)
        assertEquals(1, provider.calls, "second call must be served from the cache")
    }

    @Test
    fun `cache key is duration-bucketed`() = runBlocking {
        val provider = FakeProvider(LyricsFetchResult.NotAvailable)
        val service = LyricsService(provider)
        // Same song, durations 10s apart -> same bucket (15s) -> one call.
        service.lyrics(query().let { it.copy(durationMs = 240_000L) })
        service.lyrics(query().let { it.copy(durationMs = 250_000L) })
        assertEquals(1, provider.calls)
    }
}
