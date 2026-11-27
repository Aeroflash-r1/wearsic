package com.wearsic.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Multi-seed "made for you" recommendations.
 *
 * Tier A+B: fetch YouTube's own related-video lists for up to [MAX_SEEDS]
 * seed tracks (the user's favorites/recent plays), merge them into one pool,
 * dedupe, filter out tracks the user already knows, and SCORE by taste
 * profile — a candidate from one of the user's top artists is boosted, so
 * the shelf leans toward what the user actually likes instead of generic
 * "related to one song".
 *
 * Pure logic — no I/O except the gateway calls — so it is unit-testable.
 */
class RecommendationEngine(
    private val gateway: YoutubeMetadataClient,
) {
    data class Seeds(
        val trackIds: List<String>,
        val artists: Set<String> = emptySet(),
        val exclude: Set<String> = emptySet(),
        /** Genre taste hints ("chill", "workout", …) from listening history. */
        val genres: List<String> = emptyList(),
    )

    companion object {
        const val MAX_SEEDS = 6
        const val DEFAULT_LIMIT = 25

        /** Base score every candidate starts with. */
        private const val BASE_SCORE = 10

        /** Boost when the candidate's uploader is one of the user's top artists. */
        private const val ARTIST_BOOST = 25

        /** Small boost when the candidate title mentions a top artist. */
        private const val ARTIST_NAME_BOOST = 5

        /** Boost per matching genre keyword. */
        private const val GENRE_BOOST = 6

        /** Boost for an upload newer than [RECENT_UPLOAD_DAYS] — "new" should
         *  mean new-ish, not merely unheard. Kept below the artist boost so
         *  taste still outranks freshness. */
        private const val RECENT_UPLOAD_BOOST = 8

        /** Uploads at most this many days old count as "recent". */
        internal const val RECENT_UPLOAD_DAYS = 365L

        /** "17y ago", "3 months ago", "2 days ago", "5 hours ago". */
        private val AGE_TEXT_RE =
            Regex("(\\d+)\\s*(year|month|week|day|hour)s?\\s*ago", RegexOption.IGNORE_CASE)

        /** Upload-age text ("3 months ago", "17y ago") → approximate days. Null when unparseable. */
        internal fun ageTextToDays(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            val m = AGE_TEXT_RE.find(text) ?: return null
            val n = m.groupValues[1].toLongOrNull() ?: return null
            return when (m.groupValues[2].lowercase()) {
                "year" -> n * 365
                "month" -> n * 30
                "week" -> n * 7
                "day" -> n
                else -> n / 24 // "hours ago"
            }
        }

        /** One slow seed must not hold every other recommendation hostage. */
        internal const val RELATED_TIMEOUT_MS = 12_000L

        /**
         * Keyword groups for common vibe/Genre hints. A candidate matching a
         * hint's group earns [GENRE_BOOST]; matching several is fine, but the
         * total is capped at (hint count × GENRE_BOOST) by scoring.
         */
        private val GENRE_KEYWORDS = mapOf(
            "chill" to listOf("lofi", "lo-fi", "chill", "calm", "smooth", "acoustic", "ambient", "sleep"),
            "focus" to listOf("focus", "study", "concentration", "instrumental", "ambient", "deep focus"),
            "workout" to listOf("workout", "gym", "cardio", "energetic", "intense", "beats", "training"),
            "party" to listOf("party", "dance", "club", "remix", "festival", "hype", "banger"),
            "jazz" to listOf("jazz", "soul", "blues", "funk", "r&b", "rnb"),
            "throwback" to listOf("throwback", "hits", "classics", "90s", "80s", "oldschool", "old school"),
            "work" to listOf("work", "boss", "hustle", "grind", "motivation"),
            "sad" to listOf("sad", "heartbreak", "emotional", "cry", "slow", "melancholy"),
            "happy" to listOf("happy", "upbeat", "positive", "summer", "feel good", "good vibes"),
            "love" to listOf("love", "romantic", "romance", "relationship", "valentine"),
        )
    }

    suspend fun build(seeds: Seeds, limit: Int = DEFAULT_LIMIT): List<TrackDto> {
        if (seeds.trackIds.isEmpty()) return emptyList()

        val seen = HashSet<String>()
        val scored = ArrayList<Pair<Int, TrackDto>>()

        coroutineScope {
            seeds.trackIds.take(MAX_SEEDS).map { seedId ->
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(RELATED_TIMEOUT_MS) { gateway.related(seedId) }
                            ?: emptyList()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.forEach { deferred ->
                val related = deferred.await()
                for (track in related) {
                    if (track.videoId.isBlank() || track.videoId in seen) continue
                    if (track.videoId in seeds.exclude) continue
                    seen.add(track.videoId)
                    scored.add(score(track, seeds) to track)
                }
            }
        }

        return scored
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.videoId }
            .take(limit)
    }

    private fun score(track: TrackDto, seeds: Seeds): Int {
        var s = BASE_SCORE
        if (seeds.artists.isNotEmpty()) {
            val uploader = track.uploader.trim().lowercase()
            if (uploader in seeds.artists) s += ARTIST_BOOST
            if (seeds.artists.any { track.title.lowercase().contains(it) }) s += ARTIST_NAME_BOOST
        }
        // Genre taste: every matching keyword adds a boost, capped so a very
        // keyword-rich candidate cannot leapfrog the artist boost entirely.
        if (seeds.genres.isNotEmpty()) {
            val haystack = (track.title + " " + track.uploader).lowercase()
            var genreBoost = 0
            for (genre in seeds.genres) {
                val keywords = GENRE_KEYWORDS[genre.lowercase()] ?: continue
                if (keywords.any { haystack.contains(it) }) genreBoost += GENRE_BOOST
            }
            s += genreBoost.coerceAtMost(seeds.genres.size * GENRE_BOOST)
        }
        // Recency: between otherwise-equal candidates the fresher upload wins.
        // Unparseable or missing age text simply earns no boost.
        if (ageTextToDays(track.uploadAgeText)?.let { it <= RECENT_UPLOAD_DAYS } == true) {
            s += RECENT_UPLOAD_BOOST
        }
        return s
    }
}
