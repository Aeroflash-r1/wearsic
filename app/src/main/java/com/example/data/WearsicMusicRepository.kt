package com.example.data

import android.content.Context
import com.example.model.Album
import com.example.model.EmptyInputError
import com.example.model.InvalidUrlError
import com.example.model.Playlist
import com.example.model.Track
import com.example.model.UnknownError
import com.example.model.WearsicError
import com.example.network.WearsicApiClient
import com.example.network.WearsicHttpApiClient
import com.example.network.model.ConnectionTestState
import com.example.network.model.TrackDto
import com.example.util.Validation
import com.example.util.sanitizeDisplay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WearsicMusicRepository(
    private val context: Context,
    private val preferencesRepository: WearsicPreferencesRepository = WearsicPreferencesRepository(context),
    private val httpApiClient: WearsicApiClient = WearsicHttpApiClient()
) {

    val serverUrlFlow = preferencesRepository.serverUrlFlow

    /** Pushes the stored API key into the shared HTTP client so every
     *  request (API, media streams, downloads) carries it. */
    suspend fun refreshApiKeyWith(key: String) {
        com.example.network.WearsicHttp.apiKey = key.trim()
    }

    suspend fun saveApiKey(key: String) {
        preferencesRepository.saveApiKey(key)
        refreshApiKeyWith(key)
    }

    suspend fun getServerUrl(): String {
        return preferencesRepository.serverUrlFlow.first()
    }

    suspend fun saveServerUrl(url: String) {
        preferencesRepository.saveServerUrl(url)
    }

    suspend fun testServerConnection(targetUrl: String): ConnectionTestState {
        val urlValidation = Validation.validateServerUrl(targetUrl)
        if (urlValidation.isFailure) {
            val error = urlValidation.exceptionOrNull() as? WearsicError
            return ConnectionTestState.Error(error?.getDisplayMessage() ?: "Invalid URL")
        }
        val url = urlValidation.getOrThrow()

        val httpResult = httpApiClient.checkHealth(url)
        if (httpResult.isSuccess) {
            val health = httpResult.getOrThrow()
            return ConnectionTestState.Success(version = health.version, serverName = health.serverName)
        }

        val error = httpResult.exceptionOrNull()
        val errorMsg = when (error) {
            is WearsicError -> error.getDisplayMessage()
            else -> error?.message ?: "Connection failed"
        }
        return ConnectionTestState.Error(errorMsg)
    }

    suspend fun searchMusic(query: String): Result<List<Track>> {
        val queryValidation = Validation.validateQuery(query)
        if (queryValidation.isFailure) {
            val error = queryValidation.exceptionOrNull() as? WearsicError
            return Result.failure(error ?: EmptyInputError("query"))
        }
        val currentUrl = getServerUrl()
        
        val httpResult = httpApiClient.searchTracks(currentUrl, queryValidation.getOrThrow())
        if (httpResult.isSuccess) {
            val dtoList = httpResult.getOrThrow().tracks
            val domainTracks = dtoList.map { it.toDomainTrack() }
            if (domainTracks.isNotEmpty()) {
                return Result.success(domainTracks)
            }
        }

        val exception = httpResult.exceptionOrNull() ?: Exception("No tracks found on server")
        return Result.failure(exception)
    }

    suspend fun getFavorites(): Result<List<Track>> {
        val currentUrl = getServerUrl()
        val httpResult = httpApiClient.getFavorites(currentUrl)
        return httpResult.map { dtoList -> dtoList.map { it.toDomainTrack() } }
    }

    suspend fun addFavorite(track: Track): Result<Unit> {
        val trackIdValidation = Validation.validateTrackId(track.id)
        if (trackIdValidation.isFailure) {
            val error = trackIdValidation.exceptionOrNull() as? WearsicError
            return Result.failure(error ?: com.example.model.InvalidTrackIdError(track.id))
        }
        val currentUrl = getServerUrl()
        val dto = TrackDto(
            id = trackIdValidation.getOrThrow(),
            title = track.title.sanitizeDisplay(),
            artist = track.artist.sanitizeDisplay(),
            album = null,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            streamUrl = track.mediaUri
        )
        return httpApiClient.addFavorite(currentUrl, dto)
    }

    suspend fun removeFavorite(trackId: String): Result<Unit> {
        val trackIdValidation = Validation.validateTrackId(trackId)
        if (trackIdValidation.isFailure) {
            val error = trackIdValidation.exceptionOrNull() as? WearsicError
            return Result.failure(error ?: com.example.model.InvalidTrackIdError(trackId))
        }
        val currentUrl = getServerUrl()
        return httpApiClient.removeFavorite(currentUrl, trackIdValidation.getOrThrow())
    }

    suspend fun getPlaylists(): Result<List<Playlist>> {
        val currentUrl = getServerUrl()
        return httpApiClient.getPlaylists(currentUrl).map { dtoList -> dtoList.map { it.toDomainPlaylist() } }
    }

    suspend fun getPlaylistTracks(playlistId: String): Result<List<Track>> {
        val currentUrl = getServerUrl()
        return httpApiClient.getPlaylistTracks(currentUrl, playlistId)
            .map { dto -> dto.tracks.map { it.toDomainTrack() } }
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit> {
        val currentUrl = getServerUrl()
        return httpApiClient.removeTrackFromPlaylist(currentUrl, playlistId, trackId)
    }

    suspend fun getSuggestions(query: String): Result<List<String>> {
        val currentUrl = getServerUrl()
        return httpApiClient.getSuggestions(currentUrl, query)
    }

    suspend fun getRelated(videoId: String): Result<List<Track>> {
        val currentUrl = getServerUrl()
        return httpApiClient.getRelated(currentUrl, videoId).map { dtoList -> dtoList.map { it.toDomainTrack() } }
    }

    suspend fun searchAlbums(query: String): Result<List<Album>> {
        val currentUrl = getServerUrl()
        return httpApiClient.searchAlbums(currentUrl, query).map { dtoList -> dtoList.map { it.toDomainAlbum() } }
    }

    /**
     * Resolves a playlist by server id OR by full URL (albums use URLs).
     */
    suspend fun getPlaylistTracksFlexible(playlistRef: String): Result<List<Track>> {
        val currentUrl = getServerUrl()
        return if (playlistRef.startsWith("http")) {
            httpApiClient.getPlaylistByUrl(currentUrl, playlistRef).map { it.tracks.map { t -> t.toDomainTrack() } }
        } else {
            httpApiClient.getPlaylistTracks(currentUrl, playlistRef).map { it.tracks.map { t -> t.toDomainTrack() } }
        }
    }

    suspend fun createPlaylist(name: String): Result<Playlist> {
        val nameValidation = Validation.validatePlaylistName(name)
        if (nameValidation.isFailure) {
            val error = nameValidation.exceptionOrNull() as? WearsicError
            return Result.failure(error ?: EmptyInputError("playlist name"))
        }
        val currentUrl = getServerUrl()
        return httpApiClient.createPlaylist(currentUrl, nameValidation.getOrThrow()).map { it.toDomainPlaylist() }
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: Track): Result<Unit> {
        val trackIdValidation = Validation.validateTrackId(track.id)
        if (trackIdValidation.isFailure) {
            val error = trackIdValidation.exceptionOrNull() as? WearsicError
            return Result.failure(error ?: com.example.model.InvalidTrackIdError(track.id))
        }
        val currentUrl = getServerUrl()
        val dto = TrackDto(
            id = trackIdValidation.getOrThrow(),
            title = track.title.sanitizeDisplay(),
            artist = track.artist.sanitizeDisplay(),
            album = null,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            streamUrl = track.mediaUri
        )
        return httpApiClient.addTrackToPlaylist(currentUrl, playlistId, dto)
    }
}
