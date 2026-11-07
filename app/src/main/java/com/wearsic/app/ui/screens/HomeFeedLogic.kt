package com.wearsic.app.ui.screens

/**
 * Pure logic for the Home feed — JVM-testable decisions only, no Compose.
 */

/** A curated discovery chip. `label` shows to the user, `query` drives the existing search API. */
data class WearsicMood(val label: String, val query: String)

/**
 * Mood chips for Home. Each runs a normal musical search through the ONE
 * search pipeline (server /api/search), so moods cost zero new backend
 * surface and zero new caches — the server's 5min metadata TTL does the
 * limiting. Queries are written like real listener searches and targeted
 * at YT Music semantics (artist/genre words), not tags nobody searches.
 */
fun homeMoods(): List<WearsicMood> = listOf(
    WearsicMood("Pop hits", "pop hits"),
    WearsicMood("Chill lo-fi", "chill lo-fi beats"),
    WearsicMood("Workout", "workout energy"),
    WearsicMood("Jazz & coffee", "smooth jazz cafe"),
    WearsicMood("Focus flow", "ambient focus music"),
    WearsicMood("Throwbacks", "90s throwback hits"),
    WearsicMood("Party", "party dance hits"),
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
