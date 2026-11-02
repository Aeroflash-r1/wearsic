package com.wearsic.app.network.model

import com.wearsic.app.model.LyricLine
import com.wearsic.app.model.LyricsMode
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Pins the server lyrics DTO -> domain mapping and the sync index math. */
class LyricsDtoTest {

    @Test
    fun `synced response maps mode, lines and timestamps`() {
        val dto = LyricsResponseDto(
            mode = "synced",
            source = "lrclib",
            lines = listOf(LyricsLineDto(timeMs = 1000L, text = "one"), LyricsLineDto(timeMs = 4000L, text = "two")),
        )
        val lyrics = dto.toDomainLyrics()
        assertEquals(LyricsMode.Synced, lyrics.mode)
        assertEquals("lrclib", lyrics.source)
        assertEquals(listOf(LyricLine(1000L, "one"), LyricLine(4000L, "two")), lyrics.lines)
    }

    @Test
    fun `plain response maps to paragraphs without timestamps`() {
        val dto = LyricsResponseDto(
            mode = "plain",
            plainText = "hello\nworld",
            lines = listOf(LyricsLineDto(timeMs = null, text = "hello"), LyricsLineDto(timeMs = null, text = "world")),
        )
        val lyrics = dto.toDomainLyrics()
        assertEquals(LyricsMode.Plain, lyrics.mode)
        assertTrue(lyrics.lines.all { it.timeMs == null })
        assertEquals("hello\nworld", lyrics.plainText)
    }

    @Test
    fun `unavailable and unknown modes map to Unavailable`() {
        assertEquals(LyricsMode.Unavailable, LyricsResponseDto().toDomainLyrics().mode)
        assertEquals(LyricsMode.Unavailable, LyricsResponseDto(mode = "weird").toDomainLyrics().mode)
    }

    @Test
    fun `activeIndexFor picks the line at or before the position`() {
        val lyrics = LyricsResponseDto(
            mode = "synced",
            lines = listOf(
                LyricsLineDto(timeMs = 0L, text = "intro"),
                LyricsLineDto(timeMs = 5000L, text = "verse"),
                LyricsLineDto(timeMs = 15000L, text = "chorus"),
            ),
        ).toDomainLyrics()

        assertEquals(0, lyrics.activeIndexFor(-1L))
        assertEquals(0, lyrics.activeIndexFor(0L))
        assertEquals(0, lyrics.activeIndexFor(4999L))
        assertEquals(1, lyrics.activeIndexFor(5000L))
        assertEquals(1, lyrics.activeIndexFor(14999L))
        assertEquals(2, lyrics.activeIndexFor(15000L))
        assertEquals(2, lyrics.activeIndexFor(999999L))
    }

    @Test
    fun `activeIndexFor is -1 for non-synced lyrics`() {
        val plain = LyricsResponseDto(mode = "plain", lines = listOf(LyricsLineDto(text = "x"))).toDomainLyrics()
        assertEquals(-1, plain.activeIndexFor(1000L))
        assertEquals(-1, LyricsResponseDto().toDomainLyrics().activeIndexFor(1000L))
    }
}
