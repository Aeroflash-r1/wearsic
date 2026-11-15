package com.wearsic.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wearsic.app.MainActivity
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.ui.screens.AlbumsScreen
import com.wearsic.app.ui.screens.ArtistsScreen
import com.wearsic.app.ui.screens.DownloadsScreen
import com.wearsic.app.ui.screens.FavoritesScreen
import com.wearsic.app.ui.screens.LibraryScreen
import com.wearsic.app.ui.screens.MixScreen
import com.wearsic.app.ui.screens.PlayerScreen
import com.wearsic.app.ui.screens.PlaylistDetailScreen
import com.wearsic.app.ui.screens.PlaylistsScreen
import com.wearsic.app.ui.screens.QueueScreen
import com.wearsic.app.ui.screens.RecommendationsScreen
import com.wearsic.app.ui.screens.SearchScreen
import com.wearsic.app.ui.screens.ServerDashboardScreen
import com.wearsic.app.ui.screens.SettingsScreen
import com.wearsic.app.ui.screens.StorageStatsContent
import com.wearsic.app.ui.screens.VolumeScreen
import com.wearsic.app.ui.screens.WearsicHubScreen
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.LocalWearsicDynamicAccent
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender
import com.wearsic.app.ui.util.ArtworkColors
import com.wearsic.app.ui.util.LocalRotaryDirectionInverted
import com.wearsic.app.ui.viewmodel.WearsicPlayerViewModel

