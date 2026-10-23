package com.wearsic.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.WearsicApp
import com.wearsic.app.model.Album
import com.wearsic.app.model.ArtistGroup
import com.wearsic.app.model.DnsError
import com.wearsic.app.model.HostUnreachableError
import com.wearsic.app.model.NetworkError
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.TimeoutError
import com.wearsic.app.model.Track
import com.wearsic.app.model.displayMessage
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

    /**
     * Share the process-wide graph that WearsicApp's warm-up thread already
     * built. Constructing fresh repositories here would redo that work on the
     * main thread and leave the app with two sets of clients/repositories
     * (the warm-up's copies went unused). The fallback keeps plain-Application
     * hosts — unit tests — working.
     */
    private val container = (application as? WearsicApp)?.container
        ?: com.wearsic.app.di.AppContainer(application)
    private val musicRepository = container.musicRepository
    private val recentRepository = container.recentRepository

    val recentTracks: StateFlow<List<Track>> = recentRepository.recentTracksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ============================================================================
    // Server reachability (drives the offline surface)
    // ============================================================================

    /**
     * True while the last server round-trip succeeded. Network-shaped failures
     * (timeout, DNS, unreachable host) flip it to false so the UI can switch
     * to an offline-friendly surface. Optimistic by default — it only turns
     * false on a real network error, never on data/validation errors.
     */
    private val _serverReachable = MutableStateFlow(true)
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    private fun reportServerResult(result: Result<*>) {
        val e = result.exceptionOrNull()
        _serverReachable.value = e == null || !isNetworkFailure(e)
    }

    private fun isNetworkFailure(e: Throwable): Boolean = when (e) {
        is TimeoutError, is HostUnreachableError, is DnsError, is NetworkError -> true
        else -> false
    }

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

    /** Favorites already warmed in this process (avoids re-resolving on refresh). */
    private val prewarmedFavoriteIds = mutableSetOf<String>()

    /**
     * Resolve the top favorites as soon as the list loads, so tapping one
     * starts playback from a cached target instead of a cold extraction.
     * Capped at three and de-duplicated per process.
     */
    private fun prewarmFavorites(tracks: List<Track>) {
        val ids = tracks.asSequence()
            .map { it.id }
            .filter { it.isNotBlank() && it !in prewarmedFavoriteIds }
            .distinct()
            .take(3)
            .toList()
        if (ids.isEmpty()) return
        prewarmedFavoriteIds.addAll(ids)
        viewModelScope.launch(Dispatchers.IO) {
            musicRepository.prewarmTracks(ids)
        }
    }

    fun refreshFavorites() {
        if (_favoritesState.value.isLoading) return
        _favoritesState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = musicRepository.getFavorites()
            reportServerResult(result)
            result.onSuccess { tracks ->
                _favoritesState.update { it.copy(tracks = tracks, isLoading = false, errorMessage = null) }
                prewarmFavorites(tracks)
            }.onFailure { err ->
                _favoritesState.update {
                    it.copy(isLoading = false, errorMessage = err.displayMessage("Could not load favorites"))
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
                    it.copy(errorMessage = err.displayMessage("Could not remove favorite"))
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
            reportServerResult(favoritesResult)
            reportServerResult(playlistsResult)

            var errorMessage: String? = null
            val favorites = favoritesResult.getOrNull()
            if (favorites != null) {
                _favoritesState.update { it.copy(tracks = favorites, errorMessage = null) }
                prewarmFavorites(favorites)
            } else {
                errorMessage = favoritesResult.exceptionOrNull()?.displayMessage("Could not load favorites")
                    ?: "Could not load favorites"
            }
            val playlists = playlistsResult.getOrNull()
            if (playlists == null) {
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
                    it.copy(playlistId = playlistId, isLoading = false, errorMessage = err.displayMessage("Could not load playlist"))
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
                    it.copy(errorMessage = err.displayMessage("Could not remove track"))
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
                if (track != null && track.id.isNotBlank() && playlist.id.isNotBlank()) {
                    // playlist.id must be real: a decode fallback used to hand
                    // back an empty-id playlist, so this add hit a malformed
                    // /api/playlists//tracks URL and silently achieved
                    // nothing while the UI moved on as if it had worked. (The
                    // client now fails loudly; the guard is belt-and-braces.)
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
            reportServerResult(result)
            result.onSuccess { albums ->
                _albumsState.update { it.copy(albums = albums, isLoading = false) }
            }.onFailure { err ->
                _albumsState.update {
                    it.copy(albums = emptyList(), isLoading = false, errorMessage = err.displayMessage("Could not load albums"))
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
        val suggestions: List<String> = emptyList(),
        /** Expanded page in flight (first page stays visible meanwhile). */
        val isLoadingMore: Boolean = false,
        /** True while a bigger page could add rows (first page full, no error). */
        val canLoadMore: Boolean = false
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
            reportServerResult(result)
            result.onSuccess { tracks ->
                _searchState.update {
                    it.copy(
                        results = tracks,
                        isSearching = false,
                        errorMessage = null,
                        hasSearched = true,
                        // A full first page means the server likely has more.
                        canLoadMore = tracks.size >= com.wearsic.app.network.WearsicHttpApiClient.SEARCH_PAGE_SIZE
                    )
                }
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        results = emptyList(),
                        isSearching = false,
                        errorMessage = err.displayMessage("Search failed"),
                        hasSearched = true,
                        canLoadMore = false
                    )
                }
            }
        }
    }

    /**
     * "Show more": re-asks the server with the expanded page and merges new
     * rows behind the existing ones (dedupe by id — page boundaries can
     * shift between calls). First page stays on screen the whole time.
     */
    fun loadMoreResults() {
        val state = _searchState.value
        if (state.isSearching || state.isLoadingMore || !state.canLoadMore) return
        if (state.query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            val result = musicRepository.searchMusic(
                state.query,
                com.wearsic.app.network.WearsicHttpApiClient.SEARCH_MAX_LIMIT
            )
            result.onSuccess { tracks ->
                val known = state.results.map { it.id }.toSet()
                _searchState.update {
                    it.copy(
                        results = it.results + tracks.filter { t -> t.id !in known },
                        isLoadingMore = false,
                        errorMessage = null,
                        canLoadMore = false
                    )
                }
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        isLoadingMore = false,
                        errorMessage = err.displayMessage("Could not load more"),
                        canLoadMore = false
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
                _radioState.value = RadioState.Error(err.displayMessage("Radio unavailable"))
                delay(2500)
                _radioState.value = RadioState.Idle
            }
        }
    }
}

