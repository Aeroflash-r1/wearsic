package com.wearsic.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Home feed logic (greetings, mood chips).
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
            assertTrue("query not blank", mood.query.isNotBlank())
        }
    }
}
