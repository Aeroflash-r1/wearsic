package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A music track from YouTube Music (InnerTube `music.youtube.com` API).
 *
 * Unlike the legacy iTunes layer, every track already carries its REAL YouTube
 * [videoId] - search results play immediately with no surrogate id and no
 * second YouTube-matching step.
 */
data class YtmTrack(
    val videoId: String,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val album: String? = null,
)

@Serializable
private data class YtmSearchRequest(
    val context: YtmContext,
    val query: String,
    val params: String = YTMusicService.SONGS_PARAMS,
)

@Serializable
private data class YtmContext(val client: YtmClientInfo)

@Serializable
private data class YtmClientInfo(
    val clientName: String = "WEB_REMIX",
    val clientVersion: String,
    val hl: String = "en",
    val gl: String = "US",
)

/**
 * YouTube Music metadata source over the public InnerTube search endpoint.
 * No API key, OAuth or cookie needed - same public key + WEB_REMIX client
 * the YouTube Music web app uses (mirrors ytmusicapi unauthenticated
 * requests), restricted to the Songs filter so results are playable tracks.
 * Failures degrade to empty list so orchestrator falls back to NewPipe.
 */
class YTMusicService(
    private val client: HttpClient = defaultClient(),
    private val ownsClient: Boolean = true,
) : MetadataSource, AutoCloseable {

    companion object {
        const val BASE_URL = "https://music.youtube.com/youtubei/v1/search"
        const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val SONGS_PARAMS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"

        /** Repeat searches within this window skip the InnerTube round trip. */
        const val SEARCH_CACHE_TTL_MS: Long = 5 * 60 * 1000L // 5 minutes
        private const val SEARCH_CACHE_MAX_SIZE = 64

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0"
        private val DURATION_REGEX = Regex("""^(\d+:)?\d{1,3}:\d{2}$""")

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 15_000
            }
        }

        internal fun clientVersion(): String = runCatching {
            "1." + LocalDate.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.BASIC_ISO_DATE) + ".01.00"
        }.getOrDefault("1.20240101.01.00")

        internal fun parseDurationToMs(text: String): Long {
            val parts = text.split(":").map { it.toLongOrNull() ?: return 0L }
            if (parts.size !in 2..3 || parts.any { it < 0 }) return 0L
            val seconds = if (parts.size == 2) parts[0] * 60 + parts[1]
            else parts[0] * 3600 + parts[1] * 60 + parts[2]
            return seconds * 1000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // TTL cache for repeat queries: the watch debounces live suggestions and
    // users re-tap queries, so a 5-minute bounded cache avoids hammering
    // InnerTube with identical searches (each round trip is ~300-800ms).
    private val searchCache = BoundedCache<String, Pair<Long, List<YtmTrack>>>(SEARCH_CACHE_MAX_SIZE)

    override suspend fun searchSongs(query: String, limit: Int): List<YtmTrack> {
        if (query.isBlank()) return emptyList()
        val key = query.trim()
        val now = System.currentTimeMillis()
        searchCache.get(key)?.let { (cachedAt, cached) ->
            if (now - cachedAt < SEARCH_CACHE_TTL_MS) return cached
        }
        val tracks = runCatching {
            val body = json.encodeToString(
                YtmSearchRequest(
                    context = YtmContext(YtmClientInfo(clientVersion = clientVersion())),
                    query = key,
                )
            )
            val raw = client.post(BASE_URL) {
                parameter("alt", "json")
                parameter("key", API_KEY)
                contentType(ContentType.Application.Json)
                headers {
                    append("Origin", "https://music.youtube.com")
                    append("User-Agent", USER_AGENT)
                }
                setBody(body)
            }.bodyAsText()
            parseSearchResponse(raw, limit)
                .filter { it.title != null && it.artist != null }
        }.onFailure { e ->
            System.err.println("YTMUSIC search failed for '$query': $e")
        }.getOrDefault(emptyList())
        // Only cache usable results: a transient YTM failure must not serve
        // an empty page for the next 5 minutes (the NewPipe fallback would
        // never get its chance).
        if (tracks.isNotEmpty()) searchCache.put(key, now to tracks)
        return tracks
    }

    override fun toTrackDto(track: YtmTrack): TrackDto = TrackDto(
        videoId = track.videoId,
        title = track.title ?: "Unknown",
        uploader = track.artist ?: "Unknown",
        durationMs = track.durationMs ?: 0L,
        thumbnailUrl = track.thumbnailUrl,
    )

    override fun close() {
        if (ownsClient) runCatching { client.close() }
    }

    internal fun parseSearchResponse(body: String, limit: Int): List<YtmTrack> {
        if (limit <= 0) return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        // Collect every song row in the response, regardless of which shelf
        // type hosts it (musicShelfRenderer, musicCardShelfRenderer top
        // result, future layout renames). The old code only descended into
        // musicShelfRenderer, so a layout change silently yielded zero
        // results and forced every search onto the slower NewPipe fallback.
        // Recursive walk is layout-agnostic: any musicResponsiveListItemRenderer
        // anywhere in the tree is a candidate song row.
        val out = mutableListOf<YtmTrack>()
        collectSongRenderers(root, out, limit)
        // The same song can appear in several shelves of one response; a
        // duplicate row must never displace a second distinct song from the
        // (limited) result page.
        return out.distinctBy { it.videoId }
    }

    private fun collectSongRenderers(
        element: kotlinx.serialization.json.JsonElement,
        out: MutableList<YtmTrack>,
        limit: Int,
    ) {
        if (out.size >= limit) return
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return
        // Direct hit: this object IS a shelf entry wrapper.
        obj.getObject("musicResponsiveListItemRenderer")?.let { renderer ->
            parseItem(renderer)?.let {
                if (out.size < limit) out.add(it)
            }
            // Still descend: siblings may hide in adjacent keys, but don't
            // double-count this renderer itself.
            if (out.size >= limit) return
        }
        for ((key, child) in obj.entries) {
            if (out.size >= limit) break
            if (key == "musicResponsiveListItemRenderer") continue // already handled above
            runCatching {
                val arr = child.jsonArray
                for (item in arr) {
                    if (out.size >= limit) break
                    collectSongRenderers(item, out, limit)
                }
            }.getOrNull() ?: runCatching {
                collectSongRenderers(child, out, limit)
            }
        }
    }

    internal fun parseItem(renderer: JsonObject): YtmTrack? {
        val flexColumns = renderer.getArray("flexColumns")?.mapNotNull {
            it.asObject()?.getObject("musicResponsiveListItemFlexColumnRenderer")
        } ?: return null
        if (flexColumns.isEmpty()) return null
        val titleRuns = flexColumns.getOrNull(0)?.getObject("text")?.getArray("runs")
            ?.mapNotNull { it.asObject() } ?: emptyList()
        val title = titleRuns.firstOrNull()?.getString("text")
            ?.takeIf { it.isNotBlank() } ?: return null
        val titleNav = titleRuns.firstOrNull()?.getObject("navigationEndpoint")
        val titleVideoId = titleNav?.getObject("watchEndpoint")?.getString("videoId")
        // Album / artist / playlist CARD rows link their title to a BROWSE
        // page, not a watch page. Their thumbnail overlay play button points
        // at SOME OTHER video (an album's first track, a radio seed, …), and
        // trusting that fallback id used to leak into search results as a
        // "song" whose tap played the WRONG track. A row whose title is not a
        // playable song is never a song result.
        if (titleVideoId.isNullOrBlank() && titleNav?.getObject("browseEndpoint") != null) {
            return null
        }
        var videoId = titleVideoId
        if (videoId.isNullOrBlank()) {
            videoId = renderer.getObject("overlay")
                ?.getObject("musicItemThumbnailOverlayRenderer")
                ?.getObject("content")
                ?.getObject("musicPlayButtonRenderer")
                ?.getObject("playNavigationEndpoint")
                ?.getObject("watchEndpoint")?.getString("videoId")
        }
        if (videoId.isNullOrBlank()) {
            videoId = renderer.getObject("playlistItemData")?.getString("videoId")
        }
        if (videoId.isNullOrBlank()) return null
        val subRuns = flexColumns.getOrNull(1)?.getObject("text")?.getArray("runs")
            ?.mapNotNull { it.asObject() } ?: emptyList()
        val texts = subRuns.mapNotNull { it.getString("text") }
            .filter { it.trim() != "•" && it.isNotBlank() }
        val artist = texts.firstOrNull() ?: return null
        val durationMs = texts.lastOrNull { DURATION_REGEX.matches(it) }
            ?.let { parseDurationToMs(it) }
        val album = subRuns.firstOrNull { run ->
            run.getObject("navigationEndpoint")?.getObject("browseEndpoint")
                ?.getObject("browseEndpointContextSupportedConfigs")
                ?.getObject("browseEndpointContextMusicConfig")
                ?.getString("pageType") == "MUSIC_PAGE_TYPE_ALBUM"
        }?.getString("text")
        val thumbnailUrl = renderer.getObject("thumbnail")
            ?.getObject("musicThumbnailRenderer")?.getObject("thumbnail")
            ?.getArray("thumbnails")?.mapNotNull { el ->
                val o = el.asObject() ?: return@mapNotNull null
                val url = o.getString("url") ?: return@mapNotNull null
                val width = o.get("width")?.let {
                    runCatching { it.jsonPrimitive.content.toInt() }.getOrNull()
                } ?: 0
                width to url
            }?.maxByOrNull { it.first }?.second
        return YtmTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            album = album,
        )
    }

    private fun JsonObject.getObject(key: String): JsonObject? =
        get(key)?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.getArray(key: String) =
        get(key)?.let { runCatching { it.jsonArray }.getOrNull() }

    private fun JsonObject.getString(key: String): String? =
        get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}

