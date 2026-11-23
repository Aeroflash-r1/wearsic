package com.wearsic.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
                    runCatching { gateway.related(seedId) }.getOrDefault(emptyList())
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
        return s
    }
}
