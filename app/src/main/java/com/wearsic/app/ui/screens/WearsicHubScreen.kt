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
    section: WearsicHubSection = WearsicHubSection.Home,
    onSelectSection: ((WearsicHubSection) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var internalTab by rememberSaveable { mutableIntStateOf(WearsicHubSection.Home.ordinal) }
    // An externally driven section wins; otherwise the screen keeps its own.
    val activeTab = if (onSelectSection != null) section.ordinal else internalTab
    // The rail block (24 top pad + chip row + label + 4 bottom pad) — the
    // exact vertical span the floating rail occupies; provided pre-scaled.
    // 24dp of top pad is deliberate: it drops the chip row below the system
    // time text AND buys chord room — the circle is ~141dp wide at that row
    // (vs ~123dp at 18dp), so a 4×32dp chip row fits without bevel clipping.
    val railReserve = watchScaled(
        WearsicDimens.HubRailChip + WearsicDimens.HubRailLabelLine + 24.dp + 4.dp
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        // ---- Tab content (each tab manages its own ScreenScaffold) ----
        CompositionLocalProvider(
            LocalWearsicTopChromeReserve provides railReserve
        ) {
            when (WearsicHubSection.entries[activeTab]) {
                WearsicHubSection.Home -> HomeScreen(
                    recentTracks = recentTracks,
                    mostPlayedTracks = mostPlayedTracks,
                    recommendationsState = recommendationsState,
                    favorites = favoritesState.tracks,
                    isServerReachable = serverReachable,
                    onPlay = onPlay,
                    onOpenSearchForMood = { mood ->
                        onSearchQueryChanged(mood.query)
                        onSearchQuerySubmitted(mood.query)
                        onOpenSearch()
                    },
                    onOpenSearch = onOpenSearch,
                    onOpenMix = {
                        internalTab = WearsicHubSection.Mix.ordinal
                        onSelectSection?.invoke(WearsicHubSection.Mix)
                    },
                    onRefreshRecommendations = onRefreshRecommendations
                )

                WearsicHubSection.Library -> LibraryScreen(
                    playbackState = playbackState,
                    recentTracks = recentTracks,
                    onPlayRecentTrack = onPlay,
                    isServerReachable = serverReachable,
                    failoverServerName = failoverServerName,
                    offlineDownloads = offlineDownloads,
                    onPlayOfflineTrack = onPlay,
                    onNavigateToSearch = onOpenSearch,
                    onNavigateToDownloads = onOpenDownloads,
                    onNavigateToPlaylists = onOpenPlaylists,
                    onNavigateToAlbums = onOpenAlbums,
                    onNavigateToFavorites = onOpenFavorites,
                    onNavigateToArtists = onOpenArtists,
                    onNavigateToRecommendations = onOpenRecommendations,
                    onNavigateToSettings = onOpenSettings,
                    onNavigateToPlayer = onOpenPlayer,
                    mostPlayed = mostPlayedTracks
                )

                WearsicHubSection.Search -> SearchScreen(
                    searchState = searchState,
                    onQuerySelected = onSearchQuerySubmitted,
                    onSearchTextChanged = onSearchQueryChanged,
                    onTrackSelected = { track -> onPlay(listOf(track), 0) },
                    onDownloadTrack = onStartDownload,
                    onAddToQueue = onQueueTrack,
                    onToggleFavorite = onToggleFavoriteTrack,
                    favoriteTrackIds = favoriteTrackIds,
                    playlists = searchPlaylists,
                    onCreatePlaylistAndAdd = onCreatePlaylistAndAdd,
                    onLoadMore = onLoadMoreResults,
                    onAddToPlaylist = onAddTrackToPlaylist,
                    albumResults = albumsState.albums,
                    onSearchAlbums = onAlbumQueryChanged,
                    onOpenAlbum = { album -> onOpenAlbum(album.id, album.name) }
                )

                WearsicHubSection.Mix -> MixScreen(
                    recommendationsState = recommendationsState,
                    favorites = favoritesState.tracks,
                    recentTracks = recentTracks,
                    radioState = radioState,
                    onPlay = onPlay,
                    onStartRadio = onStartRadio,
                    onShuffleFavorites = onShuffleFavorites,
                    onRefreshRecommendations = onRefreshRecommendations
                )
            }
        }

        // ---- Section rail (floating, scrims, round-safe) ----
        WearsicHubRail(
            active = WearsicHubSection.entries[activeTab],
            onSelect = { picked ->
                internalTab = picked.ordinal
                onSelectSection?.invoke(picked)
            },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * The four-chip section rail. Round-screen aware: the chip row is centred
 * and narrow (4×26dp chips + gaps ≈ 128dp), so it always fits inside the
 * available chord just below the system time text; a fading black scrim
 * keeps scrolling content legible beneath it.
 */
@Composable
private fun WearsicHubRail(
    active: WearsicHubSection,
    onSelect: (WearsicHubSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = LocalWearsicAccent.current
    val chipSize = watchScaled(WearsicDimens.HubRailChip)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(WearsicTopScrimBrush)
            .padding(top = watchScaled(24.dp), bottom = watchScaled(4.dp))
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(watchScaled(2.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 4 chips × 26dp visual inside (26+6)=32dp touch slots +
                // 3×2dp gaps ≈ 134dp — still inside the ~123dp chord budget
                // at the rail's top row... slots sized snugly below it:
                for (section in WearsicHubSection.entries) {
                    val selected = section == active
                    // Touch slot: chip visual + an invisible margin so the
                    // segmented control still meets a sane tap size without
                    // growing the visible row out of the round chord.
                    Box(
                        modifier = Modifier
                            .size(chipSize + WearsicDimens.HubRailChipPad * 2)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(section) }
                            )
                            .semantics {
                                contentDescription = "${section.label} tab" +
                                    if (selected) ", selected" else ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(chipSize)
                                .clip(CircleShape)
                                .background(
                                    if (selected) accent.copy(alpha = 0.28f)
                                    else WearsicGlassBorder.copy(alpha = 0.10f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) accent.copy(alpha = 0.65f) else WearsicGlassBorder,
                                    CircleShape
                                )
                                .then(
                                    if (selected) Modifier.testTag("hub_rail_${section.name.lowercase()}_selected")
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = null,
                                tint = if (selected) WearsicTextPrimary else com.wearsic.app.ui.theme.WearsicTextWhite60,
                                modifier = Modifier.size(watchScaled(14.dp))
                            )
                        }
                    }
                }
            }
            Text(
                text = active.label,
                color = accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .height(WearsicDimens.HubRailLabelLine)
                    .padding(top = watchScaled(1.dp))
            )
        }
    }
}
