package com.wearsic.app.ui.screens

/**
 * Pure logic for the Home feed — JVM-testable decisions only, no Compose.
 */

/** A curated discovery chip. [id] is the server mood id; [label] shows to the user. */
data class WearsicMood(val id: String, val label: String)

/**
 * Mood chips for Home. Each builds a REAL mood mix via the server's
 * /api/mood endpoint: the mood's genre catalog blended with the user's
 * recent-listening taste profile, excluding queue + recently played so
 * songs do not repeat. Known ids mirror the server MoodEngine catalog.
 */
fun homeMoods(): List<WearsicMood> = listOf(
    WearsicMood("pop", "Pop hits"),
    WearsicMood("chill", "Chill lo-fi"),
    WearsicMood("workout", "Workout"),
    WearsicMood("jazz", "Jazz & coffee"),
    WearsicMood("focus", "Focus flow"),
    WearsicMood("throwbacks", "Throwbacks"),
    WearsicMood("party", "Party"),
)

/**
 * Time-of-day greeting for the Home header. Pure so the premium
 * "personal now" moment stays testable.
 *
 *   5–11  → Good morning   12–16 → Good afternoon   17–22 → Good evening   23–4 → Into the night
 */
fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..22 -> "Good evening"
    else -> "Into the night"
}
