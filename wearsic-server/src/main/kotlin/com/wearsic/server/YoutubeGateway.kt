package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * NewPipeExtractor is initialized with a global [Downloader] and has no
 * per-instance client configuration. The only way to influence which YouTube
 * inner client performs an extraction is the STATIC toggle
 * `YoutubeStreamExtractor.setFetchIosClient(boolean)` — a process-wide setting.
 *
 * That makes concurrent extractions a correctness hazard: request A sets
 * iOS-mode, request B sets default-mode before A's fetchPage() reads it, and A
 * silently extracts with B's configuration.
 *
 * [YoutubeGateway] is the single choke point that owns that global toggle:
 * every extraction is serialized through [extractionMutex], so the
 * set -> fetch -> reset sequence is atomic with respect to every other
 * extraction. The serialization cost is acceptable for a personal low-QPS
 * server, and [streamTarget]'s result cache means repeat plays never re-enter
 * the lock at all.
 */
class YoutubeGateway(
    /**
     * Injectable for tests: a fake Downloader can serve canned responses
     * without any network access.
     */
    downloader: Downloader = NewPipeDownloader(NewPipeDownloader.buildClient()),
    /** Self-healing: records extraction success/failure when non-null. */
    private val healthMeter: ExtractionHealthMeter? = null,
) : YoutubeMetadataClient {

    private val logger = LoggerFactory.getLogger(YoutubeGateway::class.java)

    init {
        // Idempotent: NewPipe.init just replaces the global downloader.
        NewPipe.init(downloader)
    }

    /**
     * Serializes ALL stream extractions because YoutubeStreamExtractor's
     * client selection is global static state (see class docs).
     */
    private val extractionMutex = Mutex()

    /** Shared JDK HTTP client for the lean player fast path (own pool). */
    private val leanHttp: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(LEAN_HTTP_TIMEOUT_MS))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()
    }

    companion object {
        private const val MAX_RESULTS = 10
        // Albums/playlists: 10 tracks felt like a truncated album on the watch
        // (most deluxe editions run 15-30 songs). One YouTube playlist page
        // typically carries ~100 items, so 50 is cheap and covers full albums.
        private const val MAX_PLAYLIST_TRACKS = 50
        private const val MAX_SUGGESTIONS = 5
        private const val MAX_RELATED_MINUTES = 10

        // Audio profile tuned for the Galaxy Watch client:
        //  - AAC-LC is preferred (hardware-decoded on the watch's SoC, so it
        //    uses far less battery than software-decoded Opus/Vorbis).
        //  - ~128 kbps is YouTube's standard AAC-LC tier (itag 140).
        private const val TARGET_AUDIO_BITRATE_KBPS = 128

        // Long enough that replaying a song within the hour is instant (no
        // re-extraction), short enough that expired CDN URLs never linger.
        const val STREAM_CACHE_TTL_MILLIS: Long = 60 * 60 * 1000L // 1 hour

        /** NOT_PLAYABLE markers live much shorter than real targets. */
        internal const val NOT_PLAYABLE_TTL_MILLIS: Long = 5 * 60 * 1000L // 5 minutes

        /** Wall-clock budget for one full stream resolution. */
        internal const val EXTRACTION_TIMEOUT_MS: Long = 25_000L

        /**
         * Wall-clock budget for one search flight. A hung fetchPage() must
         * never park a SingleFlight key forever — every later identical query
         * would hang with it until restart. Timeouts are NOT cached.
         */
        internal const val SEARCH_TIMEOUT_MS: Long = 30_000L

        // ---- Lean Innertube player fast path ----
        // NewPipe's full fetchPage() costs ~10s: visitor tracking, Shorts
        // reel, related/next — none of it needed for audio. ONE player POST
        // with the iOS client answers in ~0.3-0.5s. Null on ANY failure
        // (login-gated, ciphered-only, throttled) → full extraction fallback.
        private const val LEAN_HTTP_TIMEOUT_MS: Long = 15_000L

        /**
         * How long the remaining lean clients may keep a resolve open AFTER
         * the wall-safe primary (VISIONOS) answered. Their answers add
         * rotation tickets; the first answer alone is enough to start
         * playback.
         */
        private const val LEAN_ANSWER_GRACE_MS: Long = 400L

        /**
         * How long a resolve may keep waiting for the primary client when
         * OTHER clients already returned formats. Its URLs are the only
         * ones measured to survive the 1 MB CDN wall, so a slightly slower
         * primary is worth absorbing.
         */
        private const val LEAN_PREFERRED_WAIT_MS: Long = 1400L
        private const val IOS_CLIENT_VERSION = "21.03.2"
        private const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; en_US)"
        private const val IOS_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private val VIDEO_ID_RE = Regex("[A-Za-z0-9_-]{11}")

        /**
         * InnerTube client identities for the lean fast path.
         *
         * VISIONOS FIRST: live-verified, its resolved URLs stream whole songs
         * where `c=IOS` URLs die at EXACTLY 1 MB (403) on throttled networks
         * — same CDN host, same itag, same IP: a VISIONOS URL pulled 2 MB+
         * clean in under a second while the IOS one refused byte 1048576.
         * That 1 MB wall IS the "stalls at 1:04" bug (1 MB ≈ 64 s at
         * 128 kbps). VISIONOS also needs the session visitor id (see
         * [currentVisitorData]) or it answers LOGIN_REQUIRED.
         *
         * The remaining clients are rotation sources: each mints URLs from
         * its own family, and the proxy rotates across pooled tickets
         * byte-exact when one refuses mid-song.
         */
        private data class LeanClientProfile(
            val clientName: String,
            val clientVersion: String,
            val clientIdHeader: String,
            val userAgent: String,
            val contextFields: String,
            /**
             * Client needs the session visitor id in its payload. Only
             * VISIONOS does (IOS works cookie-less without one), and keeping
             * it to that single branch lets the visitor-id fetch overlap the
             * other clients' POSTs instead of serializing in front of them.
             */
            val wantsVisitorData: Boolean = false,
        )

        private val LEAN_CLIENTS: List<LeanClientProfile> = listOf(
            LeanClientProfile(
                clientName = "VISIONOS", clientVersion = "1.02", clientIdHeader = "",
                userAgent = VISIONOS_USER_AGENT,
                contextFields = """"deviceModel":"RealityDevice14,1","deviceMake":"Apple","osName":"visionOS","osVersion":"25.6.0.0",""",
                wantsVisitorData = true,
            ),
            LeanClientProfile(
                clientName = "IOS", clientVersion = IOS_CLIENT_VERSION, clientIdHeader = "5",
                userAgent = IOS_USER_AGENT,
                contextFields = """"deviceModel":"iPhone16,2","deviceMake":"Apple","osName":"iOS","osVersion":"18.7.2",""",
            ),
            LeanClientProfile(
                clientName = "ANDROID_VR", clientVersion = "1.60.19", clientIdHeader = "28",
                userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
                contextFields = """"deviceModel":"Quest 3","deviceMake":"Oculus","osName":"Android","osVersion":"12","androidSdkVersion":32,""",
            ),
        )

        // Ex-ANDROID (HTTP 400 / ciphered URLs only) and TVHTML5 (YouTube: "no
        // longer supported in this application or device") were dropped after
        // live probing — they only added latency to every resolve.

        private const val VISIONOS_USER_AGENT =
            "com.google.visionos.youtube/1.02 (RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; en_US)"

        /**
         * Session visitor id source. VISIONOS (like several modern clients)
         * answers LOGIN_REQUIRED without one — live-verified: attaching the
         * id flips it to OK with direct stream URLs.
         */
        private const val VISITOR_DATA_URL = "https://www.youtube.com/sw.js_data"
        private const val VISITOR_DATA_TTL_MS: Long = 6 * 60 * 60 * 1000L

        /**
         * Failed visitor-id fetches back off for this long. Without it, a
         * network that cannot reach sw.js_data paid a fresh (failing) fetch
         * attempt on EVERY resolve.
         */
        private const val VISITOR_DATA_RETRY_MS: Long = 5 * 60 * 1000L

        /**
         * How long a video's extractor-ticket warm is considered fresh —
         * at most one background extraction per video per window.
         */
        private const val EXTRACTOR_WARM_TTL_MS: Long = 45 * 60 * 1000L

        /**
         * Delay before a playback-triggered warm starts extracting: keeps the
         * first seconds of a stream (the ones the listener is waiting on)
         * free of competing extraction I/O. A wall refusal would come ~64s of
         * audio in, far beyond this window.
         */
        private const val WARM_EXTRACTOR_DELAY_MS: Long = 4_000L

        /** Cap per-video ticket pool: enough URLs to cover long mixes. */
        private const val MAX_TICKETS_PER_VIDEO = 40

        /** Cap videos retaining pools so memory stays flat over weeks. */
        private const val MAX_TICKET_VIDEOS = 64

        /**
         * How long a video stays marked "wall-throttled" — while marked,
         * streamTarget skips lean URLs entirely and resolves through NewPipe
         * extraction, whose URLs carry the deobfuscated `n` parameter and do
         * not hit the ~1 MB transfer wall.
         */
        private const val WALL_THROTTLE_TTL_MS: Long = 20 * 60 * 1000L

        /** Client order for stream extraction: iOS first, default fallback. */
        internal val CLIENT_ORDER: List<Boolean> = listOf(true, false)

        /** Sentinel marking "extraction ran but nothing playable was found". */
        internal const val NOT_PLAYABLE = "wearsic:not-playable"

        const val SUGGESTIONS_TTL_MS = 10 * 60 * 1000L
        const val RELATED_TTL_MS = 30 * 60 * 1000L
        const val ALBUMS_TTL_MS = 30 * 60 * 1000L
        const val PLAYLIST_TTL_MS = 10 * 60 * 1000L
        /** Search results re-resolve after 5 minutes like every other cache;
         *  they previously never expired (stale rows served for days). */
        const val SEARCH_TTL_MS = 5 * 60 * 1000L

        /** Effectively-permanent video used by the canary probe. */
        internal const val CANARY_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }

    // ---------------- Caches ----------------

    private val searchCache = BoundedCache<String, Timed<List<TrackDto>>>(maxSize = 128)
    private val streamCache = BoundedCache<String, CachedStreamTarget>(maxSize = 64)
    // Previously uncached endpoints (0% hit rate): every keystroke / radio tap
    // / album open paid a full YouTube round-trip. Small TTL caches make
    // repeat views instant with negligible staleness for a personal server.
    private data class Timed<T>(val value: T, val expiresAt: Long)
    private val suggestionsCache = BoundedCache<String, Timed<List<String>>>(maxSize = 256)
    private val relatedCache = BoundedCache<String, Timed<List<TrackDto>>>(maxSize = 64)
    private val albumsCache = BoundedCache<String, Timed<List<AlbumDto>>>(maxSize = 32)
    private val playlistCache = BoundedCache<String, Timed<PlaylistTracksResponse>>(maxSize = 32)

    // Replaces the old per-key Mutex map (one entry per key EVER seen — an
    // unbounded leak). SingleFlight removes each entry when the operation
    // completes, so memory stays flat over weeks of uptime.
    private val searchSingleFlight = SingleFlight<String, List<TrackDto>>()
    // Nullable V: a timed-out resolution returns null (transient — never cached).
    private val streamSingleFlight = SingleFlight<String, StreamTarget?>()

    /**
     * Videos whose LEAN (raw InnerTube) URLs hit the CDN's per-URL transfer
     * wall mid-song. While marked, resolution skips the lean fast path and
     * uses NewPipe extraction — live-verified to stream past the wall
     * (3 MB+ pulled clean where lean URLs die at exactly 1 MB). Value =
     * expiry millis.
     */
    private val wallThrottled = BoundedCache<String, Long>(maxSize = 256)

    /** Background scope for cache warming; never tied to one request. */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Session visitor id for the player context — VISIONOS answers
     * LOGIN_REQUIRED without it, normal playback with it (live-verified).
     * Fetched from [VISITOR_DATA_URL], cached well below its rotation rate.
     */
    @Volatile
    private var visitorData: String? = null

    @Volatile
    private var visitorDataFetchedAt: Long = 0L

    /** Backoff stamp: failed fetches retry no sooner than this. */
    @Volatile
    private var visitorDataNextAttemptAt: Long = 0L

    // ---------------- Search ----------------

    override suspend fun search(query: String): List<TrackDto> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()

        val cacheKey = "search:$normalized"
        val now = System.currentTimeMillis()
        searchCache.get(cacheKey)?.let { if (it.expiresAt > now) return it.value }

        return searchSingleFlight.run(cacheKey) {
            // Double-check the cache after joining: the winner just populated it.
            searchCache.get(cacheKey)?.let { if (it.expiresAt > System.currentTimeMillis()) return@run it.value }
            val results = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                searchMusic(normalized)
            } ?: run {
                logger.info("Search timed out for '{}'", normalized)
                return@run emptyList()
            }
            searchCache.put(cacheKey, Timed(results, now + SEARCH_TTL_MS))
            results
        }
    }

    /**
     * Limit-aware search for Show more: first page plus the extractor's
     * second page when the caller wants more rows than one page holds.
     * Page 2 is best-effort (a second fetchPage costs seconds) and only
     * fires when page 1 was full but short of the wish.
     */
    override suspend fun search(query: String, limit: Int): List<TrackDto> {
        val want = limit.coerceIn(1, 25)
        val first = search(query)
        if (first.size >= want) return first.take(want)
        return searchSingleFlight.run("search-p2:$query") {
            val second = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { searchSecondPage(query) }
            } ?: emptyList()
            (first + second).distinctBy { it.videoId }.take(want)
        }
    }

    private fun searchSecondPage(query: String): List<TrackDto> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf("music_songs"), "")
        return try {
            extractor.fetchPage()
            val next = extractor.initialPage.nextPage ?: return emptyList()
            extractor.getPage(next).items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrackDtoOrNull() }
                .distinctBy { it.videoId }
        } catch (e: Exception) {
            logger.info("Search page 2 failed for '{}': {}", query, e.message)
            emptyList()
        }
    }

    /**
     * Music-filtered search first; unfiltered only if filtered comes back
     * empty. The old code fired BOTH concurrently for every fallback search,
     * paying 2x HTTP/CPU/bot-wall exposure even when filtered already had
     * the answer. Sequential saves ~0.5-2s and halves YouTube QPS.
     */
    private suspend fun searchMusic(query: String): List<TrackDto> = withContext(Dispatchers.IO) {
        val filtered = runCatching { searchPage(query, listOf("music_songs")) }
            .onFailure { e ->
                logger.info("Music-filtered search failed for '{}': {}", query, e.message)
            }
            .getOrDefault(emptyList())
        if (filtered.isNotEmpty()) return@withContext filtered
        runCatching { searchPage(query, emptyList()) }
            .onFailure { e ->
                logger.info("Unfiltered search failed for '{}': {}", query, e.message)
            }
            .getOrDefault(emptyList())
    }

    private fun searchPage(query: String, contentFilters: List<String>): List<TrackDto> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, contentFilters, "")
        try {
            extractor.fetchPage()
            healthMeter?.recordSuccess()
        } catch (e: Exception) {
            healthMeter?.recordFailure("search: ${e.message}")
            throw e
        }
        return extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrackDtoOrNull() }
            // YouTube often returns the same video twice in one result page.
            .distinctBy { it.videoId }
            .take(MAX_RESULTS)
    }

    private fun StreamInfoItem.toTrackDtoOrNull(): TrackDto? {
        val videoId = extractVideoId(url) ?: return null
        return TrackDto(
            videoId = videoId,
            title = name ?: return null,
            uploader = uploaderName ?: "Unknown",
            durationMs = duration.coerceAtLeast(0) * 1000,
            thumbnailUrl = bestThumbnailUrl(thumbnails),
        )
    }

    // ---------------- Suggestions ----------------

    override suspend fun suggestions(prefix: String): List<String> = withContext(Dispatchers.IO) {
        val key = prefix.trim()
        if (key.length < 2) return@withContext emptyList()
        val now = System.currentTimeMillis()
        suggestionsCache.get(key)?.let { if (it.expiresAt > now) return@withContext it.value }
        val result = runCatching {
            ServiceList.YouTube.suggestionExtractor
                .suggestionList(key)
                .take(MAX_SUGGESTIONS)
        }
            .onSuccess { healthMeter?.recordSuccess() }
            .onFailure { healthMeter?.recordFailure("suggestions: ${it.message}") }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) suggestionsCache.put(key, Timed(result, now + SUGGESTIONS_TTL_MS))
        result
    }

    // ---------------- Related / radio ----------------

    override suspend fun related(videoId: String): List<TrackDto> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        relatedCache.get(videoId)?.let { if (it.expiresAt > now) return@withContext it.value }
        val result = runCatching {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            val related = extractor.relatedItems?.items
                .orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.duration == 0L || it.duration in 30..(MAX_RELATED_MINUTES * 60) }
                .mapNotNull { it.toTrackDtoOrNull() }
                .distinctBy { it.videoId }
                .take(MAX_RESULTS)
            // Recommendations read better when they stay around the same
            // artist: surface the artist that appears most in the related
            // set first, keep the rest after (all in original order).
            orderByDominantArtist(related)
        }
            .onSuccess { healthMeter?.recordSuccess() }
            .onFailure { healthMeter?.recordFailure("related: ${it.message}") }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) relatedCache.put(videoId, Timed(result, now + RELATED_TTL_MS))
        result
    }

    /** Stable reorder: most-common artist first, then the rest as-is. */
    private fun orderByDominantArtist(tracks: List<TrackDto>): List<TrackDto> {
        if (tracks.size < 4) return tracks
        val counts = HashMap<String, Int>()
        for (t in tracks) {
            val key = (t.uploader ?: "").trim().lowercase()
            if (key.isNotEmpty()) counts[key] = (counts[key] ?: 0) + 1
        }
        val dominant = counts.maxByOrNull { it.value }?.takeIf { it.value > 1 }?.key ?: return tracks
        val (first, rest) = tracks.partition { (it.uploader ?: "").trim().lowercase() == dominant }
        return first + rest
    }

    // ---------------- Albums ----------------

    override suspend fun searchAlbums(query: String): List<AlbumDto> = withContext(Dispatchers.IO) {
        val key = query.trim()
        if (key.length < 2) return@withContext emptyList()
        val now = System.currentTimeMillis()
        albumsCache.get(key)?.let { if (it.expiresAt > now) return@withContext it.value }
        val result = runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(key, listOf("music_albums"), "")
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<PlaylistInfoItem>()
                .mapNotNull { item ->
                    AlbumDto(
                        id = item.url ?: return@mapNotNull null,
                        name = item.name ?: return@mapNotNull null,
                        uploader = item.uploaderName ?: "Unknown",
                        thumbnailUrl = bestThumbnailUrl(item.thumbnails),
                    )
                }
                .take(MAX_RESULTS)
                // YouTube's album search is name-fuzzy: "Thriller Michael
                // Jackson" happily returns same-named albums by unrelated
                // artists. Rank by how well each result matches the query's
                // name AND publisher tokens so the right album from the
                // right artist leads, and matching-but-wrong-artist junk is
                // trimmed (see rankAlbums).
                .let { rankAlbums(key, it) }
        }
            .onSuccess { healthMeter?.recordSuccess() }
            .onFailure { healthMeter?.recordFailure("albums: ${it.message}") }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) albumsCache.put(key, Timed(result, now + ALBUMS_TTL_MS))
        result
    }

    // ---------------- Playlist by URL (also used for albums) ----------------

    override suspend fun playlistByUrl(url: String): PlaylistTracksResponse? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        playlistCache.get(url)?.let { if (it.expiresAt > now) return@withContext it.value }
        val result = runCatching {
            val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
            extractor.fetchPage()
            val tracks = extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .mapNotNull { it.toTrackDtoOrNull() }
                .distinctBy { it.videoId }
                .take(MAX_PLAYLIST_TRACKS)
            PlaylistTracksResponse(id = url, name = extractor.name ?: "Playlist", tracks = tracks)
        }
            .onSuccess { healthMeter?.recordSuccess() }
            .onFailure { healthMeter?.recordFailure("playlist: ${it.message}") }
            .getOrNull()
        if (result != null) playlistCache.put(url, Timed(result, now + PLAYLIST_TTL_MS))
        result
    }

    // ---------------- Stream resolution ----------------

    override suspend fun streamTarget(videoId: String): StreamTarget? {
        val now = System.currentTimeMillis()
        streamCache.get(videoId)?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.target
        }

        return streamSingleFlight.run(videoId) {
            // Re-check the cache after joining: the winner populated it.
            // The clock is re-read here — a flight can take tens of
            // seconds, so the 'now' captured before it would happily
            // serve an entry that expired while waiting in line.
            val joinedAt = System.currentTimeMillis()
            streamCache.get(videoId)?.let { cached ->
                if (cached.expiresAtMillis > joinedAt) return@run cached.target
            }

            // Fast path first: parallel player POSTs across client identities,
            // no mutex (pure HTTP, no global state). Skipped entirely for
            // wall-throttled videos (see [wallThrottled]): their raw URLs die
            // at the CDN's transfer budget while extractor URLs stream whole.
            val ranked = if (isWallThrottled(videoId)) {
                logger.info("{} is wall-throttled — resolving via extractor URLs", videoId)
                null
            } else {
                withContext(Dispatchers.IO) { resolveLeanFormats(videoId) }
            }
            val lean = ranked?.firstOrNull()
            if (lean != null) {
                healthMeter?.recordSuccess()
                stashFormatFallbacks(videoId, ranked.drop(1))
                // NOTE: extractor-ticket warm deliberately does NOT run from
                // here. streamTarget also serves search PREFETCH (every
                // result row resolves in the background), and warming one
                // heavy NewPipe extraction per row competed with the song
                // download the user was starting (measured live: ~2 MB/s
                // collapses to ~250 KB/s) — the "slow to start playing"
                // regression. The warm is fired from AudioProxy instead, the
                // moment a video is ACTUALLY streamed, so only played songs
                // pay for it.
                val target = StreamTarget(
                    url = lean.url,
                    mimeType = lean.mimeType,
                    itag = lean.itag,
                    contentLength = lean.contentLength,
                    needsExtractorWarm = !isWallSafeClient(lean.client),
                )
                streamCache.put(
                    videoId,
                    CachedStreamTarget(target, System.currentTimeMillis() + STREAM_CACHE_TTL_MILLIS)
                )
                return@run target
            }

            // Track whether the timeout fired vs extraction genuinely finding
            // nothing: a timeout is TRANSIENT (queue jam / slow YouTube) and
            // must NOT be cached as NOT_PLAYABLE, or the next 5 min of taps
            // get instant-404s instead of a retry.
            var timedOut = false
            val rankedExtractor = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                extractionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        resolveAudioStreamsWithFallback(videoId)
                    }
                }
            } ?: run { timedOut = true; emptyList() }

            val audio = rankedExtractor.firstOrNull()
            when {
                audio != null -> {
                    healthMeter?.recordSuccess()
                    // Stash the runner-up formats as rotation tickets too:
                    // even extractor URLs get a budget on some videos, and
                    // tickets let a mid-stream refusal continue seamlessly.
                    val tickets = extractorTickets(rankedExtractor)
                    if (tickets.size > 1) stashFormatFallbacks(videoId, tickets.drop(1))
                    val target = StreamTarget(
                        url = audio.content,
                        mimeType = audio.format?.mimeType ?: "audio/webm",
                        itag = audio.itagItem?.id ?: 0,
                        // ItagItem carries the full stream size from the
                        // player response. Passing it here lets the proxy
                        // skip its 1-byte upstream probe — a whole CDN round
                        // trip — before the first audio byte.
                        contentLength = audio.itagItem?.contentLength?.takeIf { it > 0L } ?: 0L,
                    )
                    streamCache.put(videoId, CachedStreamTarget(target, System.currentTimeMillis() + STREAM_CACHE_TTL_MILLIS))
                    target
                }
                timedOut -> {
                    // Transient (queue jam / slow YouTube): leave the cache
                    // empty so the next tap retries — but count it, because a
                    // jammed extraction queue is also an engine-health signal.
                    healthMeter?.recordFailure("stream: extraction timeout")
                    null
                }
                // Genuine empty inside budget (e.g. removed video): cache a
                // NOT_PLAYABLE marker briefly so repeat misses don't hammer it.
                else -> {
                    healthMeter?.recordFailure("stream: no playable audio")
                    streamCache.put(
                        videoId,
                        CachedStreamTarget(
                            StreamTarget(NOT_PLAYABLE, NOT_PLAYABLE),
                            System.currentTimeMillis() + NOT_PLAYABLE_TTL_MILLIS,
                        ),
                    )
                    null
                }
            }
        }.takeIf { it?.url != NOT_PLAYABLE }
    }

    /**
     * URL rotation + dead-URL self-heal. The proxy observed the CDN
     * rejecting this video's resolved URL (403/404/410) — either dead or at
     * its per-URL transfer budget (~1 MB on the measured network).
     *
     * Compatibility rules for mid-stream consumers ([requireCompatible]):
     * byte offsets only mean the same timeline within the SAME encoding, so
     * a mid-stream rotation may never switch itags — without a compatible
     * ticket this returns null and the response aborts (the watch retries;
     * by then the video is wall-marked and resolves via extractor URLs).
     * Entry-point consumers (nothing streamed yet) may take any format.
     */
    suspend fun invalidateStreamTarget(
        videoId: String,
        preferItag: Int = 0,
        preferMime: String? = null,
        requireCompatible: Boolean = false,
    ): StreamTarget? {
        popFormatFallback(videoId, preferItag, preferMime, requireCompatible)?.let { next ->
            return publishTicket(videoId, next, "rotating to pooled ticket")
        }

        // Pool dry for this compatibility class: re-resolve and merge
        // whatever is new. Mid-stream rotations ([requireCompatible]) and
        // wall-marked videos resolve through the extractor: live-verified,
        // those URLs stream past the wall in the same encoding. A fresh lean
        // round on a walled network only mints new signatures of the SAME
        // spent client family (measured: they 403 at the same byte
        // immediately), so it stays only as the fallback — extraction can
        // fail while lean still works.
        val fresh: List<LeanAudio> = if (isWallThrottled(videoId) || requireCompatible) {
            val streams = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                extractionMutex.withLock {
                    withContext(Dispatchers.IO) { resolveAudioStreamsWithFallback(videoId) }
                }
            }.orEmpty()
            val tickets = extractorTickets(streams)
            if (tickets.isNotEmpty()) tickets else resolveLeanFormats(videoId).orEmpty()
        } else {
            resolveLeanFormats(videoId).orEmpty()
        }
        if (fresh.isNotEmpty()) {
            stashFormatFallbacks(videoId, fresh)
            popFormatFallback(videoId, preferItag, preferMime, requireCompatible)?.let { next ->
                return publishTicket(videoId, next, "rotating to fresh ticket")
            }
        }

        // No byte-compatible URL left. Mark the video wall-throttled: the
        // NEXT resolution skips lean entirely and uses extractor-resolved
        // URLs (live-verified: they stream whole songs, no wall).
        markWallThrottled(videoId)
        streamCache.remove(videoId)
        if (requireCompatible) {
            // The watch retries this range within ~1s: resolve the extractor
            // target NOW in the background (deduped through the same
            // single-flight as the retry) so the retry finds a warm cache
            // instead of waiting through a multi-second extraction.
            warmExtractorTarget(videoId)
            logger.info("Track {}: lean URL pool exhausted mid-stream — aborts; next request uses extractor URLs", videoId)
            return null
        }
        logger.info("Track {}: lean URL pool exhausted — full re-resolve", videoId)
        return streamTarget(videoId)
    }

    /** Marks [videoId] as wall-throttled for [WALL_THROTTLE_TTL_MS]. */
    internal fun markWallThrottled(videoId: String) {
        wallThrottled.put(videoId, System.currentTimeMillis() + WALL_THROTTLE_TTL_MS)
    }

    /**
     * Fire-and-forget: resolve [videoId] through the normal (now
     * wall-marked → extractor) path so the watch's imminent retry finds a
     * warm cache instead of waiting through a multi-second extraction.
     * Deduped with any concurrent request via the stream single-flight.
     */
    internal fun warmExtractorTarget(videoId: String) {
        warmScope.launch {
            runCatching { streamTarget(videoId) }
                .onFailure { logger.info("Warm resolve failed for {}: {}", videoId, it.message) }
        }
    }

    /**
     * Fire-and-forget: extract [videoId]'s stream table ONCE per window and
     * PREPEND its URLs to the rotation pool while a lean target plays.
     *
     * Why prepend: on walled networks a mid-song refusal must rotate onto a
     * proven-unwalled URL in ONE pop. Pool order means the next problem-free
     * ticket wins; extractor-built URLs (c=VISIONOS family in NewPipe's
     * flavour) are measured to stream whole songs, so they go to the head.
     * Leans stay queued behind them for networks where extraction is the
     * broken path instead.
     */
    internal fun warmExtractorTickets(videoId: String) {
        // Wall-marked videos already resolve through the extractor — their
        // pool IS extractor-built. Warming again would burn a duplicate
        // extraction for nothing.
        if (isWallThrottled(videoId)) return
        val now = System.currentTimeMillis()
        extractorWarmed.get(videoId)?.let { if (it > now) return }
        extractorWarmed.put(videoId, now + EXTRACTOR_WARM_TTL_MS)
        warmScope.launch {
            try {
                // Let playback's initial buffer fill uncontended first. The
                // wall (when it comes) refuses ~64s of audio in, so a few
                // seconds of delay still lands tickets long before a
                // rotation could need them.
                delay(WARM_EXTRACTOR_DELAY_MS)
                val streams = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                    extractionMutex.withLock {
                        withContext(Dispatchers.IO) { resolveAudioStreamsWithFallback(videoId) }
                    }
                }.orEmpty()
                val tickets = extractorTickets(streams)
                if (tickets.isNotEmpty()) {
                    stashFormatFallbacks(videoId, tickets, prepend = true)
                    logger.info("Track {}: warmed {} extractor rotation ticket(s)", videoId, tickets.size)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                logger.info("Extractor warm failed for {}: {}", videoId, e.message)
            }
        }
    }

    private fun isWallThrottled(videoId: String): Boolean {
        val until = wallThrottled.get(videoId) ?: return false
        return until > System.currentTimeMillis()
    }

    private fun publishTicket(videoId: String, next: LeanAudio, why: String): StreamTarget {
        logger.info("Track {}: {} — {} itag {} via {}", videoId, why, next.mimeType, next.itag, next.client)
        healthMeter?.recordSuccess()
        val target = StreamTarget(
            url = next.url,
            mimeType = next.mimeType,
            itag = next.itag,
            contentLength = next.contentLength,
            needsExtractorWarm = !isWallSafeClient(next.client),
        )
        streamCache.put(
            videoId,
            CachedStreamTarget(target, System.currentTimeMillis() + STREAM_CACHE_TTL_MILLIS)
        )
        return target
    }

    /**
     * Canary probe: extract the "Me at the zoo" video (jNQXAC9IVRw — the
     * first YouTube upload, effectively permanent). Deliberately does NOT
     * touch the health meter (the watchdog decides what failures mean) and
     * does not take the extraction mutex — the static-client race it could
     * lose is irrelevant for a health probe, and the mutex may be jammed by
     * exactly the sickness this probe is diagnosing.
     */
    suspend fun canaryProbe(): Boolean = withContext(Dispatchers.IO) {
        try {
            val extractor = ServiceList.YouTube.getStreamExtractor(CANARY_URL)
            extractor.fetchPage()
            extractor.audioStreams.orEmpty().any { it.isUrl }
        } catch (e: Exception) {
            logger.warn("Canary probe failed: {}", e.message)
            false
        }
    }

    /**
     * Lean stream resolution: ONE Innertube player POST with the iOS client
     * (~0.3-0.5s) instead of NewPipe's full fetchPage (~10s of visitor
     * tracking, Shorts reel, related/next — none needed for audio).
     * Pure HTTP, no global state: needs NO mutex. Null on ANY failure —
     * the caller falls back to full NewPipe extraction.
     */
    /**
     * One directly-playable audio stream from one client identity. Tickets
     * are rotated one per CDN transfer budget; [client] is kept for logs.
     */
    internal data class LeanAudio(
        val url: String,
        val mimeType: String,
        val bitrateKbps: Int,
        val itag: Int,
        val client: String = "",
        /**
         * Byte length of this exact file as stated by the player response
         * (0 = unknown). The proxy uses it to skip its 1-byte length probe —
         * a full CDN round trip — before the first byte of every request.
         */
        val contentLength: Long = 0L,
    )

    /**
     * Rotating URL tickets per video. Every lean resolution (across ALL
     * client identities) merges its non-playing formats here; the proxy pops
     * one per mid-stream refusal. Deduplicated by URL, capped per video, and
     * the map itself is capped by insertion order so memory stays flat.
     * Guarded by its own lock (short in-memory ops only, never I/O).
     */
    private val formatFallbacks = LinkedHashMap<String, ArrayDeque<LeanAudio>>()
    private val formatFallbacksLock = Any()

    /**
     * Videos whose extractor-ticket warm already ran (value = expiry) — at
     * most one background extraction per video per [EXTRACTOR_WARM_TTL_MS].
     */
    private val extractorWarmed = BoundedCache<String, Long>(maxSize = 512)

    private fun stashFormatFallbacks(
        videoId: String,
        fresh: List<LeanAudio>,
        prepend: Boolean = false,
    ) {
        if (fresh.isEmpty()) return
        synchronized(formatFallbacksLock) {
            val q = formatFallbacks.getOrPut(videoId) { ArrayDeque() }
            mergeTickets(q, fresh, MAX_TICKETS_PER_VIDEO, prepend)
            while (formatFallbacks.size > MAX_TICKET_VIDEOS) {
                val oldest = formatFallbacks.keys.first()
                if (oldest == videoId) break
                formatFallbacks.remove(oldest)
            }
        }
    }

    private fun popFormatFallback(
        videoId: String,
        preferItag: Int = 0,
        preferMime: String? = null,
        requireCompatible: Boolean = false,
    ): LeanAudio? = synchronized(formatFallbacksLock) {
        val q = formatFallbacks[videoId] ?: return null
        val next = selectCompatibleTicket(q, preferItag, preferMime, requireCompatible)
        if (q.isEmpty()) formatFallbacks.remove(videoId)
        next
    }

    /**
     * Session visitor id ("Cg..."), refetched at most every
     * [VISITOR_DATA_TTL_MS]. A failed refetch serves the stale id — an
     * older visitor id still beats no visitor id at all. Null only when
     * YouTube could not be reached AND nothing was ever cached.
     */
    private suspend fun currentVisitorData(): String? {
        val cached = visitorData
        val now = System.currentTimeMillis()
        if (cached != null && now - visitorDataFetchedAt < VISITOR_DATA_TTL_MS) {
            return cached
        }
        // Failed fetches back off (see VISITOR_DATA_RETRY_MS) so one broken
        // sw.js_data route cannot tax every single resolve.
        if (visitorDataNextAttemptAt > now) return cached
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = HttpRequest.newBuilder(URI.create(VISITOR_DATA_URL))
                    .timeout(Duration.ofMillis(LEAN_HTTP_TIMEOUT_MS))
                    .header("User-Agent", VISIONOS_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET()
                    .build()
                val resp = leanHttp.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) {
                    logger.info("Visitor id fetch: HTTP {}", resp.statusCode())
                    return@runCatching null
                }
                val parsed = visitorDataFrom(resp.body())
                if (parsed == null) {
                    logger.info("Visitor id not found in sw.js_data ({} bytes)", resp.body().length)
                }
                parsed
            }
                .onFailure { logger.info("Visitor id fetch failed: {}", it.message) }
                .getOrNull()
                ?.let { fresh ->
                    visitorData = fresh
                    visitorDataFetchedAt = System.currentTimeMillis()
                    visitorDataNextAttemptAt = 0L
                    logger.info("Visitor id acquired ({} chars)", fresh.length)
                    fresh
                }
                ?: cached.also {
                    visitorDataNextAttemptAt = System.currentTimeMillis() + VISITOR_DATA_RETRY_MS
                }
        }
    }

    /**
     * All directly-playable audio formats from ALL lean client identities,
     * deduplicated by URL, best first (AAC near 128k, then everything else
     * by distance from the target tier). The caller plays [0] and stashes
     * the rest as rotation tickets.
     *
     * VISIONOS is the primary: its URLs are measured to stream past the
     * 1 MB wall that kills `c=IOS` URLs. IOS and ANDROID_VR remain as
     * independent rotation sources (VR needs a configured cookie). All
     * clients are queried in PARALLEL so total latency stays at one
     * round-trip.
     *
     * LATENCY BEFORE COMPLETENESS: once ANY client has answered with
     * formats, the rest get only [LEAN_ANSWER_GRACE_MS] to add rotation
     * tickets — a single stalled client must never park a tap on its own
     * HTTP timeout (live-seen: a 3.1s cold start while two clients had
     * long answered). Before the first good answer all clients are still
     * awaited: there, bailing out would only trigger the much slower
     * extraction fallback.
     */
    private suspend fun resolveLeanFormats(videoId: String): List<LeanAudio>? {
        if (!VIDEO_ID_RE.matches(videoId)) return null
        // Queries run on a DETACHED scope on purpose: java.net.http's send()
        // is blocking and not cancellation-interruptible, so structured
        // concurrency would keep this call parked in join() until a stalled
        // client hit its timeout even after an answer was picked.
        val queryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val answers = Channel<Pair<LeanClientProfile, List<LeanAudio>>>(Channel.UNLIMITED)
        LEAN_CLIENTS.forEach { profile ->
            queryScope.launch {
                val audios = try {
                    // The visitor-id fetch overlaps the OTHER clients' POSTs
                    // instead of serializing in front of the whole resolve:
                    // only the client that carries the id (VISIONOS) waits on
                    // it, so a cold visitor cache costs one branch's latency,
                    // not every resolve's.
                    val visitor = if (profile.wantsVisitorData) currentVisitorData() else null
                    if (profile.wantsVisitorData && visitor == null) {
                        // No session id: VISIONOS answers LOGIN_REQUIRED; IOS
                        // still resolves (with URLs that can hit the 1 MB
                        // wall); a configured cookie unlocks the rest.
                        logger.info("No visitor id available for {} — some clients may need a cookie", videoId)
                    }
                    fetchLeanFormats(videoId, profile, visitor)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.info("Lean player ({}) failed for {}: {}", profile.clientName, videoId, t.message)
                    emptyList()
                }
                answers.trySend(profile to audios)
            }
        }
        val preferred = LEAN_CLIENTS.firstOrNull { it.wantsVisitorData } ?: LEAN_CLIENTS.first()
        val collected = mutableListOf<Pair<LeanClientProfile, List<LeanAudio>>>()
        var firstGoodAt = 0L
        var preferredAnswered = false
        while (collected.size < LEAN_CLIENTS.size) {
            var answer = answers.tryReceive().getOrNull()
            if (answer == null) {
                if (firstGoodAt == 0L) {
                    // Nothing good yet — a null return here would send the
                    // caller into the slow extraction path, so keep waiting.
                    answer = answers.receive()
                } else {
                    // Formats already exist, so this is a pure quality wait:
                    // brief once the primary has answered (its tickets are
                    // the wall-safe ones); longer while it is still silent.
                    val budget = if (preferredAnswered) LEAN_ANSWER_GRACE_MS else LEAN_PREFERRED_WAIT_MS
                    val left = budget - (System.currentTimeMillis() - firstGoodAt)
                    if (left <= 0) break
                    answer = withTimeoutOrNull(left) { answers.receive() } ?: break
                }
            }
            collected += answer
            if (answer.first === preferred) preferredAnswered = true
            if (firstGoodAt == 0L && answer.second.isNotEmpty()) {
                firstGoodAt = System.currentTimeMillis()
            }
        }
        queryScope.cancel() // late answers are dropped by design
        // Client order — not arrival order — ranks the tickets: an early IOS
        // answer must not demote VISIONOS's wall-safe URLs.
        val audios = collected
            .sortedBy { LEAN_CLIENTS.indexOf(it.first) }
            .flatMap { it.second }
            .distinctBy { it.url }
        if (audios.isEmpty()) return null
        logger.info(
            "Lean resolve {}: {} tickets from {} client(s) [{}]",
            videoId, audios.size, collected.count { it.second.isNotEmpty() },
            collected.filter { it.second.isNotEmpty() }
                .sortedBy { LEAN_CLIENTS.indexOf(it.first) }
                .joinToString("+") { it.first.clientName },
        )
        // Same preference as the extractor path, as a RANKING so the caller
        // can rotate: AAC-LC (hardware-decoded on the watch, low battery)
        // near the 128k target tier first, then everything else by distance.
        return audios.sortedWith(
            compareBy(
                { if (it.mimeType.contains("mp4", ignoreCase = true)) 0 else 1 },
                {
                    if (it.bitrateKbps > 0) kotlin.math.abs(it.bitrateKbps - TARGET_AUDIO_BITRATE_KBPS)
                    else Int.MAX_VALUE
                }
            )
        )
    }

    /** One client's audio formats; empty on any failure. */
    private fun fetchLeanFormats(
        videoId: String,
        profile: LeanClientProfile,
        visitorData: String?,
    ): List<LeanAudio> {
        // contentCheckOk/racyCheckOk: without them modern clients can answer
        // UNPLAYABLE for perfectly ordinary music videos.
        val visitorField = visitorData?.let { ""","visitorData":"$it"""" } ?: ""
        val payload =
            """{"videoId":"$videoId","contentCheckOk":true,"racyCheckOk":true,""" +
                """"context":{"client":{""" +
                """"clientName":"${profile.clientName}","clientVersion":"${profile.clientVersion}",""" +
                profile.contextFields +
                """"hl":"en","gl":"US"$visitorField},"user":{}}}"""
        val builder = HttpRequest.newBuilder(URI.create(IOS_PLAYER_URL))
            .timeout(Duration.ofMillis(LEAN_HTTP_TIMEOUT_MS))
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            .header("User-Agent", profile.userAgent)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (profile.clientIdHeader.isNotEmpty()) {
            // VISIONOS is not addressed by the classic client-name headers —
            // live-verified call sends none (UA + context only).
            builder.header("X-YouTube-Client-Name", profile.clientIdHeader)
            builder.header("X-YouTube-Client-Version", profile.clientVersion)
        }
        // Bot-challenge workaround, same as the extractor path: attach
        // the session cookie when one is configured.
        YoutubeSession.cookie?.let { builder.header("Cookie", it) }
        val resp = leanHttp.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) return emptyList()
        val root = Json.parseToJsonElement(resp.body()).jsonObject
        val playability = root["playabilityStatus"]?.jsonObject
        if (playability?.get("status")?.jsonPrimitive?.contentOrNull != "OK") {
            // Surface WHY a client was skipped: on real deployments the fix
            // is usually "set a cookie" (bot wall) — see `wearsic cookies`.
            logger.info(
                "Lean player ({}) for {}: {} {}",
                profile.clientName,
                videoId,
                playability?.get("status")?.jsonPrimitive?.contentOrNull ?: "no status",
                playability?.get("reason")?.jsonPrimitive?.contentOrNull ?: "",
            )
            return emptyList()
        }
        val formats = root["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray ?: return emptyList()
        return formats.mapNotNull { f ->
            val o = f.jsonObject
            // Ciphered entries have signatureCipher instead of url —
            // leave those to full extraction (deciphering lives there).
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            if (!mime.contains("audio", ignoreCase = true)) return@mapNotNull null
            val br = o["bitrate"]?.jsonPrimitive?.intOrNull ?: 0
            // InnerTube states the file size as a STRING ("3449447"); the
            // proxy uses it to skip its pre-stream length probe.
            val len = o["contentLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            LeanAudio(url, mime, br / 1000, o["itag"]?.jsonPrimitive?.intOrNull ?: 0, profile.clientName, len)
        }
    }

    /**
     * iOS-spoofed client FIRST (it actually works against the bot wall), then
     * the default client as fallback. Both attempts happen INSIDE the
     * extraction mutex because the client choice is process-global state.
     */
    /**
     * Ranked playable audio streams from the FIRST client that yields any.
     * Fall through happens when a client yields NOTHING playable, not only
     * when it throws: the two clients parse stream tables differently, so a
     * video the iOS client misreads can still play via the default one.
     *
     * Ordering: AAC-LC (hardware-decoded on the watch for low battery) near
     * the 128k tier first, then everything else by distance — the caller
     * plays [0] and stashes the rest as rotation tickets.
     */
    private fun resolveAudioStreamsWithFallback(videoId: String): List<AudioStream> {
        for (useIos in CLIENT_ORDER) {
            try {
                val ranked = resolveAudioStreams(videoId, useIosClient = useIos)
                if (ranked.isNotEmpty()) return ranked
            } catch (e: Exception) {
                logger.info(
                    "{} client failed for video {}: {}",
                    if (useIos) "iOS" else "Default", videoId, e.message,
                )
            }
        }
        return emptyList()
    }

    private fun resolveAudioStreams(videoId: String, useIosClient: Boolean): List<AudioStream> {
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        if (extractor is YoutubeStreamExtractor) {
            // setFetchIosClient is a STATIC global toggle: it must be set
            // immediately before fetchPage() and always reset afterwards.
            // All such sequences are serialized by extractionMutex.
            YoutubeStreamExtractor.setFetchIosClient(useIosClient)
        }
        try {
            extractor.fetchPage()

            val candidates = extractor.audioStreams.orEmpty().filter { it.isUrl }
            if (candidates.isEmpty()) return emptyList()

            // Prefer streams with a known bitrate (good quality without
            // wasting the watch's bandwidth); rank across ALL of them so
            // failover/rotation can still reach every format.
            val withKnownBitrate = candidates.filter { it.averageBitrate > 0 }
            val ranked = if (withKnownBitrate.isNotEmpty()) withKnownBitrate else candidates
            return ranked.sortedWith(
                compareBy(
                    { if (it.format?.mimeType?.contains("mp4", ignoreCase = true) == true) 0 else 1 },
                    { kotlin.math.abs(it.averageBitrate - TARGET_AUDIO_BITRATE_KBPS) },
                )
            )
        } finally {
            if (extractor is YoutubeStreamExtractor) {
                YoutubeStreamExtractor.setFetchIosClient(false)
            }
        }
    }

    /**
     * Extractor streams as rotation tickets. These URLs are live-verified to
     * stream past the CDN's ~1 MB per-URL transfer wall, so they also serve
     * as the fresh-ticket source for wall-marked videos.
     */
    private fun extractorTickets(streams: List<AudioStream>): List<LeanAudio> =
        streams.mapNotNull { s ->
            val url = s.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LeanAudio(
                url = url,
                mimeType = s.format?.mimeType ?: "audio/mp4",
                bitrateKbps = s.averageBitrate,
                itag = s.itagItem?.id ?: 0,
                client = "extractor",
                // Same reason as the direct extractor target: skip the
                // proxy's length probe when the size is already known.
                contentLength = s.itagItem?.contentLength?.takeIf { it > 0L } ?: 0L,
            )
        }
}

/**
 * Extracts the session visitor id from YouTube's `sw.js_data` blob — a
 * `)]}'`-prefixed JSON array in which the id is the first long string
 * starting with "Cg". Real ids contain URL-encoded bytes (e.g. `%2F`), so
 * the only shape checks are the prefix, a sane length, and no whitespace.
 * Null when absent.
 */
internal fun visitorDataFrom(body: String): String? {
    val start = body.indexOf('[')
    if (start < 0) return null
    val root = runCatching { Json.parseToJsonElement(body.substring(start)) }.getOrNull() ?: return null
    fun find(e: JsonElement): String? = when (e) {
        is JsonArray -> e.firstNotNullOfOrNull { find(it) }
        is JsonPrimitive -> e.contentOrNull?.takeIf { s ->
            s.startsWith("Cg") && s.length >= 30 && s.none { it.isWhitespace() }
        }
        else -> null
    }
    return find(root)
}

/**
 * Pops the next rotation ticket from [queue] honouring byte-compatibility.
 *
 * - The same itag ALWAYS wins: byte offsets only encode the same timeline
 *   within the identical encoding, so a resumed stream must never switch
 *   itags (that would jump the audio to a different position).
 * - When [requireCompatible] is set and no same-itag ticket exists, the
 *   queue is left untouched and null is returned: the caller aborts
 *   instead of corrupting a live stream. Itag 0 (extractor-resolved
 *   targets) falls back to container MIME equality — the best available
 *   handle — before giving up.
 * - Without the compatibility requirement (nothing streamed yet), any
 *   ticket is acceptable.
 */
internal fun selectCompatibleTicket(
    queue: ArrayDeque<YoutubeGateway.LeanAudio>,
    preferItag: Int,
    preferMime: String?,
    requireCompatible: Boolean,
): YoutubeGateway.LeanAudio? {
    if (preferItag != 0) {
        val idx = queue.indexOfFirst { it.itag == preferItag }
        if (idx >= 0) return queue.removeAt(idx)
    }
    if (requireCompatible) {
        if (preferItag == 0 && preferMime != null) {
            val prefix = preferMime.substringBefore(';').trim()
            val idx = queue.indexOfFirst { it.mimeType.substringBefore(';').trim() == prefix }
            if (idx >= 0) return queue.removeAt(idx)
        }
        return null
    }
    return queue.removeFirstOrNull()
}

/**
 * Merges tickets from [fresh] into [existing], skipping URLs already known
 * and never exceeding [maxSize]. Appends by default; [prepend] puts the
 * fresh tickets at the HEAD (in their original order) so the next pop
 * prefers them — used for extractor tickets, which are the proven-unwalled
 * rotation source on throttled networks. Returns how many were added.
 */
internal fun mergeTickets(
    existing: ArrayDeque<YoutubeGateway.LeanAudio>,
    fresh: List<YoutubeGateway.LeanAudio>,
    maxSize: Int,
    prepend: Boolean = false,
): Int {
    if (fresh.isEmpty()) return 0
    val known = existing.mapTo(HashSet()) { it.url }
    var added = 0
    for (t in if (prepend) fresh.asReversed() else fresh) {
        if (existing.size >= maxSize) break
        if (!known.add(t.url)) continue
        if (prepend) existing.addFirst(t) else existing.addLast(t)
        added++
    }
    return added
}

/**
 * Client families whose raw URLs are live-verified to stream WHOLE songs
 * (no ~1 MB transfer wall): VISIONOS player URLs and extractor-built URLs
 * (which carry the deobfuscated `n`). Everything else (IOS, ANDROID_VR,
 * unknown) is wall-prone and gets the background extractor-ticket warm when
 * played.
 */
private fun isWallSafeClient(client: String): Boolean =
    client.equals("VISIONOS", ignoreCase = true) || client.equals("extractor", ignoreCase = true)

/**
 * The target the proxy streams from — one audio file, resolved from either a
 * lean player response, the extractor, or a rotation ticket.
 */
data class StreamTarget(
    val url: String,
    val mimeType: String,
    val itag: Int = 0,
    /**
     * Known byte length (0 = unknown). Lean resolutions carry it from the
     * player response so the proxy does not pay a length-probe round trip
     * before the first byte; extractor resolutions probe as before.
     */
    val contentLength: Long = 0L,
    /**
     * True when this URL's client family is known to hit the CDN's ~1 MB
     * transfer wall mid-song (IOS/VR/unknown). VISIONOS URLs are
     * live-verified wall-free and must NOT trigger the extractor warm —
     * that background extraction competes with the playback download and
     * showed up as slow tap-to-play on a real watch.
     */
    val needsExtractorWarm: Boolean = false,
)

/** Cache entry: the resolved CDN URL plus its expiry (URLs expire upstream). */
data class CachedStreamTarget(val target: StreamTarget, val expiresAtMillis: Long)

/** Extracts an 11-char YouTube video id from a full watch/short URL. */
internal fun extractVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
    Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
    Regex("/shorts/([a-zA-Z0-9_-]{11})").find(url)?.let { return it.groupValues[1] }
    return null
}

