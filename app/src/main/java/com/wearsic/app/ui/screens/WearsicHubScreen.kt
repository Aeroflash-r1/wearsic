package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.PagerScaffoldDefaults
import androidx.wear.compose.foundation.pager.HorizontalPager
import com.wearsic.app.model.ArtistGroup
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.viewmodel.LibraryViewModel.AlbumsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.ArtistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.FavoritesUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RecommendationsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.SearchUiState
import kotlinx.coroutines.launch

/**
 * Top-level destinations of the Horizon Hub. The pager itself has three pages
 * (Home / Library / Search); Mix stays a pushed secondary destination because
 * it is a focused mix view rather than a browse tab.
 */
enum class WearsicHubSection(val label: String, val page: Int) {
    Home("Home", 0),
    Search("Search", 1),
    Mix("Mix", -1);
}

/**
 * Wearsic 2.0 "Horizon Hub" — a 2-page horizontal pager (Home / Search) inside
 * the existing AppScaffold. The former Library page was removed: its functions
 * (Downloads / Playlists / Albums / Artists / Favorites / offline shelf) are
 * folded into Home's Browse section. Each page is an [AnimatedPage] with its
 * own ScreenScaffold and independent scroll state; rotary scrolling stays with
 * the active page's vertical list. Deep-link routes remain in the nav host.
 */
@Composable
fun WearsicHubScreen(
    playbackState: PlaybackUiState,
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    favoritesState: FavoritesUiState,
    playlistsState: PlaylistsUiState,
    playlistDetailState: PlaylistDetailUiState,
    albumsState: AlbumsUiState,
    artistsState: ArtistsUiState,
    recommendationsState: RecommendationsUiState,
    searchState: SearchUiState,
    radioState: RadioState,
    serverReachable: Boolean,
    failoverServerName: String?,
    offlineReason: String? = null,
    offlineDownloads: List<Track>,
    favoriteTrackIds: Set<String>,
    onPlay: (List<Track>, Int) -> Unit,
    onPlayFavorites: () -> Unit,
    onShuffleFavorites: (List<Track>) -> Unit,
    onStartRadio: () -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavoriteTrack: (Track) -> Unit,
    onStartDownload: (Track) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onOpenDownloads: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenArtists: () -> Unit,
    onOpenRecommendations: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRefreshFavorites: () -> Unit,
    onRefreshArtists: () -> Unit,
    onRefreshRecommendations: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchQuerySubmitted: (String) -> Unit,
    onLoadMoreResults: () -> Unit,
    searchPlaylists: List<Playlist>,
    onCreatePlaylistAndAdd: (String, Track) -> Unit,
    onAddTrackToPlaylist: (String, Track) -> Unit,
    onAlbumQueryChanged: (String) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onPlayArtist: (ArtistGroup, Int) -> Unit,
    currentTrackId: String? = null,
    onOpenMood: (WearsicMood) -> Unit = {},
    /** Plays a single search result with its artist-aware related queue. */
    onPlayTrackWithRelated: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    // Shortcuts that used to push standalone routes now glide to the page.
    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page) }
    }

    HorizontalPagerScaffold(
        pagerState = pagerState,
        modifier = modifier.background(WearsicAppBackground)
    ) {
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerScaffoldDefaults.snapWithSpringFlingBehavior(state = pagerState),
            // Rotary page scrolling deliberately disabled so the crown keeps
            // scrolling the active page's vertical list (blueprint §3).
            rotaryScrollableBehavior = null,
        ) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                when (page) {
                    0 -> HomeScreen(
                        recentTracks = recentTracks,
                        mostPlayedTracks = mostPlayedTracks,
                        recommendationsState = recommendationsState,
                        favorites = favoritesState.tracks,
                        isServerReachable = serverReachable,
                        offlineReason = offlineReason,
                        currentTrackId = currentTrackId,
                        currentTrack = playbackState.currentTrack,
                        isPlaying = playbackState.isPlaying,
                        onPlay = onPlay,
                        onOpenMood = onOpenMood,
                        onOpenSearch = { goTo(WearsicHubSection.Search.page) },
                        onOpenMix = onOpenRecommendations,
                        onOpenLibrary = {},
                        onOpenPlayer = onOpenPlayer,
                        onRefreshRecommendations = onRefreshRecommendations,
                        onShuffleSelected = onShuffleFavorites,
                        onOpenSettings = onOpenSettings,
                        onOpenDownloads = onOpenDownloads,
                        onOpenPlaylists = onOpenPlaylists,
                        onOpenAlbums = onOpenAlbums,
                        onOpenArtists = onOpenArtists,
                        onOpenFavorites = onOpenFavorites,
                        offlineDownloads = offlineDownloads
                    )

                    1 -> SearchScreen(
                        searchState = searchState,
                        onQuerySelected = onSearchQuerySubmitted,
                        onSearchTextChanged = onSearchQueryChanged,
                        onTrackSelected = { track ->
                            onPlayTrackWithRelated?.invoke(track) ?: onPlay(listOf(track), 0)
                        },
                        onDownloadTrack = onStartDownload,
                        onAddToQueue = onQueueTrack,
                        onToggleFavorite = onToggleFavoriteTrack,
                        favoriteTrackIds = favoriteTrackIds,
                        playlists = searchPlaylists,
                        onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onLoadMore = onLoadMoreResults,
                        onSearchAlbums = onAlbumQueryChanged,
                        onOpenMood = onOpenMood,
                        isOffline = offlineReason != null
                    )
                }
            }
        }
    }
}
