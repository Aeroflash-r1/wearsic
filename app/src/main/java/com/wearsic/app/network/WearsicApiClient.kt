package com.wearsic.app.network

import com.wearsic.app.network.model.AlbumDto
import com.wearsic.app.network.model.PlaylistDto
import com.wearsic.app.network.model.PlaylistWithTracksDto
import com.wearsic.app.network.model.ReadyResponseDto
import com.wearsic.app.network.model.SearchResponseDto
import com.wearsic.app.network.model.ServerHealthDto
import com.wearsic.app.network.model.TrackDto

interface WearsicApiClient {
    suspend fun checkHealth(baseUrl: String): Result<ServerHealthDto>
    /** /ready — "can this server actually serve music right now?" */
    suspend fun checkReady(baseUrl: String): Result<ReadyResponseDto>
    suspend fun searchTracks(baseUrl: String, query: String): Result<SearchResponseDto>
    /** Same search with an explicit page size (1..25, server-clamped). */
    suspend fun searchTracks(baseUrl: String, query: String, limit: Int): Result<SearchResponseDto>
    /**
     * Resolve a track server-side without streaming audio (`/api/prewarm`).
     * True when the server has a cached, playable target ready.
     */
    suspend fun prewarmTrack(baseUrl: String, videoId: String): Result<Boolean>
    suspend fun getFavorites(baseUrl: String): Result<List<TrackDto>>
    suspend fun addFavorite(baseUrl: String, track: TrackDto): Result<Unit>
    suspend fun removeFavorite(baseUrl: String, videoId: String): Result<Unit>
    suspend fun getPlaylists(baseUrl: String): Result<List<PlaylistDto>>
    suspend fun getPlaylistTracks(baseUrl: String, playlistId: String): Result<PlaylistWithTracksDto>
    suspend fun removeTrackFromPlaylist(baseUrl: String, playlistId: String, videoId: String): Result<Unit>

    // Discovery & library expansion
    suspend fun getSuggestions(baseUrl: String, query: String): Result<List<String>>
    suspend fun getRelated(baseUrl: String, videoId: String): Result<List<TrackDto>>
    suspend fun searchAlbums(baseUrl: String, query: String): Result<List<AlbumDto>>
    suspend fun getPlaylistByUrl(baseUrl: String, url: String): Result<PlaylistWithTracksDto>

    // Server-side playlists
    suspend fun createPlaylist(baseUrl: String, name: String): Result<PlaylistDto>
    suspend fun addTrackToPlaylist(baseUrl: String, playlistId: String, track: TrackDto): Result<Unit>
}