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
    /**
     * Fire-and-forget transcode warm (`Transcoder::prefetch`). A song YouTube
     * only offers in Opus/WebM needs server-side AAC conversion, and warming it
     * here means the tap (and every seek after it) is served from a file
     * instead of starting ffmpeg at byte 0.
     */
    private val warmTranscode: ((videoId: String, url: String, mimeType: String) -> Unit)? = null,
) {
    companion object {
        /**
         * How many rows are pre-resolved per list. Two, not three, and the
         * warm is LEAN-ONLY: every extra row used to cost a full InnerTube
         * round trip (three, before the client fan-out was cut) and could drop
         * into a ~10s extractor run that the user's tap then queued behind.
         * Two rows covers "tap the first or second result", which is what
         * actually happens, for a third of the upstream traffic.
         */
        private const val PREFETCH_COUNT = 2


        /** Prefix used by the removed iTunes layer; kept for legacy replay. */
        const val LEGACY_IT_PREFIX = "it:"

        /**
         * TTL for a positively resolved album (album membership is stable —
         * 24h is conservative) vs a miss (1h, so a track whose catalog row
         * gains an album later can still resolve).
         */
        private const val ALBUM_RESOLVE_TTL_MS = 24L * 60 * 60 * 1000
        private const val ALBUM_RESOLVE_MISS_TTL_MS = 60L * 60 * 1000

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
        private const val SEARCH_FIRST_PAGE_BUDGET_MS = 5_500L

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

    suspend fun search(query: String, limit: Int = 10, offset: Int = 0): List<TrackDto> =
        LatencyMetrics.time("search.total") { searchInternal(query, limit, offset) }

    private suspend fun searchInternal(query: String, limit: Int, offset: Int): List<TrackDto> {
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

        // ONE deadline for the whole page, enforced at EVERY step.
        //
        // Before this, only the relaxed-variant loop checked the budget: the
        // first YouTube Music fetch alone could burn SEARCH_TIMEOUT_MS (30s)
        // against a documented 7s first-page budget. "Fast first page" was a
        // promise the code did not keep, and the watch spun for the whole
        // overrun.
        fun remainingMs(): Long = (budgetMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
        fun stepTimeout(requested: Long, floor: Long = 600L): Long =
            remainingMs().coerceIn(0L, requested).coerceAtLeast(floor)

        fun addAll(tracks: List<TrackDto>) {
            for (t in tracks) {
                if (!byId.containsKey(t.videoId)) byId[t.videoId] = t
                if (byId.size >= safetyCap) break
            }
        }

        // 1. EXACT query on YouTube Music first.
        if (byId.size < safetyCap && remainingMs() > 0) {
            val ytmStarted = System.currentTimeMillis()
            val ytmExact = withTimeoutOrNull(stepTimeout(SEARCH_TIMEOUT_MS)) {
                try {
                    metadata.searchSongs(normalized, collectLimit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
                .also { LatencyMetrics.record("search.ytm", System.currentTimeMillis() - ytmStarted) }
                ?.filter { it.videoId.isNotBlank() }?.distinctBy { it.videoId }?.take(collectLimit)
                ?: emptyList()
            if (ytmExact.isNotEmpty()) {
                prefetch(ytmExact.take(PREFETCH_COUNT))
                addAll(ytmExact.map { metadata.toTrackDto(it) })
            }
        }

        // 2. Fast path: the exact query alone produced a FULL page that is
        //    mostly on-query — answer NOW.
        //
        // This used to demand that EVERY row be strong. In practice a normal
        // YTM page is 12-of-15 good with a couple of tangential rows, so the
        // condition never held and every first search fell through into the
        // general-YouTube consult (seconds of NewPipe scraping) plus the
        // relaxed-variant loop — paying for a rescue the user did not need
        // while they waited on a page that was already usable.
        //
        // Half-strong is the line: a genuinely weak or short page still gets
        // the full rescue (that is what it exists for, and the tests below
        // pin it), but a decent page no longer waits seconds to become
        // perfect. Ranking still puts the strong rows first.
        //
        // Checked after EVERY rescue step, not just once: a weak first page
        // used to run the general consult AND all five relaxed variants even
        // after a rescue had already produced a good page — seconds of extra
        // upstream per search, paid while the watch sits behind the shimmer.
        fun goodEnough(): Boolean =
            byId.size >= maxTake && strongMatchCount(byId.values, normalized).let { it > 0 && it * 2 >= maxTake }

        if (goodEnough()) {
            return rankByQueryOverlap(byId.values.toList(), normalized)
        }

        // 3. Weak/missing exact page: ask GENERAL YouTube (bounded), even if
        //    the window is already full of weak rows — rank decides what the
        //    final slice keeps.
        if (byId.size < safetyCap && remainingMs() > 0) {
            val generalStarted = System.currentTimeMillis()
            val ytExact = withTimeoutOrNull(stepTimeout(SEARCH_GENERAL_TIMEOUT_MS, 300L)) {
                youtube.searchGeneral(normalized, collectLimit)
            } ?: emptyList()
            LatencyMetrics.record("search.general", System.currentTimeMillis() - generalStarted)
            val beforeYouTube = byId.size
            addAll(ytExact)
            if (byId.size > beforeYouTube) fallbackCount.incrementAndGet()
            // The consult is the single most expensive rescue (NewPipe scrape);
            // if it already fixed the page, stop here instead of paying for
            // relaxed variants that can no longer improve the answer.
            if (goodEnough()) return rankByQueryOverlap(byId.values.toList(), normalized)
        }

        // 4. Relaxed variants top up remaining slots, while budget allows.
        //
        // Each variant is capped by what is LEFT of the page budget, with no
        // floor bump: `stepTimeout`'s 600ms floor used to let every one of
        // the five variants overrun a nearly-spent budget, so a hopeless
        // query could hold the watch ~1.5s past its own deadline (measured
        // 8.3s against a 7s first-page budget).
        for (q in relaxedQueries(normalized)) {
            if (byId.size >= safetyCap) break
            val left = remainingMs()
            if (left <= 200L) break
            addAll(fetchWindowOnce(q, collectLimit, left))
            if (goodEnough()) break
        }

        return rankByQueryOverlap(byId.values.toList(), normalized)
    }

    /**
     * One query window = metadata source, then the YouTube scrape fallback.
     *
     * [timeoutMs] is the budget for the WHOLE window, not per stage: both
     * stages share one deadline, otherwise a slow metadata call plus a slow
     * scrape could each spend the full budget and double the caller's wait.
     */
    private suspend fun fetchWindowOnce(
        query: String,
        take: Int,
        timeoutMs: Long = SEARCH_TIMEOUT_MS
    ): List<TrackDto> {
        val startedAt = System.currentTimeMillis()
        fun left(): Long = (timeoutMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
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
        val fallback = withTimeoutOrNull(left()) {
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
                    // LEAN ONLY. Resolving a row the user never taps must never
                    // spend ~10s inside the extractor: that was the queue a
                    // tap landed behind (the "sits there, then 404s" mode).
                    val target = youtube.streamTarget(track.videoId, allowExtraction = false)
                    if (target != null) {
                        warmUpstream?.invoke(track.videoId, target.url)
                        warmTranscode?.invoke(track.videoId, target.url, target.mimeType)
                    }
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
        warmTranscode?.invoke(realId, target.url, target.mimeType)
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

    // ---------------- Album resolution (player disc tap) ----------------

    private data class CachedAlbum(val album: AlbumDto?, val expiresAt: Long)

    private val albumResolveCache = BoundedCache<String, CachedAlbum>(64)

    /**
     * Resolves the album that [videoId] belongs to, best-effort.
     *
     * Called when the watch taps the player disc ("open the album for this
     * song"). Two steps, each cheap and already-cached:
     *
     *  1. A YouTube Music SONG search for "title artist" pins the exact row
     *     (same title + same uploader) and reads its album name.
     *  2. The album search resolves that name to a playable album playlist
     *     URL, preferring a result whose uploader matches the track's
     *     artist (so same-named albums by other artists are skipped).
     *
     * Anything ambiguous returns null: opening the WRONG album is worse than
     * opening none. Intent is explicit — the caller must not conflate "no
     * album" with "try harder on the client".
     */
    suspend fun resolveAlbum(videoId: String, title: String, artist: String): AlbumDto? {
        if (!YoutubeGateway.VIDEO_ID_RE.matches(videoId)) return null
        val now = System.currentTimeMillis()
        albumResolveCache.get(videoId)?.let { if (it.expiresAt > now) return it.album }

        val result = runCatching {
            val row = bestAlbumRow(
                metadata.searchSongs(
                    listOf(title.trim(), artist.trim()).filter { it.isNotEmpty() }.joinToString(" "),
                    10,
                ),
                title,
                artist,
            ) ?: return@runCatching null
            val albumName = row.album?.takeIf { it.isNotBlank() } ?: return@runCatching null
            bestAlbumMatch(albumName, youtube.searchAlbums(albumName), artist)
        }.getOrNull()

        albumResolveCache.put(
            videoId,
            CachedAlbum(
                result,
                now + if (result != null) ALBUM_RESOLVE_TTL_MS else ALBUM_RESOLVE_MISS_TTL_MS,
            ),
        )
        return result
    }

    /**
     * The row from a song search that is the SAME track as [title]/[artist].
     * Null when nothing matches confidently — a wrong row would ship the
     * wrong album name.
     */
    internal fun bestAlbumRow(
        rows: List<YtmTrack>,
        title: String,
        artist: String,
    ): YtmTrack? {
        val qTitle = normalizeTrackText(title)
        val qArtist = normalizeTrackText(artist)
        if (qTitle.isBlank()) return null
        var best: YtmTrack? = null
        var bestScore = 0
        for (row in rows) {
            val rTitle = normalizeTrackText(row.title)
            val rArtist = normalizeTrackText(row.artist)
            var score = 0
            when {
                rTitle == qTitle -> score += 4
                tokenOverlap(rTitle, qTitle) -> score += 2
                else -> continue
            }
            if (qArtist.isNotBlank()) {
                when {
                    rArtist == qArtist -> score += 2
                    tokenOverlap(rArtist, qArtist) -> score += 1
                    // A different artist can never be "the same track" —
                    // even with an exact title and an album name. A cover
                    // or mis-tagged row must not hijack the resolution.
                    else -> continue
                }
            }
            // A row without an album name cannot resolve anything — do not
            // let it win over a weaker-but-present candidate.
            if (row.album?.isNotBlank() == true) score += 2 else score -= 4
            if (score > bestScore) {
                bestScore = score
                best = row
            }
        }
        return best?.takeIf { bestScore >= 4 }
    }

    /**
     * The album from [albums] that best matches [albumName] and the track's
     * [artist].
     *
     * When the artist is known, an uploader/artist overlap is REQUIRED — a
     * same-named album by a different artist is never returned (opening the
     * wrong album is worse than opening none). Without an artist hint (or
     * when the artist is genuinely unknown), the top-ranked name match wins.
     */
    internal fun bestAlbumMatch(
        albumName: String,
        albums: List<AlbumDto>,
        artist: String,
    ): AlbumDto? {
        if (albums.isEmpty()) return null
        val ranked = rankAlbums(albumName, albums)
        val qArtist = normalizeTrackText(artist)
        if (qArtist.isNotBlank()) {
            return ranked.firstOrNull {
                val uploader = normalizeTrackText(it.uploader)
                uploader == qArtist || tokenOverlap(uploader, qArtist)
            }
        }
        return ranked.firstOrNull()
    }

    private fun normalizeTrackText(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun tokenOverlap(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        val aTokens = a.split(' ').filter { it.isNotEmpty() }
        val bTokens = b.split(' ').filter { it.isNotEmpty() }
        val hits = bTokens.count { it in aTokens }
        return hits >= ((bTokens.size + 1) / 2)
    }
}