/**
 * Picks the SHARPEST thumbnail from NewPipeExtractor's Image list (the
 * largest one). The old min-height pick grabbed the 60px icon, which the
 * watch then displayed upscaled — the single biggest source of the
 * "blurry, cheap" artwork look.
 */
internal fun bestThumbnailUrl(thumbnails: List<Image>?): String? =
    thumbnails?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }?.url
        ?: thumbnails?.firstOrNull()?.url

/** Splits [text] into lowercase alphanumeric tokens ("AC/DC — Live!" -> ac dc live). */
private fun queryTokens(text: String): Set<String> =
    text.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
        .toSet()

/**
 * Album search relevance ranking.
 *
 * YouTube's "music_albums" search is name-fuzzy and happily returns
 * same-named albums by completely different artists/publishers. Ranking:
 *
 *  1. If ANY result contains every query token across name + uploader
 *     (e.g. "Thriller Michael Jackson"), those fully-matching albums are the
 *     answer set — the wrong-artist namesakes are dropped entirely.
 *  2. Otherwise fall back to partial scoring (name matches weigh more than
 *     uploader matches, exact normalized name match leads) and trim results
 *     that share no token at all with the query.
 *  3. If NOTHING matches the query (fuzzy/other-script queries), keep the
 *     original order — never nuke the result page over an imperfect query.
 */
internal fun rankAlbums(query: String, albums: List<AlbumDto>): List<AlbumDto> {
    val qTokens = queryTokens(query)
    if (albums.isEmpty() || qTokens.isEmpty()) return albums

    fun nameTokensOf(a: AlbumDto) = queryTokens(a.name)
    fun uploaderTokensOf(a: AlbumDto) = queryTokens(a.uploader)
    fun scoreOf(a: AlbumDto): Int {
        val name = nameTokensOf(a)
        val uploader = uploaderTokensOf(a)
        var score = qTokens.count { it in name } * 2 + qTokens.count { it in uploader }
        if (name == qTokens) score += 4 // exact normalized album-name match
        return score
    }

    val fullyMatching = albums.filter { a ->
        val all = nameTokensOf(a) + uploaderTokensOf(a)
        qTokens.all { it in all }
    }
    val pool = if (fullyMatching.isNotEmpty()) fullyMatching else albums
    val best = pool.maxOf { scoreOf(it) }
    if (best <= 0) return albums

    return pool
        .filter { scoreOf(it) > 0 }
        .sortedByDescending { scoreOf(it) } // stable: ties keep search order
}
