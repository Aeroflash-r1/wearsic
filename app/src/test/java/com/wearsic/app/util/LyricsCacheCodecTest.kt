package com.wearsic.app.util

import com.wearsic.app.data.lyrics.LyricsCacheCodec
import com.wearsic.app.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the offline-lyrics persistence codec (plain JUnit, runs on ARM too):
 * a round-trip must preserve every line, and garbage must decode to null so
 * a corrupted cache row falls through to a (re)fetch instead of crashing.
 */
class LyricsCacheCodecTest {

    @Test
    fun `lines round trip losslessly`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "Yellow"),
            LyricLine(timeMs = 4200L, text = "And it was called Yellow"),
            LyricLine(timeMs = null, text = "plain text line"),
        )
        val raw = LyricsCacheCodec.encode(lines)
        assertEquals(lines, LyricsCacheCodec.decode(raw))
    }

    @Test
    fun `malformed json decodes to null`() {
        assertNull(LyricsCacheCodec.decode("not json"))
        assertNull(LyricsCacheCodec.decode(""))
        assertNull(LyricsCacheCodec.decode("[{\"x\":1}]"))
    }
}
