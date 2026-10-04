package com.example.network.model

import com.example.model.Track
import com.example.model.Playlist
import com.example.model.Album
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class ServerHealthDto(
    val status: String = "ok",
    val version: String = "1.0.0",
    @SerialName("serverName") val serverName: String = "Wearsic Engine"
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

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val version: String, val serverName: String) : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}