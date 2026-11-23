package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LrcParserTest {

    @Test
    fun `parses standard timestamps with milliseconds`() {
        val parsed = LrcParser.parse(
            """
            [00:12.34]First line
            [00:15.00]Second line
            [00:18.50]Third line
            """.trimIndent(),
        )
        assertTrue(parsed.complete)
        assertEquals(3, parsed.lines.size)
        assertEquals(12_340L, parsed.lines[0].timeMs)
        assertEquals("First line", parsed.lines[0].text)
        assertEquals(15_000L, parsed.lines[1].timeMs)
        assertEquals(18_500L, parsed.lines[2].timeMs)
    }

    @Test
    fun `supports colon millisecond separator and variable widths`() {
        val parsed = LrcParser.parse("[00:01:5]a\n[00:02:55]b\n[00:03:555]c\n")
        assertTrue(parsed.complete)
        assertEquals(1_500L, parsed.lines[0].timeMs)
        assertEquals(2_550L, parsed.lines[1].timeMs)
        assertEquals(3_555L, parsed.lines[2].timeMs)
    }

    @Test
    fun `multiple timestamps on one line duplicate the line`() {
        val parsed = LrcParser.parse("[00:01.00][00:03.00]echo\n")
        assertEquals(2, parsed.lines.size)
        assertEquals(1_000L, parsed.lines[0].timeMs)
        assertEquals(3_000L, parsed.lines[1].timeMs)
        assertEquals("echo", parsed.lines[0].text)
    }

    @Test
    fun `metadata tags are ignored`() {
        val parsed = LrcParser.parse("[ti:Some Song]\n[ar:Some Artist]\n[00:10.00]real line\n")
        assertEquals(1, parsed.lines.size)
        assertEquals("real line", parsed.lines[0].text)
    }

    @Test
    fun `lines are sorted by time regardless of input order`() {
        val parsed = LrcParser.parse("[00:30.00]later\n[00:05.00]earlier\n")
        assertEquals("earlier", parsed.lines[0].text)
        assertEquals("later", parsed.lines[1].text)
    }

    @Test
    fun `blank and garbage input is not complete`() {
        assertFalse(LrcParser.parse("").complete)
        assertFalse(LrcParser.parse("no timestamps here").complete)
    }

    @Test
    fun `untimed plain text is rejected by the parser`() {
        val parsed = LrcParser.parse("Only plain lyrics, no time tags.")
        assertFalse(parsed.complete)
        assertTrue(parsed.lines.isEmpty())
    }
}
