package com.wearsic.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.WearsicApp
import com.wearsic.app.data.WearsicDownloadRepository
import com.wearsic.app.data.WearsicMusicRepository
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.data.WearsicRecentRepository
import com.wearsic.app.data.db.WearsicDownloadEntity
import com.wearsic.app.media.WearsicPlaybackController
import com.wearsic.app.media.download.WearsicDownloadManager
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.network.model.ConnectionTestState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Main ViewModel that coordinates all app functionality.
 * Delegates to specialized ViewModels for better separation of concerns.
 * 
 * This is the refactored version that splits responsibilities into:
 * - PlaybackViewModel: All playback operations
 * - LibraryViewModel: Library browsing (favorites, playlists, albums, artists)
 * - DownloadsViewModel: Download management and storage
 * - SettingsViewModel: Settings and configuration
 */
class WearsicPlayerViewModel(
    application: Application
) : AndroidViewModel(application) {

    // Specialized ViewModels
    val playbackViewModel: PlaybackViewModel
    val libraryViewModel: LibraryViewModel
    val downloadsViewModel: DownloadsViewModel
    val settingsViewModel: SettingsViewModel

    // Legacy properties for backward compatibility
    val uiState: StateFlow<PlaybackUiState> get() = playbackViewModel.uiState
    val downloads: StateFlow<List<WearsicDownloadEntity>> get() = downloadsViewModel.allDownloads
    val completedTracks: StateFlow<List<Track>> get() = downloadsViewModel.completedTracks
    val recentTracks: StateFlow<List<Track>> get() = libraryViewModel.recentTracks
    val serverUrl: StateFlow<String> get() = settingsViewModel.serverUrl
    val apiKey: StateFlow<String> get() = settingsViewModel.apiKey
    val autoCacheEnabled: StateFlow<Boolean> get() = settingsViewModel.autoCacheEnabled
    val offlineLimit: StateFlow<Int> get() = settingsViewModel.offlineLimit
    val connectionTestState: StateFlow<ConnectionTestState> get() = settingsViewModel.connectionTestState
    val searchState: StateFlow<LibraryViewModel.SearchUiState> get() = libraryViewModel.searchState
    val favoritesState: StateFlow<LibraryViewModel.FavoritesUiState> get() = libraryViewModel.favoritesState
    val playlistsState: StateFlow<LibraryViewModel.PlaylistsUiState> get() = libraryViewModel.playlistsState
    val playlistDetailState: StateFlow<LibraryViewModel.PlaylistDetailUiState> get() = libraryViewModel.playlistDetailState
    val albumsState: StateFlow<LibraryViewModel.AlbumsUiState> get() = libraryViewModel.albumsState
    val artistsState: StateFlow<LibraryViewModel.ArtistsUiState> get() = libraryViewModel.artistsState
    val storageStats: StateFlow<DownloadsViewModel.StorageStats> get() = downloadsViewModel.storageStats
    val radioState: StateFlow<LibraryViewModel.RadioState> get() = libraryViewModel.radioState
    val sleepRemainingMs: StateFlow<Long> get() = _sleepRemainingMs

    /**
     * Length the CURRENT sleep timer was set for (0 when off). The Sound
     * screen highlights the exact chip the user picked — guessing from the
     * remaining time matched the wrong chip over time.
     */
    val sleepSetMinutes: StateFlow<Int> get() = _sleepSetMinutes
    // Delegates to the SettingsViewModel's flow: keeping a second copy meant
    // reading the same startup log twice to produce one string.
    val startupHealth: StateFlow<String> get() = settingsViewModel.startupHealth

    // Sleep timer state (kept here as it spans playback and UI)
    private val _sleepRemainingMs = MutableStateFlow(0L)
    private val _sleepSetMinutes = MutableStateFlow(0)
    private var sleepJob: Job? = null

    // Server truth for hearts: refreshed from GET /api/favorites, applied to
    // every track entering a queue and to the current track on change.
    @Volatile
    private var favoriteIds: Set<String> = emptySet()

    init {
        val container = (getApplication<WearsicApp>()).container
        
        // Initialize the specialized ViewModels
        playbackViewModel = PlaybackViewModel(application)
        libraryViewModel = LibraryViewModel(application)
        downloadsViewModel = DownloadsViewModel(application)
        settingsViewModel = SettingsViewModel(application)

        // Sync auto-cache limit to download manager
        viewModelScope.launch {
            settingsViewModel.offlineLimit.collect { limit ->
                downloadsViewModel.setMaxAutoCachedTracks(limit.coerceIn(5, 200))
            }
        }

        // Auto-eviction setup
        viewModelScope.launch {
            playbackViewModel.uiState.collect { state ->
                val protectedLocalId = state.currentTrack.id.takeIf {
                    it.isNotBlank() && state.currentTrack.mediaUri.startsWith("/")
                }
                if (protectedLocalId != null) {
                    downloadsViewModel.flushPendingDeletions()
                }
            }
        }

        // Auto-cache on listen
        viewModelScope.launch {
            val autoCachedTrackIds = mutableSetOf<String>()
            var lastTrack: Track? = null
            var trackBecameCurrentAtMs = 0L
            var lastLocalProtectedId: String? = null

            playbackViewModel.uiState.collect { state ->
                try {
                    val track = state.currentTrack
                    val protectedLocalId = track.id.takeIf {
                        it.isNotBlank() && track.mediaUri.startsWith("/")
                    }
                    if (protectedLocalId != lastLocalProtectedId) {
                        lastLocalProtectedId = protectedLocalId
                        downloadsViewModel.flushPendingDeletions()
                    }
                    if (track.id.isNotBlank()) {
                        if (track.id != lastTrack?.id) {
                            lastTrack = track
                            recordPlayed(track)
                            trackBecameCurrentAtMs = SystemClock.elapsedRealtime()
                        }

                        if (SystemClock.elapsedRealtime() - trackBecameCurrentAtMs >= AUTO_CACHE_LISTEN_MS) {
                            maybeAutoCacheTrack(track, autoCachedTrackIds)
                        }

                        val nextTrack = state.playlist.getOrNull(state.currentTrackIndex + 1)
                        if (nextTrack != null) {
                            warmUpStream(nextTrack)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("WearsicVM", "playback-state handling failed", e)
                }
            }
        }

        // Hot-swap: the moment any download/AutoCache completes, rewrite queued
        // http items to their local file. Prev/next then start instantly from
        // disk — no server re-fetch, no radio wakeup (battery).
        viewModelScope.launch {
            downloadsViewModel.completedTracks.collect { completed ->
                for (track in completed) {
                    if (track.id.isNotBlank() && track.mediaUri.startsWith("/")) {
                        playbackViewModel.swapQueuedItemToLocalFile(track.id, track.mediaUri)
                    }
                }
            }
        }

        // Favorite hydration: keep hearts truthful to the server. The player
        // heart previously only reflected local taps; now every favorites
        // refresh re-stamps the set and the current track follows it.
        viewModelScope.launch {
            libraryViewModel.refreshFavorites()
            libraryViewModel.favoritesState.collect { fav ->
                favoriteIds = fav.tracks.map { it.id }.toSet()
                val current = playbackViewModel.uiState.value.currentTrack
                if (current.id.isNotBlank()) {
                    playbackViewModel.setCurrentTrackFavorite(current.id in favoriteIds)
                }
            }
        }
    }

    /** Stamp server favorite truth onto a track entering a queue. */
    private fun applyFavoriteFlags(track: Track): Track {
        if (track.id.isBlank()) return track
        val fav = track.id in favoriteIds
        return if (track.isFavorite == fav) track else track.copy(isFavorite = fav)
    }

    // ============================================================================
    // Sleep Timer
    // ============================================================================

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        playbackViewModel.setVolumeScale(1f)
        if (minutes <= 0) {
            _sleepRemainingMs.value = 0L
            _sleepSetMinutes.value = 0
            return
        }
        val durationMs = minutes * 60_000L
        val endsAt = System.currentTimeMillis() + durationMs
        _sleepSetMinutes.value = minutes
        sleepJob = viewModelScope.launch {
            var lastTick = System.nanoTime()
            while (true) {
                val now = System.currentTimeMillis()
                val remaining = endsAt - now
                _sleepRemainingMs.value = remaining.coerceAtLeast(0L)
                if (remaining <= 0L) break

                if (remaining <= 10_000L) {
                    val nowNano = System.nanoTime()
                    val dtSec = ((nowNano - lastTick) / 1_000_000_000f).coerceIn(0.05f, 0.5f)
                    lastTick = nowNano
                    val step = dtSec / 10f
                    val currentVol = 1f - ((10_000f - remaining.toFloat()) / 10_000f)
                    playbackViewModel.setVolumeScale((currentVol - step).coerceIn(0f, 1f))
                    delay(250)
                } else {
                    delay(1000)
                }
            }
            playbackViewModel.pause()
            playbackViewModel.setVolumeScale(1f)
            _sleepRemainingMs.value = 0L
            _sleepSetMinutes.value = 0
        }
    }

    // ============================================================================
    // Settings Delegation
    // ============================================================================

    fun setApiKey(key: String) {
        settingsViewModel.saveApiKey(key)
    }

    fun saveOfflineLimit(limitSongs: Int) {
        settingsViewModel.saveOfflineLimit(limitSongs)
    }

    fun setAutoCacheEnabled(enabled: Boolean) {
        settingsViewModel.setAutoCacheEnabled(enabled)
    }

    fun testConnection(targetUrl: String) {
        settingsViewModel.testConnection(targetUrl)
    }

    fun saveServerUrl(url: String) {
        settingsViewModel.saveServerUrl(url)
    }

    fun toggleHiddenPlaylist(id: String) {
        settingsViewModel.toggleHiddenPlaylist(id)
    }

    // ============================================================================
    // Playback Delegation
    // ============================================================================

    fun playTrack(track: Track) {
        // Same-track tap: never tear down the pipeline — just (re)play.
        // This alone removes the full stop/clear/prepare/play stall that made
        // every re-tap feel like a fresh cold start.
        val current = playbackViewModel.uiState.value
        if (track.id.isNotBlank() && track.id == current.currentTrack.id) {
            if (!current.isPlaying) playbackViewModel.togglePlayPause()
            return
        }
        viewModelScope.launch {
            val localTrack = withContext(Dispatchers.IO) {
                (getApplication<WearsicApp>()).container.downloadRepository.getDownloadedTrack(track.id)
            }
            val trackToPlay = applyFavoriteFlags(localTrack ?: track)
            warmUpStream(trackToPlay)

            val completed = completedTracks.value
            val completedIndex = completed.indexOfFirst { it.id == trackToPlay.id }
            if (completedIndex >= 0) {
                playbackViewModel.playTracks(completed, completedIndex)
                return@launch
            }

            val results = searchState.value.results
            val resultIndex = results.indexOfFirst { it.id == trackToPlay.id }
            if (resultIndex >= 0) {
                playbackViewModel.playTracks(results, resultIndex)
                return@launch
            }

            playbackViewModel.playTrack(trackToPlay)
        }
    }

    fun addToQueue(track: Track) {
        playbackViewModel.addToQueue(track)
    }

    /**
     * Queue ONE track with local-file resolution: a downloaded track is queued
     * as its file (instant later playback), never as a re-streamable http URI.
     */
    fun queueTrack(track: Track) {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) {
                (getApplication<WearsicApp>()).container.downloadRepository.getDownloadedTrack(track.id)
            }
            playbackViewModel.addToQueue(applyFavoriteFlags(local ?: track))
        }
    }

    fun playTracksFromList(tracks: List<Track>, startIndex: Int = 0) {
        viewModelScope.launch {
            val container = (getApplication<WearsicApp>()).container
            // ONE batched Room query (not N serial lookups on the tap path),
            // then stamp server favorite flags onto the whole queue at once.
            val localById = withContext(Dispatchers.IO) {
                container.downloadRepository.getCompletedTracksById(tracks.map { it.id })
            }
            val resolved = tracks.map { t -> applyFavoriteFlags(localById[t.id] ?: t) }
            if (resolved.isNotEmpty()) {
                warmUpStream(resolved[startIndex.coerceIn(0, resolved.lastIndex)])
            }
            playbackViewModel.playTracks(resolved, startIndex.coerceIn(0, resolved.lastIndex))
        }
    }

    fun removeFromQueue(index: Int) {
        playbackViewModel.removeFromQueue(index)
    }

    fun seekToQueueItem(index: Int) {
        playbackViewModel.seekToQueueItem(index)
    }

    fun clearQueue() {
        playbackViewModel.clearQueue()
    }

    fun togglePlayPause() {
        playbackViewModel.togglePlayPause()
    }

    fun skipToNext() {
        playbackViewModel.skipToNext()
    }

    fun skipToPrevious() {
        playbackViewModel.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackViewModel.seekTo(positionMs)
    }

    fun seekForward() {
        playbackViewModel.seekForward()
    }

    fun seekBack() {
        playbackViewModel.seekBack()
    }

    fun toggleShuffle() {
        playbackViewModel.toggleShuffle()
    }

    fun cycleRepeatMode() {
        playbackViewModel.cycleRepeatMode()
    }

    fun setVolumeScale(volume: Float) {
        playbackViewModel.setVolumeScale(volume)
    }

    fun refreshOutputDevice() {
        playbackViewModel.refreshOutputDevice()
    }

    fun handleTileAction(action: String) {
        playbackViewModel.handleTileAction(action)
    }

    // ============================================================================
    // Library Delegation
    // ============================================================================

    fun search(query: String) {
        libraryViewModel.search(query)
    }

    fun onSearchTextChanged(text: String) {
        libraryViewModel.onSearchTextChanged(text)
    }

    fun refreshFavorites() {
        libraryViewModel.refreshFavorites()
    }

    fun removeFavorite(trackId: String) {
        libraryViewModel.removeFavorite(trackId)
    }

    fun refreshLibrary() {
        libraryViewModel.refreshLibrary()
    }

    fun loadPlaylistTracks(playlistId: String) {
        libraryViewModel.loadPlaylistTracks(playlistId)
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        libraryViewModel.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun removePlaylist(id: String) {
        libraryViewModel.removePlaylist(id)
    }

    fun createPlaylist(name: String) {
        libraryViewModel.createPlaylist(name)
    }

    fun createPlaylistAndAdd(name: String, track: Track?) {
        libraryViewModel.createPlaylistAndAdd(name, track)
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        libraryViewModel.addToPlaylist(playlistId, track)
    }

    fun searchAlbums(query: String) {
        libraryViewModel.searchAlbums(query)
    }

    fun refreshArtists() {
        libraryViewModel.refreshArtists()
    }

    fun startRadio() {
        libraryViewModel.startRadio(playbackViewModel)
    }

    // ============================================================================
    // Downloads Delegation
    // ============================================================================

    fun startDownload(track: Track) {
        downloadsViewModel.startDownload(track)
    }

    fun cancelDownload(trackId: String) {
        downloadsViewModel.cancelDownload(trackId)
    }

    fun deleteDownload(trackId: String) {
        downloadsViewModel.deleteDownload(trackId)
    }

    fun clearAllDownloads() {
        downloadsViewModel.clearAllDownloads()
    }

    fun clearAutoCachedDownloads() {
        downloadsViewModel.clearAutoCachedDownloads()
    }

    fun refreshStorageStats() {
        downloadsViewModel.refreshStorageStats()
    }

    fun isDownloading(trackId: String): Boolean {
        return downloadsViewModel.isDownloading(trackId)
    }

    // ============================================================================
    // Favorite Toggle
    // ============================================================================

    fun toggleFavorite() {
        val track = playbackViewModel.uiState.value.currentTrack
        if (track.id.isBlank()) return

        val targetFavorite = !track.isFavorite
        playbackViewModel.setCurrentTrackFavorite(targetFavorite)

        viewModelScope.launch {
            val result = if (targetFavorite) {
                (getApplication<WearsicApp>()).container.musicRepository.addFavorite(track)
            } else {
                (getApplication<WearsicApp>()).container.musicRepository.removeFavorite(track.id)
            }

            result.onSuccess {
                libraryViewModel.refreshFavorites()
            }.onFailure {
                playbackViewModel.setCurrentTrackFavorite(!targetFavorite)
            }
        }
    }

    /**
     * Favorite toggle for ANY track (action sheets, lists) — decided by server
     * truth ([favoriteIds]), not by the possibly-stale row flag. Refreshes the
     * set on success so hearts everywhere converge.
     */
    fun toggleFavoriteFor(track: Track) {
        if (track.id.isBlank()) return
        viewModelScope.launch {
            val repo = (getApplication<WearsicApp>()).container.musicRepository
            val result = if (track.id in favoriteIds) {
                repo.removeFavorite(track.id)
            } else {
                repo.addFavorite(track)
            }
            result.onSuccess { libraryViewModel.refreshFavorites() }
        }
    }

    // ============================================================================
    // Internal Helpers
    // ============================================================================

    private fun recordPlayed(track: Track) {
        viewModelScope.launch {
            (getApplication<WearsicApp>()).container.recentRepository.recordPlayed(track)
        }
    }

    private fun maybeAutoCacheTrack(track: Track, evaluatedTrackIds: MutableSet<String>) {
        // The playback state flow re-emits every ~2 s while playing, so this is
        // reached on every tick once a track has been listened to long enough.
        // Without this check the whole guard chain below re-ran — and
        // startDownload was requested again — for a track already handled.
        if (track.id in evaluatedTrackIds) return
        if (!settingsViewModel.autoCacheEnabled.value) return
        if (!track.mediaUri.startsWith("http")) return
        if (!isNetworkAvailable()) return

        if (downloadsViewModel.isDownloading(track.id)) return
        if (completedTracks.value.any { it.id == track.id }) return

        evaluatedTrackIds.add(track.id)
        downloadsViewModel.startDownload(track, autoCached = true)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private val warmUpClient = com.wearsic.app.network.WearsicHttp.client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var lastWarmUpTrackId: String? = null

    private fun warmUpStream(nextTrack: Track) {
        if (!nextTrack.mediaUri.startsWith("http")) return
        // The playback state flow re-emits every ~2s while playing; without
        // this guard every emission re-fired a Range request to /api/stream —
        // 30 req/min of pure noise that burned the server's stream rate limit
        // (the real request for the NEXT track then got throttled) and made
        // the watch's radio work for nothing. Warm each target at most once.
        if (nextTrack.id == lastWarmUpTrackId) return
        if (!isNetworkAvailable()) {
            lastWarmUpTrackId = nextTrack.id
            return
        }
        lastWarmUpTrackId = nextTrack.id
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(nextTrack.mediaUri)
                    .header("Range", "bytes=0-0")
                    .build()
                warmUpClient.newCall(request).execute().use { response ->
                    response.body?.close()
                }
            } catch (_: Exception) {
                // Warm-up is best-effort
            }
        }
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================

    override fun onCleared() {
        sleepJob?.cancel()
        // These child ViewModels are constructed directly rather than through a
        // ViewModelStore, so the framework never calls their onCleared(). Without
        // this the MediaController, its coroutine scope and any in-flight
        // download jobs outlive the screen.
        playbackViewModel.release()
        downloadsViewModel.release()
        super.onCleared()
    }

    companion object {
        private const val AUTO_CACHE_LISTEN_MS = 45_000L
    }
}