@Composable
fun WearsicApp(
    modifier: Modifier = Modifier,
    playerViewModel: WearsicPlayerViewModel = viewModel(),
    timeText: @Composable () -> Unit = {
        val base = LocalDensity.current
        val scaledDensity = remember(base.density) { Density(base.density, fontScale = 0.85f) }
        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            TimeText()
        }
    }
) {
    val navController = rememberSwipeDismissableNavController()
    // Which Hub section (Home / Library / Search / Mix) is showing. Hoisted
    // so a tap inside the Hub switches the TAB instead of pushing the legacy
    // standalone Library/Search/Made-For-You routes.
    val playbackVM = playerViewModel.playbackViewModel
    val libraryVM = playerViewModel.libraryViewModel
    val downloadsVM = playerViewModel.downloadsViewModel
    val settingsVM = playerViewModel.settingsViewModel

    // Root shell observes ONLY app-wide theme/input preferences and a stable
    // playback projection (positionMs zeroed) so 2s playback ticks, search
    // typing, lyrics sync, and list refreshes never recompose the NavHost.
    val dynamicColorEnabled by settingsVM.dynamicColorEnabled.collectAsStateWithLifecycle()
    val userAccent by settingsVM.userAccent.collectAsStateWithLifecycle()
    val rotatingArtworkEnabled by settingsVM.rotatingArtworkEnabled.collectAsStateWithLifecycle()
    val rotaryDirectionInverted by settingsVM.rotaryDirectionInverted.collectAsStateWithLifecycle()

    val playbackStateHolder = playbackVM.uiState.collectAsStateWithLifecycle()
    val stablePlaybackState by remember {
        derivedStateOf { playbackStateHolder.value.copy(currentPositionMs = 0L) }
    }

    // Accent precedence:
    //   1. Dynamic (opt-in): artwork accent when real colours exist.
    //   2. User-picked app colour from Settings (catalog or saved from art).
    //   3. Default lavender.
    var artworkColors by remember { mutableStateOf(ArtworkColors.Default) }
    val effectiveAccent = when {
        dynamicColorEnabled && artworkColors.fromArtwork -> artworkColors.accent
        userAccent != null -> Color(userAccent!!)
        else -> WearsicVibrantLavender
    }

    // Lyric results belong to one track; drop them when the track changes.
    // Reads stablePlaybackState so 2s position ticks do not recompose root.
    val currentTrackId = stablePlaybackState.currentTrack.id
    LaunchedEffect(currentTrackId) {
        playerViewModel.lyricsViewModel.invalidateIfStale(currentTrackId)
    }

    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        com.wearsic.app.StartupDiagnostics.markUiReady(appContext)
        kotlinx.coroutines.delay(1500)
        MainActivity.pendingTileAction?.let { action ->
            MainActivity.pendingTileAction = null
            playbackVM.handleTileAction(action)
        }
    }

    val openPlayerRequested by MainActivity.openPlayerRequest
    LaunchedEffect(openPlayerRequested) {
        if (openPlayerRequested) {
            MainActivity.openPlayerRequest.value = false
            navController.navigate(Screen.Player.route) { launchSingleTop = true }
        }
    }

    CompositionLocalProvider(
        LocalWearsicAccent provides effectiveAccent,
        LocalWearsicDynamicAccent provides dynamicColorEnabled,
        LocalRotaryDirectionInverted provides rotaryDirectionInverted
    ) {
        WearsicTheme(accent = effectiveAccent, dynamic = dynamicColorEnabled) {
            AppScaffold(
                timeText = timeText,
                modifier = modifier
            ) {
                // ---- Persistent mini-player shell ----
                // The compact pill floats above Hub navigation (the four-tab
                // shell and every open sub-screen), and hides on the surfaces
                // that own the bottom of the screen (Player/Queue/Volume/
                // Settings/Storage/Dashboard). Its reserve is injected through
                // a CompositionLocal so every screen's
                // `rememberWearsicListContentPadding()` lifts its last row above the
                // pill without per-screen changes.
                val currentDestination by navController.currentBackStackEntryAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    SwipeDismissableNavHost(
                            navController = navController,
                            startDestination = Screen.Hub.route
                        ) {
                            // 0. Hub — Home / Library / Search / Mix tabs + section rail.
                            // Its state is collected INSIDE this destination, so a
                            // library refresh never recomposes the other screens.
                            composable(Screen.Hub.route) {
                                val recentTracks by libraryVM.recentTracks.collectAsStateWithLifecycle()
                                val mostPlayedTracks by libraryVM.mostPlayedTracks.collectAsStateWithLifecycle()
                                val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
                                val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
                                val playlistDetailState by libraryVM.playlistDetailState.collectAsStateWithLifecycle()
                                val albumsState by libraryVM.albumsState.collectAsStateWithLifecycle()
                                val artistsState by libraryVM.artistsState.collectAsStateWithLifecycle()
                                val recommendationsState by libraryVM.recommendationsState.collectAsStateWithLifecycle()
                                val searchState by libraryVM.searchState.collectAsStateWithLifecycle()
                                val radioState by libraryVM.radioState.collectAsStateWithLifecycle()
                                val serverReachable by libraryVM.serverReachable.collectAsStateWithLifecycle()
                                val failoverServerName by libraryVM.failoverServerName.collectAsStateWithLifecycle()
                                val offlineDownloads by downloadsVM.completedTracks.collectAsStateWithLifecycle()

                                WearsicHubScreen(
                                    playbackState = stablePlaybackState,
                                    currentTrackId = stablePlaybackState.currentTrack.id.takeIf { it.isNotBlank() },
                                    recentTracks = recentTracks,
                                    mostPlayedTracks = mostPlayedTracks,
                                    favoritesState = favoritesState,
                                    playlistsState = playlistsState,
                                    playlistDetailState = playlistDetailState,
                                    albumsState = albumsState,
                                    artistsState = artistsState,
                                    recommendationsState = recommendationsState,
                                    searchState = searchState,
                                    radioState = radioState,
                                    serverReachable = serverReachable,
                                    failoverServerName = failoverServerName,
                                    offlineDownloads = offlineDownloads,
                                    favoriteTrackIds = favoritesState.tracks.map { it.id }.toSet(),
                                    onPlay = { tracks, index ->
                                        playerViewModel.playTracksFromList(tracks, index)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onPlayFavorites = {
                                        playerViewModel.playTracksFromList(favoritesState.tracks, 0)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onShuffleFavorites = {
                                        playerViewModel.playTracksShuffled(favoritesState.tracks)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onStartRadio = {
                                        playerViewModel.startRadio()
                                    },
                                    onQueueTrack = { playerViewModel.queueTrack(it) },
                                    onToggleFavoriteTrack = { playerViewModel.toggleFavoriteFor(it) },
                                    onStartDownload = { downloadsVM.startDownload(it) },
                                    onOpenPlayer = { navController.navigate(Screen.Player.route) },
                                    onOpenSearch = { navController.navigate(Screen.Search.route) },
                                    onOpenLibrary = { navController.navigate(Screen.Library.route) },
                                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                                    onOpenPlaylists = { navController.navigate(Screen.Playlists.route) },
                                    onOpenAlbums = { navController.navigate(Screen.Albums.route) },
                                    onOpenFavorites = { navController.navigate(Screen.Favorites.route) },
                                    onOpenArtists = { navController.navigate(Screen.Artists.route) },
                                    onOpenRecommendations = { navController.navigate(Screen.MadeForYou.route) },
                                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                                    onRefreshLibrary = { libraryVM.refreshLibrary() },
                                    onRefreshFavorites = { libraryVM.refreshFavorites() },
                                    onRefreshArtists = { libraryVM.refreshArtists() },
                                    onRefreshRecommendations = { libraryVM.refreshRecommendations() },
                                    onSearchQueryChanged = { libraryVM.onSearchTextChanged(it) },
                                    onSearchQuerySubmitted = { libraryVM.search(it) },
                                    onLoadMoreResults = { libraryVM.loadMoreResults() },
                                    searchPlaylists = playlistsState.playlists,
                                    onCreatePlaylistAndAdd = { name, track ->
                                        libraryVM.createPlaylistAndAdd(name, track)
                                    },
                                    onAddTrackToPlaylist = { playlistId, track ->
                                        libraryVM.addToPlaylist(playlistId, track)
                                    },
                                    onAlbumQueryChanged = { libraryVM.searchAlbums(it) },
                                    onOpenAlbum = { id, name ->
                                        navController.navigate(Screen.PlaylistDetail.createRoute(id, name))
                                    },
                                    onPlayArtist = { group, index ->
                                        playerViewModel.playTracksFromList(group.songs, index)
                                        navController.navigate(Screen.Player.route)
                                    }
                                )
                            }

                            // 1. Library Screen
                            composable(Screen.Library.route) {
                                val recentTracks by libraryVM.recentTracks.collectAsStateWithLifecycle()
                                val mostPlayedTracks by libraryVM.mostPlayedTracks.collectAsStateWithLifecycle()
                                val serverReachable by libraryVM.serverReachable.collectAsStateWithLifecycle()
                                val failoverServerName by libraryVM.failoverServerName.collectAsStateWithLifecycle()
                                val offlineDownloads by downloadsVM.completedTracks.collectAsStateWithLifecycle()

                                LibraryScreen(
                                    playbackState = stablePlaybackState,
                                    recentTracks = recentTracks,
                                    mostPlayed = mostPlayedTracks,
                                    onPlayRecentTrack = { tracks, index ->
                                        playerViewModel.playTracksFromList(tracks, index)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    isServerReachable = serverReachable,
                                    failoverServerName = failoverServerName,
                                    offlineDownloads = offlineDownloads,
                                    onPlayOfflineTrack = { tracks, index ->
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
                                    onNavigateToFavorites = {
                                        navController.navigate(Screen.Favorites.route)
                                    },
                                    onNavigateToArtists = {
                                        navController.navigate(Screen.Artists.route)
                                    },
                                    onNavigateToRecommendations = {
                                        navController.navigate(Screen.MadeForYou.route)
                                    },
                                    onNavigateToSettings = {
                                        navController.navigate(Screen.Settings.route)
                                    },
                                    onNavigateToPlayer = {
                                        navController.navigate(Screen.Player.route)
                                    }
                                )
                            }

                            // Recommendations ("Made for you") — taste-ranked, server-side.
                            composable(Screen.MadeForYou.route) {
                                val recommendationsState by libraryVM.recommendationsState.collectAsStateWithLifecycle()
                                val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
                                val recentTracks by libraryVM.recentTracks.collectAsStateWithLifecycle()
                                val radioState by libraryVM.radioState.collectAsStateWithLifecycle()
                                MixScreen(
                                    recommendationsState = recommendationsState,
                                    favorites = favoritesState.tracks,
                                    recentTracks = recentTracks,
                                    radioState = radioState,
                                    onPlay = { tracks, index ->
                                        playerViewModel.playTracksFromList(tracks, index)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onStartRadio = { playerViewModel.startRadio() },
                                    onShuffleFavorites = {
                                        playerViewModel.playTracksShuffled(favoritesState.tracks)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onRefreshRecommendations = { libraryVM.refreshRecommendations() }
                                )
                            }

                            // 2. Search Screen (API-connected music discovery)
                            composable(Screen.Search.route) {
                                val searchState by libraryVM.searchState.collectAsStateWithLifecycle()
                                val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
                                val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
                                val favoriteTrackIds = remember(favoritesState.tracks) {
                                    favoritesState.tracks.mapTo(HashSet(favoritesState.tracks.size)) { it.id }
                                }

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
                                        playerViewModel.queueTrack(track)
                                    },
                                    onToggleFavorite = { track ->
                                        playerViewModel.toggleFavoriteFor(track)
                                    },
                                    favoriteTrackIds = favoriteTrackIds,
                                    playlists = playlistsState.playlists,
                                    onCreatePlaylistAndAdd = { name, track ->
                                        libraryVM.createPlaylistAndAdd(name, track)
                                    },
                                    onLoadMore = {
                                        libraryVM.loadMoreResults()
                                    },
                                    onAddToPlaylist = { playlistId, track ->
                                        libraryVM.addToPlaylist(playlistId, track)
                                    }
                                )
                            }

                            // 3. Player Screen (Connected to real Media3 Playback + Offline awareness)
                            composable(Screen.Player.route) {
                                val livePlaybackState by playbackVM.uiState.collectAsStateWithLifecycle()
                                val lyricsState by playerViewModel.lyricsState.collectAsStateWithLifecycle()
                                val downloads by downloadsVM.allDownloads.collectAsStateWithLifecycle()

                                val currentTrackDownload = remember(downloads, livePlaybackState.currentTrack.id) {
                                    downloads.find { it.trackId == livePlaybackState.currentTrack.id }
                                }
                                val isCurrentTrackDownloaded = currentTrackDownload?.isCompleted() == true
                                val isCurrentTrackDownloading =
                                    currentTrackDownload?.downloadState == DownloadState.DOWNLOADING.name ||
                                        currentTrackDownload?.downloadState == DownloadState.QUEUED.name
                                val currentTrackDownloadProgress = currentTrackDownload?.progress ?: 0

                                LaunchedEffect(livePlaybackState.currentTrack.id) {
                                    libraryVM.prewarmAlbum(livePlaybackState.currentTrack)
                                }
                                PlayerScreen(
                                    playbackState = livePlaybackState,
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
                                    onSeekTo = { positionMs ->
                                        playbackVM.seekTo(positionMs)
                                    },
                                    onToggleFavorite = {
                                        playerViewModel.toggleFavorite()
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
                                    downloadProgress = currentTrackDownloadProgress,
                                    onArtworkColor = { artworkColors = it },
                                    rotateArtwork = rotatingArtworkEnabled,
                                    onSaveArtworkAccent = { color ->
                                        settingsVM.setUserAccentColor(color.toArgb())
                                    },
                                    onOpenAlbum = { track ->
                                        libraryVM.openAlbumForTrack(track) { album ->
                                            if (album != null) {
                                                navController.navigate(
                                                    Screen.PlaylistDetail.createRoute(album.id, album.name)
                                                )
                                            }
                                        }
                                    },
                                    lyricsState = lyricsState,
                                    onRequestLyrics = {
                                        playerViewModel.lyricsViewModel.ensureLyrics(livePlaybackState.currentTrack)
                                    },
                                    onRetryLyrics = {
                                        playerViewModel.lyricsViewModel.retry(livePlaybackState.currentTrack)
                                    }
                                )
                            }

                            // 4. Volume & Output Screen
                            composable(Screen.Volume.route) {
                                val sleepRemainingMs by playerViewModel.sleepRemainingMs.collectAsStateWithLifecycle()
                                val sleepSetMinutes by playerViewModel.sleepSetMinutes.collectAsStateWithLifecycle()
                                val crossfadeMs by settingsVM.crossfadeMs.collectAsStateWithLifecycle()

                                VolumeScreen(
                                    currentOutputDevice = stablePlaybackState.outputDeviceName,
                                    sleepRemainingMs = sleepRemainingMs,
                                    sleepSetMinutes = sleepSetMinutes,
                                    crossfadeMs = crossfadeMs,
                                    onSleepTimerSet = { minutes ->
                                        playerViewModel.setSleepTimer(minutes)
                                    },
                                    onCrossfadeMsChanged = { ms ->
                                        settingsVM.setCrossfadeMs(ms)
                                    },
                                    onOutputDeviceChanged = {
                                        playbackVM.refreshOutputDevice()
                                    }
                                )
                            }

                            // 5. Settings Screen (Persistent Server URL, Cache & Downloads Management)
                            composable(Screen.Settings.route) {
                                val serverUrl by settingsVM.serverUrl.collectAsStateWithLifecycle()
                                val connectionTestState by settingsVM.connectionTestState.collectAsStateWithLifecycle()
                                val apiKey by settingsVM.apiKey.collectAsStateWithLifecycle()
                                val autoCacheEnabled by settingsVM.autoCacheEnabled.collectAsStateWithLifecycle()
                                val offlineLimit by settingsVM.offlineLimit.collectAsStateWithLifecycle()
                                val lyricsDownloadMode by settingsVM.lyricsDownloadMode.collectAsStateWithLifecycle()
                                val searchSuggestionsEnabled by settingsVM.searchSuggestionsEnabled.collectAsStateWithLifecycle()
                                val serverProfiles by settingsVM.serverProfiles.collectAsStateWithLifecycle()
                                val startupHealth by settingsVM.startupHealth.collectAsStateWithLifecycle()

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
                                    onOpenServerDashboard = {
                                        navController.navigate(Screen.ServerDashboard.route)
                                    },
                                    dynamicColorEnabled = dynamicColorEnabled,
                                    onDynamicColorToggled = { enabled ->
                                        settingsVM.setDynamicColorEnabled(enabled)
                                    },
                                    userAccentArgb = userAccent,
                                    onUserAccentSelected = { argb ->
                                        settingsVM.setUserAccentColor(argb)
                                    },
                                    rotatingArtworkEnabled = rotatingArtworkEnabled,
                                    onRotatingArtworkToggled = { enabled ->
                                        settingsVM.setRotatingArtworkEnabled(enabled)
                                    },
                                    rotaryDirectionInverted = rotaryDirectionInverted,
                                    onRotaryDirectionToggled = { inverted ->
                                        settingsVM.setRotaryDirectionInverted(inverted)
                                    },
                                    lyricsDownloadMode = lyricsDownloadMode,
                                    onLyricsDownloadModeChanged = { mode ->
                                        settingsVM.setLyricsDownloadMode(mode)
                                    },
                                    searchSuggestionsEnabled = searchSuggestionsEnabled,
                                    onSearchSuggestionsToggled = { enabled ->
                                        settingsVM.setSearchSuggestionsEnabled(enabled)
                                    },
                                    profiles = serverProfiles,
                                    onSaveProfile = { name ->
                                        settingsVM.addCurrentAsProfile(name)
                                    },
                                    onApplyProfile = { profile ->
                                        settingsVM.applyProfile(profile)
                                    },
                                    onRemoveProfile = { id ->
                                        settingsVM.removeProfile(id)
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
                                val radioState by libraryVM.radioState.collectAsStateWithLifecycle()
                                QueueScreen(
                                    playbackState = stablePlaybackState,
                                    onPlayItem = { index ->
                                        playbackVM.seekToQueueItem(index)
                                    },
                                    onRemoveItem = { index ->
                                        playbackVM.removeFromQueue(index)
                                    },
                                    onMoveItem = { from, to ->
                                        playbackVM.moveQueueItem(from, to)
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
                                val downloads by downloadsVM.allDownloads.collectAsStateWithLifecycle()
                                DownloadsScreen(
                                    downloads = downloads,
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
                                        downloadsVM.retryDownload(track)
                                    },
                                    onClearAllDownloads = {
                                        downloadsVM.clearAllDownloads()
                                    },
                                    onPlayAll = { tracks ->
                                        playerViewModel.playTracksFromList(tracks, 0)
                                        navController.navigate(Screen.Player.route)
                                    }
                                )
                            }

                            // Storage stats
                            composable(Screen.Storage.route) {
                                val storageStats by downloadsVM.storageStats.collectAsStateWithLifecycle()
                                StorageStatsContent(
                                    autoCount = storageStats.autoCount,
                                    autoMb = storageStats.autoMb,
                                    manualCount = storageStats.manualCount,
                                    manualMb = storageStats.manualMb,
                                    onClearAutoCached = { downloadsVM.clearAutoCachedDownloads() }
                                )
                            }

                            // Server engine dashboard: live /health + /ready
                            composable(Screen.ServerDashboard.route) {
                                val dashboardState by settingsVM.serverDashboard.collectAsStateWithLifecycle()
                                LaunchedEffect(Unit) {
                                    settingsVM.refreshServerDashboard()
                                }
                                ServerDashboardScreen(
                                    state = dashboardState,
                                    onRefresh = { settingsVM.refreshServerDashboard() }
                                )
                            }

                            // Playlists & Favorites hub
                            composable(Screen.Playlists.route) {
                                val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
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
                                val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
                                val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
                                val downloads by downloadsVM.allDownloads.collectAsStateWithLifecycle()
                                val downloadedTrackIds = remember(downloads) {
                                    downloads.filter { it.isCompleted() }.mapTo(HashSet()) { it.trackId }
                                }

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
                                    onQueue = { track ->
                                        playerViewModel.queueTrack(track)
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
                                    },
                                    downloadedTrackIds = downloadedTrackIds,
                                    onPlayAll = { tracks ->
                                        playerViewModel.playTracksFromList(tracks, 0)
                                        navController.navigate(Screen.Player.route)
                                    },
                                    onShuffleAll = { tracks ->
                                        playerViewModel.playTracksShuffled(tracks)
                                        navController.navigate(Screen.Player.route)
                                    }
                                )
                            }

                            // Playlist detail track list
                            composable(Screen.PlaylistDetail.route) { backStackEntry ->
                                val playlistId = backStackEntry.arguments?.getString("id") ?: ""
                                val playlistName = backStackEntry.arguments?.getString("name") ?: "Playlist"
                                val detailState by libraryVM.playlistDetailState.collectAsStateWithLifecycle()
                                val favoritesState by libraryVM.favoritesState.collectAsStateWithLifecycle()
                                val playlistsState by libraryVM.playlistsState.collectAsStateWithLifecycle()
                                val downloads by downloadsVM.allDownloads.collectAsStateWithLifecycle()
                                val favoriteTrackIds = remember(favoritesState.tracks) {
                                    favoritesState.tracks.mapTo(HashSet(favoritesState.tracks.size)) { it.id }
                                }
                                val downloadedTrackIds = remember(downloads) {
                                    downloads.filter { it.isCompleted() }.mapTo(HashSet()) { it.trackId }
                                }

                                PlaylistDetailScreen(
                                    playlistId = playlistId,
                                    playlistName = playlistName,
                                    detailState = detailState,
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
                                    onQueue = { track ->
                                        playerViewModel.queueTrack(track)
                                    },
                                    onToggleFavorite = { track ->
                                        playerViewModel.toggleFavoriteFor(track)
                                    },
                                    favoriteTrackIds = favoriteTrackIds,
                                    onRemoveTrack = { id, trackId ->
                                        libraryVM.removeTrackFromPlaylist(id, trackId)
                                    },
                                    playlists = playlistsState.playlists,
                                    onCreatePlaylistAndAdd = { name, track ->
                                        libraryVM.createPlaylistAndAdd(name, track)
                                    },
                                    onAddToPlaylist = { playlistId, track ->
                                        libraryVM.addToPlaylist(playlistId, track)
                                    },
                                    downloadedTrackIds = downloadedTrackIds,
                                    onDownloadAllTracks = { tracks ->
                                        downloadsVM.startDownloadBatch(tracks)
                                    }
                                )
                            }

                            // Albums (search + open as queue)
                            composable(Screen.Albums.route) {
                                val albumsState by libraryVM.albumsState.collectAsStateWithLifecycle()
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
                                val artistsState by libraryVM.artistsState.collectAsStateWithLifecycle()
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
        }
    }
}
