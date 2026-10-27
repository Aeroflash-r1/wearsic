package com.wearsic.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * /api/search returns YouTube Music metadata directly - official titles,
 * artists, durations and artwork with REAL YouTube videoIds. No surrogate
 * ids, no second matching step: results play immediately.
 *
 * Fallback contract (see OrchestratorFallbackTest):
 *   YTM usable results   -> return them; only stream prefetch runs behind
 *                           the response (warms the CDN-URL cache)
 *   YTM empty/unusable/failed -> direct NewPipeExtractor YouTube search so the
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
    /**
     * Fire-and-forget "warm this prefetched target's CDN path" hook
     * (`AudioProxy::warmUpstream`). Pools the host connection and heats the
     * edge a few seconds before the likely tap, so the tap's own request
     * opens fast instead of paying a cold first byte (live-seen: 3.8s).
     */
    private val warmUpstream: ((videoId: String, url: String) -> Unit)? = null,
) {
    companion object {
        /**
         * Only the top-3 results are pre-resolved: the user taps one of them
         * most of the time, and each prefetch used to hold the global
         * extraction mutex for seconds. The lean player path needs NO mutex
         * (pure HTTP), so 3 concurrent warms are cheap; the NewPipe fallback
         * still serializes, and foreground playback preempts background work.
         */
        private const val PREFETCH_COUNT = 3


        /** Prefix used by the removed iTunes layer; kept for legacy replay. */
        const val LEGACY_IT_PREFIX = "it:"

        /**
         * Wall-clock budget for one metadata search. A hung fetch must never
         * park a SingleFlight key forever — later identical queries would hang
         * with it until restart. Timeouts fall through to the same empty path
         * as failures (and are never cached).
         */
        private const val SEARCH_TIMEOUT_MS = 30_000L

        /**
         * Total budget for one search across the exact query + relaxed
         * variants: enough to rescue a missed song without letting deep
         * multi-query searches turn a normal tap slow.
         */
        private const val SEARCH_TOTAL_BUDGET_MS = 22_000L

        /**
         * General-YouTube consult budget. A weak/missing exact page asks the
         * unfiltered index for the real upload, but that fetch must never
         * make a first search hang: 5s is plenty to either find it or move
         * on.
         */
        private const val SEARCH_GENERAL_TIMEOUT_MS = 5_000L

        /**
         * First-page budget (offset 0) is much tighter than the deep-paging
         * budget: users should see results fast. Relaxed variants only run
         * while this remains.
         */
        private const val SEARCH_FIRST_PAGE_BUDGET_MS = 7_000L

        /**
         * How long a built search pool is reused before re-fetching. Paging
         * slides within it; Show more extends it only when needed.
         */
        private const val SEARCH_POOL_TTL_MS = 5 * 60 * 1000L

        /**
         * Deepest a single search request may fetch. Paging slices this
         * window (e.g. page 1 = rows 0..9, page 2 = 10..19, …).
         */
        private const val MAX_FETCH = 50
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
    private val fallbackCount = AtomicInteger()
    val youtubeFallbackCount: Int get() = fallbackCount.get()

    /**
     * Merged, ranked pool per normalized query, so pagination is stable and
     * non-repeating: every offset slices the same underlying ordered pool,
     * and "Show more" only extends it when a deeper window is requested.
     */
    private class SearchPool(val tracks: List<TrackDto>, val expiresAt: Long)

    private val searchPoolCache = BoundedCache<String, SearchPool>(64)

    suspend fun search(query: String, limit: Int = 10, offset: Int = 0): List<TrackDto> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return emptyList()
        require(normalized.length <= 200) { "Search query is too long" }
        val offsetClamped = offset.coerceAtLeast(0)
        val requiredEnd = (offsetClamped + limit).coerceIn(1, MAX_FETCH)
        val now = System.currentTimeMillis()

        val entry = searchPoolCache.get(normalized)
        if (entry == null || now > entry.expiresAt) {
            // First build: keep it fast — only enough rows for the requested
            // window (no speculative 2x fetches on the first page).
            val budget = if (offsetClamped == 0) SEARCH_FIRST_PAGE_BUDGET_MS else SEARCH_TOTAL_BUDGET_MS
            val built = buildPool(normalized, requiredEnd, budget, emptyList())
            searchPoolCache.put(normalized, SearchPool(built, now + SEARCH_POOL_TTL_MS))
            return built.drop(offsetClamped).take(limit)
        }

        var pool = entry.tracks
        if (pool.size < requiredEnd) {
            // "Show more": extend the pool to cover the deeper window.
            val extended = buildPool(normalized, requiredEnd, SEARCH_TOTAL_BUDGET_MS, pool)
            searchPoolCache.put(normalized, SearchPool(extended, now + SEARCH_POOL_TTL_MS))
            pool = extended
        }
        return pool.drop(offsetClamped).take(limit)
    }

    /**
     * Builds (or extends from [seed]) the merged+ranked pool for a query up
     * to [maxTake] rows. YTM exact → strong fast path → general YouTube
     * consult → relaxed variants, all under [budgetMs].
     */
    private suspend fun buildPool(
        normalized: String,
        maxTake: Int,
        budgetMs: Long,
        seed: List<TrackDto>
    ): List<TrackDto> {
        val collectLimit = maxTake.coerceAtMost(MAX_FETCH)
        val startedAt = System.currentTimeMillis()
        val byId = LinkedHashMap<String, TrackDto>()
        if (seed.isNotEmpty()) {
            for (t in seed) {
                if (!byId.containsKey(t.videoId)) byId[t.videoId] = t
            }
        }
        // A hard cap for temporary collection: weak sources may fill the
        // window, so rescue sources need room; the final slice trims back to
        // exactly `maxTake` after ranking.
        val safetyCap = (maxTake * 3).coerceAtMost(MAX_FETCH)

        fun addAll(tracks: List<TrackDto>) {
            for (t in tracks) {
                if (!byId.containsKey(t.videoId)) byId[t.videoId] = t
                if (byId.size >= safetyCap) break
            }
        }

        // 1. EXACT query on YouTube Music first.
        if (byId.size < safetyCap) {
            val ytmExact = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                try {
                    metadata.searchSongs(normalized, collectLimit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }?.filter { it.videoId.isNotBlank() }?.distinctBy { it.videoId }?.take(collectLimit)
                ?: emptyList()
            if (ytmExact.isNotEmpty()) {
                prefetch(ytmExact.take(PREFETCH_COUNT))
                addAll(ytmExact.map { metadata.toTrackDto(it) })
            }
        }

        // 2. Fast path: the exact query alone produced enough strong matches.
        if (byId.size >= maxTake && strongMatchCount(byId.values, normalized) >= maxTake) {
            return rankByQueryOverlap(byId.values.toList(), normalized)
        }

        // 3. Weak/missing exact page: ask GENERAL YouTube (bounded), even if
        //    the window is already full of weak rows — rank decides what the
        //    final slice keeps.
        if (byId.size < safetyCap) {
            val ytExact = withTimeoutOrNull(SEARCH_GENERAL_TIMEOUT_MS) {
                youtube.searchGeneral(normalized, collectLimit)
            } ?: emptyList()
            val beforeYouTube = byId.size
            addAll(ytExact)
            if (byId.size > beforeYouTube) fallbackCount.incrementAndGet()
        }

        // 4. Relaxed variants top up remaining slots, while budget allows.
        for (q in relaxedQueries(normalized)) {
            if (byId.size >= safetyCap) break
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > budgetMs) break
            val remaining = budgetMs - elapsed
            addAll(fetchWindowOnce(q, collectLimit, remaining.coerceAtLeast(500L)))
        }

        return rankByQueryOverlap(byId.values.toList(), normalized)
    }

    /** One query window = metadata source, then the YouTube scrape fallback. */
    private suspend fun fetchWindowOnce(
        query: String,
        take: Int,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): List<TrackDto> {
        val tracks = withTimeoutOrNull(timeoutMs) {
            try {
                metadata.searchSongs(query, take)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }?.filter { it.videoId.isNotBlank() }?.distinctBy { it.videoId }?.take(take)
            ?: emptyList()
        if (tracks.isNotEmpty()) {
            prefetch(tracks.take(PREFETCH_COUNT))
            return tracks.map { metadata.toTrackDto(it) }
        }
        fallbackCount.incrementAndGet()
        val fallback = withTimeoutOrNull(timeoutMs) {
            youtube.search(query, take)
        } ?: emptyList()
        return fallback.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }.take(take)
    }

    /**
     * Progressive relaxations of a query. Used both when the exact query
     * finds nothing and to top up a short/weak result page: strip
     * parentheticals, strip "official video/audio/lyrics" suffixes, drop a
     * leading article, keep only the part after an artist separator
     * ("X - Song"), and try the last two words.
     */
    private fun relaxedQueries(query: String): List<String> {
        val out = mutableListOf<String>()
        val noParen = query
            .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noParen != query && noParen.isNotEmpty()) out += noParen
        val noSuffix = noParen
            .replace(Regex("(?i)\\b(official\\s*)?(video|audio|lyrics?|music\\s*video)\\b\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noSuffix.isNotEmpty() && noSuffix != noParen) out += noSuffix
        // "Lil Tjay x Pop Smoke - Mary Jane (Unreleased Audio)" -> try the
        // part after the last separator: "Mary Jane (Unreleased Audio)".
        val separator = Regex("\\s+[-–—]\\s+")
        val parts = noSuffix.split(separator)
        if (parts.size > 1) {
            val remainder = parts.last().trim()
            if (remainder.length >= 4 && remainder != noSuffix) out += remainder
        }
        val words = noSuffix.split(" ")
        if (words.size > 2) {
            out += words.takeLast(2).joinToString(" ")
            val noLead = words
                .filterIndexed { i, w -> !(i == 0 && w.lowercase() in setOf("the", "a", "an")) }
                .joinToString(" ")
            if (noLead != noSuffix && noLead.isNotEmpty()) out += noLead
        }
        return out.distinct().take(5)
    }

    /** Lowercased, non-trivial word tokens of a string. */
    private fun normalizedTokens(s: String): Set<String> =
        s.lowercase().split(Regex("\\W+")).filter { it.length > 1 }.toSet()

    /**
     * Fraction of query tokens present in the track's title/artist. 1f =
     * every query word appears; 0f = no overlap.
     */
    private fun overlapScore(t: TrackDto, q: String): Float {
        val qt = normalizedTokens(q)
        if (qt.isEmpty()) return 0f
        val ht = normalizedTokens((t.title ?: "") + " " + (t.uploader ?: ""))
        val hit = ht.count { it in qt }
        return hit.toFloat() / qt.size
    }

    private fun strongMatchCount(items: Collection<TrackDto>, q: String): Int =
        items.count { overlapScore(it, q) >= 0.5f }

    /** Stable relevance sort: best token overlap first, ties keep original order. */
    private fun rankByQueryOverlap(items: List<TrackDto>, q: String): List<TrackDto> =
        items.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<TrackDto>> { overlapScore(it.value, q) }
                    .thenBy { it.index }
            )
            .map { it.value }

    /**
     * Warms the stream cache for already-known videoIds (album/playlist open,
     * radio results): by the time the user reads the list and taps, the first
     * tracks resolve instantly instead of queueing a cold extraction.
     */
    fun prefetchVideoIds(videoIds: List<String>) {
        val ids = videoIds.filter { it.isNotBlank() && !it.startsWith(LEGACY_IT_PREFIX) }.distinct().take(PREFETCH_COUNT)
        if (ids.isEmpty()) return
        prefetch(ids.map { YtmTrack(videoId = it) })
    }

    private var prefetchJob: Job? = null

    // Count of in-flight foreground playback requests, NOT a boolean: with two
    // overlapping playback requests (taps on consecutive queue items), the
    // first request's finally previously cleared the shared flag while the
    // second was still starting, and the background prefetch warm resumed
    // heavy extraction I/O during that request's startup window — the exact
    // contention this mechanism exists to prevent.
    private val foregroundPlaybackDemand =
        java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Top-first, event-driven warmup. There is deliberately no fixed delay:
     * the next candidate starts only after the previous resolution finishes,
     * and background work stops before starting another extraction whenever a
     * foreground playback request is waiting.
     */
    @Synchronized
    private fun prefetch(tracks: List<YtmTrack>) {
        if (tracks.isEmpty()) return
        // Replace any in-flight warm instead of returning early. The old
        // early-return silently dropped the warm for the NEWEST results — a
        // search issued while a previous prefetch was still running (normal
        // while typing) left the visible rows cold. Cancelling here is safe:
        // streamTarget's single-flight is reference-counted, so a foreground
        // playback request that joined the same resolution is not cancelled
        // with this caller.
        prefetchJob?.cancel()
        prefetchJob = backgroundScope.launch {
            for (track in tracks) {
                if (!isActive || foregroundPlaybackDemand.get() > 0) break
                try {
                    val target = youtube.streamTarget(track.videoId)
                    if (target != null) warmUpstream?.invoke(track.videoId, target.url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Continue to the next candidate after a warmup miss.
                }
            }
        }
    }

    suspend fun <T> withForegroundPlayback(block: suspend () -> T): T {
        foregroundPlaybackDemand.incrementAndGet()
        return try {
            block()
        } finally {
            foregroundPlaybackDemand.decrementAndGet()
        }
    }

    /**
     * Resolve [requestedId] to a playable target and warm the server-side CDN
     * path WITHOUT streaming audio back to the caller.
     *
     * Why this exists: the watch used to "warm" a track by requesting
     * `/api/stream/{id}` with `Range: bytes=0-0`. The proxy deliberately runs
     * tiny-window requests to the natural EOF (clamping left the socket
     * silent in live testing), so that probe pumped the WHOLE song through the
     * response until the client disconnect was noticed. On a LAN that is
     * harmless; over Tailscale Funnel it relays an entire track through the
     * tailnet, competing with the real playback request and forcing a second
     * TLS connection. This resolves the same target (single-flighted with the
     * playback request) and answers with a tiny JSON body instead.
     *
     * Returns true when a playable target is cached and ready.
     */
    suspend fun prewarm(requestedId: String): Boolean {
        val realId = resolveStreamVideoId(requestedId) ?: return false
        val target = try {
            youtube.streamTarget(realId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } ?: return false
        // Heat the CDN edge on the SERVER side (one ranged byte, bounded).
        // This is what makes the next track's tap start instantly; it costs
        // the watch nothing.
        warmUpstream?.invoke(realId, target.url)
        return true
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
