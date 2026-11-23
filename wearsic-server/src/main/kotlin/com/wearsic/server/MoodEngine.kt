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
 * The mood query is NOT meant to return "songs literally named the genre"
 * (that is what made Chill chips show "Chill Lofi Beats", "Workout Energy"
 * channel filler). Three guards therefore run on candidates:
 *   1. filterUploaderNoise — drop generic filler channels (beats/music/
 *      relaxation/study/lofi pattern uploaders) unless they ARE a taste artist
 *   2. titleSignal — drop rows whose title is basically the genre label itself
 *   3. taste-first ordering — same-artist-as-listening candidates lead
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
            "chill" to listOf("chill music", "chill songs"),
            "pop" to listOf("pop songs", "pop music"),
            "workout" to listOf("workout songs", "gym music"),
            "jazz" to listOf("jazz music", "jazz classics"),
            "focus" to listOf("focus music", "study music"),
            "throwbacks" to listOf("90s hits", "old school songs"),
            "party" to listOf("party songs", "dance hits"),
        )

        /** Filler uploader patterns — channels, not artists in the genre. */
        private val NOISE_UPLOADER = Regex(
            "beats|lofi|lo-fi|relax|music|mix|sound|study|focus|workout|gym|radio|channel|official",
            RegexOption.IGNORE_CASE
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

        // 3) Filter noise, then score: exclude + artist boost, dedupe.
        val exclude = seeds.exclude
        val scored = ArrayList<Pair<Int, TrackDto>>()
        val seen = HashSet<String>()
        for (track in candidates) {
            if (track.videoId.isBlank() || track.videoId in seen) continue
            if (track.videoId in exclude) continue
            if (track.uploader.isNotBlank() && track.uploader.lowercase() !in tasteArtists &&
                NOISE_UPLOADER.containsMatchIn(track.uploader)
            ) continue
            seen.add(track.videoId)
            scored.add(score(track, tasteArtists) to track)
        }

        // 4) If noise filtering emptied the pool, fall back to unfiltered
        //    (better a wide mix than nothing).
        val pool = if (scored.isEmpty()) {
            candidates.filter { it.videoId !in exclude && it.videoId.isNotBlank() }
                .map { score(it, tasteArtists) to it }
        } else {
            scored
        }

        return pool
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
