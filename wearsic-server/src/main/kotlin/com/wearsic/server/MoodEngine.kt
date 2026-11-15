package com.wearsic.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Real mood mixes. A mood is a curated genre/energy surface (server-side
 * query catalog) that is then PERSONALIZED from the user's recent listening:
 * seeds give a taste profile (which artists dominate), and candidates from
 * the mood query are boosted when their uploader matches that profile.
 *
 * This replaces the old client behavior where a mood chip literally typed
 * "chill lo-fi beats" into the search bar — a text search that was not
 * personalized and could repeat the same songs.
 */
class MoodEngine(
    private val metadata: MetadataSearchOrchestrator,
    private val gateway: YoutubeMetadataClient,
) {
    companion object {
        const val MAX_SEEDS = 6
        const val DEFAULT_LIMIT = 20
        private const val BASE_SCORE = 10
        private const val ARTIST_BOOST = 25
        private const val ARTIST_NAME_BOOST = 5

        /**
         * Canonical mood ids -> YT Music-facing queries. The first non-empty
         * result set wins; the fallback is used when the primary is sparse.
         */
        private val CATALOG: Map<String, List<String>> = mapOf(
            "chill" to listOf("chill lo-fi beats", "chill music"),
            "pop" to listOf("pop hits", "pop music today"),
            "workout" to listOf("workout energy", "gym workout songs"),
            "jazz" to listOf("smooth jazz cafe", "jazz classics"),
            "focus" to listOf("ambient focus music", "study focus beats"),
            "throwbacks" to listOf("90s throwback hits", "old school hits"),
            "party" to listOf("party dance hits", "party anthems"),
        )
    }

    data class MoodSeeds(
        val mood: String,
        val trackIds: List<String> = emptyList(),
        val exclude: Set<String> = emptySet(),
    )

    /** The resolved mood id, or null when unknown. */
    fun knownMood(mood: String): String? =
        CATALOG.keys.firstOrNull { it == mood.trim().lowercase() }

    suspend fun build(seeds: MoodSeeds, limit: Int = DEFAULT_LIMIT): List<TrackDto> {
        val mood = knownMood(seeds.mood) ?: return emptyList()
        val queries = CATALOG.getValue(mood)

        // 1) Taste profile from seeds: which artists dominate recent listens.
        val tasteArtists = tasteProfile(seeds.trackIds.take(MAX_SEEDS))

        // 2) Fetch candidates for the first query that yields enough.
        var candidates = metadata.search(queries.first(), limit = 40)
        if (candidates.size < 8 && queries.size > 1) {
            candidates = metadata.search(queries[1], limit = 40)
        }
        if (candidates.isEmpty()) return emptyList()

        // 3) Score: exclude + artist boost, then dedupe.
        val exclude = seeds.exclude
        val scored = ArrayList<Pair<Int, TrackDto>>()
        val seen = HashSet<String>()
        for (track in candidates) {
            if (track.videoId.isBlank() || track.videoId in seen) continue
            if (track.videoId in exclude) continue
            seen.add(track.videoId)
            scored.add(score(track, tasteArtists) to track)
        }
        return scored
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.videoId }
            .take(limit)
    }

    private suspend fun tasteProfile(seedIds: List<String>): Set<String> {
        if (seedIds.isEmpty()) return emptySet()
        val counts = HashMap<String, Int>()
        coroutineScope {
            seedIds.map { seedId ->
                async(Dispatchers.IO) {
                    runCatching {
                        gateway.related(seedId)
                    }.getOrDefault(emptyList())
                }
            }.forEach { deferred ->
                for (track in deferred.await()) {
                    val uploader = track.uploader.trim().lowercase()
                    if (uploader.isNotEmpty()) {
                        counts[uploader] = (counts[uploader] ?: 0) + 1
                    }
                }
            }
        }
        // Artists appearing at least twice across seeds = a real taste signal.
        return counts.filterValues { it >= 2 }.keys
    }

    private fun score(track: TrackDto, tasteArtists: Set<String>): Int {
        var s = BASE_SCORE
        val uploader = track.uploader.trim().lowercase()
        if (uploader in tasteArtists) s += ARTIST_BOOST
        if (tasteArtists.any { track.title.lowercase().contains(it) }) s += ARTIST_NAME_BOOST
        return s
    }
}
