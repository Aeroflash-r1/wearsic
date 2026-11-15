package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.LocalWearsicTopChromeReserve
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicTopScrimBrush
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.viewmodel.LibraryViewModel.AlbumsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.ArtistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.FavoritesUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RecommendationsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.SearchUiState
import com.wearsic.app.model.Playlist

/**
 * The four top-level destinations of the redesigned app. One enum, one rail,
 * one state — switching between them is a state flip inside the start
 * destination, so the mini player never recomposes navigation and every tab
 * survives bezel-across home screen detours.
 */
enum class WearsicHubSection(val label: String) {
    Home("Home"),
    Library("Library"),
    Search("Search"),
    Mix("Mix");

    val icon: ImageVector
        get() = when (this) {
            Home -> Icons.Rounded.Home
            Library -> Icons.Rounded.LibraryMusic
            Search -> Icons.Rounded.Search
            Mix -> Icons.Rounded.Album
        }
}

/**
 * WearsicHubScreen — the Wear "app shell".
 *
 * Four tabs (Home, Library, Search, Mix) reachable through the Wearsic
 * section rail — a compact chip row floating under the system time text,
 * drawn on a scrim so the lists scroll *under* it. Tab content is the
 * existing screens, reused as-is (LibraryScreen/SearchScreen) plus the new
 * HomeStation and Mix screens; nothing is duplicated.
 *
 * The top reserve is injected via [LocalWearsicTopChromeReserve] so every
 * tab's own `rememberWearsicListContentPadding()` lifts its first row below the
 * rail without per-screen changes.
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
    offlineDownloads: List<Track>,
    favoriteTrackIds: Set<String>,
    onPlay: (List<Track>, Int) -> Unit,
    onPlayFavorites: () -> Unit,
    onShuffleFavorites: () -> Unit,
    onStartRadio: () -> Unit,
    onQueueTrack: (Track) -> Unit,
    onToggleFavoriteTrack: (Track) -> Unit,
    onStartDownload: (Track) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSearch: () -> Unit,
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
    onPlayArtist: (com.wearsic.app.model.ArtistGroup, Int) -> Unit,
    /**
     * The selected rail section. Hoisted so the nav host can switch tabs
     * INSTEAD of pushing the legacy standalone Library/Search/Made-For-You
     * routes — those still existed next to the Hub and made the OLD layout
     * come back mid-session.
     */
    /**
     * The Hub shell renders the HOME surface only. Library, Search and Mix
     * are real pushed routes (with right-swipe back), so this screen no
     * longer hosts tab content or the floating section rail — that rail was
     * clipped on the round bezel and duplicated navigation that routes do
     * better. The scroll list avoids the default auto-centering so the top
     * of the feed stays reachable.
     */
    currentTrackId: String? = null,
    onOpenLibrary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        HomeScreen(
            recentTracks = recentTracks,
            mostPlayedTracks = mostPlayedTracks,
            recommendationsState = recommendationsState,
            favorites = favoritesState.tracks,
            isServerReachable = serverReachable,
            currentTrackId = currentTrackId,
            onPlay = onPlay,
            onOpenSearchForMood = { mood ->
                onSearchQueryChanged(mood.query)
                onSearchQuerySubmitted(mood.query)
                onOpenSearch()
            },
            onOpenSearch = onOpenSearch,
            onOpenMix = onOpenRecommendations,
            onOpenLibrary = onOpenLibrary,
            onOpenPlayer = onOpenPlayer,
            onRefreshRecommendations = onRefreshRecommendations
        )
    }
}
