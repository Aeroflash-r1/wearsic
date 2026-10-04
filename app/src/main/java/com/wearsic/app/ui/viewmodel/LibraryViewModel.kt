package com.wearsic.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicRecentRepository
import com.wearsic.app.model.Album
import com.wearsic.app.model.ArtistGroup
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.Track
import com.wearsic.app.network.model.ConnectionTestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for library content: favorites, playlists, albums, artists.
 * Handles all library browsing and discovery operations.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val musicRepository = WearsicMusicRepository(application.applicationContext)
    private val recentRepository = WearsicRecentRepository(application.applicationContext)

    val recentTracks: StateFlow<List<Track>> = recentRepository.recentTracksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ============================================================================
    // Favorites State
    // ============================================================================

    data class FavoritesUiState(
        val tracks: List<Track> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _favoritesState = MutableStateFlow(FavoritesUiState())
    val favoritesState: StateFlow<FavoritesUiState> = _favoritesState.asStateFlow()

    fun refreshFavorites() {
        if (_favoritesState.value.isLoading) return
        _favoritesState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = musicRepository.getFavorites()
            result.onSuccess { tracks ->
                _favoritesState.update { it.copy(tracks = tracks, isLoading = false, errorMessage = null) }
            }.onFailure { err ->
                _favoritesState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Could not load favorites")
                }
            }
        }
    }

    fun removeFavorite(trackId: String) {
        viewModelScope.launch {
            val result = musicRepository.removeFavorite(trackId)
            result.onSuccess {
                val updatedFavorites = _favoritesState.value.tracks.filterNot { it.id == trackId }
                _favoritesState.update { it.copy(tracks = updatedFavorites, errorMessage = null) }
            }.onFailure { err ->
                _favoritesState.update {
                    it.copy(errorMessage = err.message ?: "Could not remove favorite")
                }
            }
        }
    }

    // ============================================================================
    // Playlists State
    // ============================================================================

    data class PlaylistsUiState(
        val favorites: List<Track> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _playlistsState = MutableStateFlow(PlaylistsUiState())
    val playlistsState: StateFlow<PlaylistsUiState> = _playlistsState.asStateFlow()

    fun refreshLibrary() {
        if (_playlistsState.value.isLoading) return
        _playlistsState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val favoritesResult = musicRepository.getFavorites()
            val playlistsResult = musicRepository.getPlaylists()

            var errorMessage: String? = null
            val favorites = favoritesResult.getOrNull()
            if (favorites != null) {
                _favoritesState.update { it.copy(tracks = favorites, errorMessage = null) }
            } else {
                errorMessage = favoritesResult.exceptionOrNull()?.message ?: "Could not load favorites"
            }
            val playlists = playlistsResult.getOrNull()
            if (playlists != null) {
                // no-op, applied below
            } else {
                errorMessage = playlistsResult.exceptionOrNull()?.message ?: errorMessage ?: "Could not load playlists"
            }

            _playlistsState.update {
                it.copy(
                    favorites = favorites ?: _favoritesState.value.tracks,
                    playlists = playlists ?: it.playlists,
                    isLoading = false,
                    errorMessage = errorMessage
                )
            }
        }
    }

    // ============================================================================
    // Playlist Detail State
    // ============================================================================

    data class PlaylistDetailUiState(
        val playlistId: String = "",
        val tracks: List<Track> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _playlistDetailState = MutableStateFlow(PlaylistDetailUiState())
    val playlistDetailState: StateFlow<PlaylistDetailUiState> = _playlistDetailState.asStateFlow()

    private var detailJob: Job? = null

    fun loadPlaylistTracks(playlistId: String) {
        val current = _playlistDetailState.value
        if (current.playlistId == playlistId && (current.isLoading || current.errorMessage == null)) return

        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            _playlistDetailState.update {
                it.copy(playlistId = playlistId, isLoading = true, errorMessage = null, tracks = emptyList())
            }
            var result = musicRepository.getPlaylistTracksFlexible(playlistId)
            if (result.isFailure) {
                delay(1500)
                result = musicRepository.getPlaylistTracksFlexible(playlistId)
            }
            result.onSuccess { tracks ->
                if (_playlistDetailState.value.playlistId != playlistId) return@onSuccess
                _playlistDetailState.update {
                    it.copy(playlistId = playlistId, tracks = tracks, isLoading = false, errorMessage = null)
                }
            }.onFailure { err ->
                if (_playlistDetailState.value.playlistId != playlistId) return@launch
                _playlistDetailState.update {
                    it.copy(playlistId = playlistId, isLoading = false, errorMessage = err.message ?: "Could not load playlist")
                }
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            val result = musicRepository.removeTrackFromPlaylist(playlistId, trackId)
            result.onSuccess {
                _playlistDetailState.update { current ->
                    current.copy(
                        tracks = current.tracks.filterNot { it.id == trackId },
                        errorMessage = null
                    )
                }
                refreshLibrary()
            }.onFailure { err ->
                _playlistDetailState.update {
                    it.copy(errorMessage = err.message ?: "Could not remove track")
                }
            }
        }
    }

    fun removePlaylist(id: String) {
        viewModelScope.launch {
            musicRepository.removeTrackFromPlaylist(id, "*")
            refreshLibrary()
        }
    }

    // ============================================================================
    // Playlist Creation
    // ============================================================================

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val created = musicRepository.createPlaylist(name.trim())
            created.onSuccess { playlist ->
                refreshLibrary()
            }
        }
    }

    fun createPlaylistAndAdd(name: String, track: Track?) {
        viewModelScope.launch {
            val created = musicRepository.createPlaylist(name.trim())
            created.onSuccess { playlist ->
                if (track != null && track.id.isNotBlank()) {
                    musicRepository.addTrackToPlaylist(playlist.id, track)
                }
                refreshLibrary()
            }
        }
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    // ============================================================================
    // Albums State
    // ============================================================================

    data class AlbumsUiState(
        val query: String = "",
        val albums: List<Album> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _albumsState = MutableStateFlow(AlbumsUiState())
    val albumsState: StateFlow<AlbumsUiState> = _albumsState.asStateFlow()

    private var albumsJob: Job? = null

    fun searchAlbums(query: String) {
        if (query.isBlank()) {
            _albumsState.update { it.copy(query = "", albums = emptyList(), isLoading = false) }
            return
        }
        albumsJob?.cancel()
        _albumsState.update { it.copy(query = query, isLoading = true, errorMessage = null) }
        albumsJob = viewModelScope.launch {
            val result = musicRepository.searchAlbums(query.trim())
            result.onSuccess { albums ->
                _albumsState.update { it.copy(albums = albums, isLoading = false) }
            }.onFailure { err ->
                _albumsState.update {
                    it.copy(albums = emptyList(), isLoading = false, errorMessage = err.message ?: "Could not load albums")
                }
            }
        }
    }

    // ============================================================================
    // Artists State
    // ============================================================================

    data class ArtistsUiState(
        val artists: List<ArtistGroup> = emptyList(),
        val isLoading: Boolean = false
    )

    private val _artistsState = MutableStateFlow(ArtistsUiState())
    val artistsState: StateFlow<ArtistsUiState> = _artistsState.asStateFlow()

    fun refreshArtists() {
        viewModelScope.launch {
            _artistsState.value = ArtistsUiState(isLoading = true)
            val downloaded = withContext(Dispatchers.IO) {
                musicRepository.getFavorites().getOrElse { emptyList() }
            }
            val favorites = _favoritesState.value.tracks
            val combined = (downloaded + favorites).distinctBy { it.id }
            val groups = combined
                .filter { it.artist.isNotBlank() }
                .groupBy { it.artist.trim().lowercase() }
                .map { (_, songs) -> ArtistGroup(name = songs.first().artist.trim(), songs = songs.sortedBy { it.title.lowercase() }) }
                .sortedBy { it.name.lowercase() }
            _artistsState.value = ArtistsUiState(artists = groups)
        }
    }

    // ============================================================================
    // Search State
    // ============================================================================

    data class SearchUiState(
        val query: String = "",
        val results: List<Track> = emptyList(),
        val isSearching: Boolean = false,
        val errorMessage: String? = null,
        val hasSearched: Boolean = false,
        val suggestions: List<String> = emptyList()
    )

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.update { it.copy(query = query, isSearching = true, errorMessage = null) }
            val result = musicRepository.searchMusic(query)
            result.onSuccess { tracks ->
                _searchState.update {
                    it.copy(
                        results = tracks,
                        isSearching = false,
                        errorMessage = null,
                        hasSearched = true
                    )
                }
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        results = emptyList(),
                        isSearching = false,
                        errorMessage = err.message ?: "Search failed",
                        hasSearched = true
                    )
                }
            }
        }
    }

    fun onSearchTextChanged(text: String) {
        _searchState.update { it.copy(query = text) }
        suggestionsJob?.cancel()
        if (text.isBlank()) {
            _searchState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionsJob = viewModelScope.launch {
            delay(300)
            val result = musicRepository.getSuggestions(text.trim())
            result.onSuccess { list ->
                _searchState.update { it.copy(suggestions = list.take(6)) }
            }.onFailure {
                _searchState.update { it.copy(suggestions = emptyList()) }
            }
        }
    }

    // ============================================================================
    // Radio
    // ============================================================================

    sealed interface RadioState {
        data object Idle : RadioState
        data object Loading : RadioState
        data class Error(val message: String) : RadioState
    }

    private val _radioState = MutableStateFlow<RadioState>(RadioState.Idle)
    val radioState: StateFlow<RadioState> = _radioState.asStateFlow()

    private var radioJob: Job? = null

    fun startRadio(playbackViewModel: PlaybackViewModel) {
        val current = playbackViewModel.uiState.value.currentTrack
        if (current.id.isBlank()) return
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            _radioState.value = RadioState.Loading
            val result = musicRepository.getRelated(current.id)
            result.onSuccess { related ->
                val existingIds = playbackViewModel.uiState.value.playlist.map { it.id }.toSet()
                val fresh = related.filter { rel ->
                    rel.id !in existingIds && rel.durationMs in 1..600_000
                }
                if (fresh.isEmpty()) {
                    _radioState.value = RadioState.Idle
                    return@launch
                }
                playbackViewModel.addToQueue(fresh)
                _radioState.value = RadioState.Idle
            }.onFailure { err ->
                _radioState.value = RadioState.Error(err.message ?: "Radio unavailable")
                delay(2500)
                _radioState.value = RadioState.Idle
            }
        }
    }
}

