package com.wearsic.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * /api/search returns YouTube Music metadata directly - official titles,
 * artists, durations and artwork with REAL YouTube videoIds. No surrogate
 * ids, no second matching step: results play immediately.
 *
 * Fallback contract (see OrchestratorFallbackTest):
 *   YTM usable results   -> return them; only stream prefetch runs behind
 *                           the response (warms the CDN-URL cache)
 *   YTM empty/unusable   -> direct NewPipeExtractor YouTube search so the
 *                           watch still gets results instead of an empty page
 *
 * Legacy `it:<id>` ids saved by pre-1.5 builds (favorites/playlists from the
 * old iTunes layer) still resolve via the persistent match store.
 */
class MetadataSearchOrchestrator(
    private val metadata: MetadataSource,
    private val youtube: YoutubeMetadataClient,
    /**
     * Legacy surrogate -> videoId matches (pre-1.5 `it:<id>` ids). YTM ids
     * need no matching, but saved favorites from the old iTunes layer replay
     * instantly when their match survived. Nullable so tests run in-memory.
     * Read-only: no new matches are ever written since the iTunes layer was
     * removed — this only serves rows persisted by older builds.
     */
    private val persistentMatches: MatchPersistence? = null,
) {
    companion object {
        /**
         * Only the top-2 results are pre-resolved: the user taps one of them
         * ~80% of the time, and each prefetch holds the global extraction
         * mutex for seconds. 6 prefetches held it for 12-24s and made the
         * actual tap queue behind background work — the opposite of instant.
         */
        private const val PREFETCH_COUNT = 2

        /** Pause between prefetch extractions so user taps can jump ahead. */
        private const val PREFETCH_EXTRACT_STAGGER_MS = 300L

        /** Prefix used by the removed iTunes layer; kept for legacy replay. */
        const val LEGACY_IT_PREFIX = "it:"
    }

    /** Read-only view over persisted legacy surrogate matches (SQLite). */
    interface MatchPersistence {
        fun getMatchedVideoId(surrogateId: String): String?
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val matchCache = BoundedCache<String, String>(maxSize = 256)

    /**
     * Counts fallback invocations; exposed for tests to prove that usable
     * YTM results never trigger the synchronous YouTube fallback.
     */
    var youtubeFallbackCount: Int = 0
        private set

    suspend fun search(query: String): List<TrackDto> {
        val tracks = metadata.searchSongs(query)
        if (tracks.isNotEmpty()) {
            prefetch(tracks.take(PREFETCH_COUNT))
            return tracks.map { metadata.toTrackDto(it) }
        }
        youtubeFallbackCount++
        return youtube.search(query)
    }

    /**
     * Warms the stream cache for already-known videoIds (album/playlist open,
     * radio results): by the time the user reads the list and taps, the first
     * tracks resolve instantly instead of queueing a cold extraction.
     */
    fun prefetchVideoIds(videoIds: List<String>) {
        val ids = videoIds.filter { it.isNotBlank() && !it.startsWith(LEGACY_IT_PREFIX) }
        if (ids.isEmpty()) return
        prefetch(ids.map { YtmTrack(videoId = it) })
    }

    private var prefetchJob: Job? = null

    /**
     * Warms the stream-URL cache top-first so taps play instantly. YTM ids
     * are already real videoIds, so this is pure extraction with a stagger
     * that lets an interactive tap jump the queue.
     */
    private fun prefetch(tracks: List<YtmTrack>) {
        prefetchJob?.cancel()
        prefetchJob = backgroundScope.launch {
            coroutineScope {
                for (track in tracks) {
                    if (!isActive) break
                    runCatching { youtube.streamTarget(track.videoId) }
                    delay(PREFETCH_EXTRACT_STAGGER_MS)
                }
            }
        }
    }

    /**
     * YTM search ids are real YouTube ids and pass through unchanged.
     * Legacy `it:<id>` ids consult the in-memory + persisted match store.
     */
    suspend fun resolveStreamVideoId(requestedId: String): String? {
        if (!requestedId.startsWith(LEGACY_IT_PREFIX)) return requestedId
        matchCache.get(requestedId)?.let { return it }
        persistentMatches?.let { store ->
            runCatching { store.getMatchedVideoId(requestedId) }.getOrNull()?.let { cached ->
                matchCache.put(requestedId, cached)
                return cached
            }
        }
        return null
    }

    /** Cancels any in-flight prefetch; call on server shutdown in tests. */
    fun shutdown() {
        prefetchJob?.cancel()
        backgroundScope.coroutineContext[Job]?.cancel()
    }
}
