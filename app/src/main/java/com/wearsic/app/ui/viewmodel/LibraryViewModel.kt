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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel responsible for library content: favorites, playlists, albums, artists.
 * Handles all library browsing and discovery operations.
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        /** Network failures flip the UI offline only after this much silence. */
        const val OFFLINE_GRACE_MS = 30_000L
    }

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
    private val favoriteRepository = container.favoriteRepository

    /** Live search-suggestions preference (set from Settings). */
    private val _searchSuggestionsEnabled = MutableStateFlow(true)
    val searchSuggestionsEnabled: StateFlow<Boolean> = _searchSuggestionsEnabled.asStateFlow()

    init {
        // Always keep the toggle mirrored — not a WhileSubscribed stateIn:
        // onSearchTextChanged reads .value, and if nothing happened to be
        // subscribed to the flow at that moment, a lazy stateIn would still
        // report the default (true) and the disabled toggle would never
        // actually disable suggestions.
        viewModelScope.launch {
            container.preferencesRepository.searchSuggestionsEnabledFlow.collect {
                _searchSuggestionsEnabled.value = it
            }
        }
    }

    val recentTracks: StateFlow<List<Track>> = recentRepository.recentTracksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Tears down this manually-constructed ViewModel's scope when the owning
     * player ViewModel is cleared (the framework never calls onCleared here).
     */
    fun release() {
        viewModelScope.cancel()
    }

    // ============================================================================
    // Server reachability (drives the offline surface)
    // ============================================================================

    /**
     * True while the server is considered up. HYSTERESIS on purpose: watch
     * Wi-Fi power-save makes single round-trips time out all the time, so a
     * one-miss "offline→online→offline" flip-flop (what v1.9.41 did) is
     * exactly the inconsistent state the user sees on the home screen:
     *
     *   · a SUCCESS flips to online IMMEDIATELY;
     *   · a network-shaped FAILURE only flips to offline when the last
     *     success is older than [OFFLINE_GRACE_MS] (30 s) — transient blips
     *     are invisible, a genuinely down server shows within half a minute.
     */
    private val _serverReachable = MutableStateFlow(true)
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    /** A network failure marks offline only after this long without success. */
    private var lastServerSuccessAt = 0L
    private var everSucceeded = false

    /** One health-confirmation ping at a time (a search burst must not spam). */
    private val confirmPingGate = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun reportServerResult(result: Result<*>) {
        val e = result.exceptionOrNull()
        if (e == null || !isNetworkFailure(e)) {
            lastServerSuccessAt = System.currentTimeMillis()
            everSucceeded = true
            if (!_serverReachable.value) _serverReachable.value = true
            return
        }
        val stale = everSucceeded &&
            (System.currentTimeMillis() - lastServerSuccessAt) > OFFLINE_GRACE_MS
        if (stale && _serverReachable.value && confirmPingGate.compareAndSet(false, true)) {
            // One slow failure alone proves nothing: a search may legitimately
            // run long on a HEALTHY server. Before the UI announces offline,
            // confirm with a quick health ping; if it answers (or fails with
            // anything network-plausible-but-alive), the server stays online.
            viewModelScope.launch {
                try {
                    val ping = musicRepository.probeServerHealth(
                        musicRepository.serverUrlFlow.first()
                    )
                    val pingError = ping.exceptionOrNull()
                    if (pingError != null && isNetworkFailure(pingError)) {
                        _serverReachable.value = false
                    } else {
                        // Reachable after all — refresh the success clock so
                        // the next miss starts its grace window fresh.
                        lastServerSuccessAt = System.currentTimeMillis()
                    }
                } catch (_: Exception) {
                    // Probe transport hiccup — keep the current online state.
                } finally {
                    confirmPingGate.set(false)
                }
            }
        }
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

    /** Live local favorite ids for hearts everywhere (Room-backed, offline-safe). */
    val favoriteIds: StateFlow<Set<String>> = favoriteRepository.favoriteIdsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    init {
        // The Favorites UI is fed straight from the local Room store — instant,
        // offline-safe, no network round trip. Server import happens separately
        // via refreshFavorites() and never gates what the screen shows.
        viewModelScope.launch {
            favoriteRepository.favoriteTracksFlow.collect { tracks ->
                _favoritesState.value = FavoritesUiState(
                    tracks = tracks,
                    isLoading = false,
                    errorMessage = null,
                )
                prewarmFavorites(tracks)
            }
        }
    }

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
        // Favorites are local and always current; "refresh" only attempts a
        // best-effort merge of the server backup (never blocks, never wipes).
        viewModelScope.launch {
            favoriteRepository.importFromServer()
        }
    }

    /** Local toggle (instant). Server backup push is fire-and-forget. */
    fun toggleFavorite(track: Track) {
        viewModelScope.launch {
            if (track.id in favoriteIds.value) {
                favoriteRepository.removeFavorite(track.id)
            } else {
                favoriteRepository.addFavorite(track)
            }
        }
    }

    fun removeFavorite(trackId: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(trackId)
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
            // Favorites are local — they are already current; only a
            // best-effort server-backup merge is attempted.
            favoriteRepository.importFromServer()
            val favorites = _favoritesState.value.tracks

            val playlistsResult = musicRepository.getPlaylists()
            reportServerResult(playlistsResult)

            var errorMessage: String? = null
            val playlists = playlistsResult.getOrNull()
            if (playlists == null) {
                errorMessage = playlistsResult.exceptionOrNull()?.message ?: "Could not load playlists"
            }

            _playlistsState.update {
                it.copy(
                    favorites = favorites,
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
                // Albums/playlists can take a while to resolve on the server
                // (a full YouTube album fetch). Retry after a real delay, not
                // 1.5 s — a premature retry just hits the same cold state.
                delay(8000)
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

    /**
     * Resolves the album for [track] (the player disc's "open the album"
     * tap) and reports it via [onResult].
     *
     * The server's precise two-step lookup is tried first; when it cannot
     * pin an album (or the server is unreachable), fall back to searching
     * the album catalog with the best query available — the track's real
     * album name when present, else title + artist — and take the top
     * match. Null means "no album could be found"; we never fabricate one.
     *
     * The result is CACHED per track id and PRE-WARMED as a track starts
     * (see [prewarmAlbum]) — a disc tap then answers instantly instead of
     * waiting a server round-trip. Misses are cached too, so a track that
     * has no album does not retry forever.
     *
     * ConcurrentHashMap REJECTS null values — caching a miss as a bare
     * null crashed the app (13 recorded release NPEs on 1.9.42, all
     * ConcurrentHashMap.putVal, right after a track started playing).
     * Misses are wrapped in Optional.empty() instead.
     */
    private val albumByTrackId =
        java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Album>>()
    private val albumInFlight = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    private val albumWaiters = java.util.concurrent.ConcurrentHashMap<String, MutableList<(Album?) -> Unit>>()

    private fun cachedAlbum(id: String): Album? = albumByTrackId[id]?.orElse(null)

    private fun cacheAlbum(id: String, album: Album?) {
        albumByTrackId[id] =
            if (album == null) java.util.Optional.empty() else java.util.Optional.of(album)
    }

    /** Warm the disc-tap resolution in the background as a track starts. */
    fun prewarmAlbum(track: Track) {
        startAlbumResolve(track) { /* result lands in the cache */ }
    }

    fun openAlbumForTrack(track: Track, onResult: (Album?) -> Unit) {
        if (track.id.isBlank()) {
            onResult(null)
            return
        }
        if (albumByTrackId.containsKey(track.id)) {
            onResult(cachedAlbum(track.id))
            return
        }
        val waiters = albumWaiters.getOrPut(track.id) { java.util.concurrent.CopyOnWriteArrayList() }
        // Completed while this waiter was being attached?
        if (albumByTrackId.containsKey(track.id)) {
            waiters.remove(onResult)
            onResult(cachedAlbum(track.id))
            return
        }
        if (waiters.size < 4) waiters.add(onResult)
        startAlbumResolve(track) { album ->
            // Answers every pending tap exactly once; no cache hit = false.
            albumWaiters.remove(track.id)?.forEach { w -> w(album) }
        }
    }

    private fun startAlbumResolve(track: Track, onDone: (Album?) -> Unit) {
        val id = track.id
        if (id.isBlank()) {
            onDone(null)
            return
        }
        if (albumByTrackId.containsKey(id)) {
            onDone(cachedAlbum(id))
            return
        }
        if (!albumInFlight.add(id)) return // already resolving; waiters catch the result
        viewModelScope.launch {
            var album: Album? = null
            musicRepository.resolveTrackAlbum(track).onSuccess { album = it }
            if (album == null) {
                val query = when {
                    track.album.isNotBlank() && track.album != "Single" -> track.album
                    track.title.isNotBlank() && track.artist.isNotBlank() -> "${track.title} ${track.artist}"
                    track.title.isNotBlank() -> track.title
                    else -> ""
                }
                if (query.isNotBlank()) {
                    musicRepository.searchAlbums(query).onSuccess { albums ->
                        // Mirror the server's rule: never open a same-named
                        // album by a different artist. Only an uploader that
                        // equals the artist (normalized) or contains every
                        // artist token qualifies; otherwise nothing opens.
                        fun norm(s: String) =
                            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                        val artistNorm = norm(track.artist)
                        val artistTokens = artistNorm.split(' ').filter { it.isNotEmpty() }
                        album = if (artistTokens.isEmpty()) {
                            null
                        } else {
                            albums.firstOrNull { a ->
                                val uploader = norm(a.uploader)
                                uploader == artistNorm || artistTokens.all { it in uploader }
                            }
                        }
                    }
                }
            }
            cacheAlbum(id, album)
            albumInFlight.remove(id)
            onDone(album)
        }
    }

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
            // Fully local: the artists list is derived from favorites, recents
            // and downloads — all Room-backed, so it works offline and never
            // needs a server call.
            val favorites = _favoritesState.value.tracks
            val recents = recentTracks.value
            val downloads = withContext(Dispatchers.IO) {
                container.downloadRepository.completedTracksFlow.first()
            }
            val combined = (favorites + recents + downloads).distinctBy { it.id }
            val groups = combined
                .filter { it.artist.isNotBlank() }
                .groupBy { it.artist.trim().lowercase() }
                .map { (_, songs) -> ArtistGroup(name = songs.first().artist.trim(), songs = songs.sortedBy { it.title.lowercase() }) }
                .sortedBy { it.name.lowercase() }
            _artistsState.value = ArtistsUiState(artists = groups)
        }
    }

    // ============================================================================
    // Recommendations State ("Made for you" — Tier A+B)
    // ============================================================================

    data class RecommendationsUiState(
        val tracks: List<Track> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )

    private val _recommendationsState = MutableStateFlow(RecommendationsUiState())
    val recommendationsState: StateFlow<RecommendationsUiState> = _recommendationsState.asStateFlow()

    /**
     * Builds the seed input from LOCAL state — favorites first (the taste
     * profile), recents as a fallback when nothing is favorited yet — then
     * asks the server for merged, taste-ranked recommendations. One round
     * trip; results are cached server-side for 30 minutes.
     */
    fun refreshRecommendations() {
        if (_recommendationsState.value.isLoading) return
        viewModelScope.launch {
            _recommendationsState.update { it.copy(isLoading = true, errorMessage = null) }
            val favs = _favoritesState.value.tracks
            val seeds = when {
                favs.isNotEmpty() -> favs.take(6).map { it.id }
                else -> recentTracks.value.take(6).map { it.id }
            }
            if (seeds.isEmpty()) {
                _recommendationsState.value = RecommendationsUiState(
                    errorMessage = "Favorite or play a few songs first"
                )
                return@launch
            }
            // Taste profile: the top artists among favorites become the boost
            // set the server scores candidates against.
            val artists = favs.groupBy { it.artist.trim().lowercase() }
                .entries
                .sortedByDescending { it.value.size }
                .take(5)
                .map { it.value.first().artist.trim() }
            val exclude = favs.map { it.id }.toList()
            val result = musicRepository.getRecommendations(seeds, artists, exclude)
            result.onSuccess { tracks ->
                _recommendationsState.value = RecommendationsUiState(tracks = tracks)
            }.onFailure { err ->
                _recommendationsState.value = RecommendationsUiState(
                    errorMessage = err.displayMessage("Could not load recommendations")
                )
            }
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
            var result = musicRepository.searchMusic(query)
            // A search computes its WHOLE page before answering, so a run past
            // one client timeout window does not mean the server failed — it
            // was still working. ONE silent retry keeps genuinely slow
            // searches working instead of reading "Search failed"; a real
            // outage surfaces after the second attempt.
            if (result.exceptionOrNull() is TimeoutError) {
                result = musicRepository.searchMusic(query)
            }
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
     * "Show more": fetches the next page of results and appends them. The
     * request uses BOTH a growing limit and the offset — a server that
     * honours offset returns the next window; an older server that ignores it
     * returns the first rows again, which the dedupe drops, and we still make
     * progress as long as the server has more rows to give. The button stays
     * visible until a page comes back duplicated or short (exhausted).
     */
    fun loadMoreResults() {
        val state = _searchState.value
        if (state.isSearching || state.isLoadingMore || !state.canLoadMore) return
        if (state.query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            val pageSize = com.wearsic.app.network.WearsicHttpApiClient.SEARCH_PAGE_SIZE
            val maxLimit = com.wearsic.app.network.WearsicHttpApiClient.SEARCH_MAX_LIMIT
            val reqLimit = (state.results.size + pageSize).coerceAtMost(maxLimit)
            val result = musicRepository.searchMusic(state.query, reqLimit, state.results.size)
            result.onSuccess { tracks ->
                val known = state.results.map { it.id }.toSet()
                val fresh = tracks.filter { t -> t.id !in known }
                _searchState.update {
                    it.copy(
                        results = it.results + fresh,
                        isLoadingMore = false,
                        errorMessage = null,
                        // Exhausted when the server returned nothing new or a
                        // page smaller than requested.
                        canLoadMore = fresh.isNotEmpty() && tracks.size >= reqLimit
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
        if (text.isBlank()) {
            // Clearing the field (X button or backspace to empty) resets the
            // whole search surface: no suggestions, no results, no error.
            _searchState.update {
                it.copy(
                    query = "",
                    suggestions = emptyList(),
                    results = emptyList(),
                    hasSearched = false,
                    isSearching = false,
                    isLoadingMore = false,
                    canLoadMore = false,
                    errorMessage = null
                )
            }
            suggestionsJob?.cancel()
            searchJob?.cancel()
            return
        }
        _searchState.update { it.copy(query = text) }
        suggestionsJob?.cancel()
        if (!searchSuggestionsEnabled.value) {
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
            val repo = musicRepository
            val existingIds = playbackViewModel.uiState.value.playlist.map { it.id }.toSet()
            val targetCount = 10

            // Seeds: the current track first, then the two most recent tracks
            // (different tracks) — a single seed's related set is often thin,
            // so blending a couple of seeds gives a fuller, more relevant set.
            val recents = runCatching {
                recentRepository.recentTracksFlow.first()
            }.getOrDefault(emptyList())
            val seeds = buildList {
                add(current)
                addAll(recents.filter { it.id != current.id }.take(2))
            }.distinctBy { it.id }.take(3)

            val gathered = mutableListOf<Track>()
            for (seed in seeds) {
                if (gathered.size >= targetCount) break
                val related = repo.getRelated(seed.id).getOrDefault(emptyList())
                gathered += related.filter { candidate ->
                    candidate.id !in existingIds &&
                        candidate.id !in gathered.map { it.id } &&
                        (candidate.durationMs == 0L || candidate.durationMs in 30_000..900_000)
                }
            }

            // Prioritise tracks by the same artist as what is playing — that
            // is what makes a radio feel coherent instead of scattered.
            val seedArtist = current.artist.trim().lowercase()
            val sameArtist = gathered.filter { it.artist.trim().lowercase() == seedArtist }
            val rest = gathered.filter { it.artist.trim().lowercase() != seedArtist }
            val fresh = (sameArtist + rest).distinctBy { it.id }.take(targetCount)

            val final = if (fresh.size < 3) {
                // Fallback: pad from favorites so radio never returns a
                // nearly-empty set on sparse related data.
                val favs = _favoritesState.value.tracks.filter { f ->
                    f.id !in existingIds && f.id !in fresh.map { it.id }
                }
                (fresh + favs).take(targetCount)
            } else {
                fresh
            }

            if (final.isEmpty()) {
                _radioState.value = RadioState.Idle
                return@launch
            }
            playbackViewModel.addToQueue(final)
            _radioState.value = RadioState.Idle
        }
    }
}

