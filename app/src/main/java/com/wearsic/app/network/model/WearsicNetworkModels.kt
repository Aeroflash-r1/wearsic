package com.wearsic.app.network.model

import com.wearsic.app.model.Track
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.Album
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ServerHealthDto(
    val status: String = "ok",
    val version: String = "1.0.0",
    @SerialName("serverName") val serverName: String = "Wearsic Engine",
    // Engine capabilities + self-healing observability (server v1.9+). Every
    // field is optional with a default so a watch pointed at an older server
    // still decodes this response and only shows what the server reports.
    val transcoderAvailable: Boolean = false,
    val extraction: ExtractionHealthDto? = null,
    val canaryHealthy: Boolean? = null,
    val update: ServerUpdateStatusDto? = null,
    val crashCount: Int = 0,
    val lastCrashAtMillis: Long = 0,
    val upstreamRefusals: Int = 0
)

/** Extraction-engine success/failure counters from /health. */
@Serializable
data class ExtractionHealthDto(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val failureRatePercent: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val lastSuccessAtMillis: Long = 0,
    val lastFailureAtMillis: Long = 0
)

/**
 * Engine-update status from /health. `status == "staged"` means a new engine
 * is downloaded and waiting for the server supervisor to apply on restart.
 */
@Serializable
data class ServerUpdateStatusDto(
    val status: String = "idle",
    val latestKnownVersion: String? = null,
    val lastCheckAtMillis: Long = 0,
    val lastError: String? = null,
    val stagedVersion: String? = null,
    val updateAttempt: Int = 0,
    val rollbackReason: String? = null
)

/**
 * /ready — "can the server serve music right now?". Deliberately cheap:
 * database ping + in-memory counters, no network, no secrets.
 */
@Serializable
data class ReadyResponseDto(
    val ready: Boolean = false,
    val database: Boolean = false,
    val extractor: Boolean = false,
    val transcoder: Boolean = false,
    val engineVersion: String = ""
)

@Serializable
data class TrackDto(
    // Server contract (API_CONTRACT.md): tracks ship as videoId/uploader/
    // thumbnailUrl — NOT id/artist/artworkUrl. The kotlinx.serialization
    // rewrite (1.0.3) dropped these mappings, so every server response failed
    // to decode and search/favorites/playlists came back empty. The Kotlin
    // property names stay the same so domain call sites don't change.
    @SerialName("videoId") val id: String,
    val title: String,
    @SerialName("uploader") val artist: String,
    val album: String? = null,
    @SerialName("thumbnailUrl") val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    // Servers never send a streamUrl — the client synthesizes it from the
    // base URL + videoId. @Transient keeps it out of (de)serialization.
    @Transient val streamUrl: String = ""
) {
    fun toDomainTrack(): Track {
        return Track(
            id = id,
            title = title,
            artist = artist,
            album = album ?: "Single",
            artworkUrl = artworkUrl?.toHighResArtwork(),
            durationMs = durationMs,
            mediaUri = streamUrl,
            isFavorite = false
        )
    }

    /**
     * Server search results ship small thumbnails (e.g. `...=w60-h60-l90-rj`).
     * YouTube resizes on the fly — swap the size segment to w256-h256: sharp
     * enough for the 96dp header artwork and the blurred ambient glow, while
     * cutting image bytes ~4x vs the old w544-h544 (less radio = less heat).
     */
    private fun String.toHighResArtwork(): String {
        return if (contains("ytimg") || contains("googleusercontent")) {
            replace(Regex("w\\d+-h\\d+"), "w256-h256")
        } else {
            this
        }
    }

    fun toRequestBodyJson(): String {
        return kotlinx.serialization.json.Json.encodeToString(serializer(), this)
    }
}

@Serializable
data class SearchResponseDto(
    val query: String = "",
    val tracks: List<TrackDto> = emptyList()
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String,
    val uploader: String = "",
    val thumbnailUrl: String? = null
) {
    fun toDomainAlbum(): Album {
        return Album(
            id = id,
            name = name,
            uploader = uploader,
            thumbnailUrl = thumbnailUrl?.let { url ->
                if (url.contains("ytimg") || url.contains("googleusercontent")) {
                    url.replace(Regex("w\\d+-h\\d+"), "w256-h256")
                } else url
            }
        )
    }
}

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val thumbnailUrl: String? = null
) {
    fun toDomainPlaylist(): Playlist {
        return Playlist(
            id = id,
            name = name,
            trackCount = trackCount,
            thumbnailUrl = thumbnailUrl
        )
    }
}

@Serializable
data class PlaylistWithTracksDto(
    val id: String = "",
    val name: String = "",
    val tracks: List<TrackDto> = emptyList()
)

@Serializable
data class FavoritesResponseDto(
    val favorites: List<TrackDto> = emptyList()
)

@Serializable
data class SuggestionsResponseDto(
    val suggestions: List<String> = emptyList()
)

@Serializable
data class RelatedResponseDto(
    val results: List<TrackDto> = emptyList()
)

@Serializable
data class SearchResultsResponseDto(
    val results: List<TrackDto> = emptyList()
)

/** "Made for you" recommendations response (server v1.9.39+). */
@Serializable
data class RecommendationsResponseDto(
    val results: List<TrackDto> = emptyList()
)

@Serializable
data class RecommendationsRequestDto(
    val seedTrackIds: List<String> = emptyList(),
    val topArtists: List<String> = emptyList(),
    val excludeTrackIds: List<String> = emptyList(),
)

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val version: String, val serverName: String) : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}