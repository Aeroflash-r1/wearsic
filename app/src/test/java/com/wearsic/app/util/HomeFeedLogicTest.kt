package com.wearsic.app.ui.screens

import com.wearsic.app.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Home feed logic (greetings, mood chips,
 * the "New for you" freshness filter).
 */
class HomeFeedLogicTest {

    @Test
    fun `greeting buckets match the day`() {
        assertEquals("Good morning", greetingForHour(5))
        assertEquals("Good morning", greetingForHour(11))
        assertEquals("Good afternoon", greetingForHour(12))
        assertEquals("Good afternoon", greetingForHour(16))
        assertEquals("Good evening", greetingForHour(17))
        assertEquals("Good evening", greetingForHour(22))
        assertEquals("Into the night", greetingForHour(23))
        assertEquals("Into the night", greetingForHour(4))
    }

    @Test
    fun `every boundary hour yields a greeting`() {
        for (hour in 0..23) {
            val g = greetingForHour(hour)
            assertTrue("hour $hour must resolve to a non-blank greeting", g.isNotBlank())
        }
    }

    @Test
    fun `moods are unique and search-ready`() {
        val moods = homeMoods()
        assertTrue("a useful mood budget (got ${moods.size})", moods.size in 4..10)
        assertEquals("labels unique", moods.map { it.label }.distinct().size, moods.size)
        for (mood in moods) {
            assertTrue("label not blank", mood.label.isNotBlank())
            assertTrue("mood id not blank", mood.id.isNotBlank())
        }
    }

    // ── "New for you" freshness filter ──────────────────────────────────────

    @Test
    fun `filterNewToYou drops tracks the user already knows`() {
        val fresh = Track(id = "new1", title = "New Song")
        val heard = Track(id = "heard1", title = "Old Favourite")
        val result = filterNewToYou(listOf(fresh, heard), knownIds = setOf("heard1"))
        assertEquals("only the unknown track survives", listOf("new1"), result.map { it.id })
    }

    @Test
    fun `filterNewToYou preserves server order and caps the shelf`() {
        val tracks = (1..15).map { Track(id = "t$it", title = "Song $it") }
        val result = filterNewToYou(tracks, knownIds = setOf("t2"), limit = 10)
        assertEquals("cap honoured", 10, result.size)
        assertEquals("order preserved (t2 skipped, no reshuffle)", "t1", result[0].id)
        assertEquals("t2 excluded", "t3", result[1].id)
        assertEquals("cap is exact", "t11", result.last().id)
    }

    @Test
    fun `filterNewToYou rejects blank ids and empty input`() {
        val blank = Track(id = "", title = "No id")
        assertTrue("blank id filtered", filterNewToYou(listOf(blank), emptySet()).isEmpty())
        assertTrue("empty input stays empty", filterNewToYou(emptyList(), setOf("x")).isEmpty())
        assertTrue("empty known set keeps everything", filterNewToYou(listOf(blank.copy(id = "a")), emptySet()).isNotEmpty())
    }
}
