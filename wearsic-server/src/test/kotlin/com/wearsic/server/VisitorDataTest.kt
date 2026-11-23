package com.wearsic.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Parser for the session visitor id out of YouTube's `sw.js_data` blob.
 *
 * This id is what flips the VISIONOS player from LOGIN_REQUIRED to OK —
 * its URLs are the ones that stream past the 1 MB CDN wall — so a parser
 * regression means "no playable stream", not just a cosmetic miss.
 */
class VisitorDataTest {

    @Test
    fun `parses the visitor id from an sw js data blob`() {
        val blob = ")]}'\n" +
            "[[\"yt.sw.adr\",null,[[[\"en-GB\",\"IN\",null,\"117.0.0.1\"," +
            "\"CgtestVisitorData0123456789abcdefghijklmnop\"]]]]]"
        assertEquals("CgtestVisitorData0123456789abcdefghijklmnop", visitorDataFrom(blob))
    }

    @Test
    fun `parses a nested visitor id wherever it sits`() {
        val blob = "[ [ \"en-GB\", [ \"x\", \"CgdeeplyNestedVisitorDataValue123456789\" ] ] ]"
        assertEquals("CgdeeplyNestedVisitorDataValue123456789", visitorDataFrom(blob))
    }

    @Test
    fun `real-world visitor ids contain url-encoded characters`() {
        // Live sample: the id body carries percent-encoded bytes (e.g. %2F).
        val id = "CgtuamFRUXpPRVpmYyjEvszWBjIKCgJJThIEGgAgXGK1Agqy%2Ftest1234567890"
        assertEquals(id, visitorDataFrom("[[\"yt.sw.adr\",null,[[[\"en-GB\",\"$id\"]]]]]"))
    }

    @Test
    fun `short ids and whitespace-bearing strings do not count`() {
        assertNull(visitorDataFrom("[[\"Cgshort\"]]"), "too short to be a visitor id")
        assertNull(visitorDataFrom("[[\"Cgwith spaces inside the value 123456789\"]]"))
    }

    @Test
    fun `garbage and empty bodies yield null`() {
        assertNull(visitorDataFrom("not json at all"))
        assertNull(visitorDataFrom(""))
        assertNull(visitorDataFrom(")]}'\nno array here"))
    }
}
