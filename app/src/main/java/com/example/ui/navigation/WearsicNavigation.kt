package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.MainActivity
import com.example.data.db.DownloadState
import com.example.model.Track
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.AlbumsScreen
import com.example.ui.screens.StorageStatsContent
import com.example.ui.screens.ArtistsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlaceholderScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VolumeScreen
import com.example.model.ArtistGroup
import com.example.ui.viewmodel.WearsicPlayerViewModel

@Composable
fun WearsicApp(
    modifier: Modifier = Modifier,
    playerViewModel: WearsicPlayerViewModel = viewModel(),
    timeText: @Composable () -> Unit = {
        // Slightly smaller system clock than the default Wear size.
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale = 0.85f)
        ) {
            TimeText()
        }
    }
) {
    val navController = rememberSwipeDismissableNavController()
    // Extract the specialized ViewModels for convenience
    val playbackVM = playerViewModel.playbackViewModel
    val libraryVM = playerViewModel.libraryViewModel
    val downloadsVM = playerViewModel.downloadsViewModel
    val settingsVM = playerViewModel.settingsViewModel

    // Held as a State (not read here) so ticking position updates never
    // recompose this whole navigation host — see the derived projections below.
    val playbackStateHolder = playbackVM.uiState.collectAsStateWithLifecycle()
    val searchState by libraryVM.searchState.collectAsStateWithLifecycle()
    val serverUrl by settingsVM.serverUrl.collectAsStateWithLifecycle()
    val connectionTestState by settingsVM.connectionTestState.collectAsStateWithLifecycle()
    val downloadsHolder = downloadsVM.allDownloads.collectAsStateWithLifecycle()
    val autoCacheEnabled by settingsVM.autoCacheEnabled.collectAsStateWithLifecycle()
    val offlineLimit by settingsVM.offlineLimit.collectAsStateWithLifecycle()
    val radioState by libraryVM.radioState.collectAsStateWithLifecycle()
    val apiKey by settingsVM.apiKey.collectAsStateWithLifecycle()
    val storageStats by downloadsVM.storageStats.collectAsStateWithLifecycle()
    val sleepRemainingMs by playerViewModel.sleepRemainingMs.collectAsStateWithLifecycle()
    val albumsState by libraryVM.albumsState.collectAsStateWithLifecycle()
    val artistsState by libraryVM.artistsState.collectAsStateWithLifecycle()
    val recentTracks by libraryVM.recentTracks.collectAsStateWithLifecycle()
    val startupHealth by settingsVM.startupHealth.collectAsStateWithLifecycle()
    val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
    val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
    val playlistDetailState by libraryVM.playlistDetailState.collectAsStateWithLifecycle()

    // Derived projections: recompute whenever the flow emits, but only
    // NOTIFY their readers when the projected value actually changes. The
    // position tracker pushes a tick every 2s for the whole session; without
    // this, each tick recomposed the entire navigation graph (all 12 screens
    // are reachable from here). Zeroing the ticking fields collapses ~1800
    // no-op emissions an hour into zero recompositions.
    val stablePlaybackState by remember {
        derivedStateOf { playbackStateHolder.value.copy(currentPositionMs = 0L) }
    }

    val currentTrackDownload by remember {
        derivedStateOf {
            val trackId = playbackStateHolder.value.currentTrack.id
            downloadsHolder.value.find { it.trackId == trackId }
        }
    }
    val isCurrentTrackDownloaded = currentTrackDownload?.isCompleted() == true
    val isCurrentTrackDownloading = currentTrackDownload?.downloadState == DownloadState.DOWNLOADING.name || currentTrackDownload?.downloadState == DownloadState.QUEUED.name
    val currentTrackDownloadProgress = currentTrackDownload?.progress ?: 0

    // Library/Queue render only metadata + play state, never progress, so
    // they take the stable projection.
    val libraryPlaybackState = stablePlaybackState
    val queuePlaybackState = stablePlaybackState

    // The first committed frame marks the process as having survived cold
    // start (clears the previous-run crash detector) and records the tile
    // actions requested from the system Tile (opens app briefly).
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        com.example.StartupDiagnostics.markUiReady(appContext)
        kotlinx.coroutines.delay(1500)
        MainActivity.pendingTileAction?.let { action ->
            MainActivity.pendingTileAction = null
            playbackVM.handleTileAction(action)
        }
    }

    // Opened from the media notification → go straight to the player rather
    // than the library home screen. Fires both on cold start (the request is
    // already set before composition) and on onNewIntent while running.
    val openPlayerRequested by MainActivity.openPlayerRequest
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            MainActivity.openPlayerRequest.value = false
            navController.navigate(Screen.Player.route) { launchSingleTop = true }
        }
    }

    AppScaffold(
        timeText = timeText,
        modifier = modifier
    ) {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Screen.Library.route
        ) {
            // 1. Library Screen (Start destination)
            composable(Screen.Library.route) {
                LibraryScreen(
                    playbackState = libraryPlaybackState,
                    recentTracks = recentTracks,
                    onPlayRecentTrack = { tracks, index ->
                        playerViewModel.playTracksFromList(tracks, index)
                        navController.navigate(Screen.Player.route)
                    },
                    onNavigateToSearch = {
                        navController.navigate(Screen.Search.route)
                    },
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route)
                    },
                    onNavigateToPlaylists = {
                        navController.navigate(Screen.Playlists.route)
                    },
                    onNavigateToAlbums = {
                        navController.navigate(Screen.Albums.route)
                    },
                    onNavigateToArtists = {
                        navController.navigate(Screen.Artists.route)
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToPlayer = {
                        navController.navigate(Screen.Player.route)
                    }
                )
            }

            // 2. Search Screen (API-connected music discovery)
            composable(Screen.Search.route) {
                SearchScreen(
                    searchState = searchState,
                    onQuerySelected = { query ->
                        libraryVM.search(query)
                    },
                    onSearchTextChanged = { text ->
                        libraryVM.onSearchTextChanged(text)
                    },
                    onTrackSelected = { track ->
                        playerViewModel.playTrack(track)
                        navController.navigate(Screen.Player.route)
                    },
                    onDownloadTrack = { track ->
                        downloadsVM.startDownload(track)
                    },
                    onAddToQueue = { track ->
                        playbackVM.addToQueue(track)
                    },
                    playlists = playlistsState.playlists,
                    onCreatePlaylistAndAdd = { name, track ->
                        libraryVM.createPlaylistAndAdd(name, track)
                    },
                    onAddToPlaylist = { playlistId, track ->
                        libraryVM.addToPlaylist(playlistId, track)
                    }
                )
            }

            // 3. Player Screen (Connected to real Media3 Playback + Offline awareness)
            composable(Screen.Player.route) {
                PlayerScreen(
                    // Read here (destination scope) so position ticks recompose
                    // only the player, not the host above it.
                    playbackState = playbackStateHolder.value,
                    onTogglePlayPause = {
                        playbackVM.togglePlayPause()
                    },
                    onSkipNext = {
                        playbackVM.skipToNext()
                    },
                    onSkipPrevious = {
                        playbackVM.skipToPrevious()
                    },
                    onSeekForward = {
                        playbackVM.seekForward()
                    },
                    onSeekBack = {
                        playbackVM.seekBack()
                    },
                    onToggleFavorite = {
                        playbackVM.toggleFavorite()
                    },
                    onNavigateToVolume = {
                        navController.navigate(Screen.Volume.route)
                    },
                    onNavigateToQueue = {
                        navController.navigate(Screen.Queue.route)
                    },
                    onDownloadTrack = { track ->
                        downloadsVM.startDownload(track)
                    },
                    isDownloaded = isCurrentTrackDownloaded,
                    isDownloading = isCurrentTrackDownloading,
                    downloadProgress = currentTrackDownloadProgress
                )
            }

            // 4. Volume & Output Screen
            composable(Screen.Volume.route) {
                VolumeScreen(
                    currentOutputDevice = stablePlaybackState.outputDeviceName,
                    sleepRemainingMs = sleepRemainingMs,
                    onSleepTimerSet = { minutes ->
                        playerViewModel.setSleepTimer(minutes)
                    },
                    onOutputDeviceChanged = {
                        playbackVM.refreshOutputDevice()
                    }
                )
            }

            // 5. Settings Screen (Persistent Server URL, Cache & Downloads Management)
            composable(Screen.Settings.route) {
                SettingsScreen(
                    serverUrl = serverUrl,
                    connectionTestState = connectionTestState,
                    onServerUrlChanged = { newUrl ->
                        settingsVM.saveServerUrl(newUrl)
                    },
                    onTestConnection = { urlToTest ->
                        settingsVM.testConnection(urlToTest)
                    },
                    apiKey = apiKey,
                    onApiKeyChanged = { key ->
                        settingsVM.saveApiKey(key)
                    },
                    onOpenStorage = {
                        downloadsVM.refreshStorageStats()
                        navController.navigate(Screen.Storage.route)
                    },
                    autoCacheEnabled = autoCacheEnabled,
                    onAutoCacheToggled = { enabled ->
                        settingsVM.setAutoCacheEnabled(enabled)
                    },
                    offlineLimitSongs = offlineLimit,
                    onOfflineLimitChanged = { limit ->
                        settingsVM.saveOfflineLimit(limit)
                    },
                    onClearDownloads = {
                        downloadsVM.clearAllDownloads()
                    },
                    startupHealth = startupHealth
                )
            }

            // 6. Queue Screen (Up-next list with jump-to & remove)
            composable(Screen.Queue.route) {
                QueueScreen(
                    playbackState = queuePlaybackState,
                    onPlayItem = { index ->
                        playbackVM.seekToQueueItem(index)
                    },
                    onRemoveItem = { index ->
                        playbackVM.removeFromQueue(index)
                    },
                    onClearQueue = {
                        playbackVM.clearQueue()
                    },
                    shuffleEnabled = stablePlaybackState.shuffleEnabled,
                    repeatMode = stablePlaybackState.repeatMode,
                    onToggleShuffle = { playbackVM.toggleShuffle() },
                    onCycleRepeat = { playbackVM.cycleRepeatMode() },
                    radioState = radioState,
                    onStartRadio = { playerViewModel.startRadio() }
                )
            }

            // 7. Downloads Screen (Full offline playback and download queue management)
            composable(Screen.Downloads.route) {
                DownloadsScreen(
                    downloads = downloadsHolder.value,
                    onPlayTrack = { track ->
                        playerViewModel.playTrack(track)
                        navController.navigate(Screen.Player.route)
                    },
                    onDeleteDownload = { trackId ->
                        downloadsVM.deleteDownload(trackId)
                    },
                    onCancelDownload = { trackId ->
                        downloadsVM.cancelDownload(trackId)
                    },
                    onRetryDownload = { track ->
                        downloadsVM.deleteDownload(track.id)
                        downloadsVM.startDownload(track)
                    },
                    onClearAllDownloads = {
                        downloadsVM.clearAllDownloads()
                    }
                )
            }

            // Storage stats
            composable(Screen.Storage.route) {
                StorageStatsContent(
                    autoCount = storageStats.autoCount,
                    autoMb = storageStats.autoMb,
                    manualCount = storageStats.manualCount,
                    manualMb = storageStats.manualMb,
                    onClearAutoCached = { downloadsVM.clearAutoCachedDownloads() }
                )
            }

            // Playlists & Favorites hub
            composable(Screen.Playlists.route) {
                PlaylistsScreen(
                    playlistsState = playlistsState,
                    onRefresh = {
                        libraryVM.refreshLibrary()
                    },
                    onNavigateToFavorites = {
                        navController.navigate(Screen.Favorites.route)
                    },
                    onOpenPlaylist = { playlist ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(playlist.id, playlist.name))
                    },
                    onCreatePlaylist = { name ->
                        libraryVM.createPlaylistAndAdd(name, null)
                    },
                    onRemovePlaylist = { id ->
                        libraryVM.removePlaylist(id)
                    }
                )
            }

            // Favorites track list
            composable(Screen.Favorites.route) {
                FavoritesScreen(
                    favoritesState = favoritesState,
                    onRefresh = {
                        libraryVM.refreshFavorites()
                    },
                    onPlayTrack = { tracks, index ->
                        playerViewModel.playTracksFromList(tracks, index)
                        navController.navigate(Screen.Player.route)
                    },
                    onDownloadTrack = { track ->
                        downloadsVM.startDownload(track)
                    },
                    onRemoveFavorite = { trackId ->
                        libraryVM.removeFavorite(trackId)
                    },
                    playlists = playlistsState.playlists,
                    onCreatePlaylistAndAdd = { name, track ->
                        libraryVM.createPlaylistAndAdd(name, track)
                    },
                    onAddToPlaylist = { playlistId, track ->
                        libraryVM.addToPlaylist(playlistId, track)
                    }
                )
            }

            // Playlist detail track list
            composable(Screen.PlaylistDetail.route) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("id") ?: ""
                val playlistName = backStackEntry.arguments?.getString("name") ?: "Playlist"
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    playlistName = playlistName,
                    detailState = playlistDetailState,
                    onLoadTracks = { id ->
                        libraryVM.loadPlaylistTracks(id)
                    },
                    onPlayTrack = { tracks, index ->
                        playerViewModel.playTracksFromList(tracks, index)
                        navController.navigate(Screen.Player.route)
                    },
                    onDownloadTrack = { track ->
                        downloadsVM.startDownload(track)
                    },
                    onRemoveTrack = { id, trackId ->
                        libraryVM.removeTrackFromPlaylist(id, trackId)
                    },
                    playlists = playlistsState.playlists,
                    onCreatePlaylistAndAdd = { name, track ->
                        libraryVM.createPlaylistAndAdd(name, track)
                    },
                    onAddToPlaylist = { playlistId, track ->
                        libraryVM.addToPlaylist(playlistId, track)
                    }
                )
            }

            // Albums (search + open as queue)
            composable(Screen.Albums.route) {
                AlbumsScreen(
                    albumsState = albumsState,
                    onQueryChanged = { query ->
                        libraryVM.searchAlbums(query)
                    },
                    onOpenAlbum = { album ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(album.id, album.name))
                    }
                )
            }

            // Artists (local grouping of saved songs)
            composable(Screen.Artists.route) {
                ArtistsScreen(
                    artistsState = artistsState,
                    onRefresh = { libraryVM.refreshArtists() },
                    onPlayArtistSongs = { group, index ->
                        playerViewModel.playTracksFromList(group.songs, index)
                        navController.navigate(Screen.Player.route)
                    }
                )
            }
        }
    }
}
