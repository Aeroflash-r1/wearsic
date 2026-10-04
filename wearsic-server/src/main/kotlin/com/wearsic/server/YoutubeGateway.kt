package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.Dispatchers
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

        /** Client order for stream extraction: iOS first, default fallback. */
        internal val CLIENT_ORDER: List<Boolean> = listOf(true, false)

        /** Sentinel marking "extraction ran but nothing playable was found". */
        internal const val NOT_PLAYABLE = "wearsic:not-playable"

        const val SUGGESTIONS_TTL_MS = 10 * 60 * 1000L
        const val RELATED_TTL_MS = 30 * 60 * 1000L
        const val ALBUMS_TTL_MS = 30 * 60 * 1000L
        const val PLAYLIST_TTL_MS = 10 * 60 * 1000L

        /** Effectively-permanent video used by the canary probe. */
        internal const val CANARY_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }

    // ---------------- Caches ----------------

    private val searchCache = BoundedCache<String, List<TrackDto>>(maxSize = 128)
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

    // ---------------- Search ----------------

    override suspend fun search(query: String): List<TrackDto> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()

        val cacheKey = "search:$normalized"
        searchCache.get(cacheKey)?.let { return it }

        return searchSingleFlight.run(cacheKey) {
            // Double-check the cache after joining: the winner just populated it.
            searchCache.get(cacheKey)?.let { return@run it }
            val results = searchMusic(normalized)
            searchCache.put(cacheKey, results)
            results
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
            extractor.relatedItems?.items
                .orEmpty()
                .filterIsInstance<StreamInfoItem>()
                .filter { it.duration in 1..(MAX_RELATED_MINUTES * 60) }
                .mapNotNull { it.toTrackDtoOrNull() }
                .distinctBy { it.videoId }
                .take(MAX_RESULTS)
        }
            .onSuccess { healthMeter?.recordSuccess() }
            .onFailure { healthMeter?.recordFailure("related: ${it.message}") }
            .getOrDefault(emptyList())
        if (result.isNotEmpty()) relatedCache.put(videoId, Timed(result, now + RELATED_TTL_MS))
        result
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

            // Track whether the timeout fired vs extraction genuinely finding
            // nothing: a timeout is TRANSIENT (queue jam / slow YouTube) and
            // must NOT be cached as NOT_PLAYABLE, or the next 5 min of taps
            // get instant-404s instead of a retry.
            var timedOut = false
            val audio = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
                extractionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        resolveAudioStreamWithFallback(videoId)
                    }
                }
            } ?: run { timedOut = true; null }

            when {
                audio != null -> {
                    healthMeter?.recordSuccess()
                    val target = StreamTarget(
                        url = audio.content,
                        mimeType = audio.format?.mimeType ?: "audio/webm",
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
     * Dead-URL self-heal: the proxy observed the CDN rejecting this video's
     * resolved URL (403/404/410). Drop the stale entry so the next resolution
     * does fresh extraction, then return the new target (null = re-resolve
     * also failed; the proxy answers the error it already has).
     */
    suspend fun invalidateStreamTarget(videoId: String): StreamTarget? {
        streamCache.remove(videoId)
        logger.info("Invalidated stale stream target for {} — re-resolving", videoId)
        return streamTarget(videoId)
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
     * iOS-spoofed client FIRST (it actually works against the bot wall), then
     * the default client as fallback. Both attempts happen INSIDE the
     * extraction mutex because the client choice is process-global state.
     */
    private fun resolveAudioStreamWithFallback(videoId: String): AudioStream? {
        for (useIos in CLIENT_ORDER) {
            try {
                val audio = resolveAudioStream(videoId, useIosClient = useIos)
                // Fall through when a client yields NOTHING playable,
                // not only when it throws: the two clients parse
                // stream tables differently, so a video the iOS
                // client misreads can still play via the default one.
                if (audio != null) return audio
            } catch (e: Exception) {
                logger.info(
                    "{} client failed for video {}: {}",
                    if (useIos) "iOS" else "Default", videoId, e.message,
                )
            }
        }
        return null
    }

    private fun resolveAudioStream(videoId: String, useIosClient: Boolean): AudioStream? {
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
            if (candidates.isEmpty()) return null

            // Prefer AAC-LC (m4a/mp4 container): hardware-decoded on the watch
            // for low battery drain. Only fall back to Opus/Vorbis (WebM) when
            // YouTube offers no AAC audio at all.
            val aacCandidates = candidates.filter { stream ->
                stream.format?.mimeType?.contains("mp4", ignoreCase = true) == true
            }
            val pool = if (aacCandidates.isNotEmpty()) aacCandidates else candidates

            // Prefer streams with a known bitrate; among those, pick the one
            // closest to the target — good quality without wasting the watch's
            // storage/bandwidth.
            val withKnownBitrate = pool.filter { it.averageBitrate > 0 }
            val selectable = if (withKnownBitrate.isNotEmpty()) withKnownBitrate else pool
            return selectable.minByOrNull { stream ->
                kotlin.math.abs(stream.averageBitrate - TARGET_AUDIO_BITRATE_KBPS)
            }
        } finally {
            if (extractor is YoutubeStreamExtractor) {
                YoutubeStreamExtractor.setFetchIosClient(false)
            }
        }
    }
}

/** A resolved, playable audio target: CDN URL plus MIME type. */
data class StreamTarget(val url: String, val mimeType: String)

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
