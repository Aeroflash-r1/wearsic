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
    private val searchFlight = SingleFlight<String, List<TrackDto>>()

    suspend fun search(query: String, limit: Int = 10, offset: Int = 0): List<TrackDto> {
        val normalized = query.trim().replace(Regex("\\s+"), " ")
        if (normalized.length < 2) return emptyList()
        require(normalized.length <= 200) { "Search query is too long" }
        val offsetClamped = offset.coerceAtLeast(0)
        // Fetch enough rows to cover the requested window. The YTM source
        // caches the biggest page it has fetched for a query, so paging just
        // grows the cache and slices — offset pages are cheap.
        val take = (offsetClamped + limit).coerceIn(1, MAX_FETCH)
        val page = searchFlight.run("$normalized|$take|$offsetClamped") {
            val tracks = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                try {
                    metadata.searchSongs(normalized, take)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }?.filter { it.videoId.isNotBlank() }?.distinctBy { it.videoId }?.take(take)
                ?: emptyList()
            if (tracks.isNotEmpty()) {
                prefetch(tracks.take(PREFETCH_COUNT))
                tracks.map { metadata.toTrackDto(it) }
            } else {
                fallbackCount.incrementAndGet()
                val fallback = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    youtube.search(normalized, take)
                } ?: emptyList()
                fallback.filter { it.videoId.isNotBlank() }.distinctBy { it.videoId }.take(take)
            }
        }
        return page.drop(offsetClamped).take(limit)
    }

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
