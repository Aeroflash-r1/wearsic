package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Brush
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.wearsicAccentContainer
import com.wearsic.app.ui.theme.LocalWearsicAccent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import com.wearsic.app.ui.theme.WearsicAccentAmber
import com.wearsic.app.ui.theme.WearsicSolarAmber
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.NowPlayingMiniCard
import com.wearsic.app.ui.components.WearsicActionTile
import com.wearsic.app.ui.components.WearsicPrimaryPillButton
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.components.WearsicSecondaryPillButton
import com.wearsic.app.ui.components.WearsicSettingsActionPill
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.watchScaled

import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

@Composable
fun LibraryScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToFavorites: (() -> Unit)? = null,
    onShuffleFavorites: (() -> Unit)? = null,
    onNavigateToArtists: () -> Unit,
    onNavigateToRecommendations: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    playbackState: PlaybackUiState = PlaybackUiState(),
    recentTracks: List<Track> = emptyList(),
    onPlayRecentTrack: (List<Track>, Int) -> Unit = { _, _ -> },
    isServerReachable: Boolean = true,
    failoverServerName: String? = null,
    offlineDownloads: List<Track> = emptyList(),
    onPlayOfflineTrack: (List<Track>, Int) -> Unit = { _, _ -> },
    // Cumulative listen ranking (v1.9.49 redesign); empty on fresh installs
    // until a few songs are played, then it powers the "Most played" shelf.
    mostPlayed: List<Track> = emptyList(),
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val offlinePreview = remember(offlineDownloads) { offlineDownloads.take(8) }
    val recentPreview = remember(recentTracks) { recentTracks.take(5) }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            // No auto-centering: the framework otherwise anchors item 1 to the
            // viewport centre, pushing the header (and the clock) off the top
            // of the round face. We pin the list with an explicit content
            // padding instead.
            autoCentering = null,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .testTag("library_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                WearsicScreenHeader(
                    title = "Library",
                    subtitle = "Your music, everywhere"
                )
            }

            // Offline banner — the server is unreachable, so the home screen
            // surfaces saved music first instead of failing silently.
            if (!isServerReachable) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("library_offline_banner"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = WearsicAccentAmber,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Offline — showing saved music",
                                color = WearsicTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Failover banner — the saved server proved unreachable, the app
            // switched ITSELF to another saved profile, and music keeps
            // working without a Settings visit. Deliberately a different
            // banner from the offline one: this is reassurance ("handled"),
            // that one is a limitation ("saved music only").
            if (failoverServerName != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("library_failover_banner"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = LocalWearsicAccent.current,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Switched to $failoverServerName",
                                color = WearsicTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Now Playing hero — only appears when a track is actually loaded
            // (playing OR paused). With nothing selected the home screen opens
            // clean instead of showing the placeholder "READY TO PLAY" box.
            if (playbackState.currentTrack.id.isNotBlank()) {
                item {
                    NowPlayingMiniCard(
                        title = playbackState.currentTrack.title,
                        artist = playbackState.currentTrack.artist,
                        artworkUrl = playbackState.currentTrack.artworkUrl,
                        isPlaying = playbackState.isPlaying,
                        onClick = onNavigateToPlayer
                    )
                }
            }

            // Primary Search Action
            item {
                WearsicPrimaryPillButton(
                    label = "Search Music",
                    icon = Icons.Rounded.Search,
                    onClick = onNavigateToSearch,
                    backgroundColor = LocalWearsicAccent.current,
                    contentColor = WearsicTextPrimaryDark,
                    testTag = "library_search_button"
                )
            }

            // Browse 2×2 Grid: compact round-screen destination tiles kept near
            // the top so Downloads and Playlists are one tap from launch.
            // The grid is a fixed 2×2 so the layout never reflows when an
            // optional destination (Favorites) is present or absent.
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WearsicActionTile(
                        label = "Downloads",
                        icon = Icons.Rounded.Download,
                        onClick = onNavigateToDownloads,
                        modifier = Modifier.weight(1f),
                        accentColor = WearsicSolarAmber,
                        badge = "${offlineDownloads.size} saved",
                        testTag = "library_downloads_button"
                    )
                    WearsicActionTile(
                        label = "Playlists",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        onClick = onNavigateToPlaylists,
                        modifier = Modifier.weight(1f),
                        testTag = "library_playlists_button"
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WearsicActionTile(
                        label = "Albums",
                        icon = Icons.Rounded.Album,
                        onClick = onNavigateToAlbums,
                        modifier = Modifier.weight(1f),
                        testTag = "library_albums_button"
                    )
                    WearsicActionTile(
                        label = "Artists",
                        icon = Icons.Rounded.Person,
                        onClick = onNavigateToArtists,
                        modifier = Modifier.weight(1f),
                        testTag = "library_artists_button"
                    )
                }
            }

            if (onNavigateToFavorites != null || onShuffleFavorites != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onNavigateToFavorites != null) {
                            WearsicSecondaryPillButton(
                                label = "Favorites",
                                icon = Icons.Rounded.Favorite,
                                onClick = onNavigateToFavorites,
                                modifier = Modifier.weight(1f),
                                testTag = "library_favorites_button"
                            )
                        }
                        if (onShuffleFavorites != null) {
                            WearsicSecondaryPillButton(
                                label = "Shuffle",
                                icon = Icons.Rounded.Shuffle,
                                onClick = onShuffleFavorites,
                                modifier = Modifier.weight(1f),
                                testTag = "library_shuffle_favorites_button"
                            )
                        }
                    }
                }
            }

            // Saved-offline section: shown when the server is unreachable so
            // the home screen stays useful as an offline library.
            if (!isServerReachable && offlinePreview.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "Saved offline",
                        icon = Icons.Rounded.Download,
                        trailing = {
                            Text(
                                text = "${offlineDownloads.size} songs",
                                color = wearsicSecondaryText(),
                                fontFamily = WearsicBodyFont,
                                fontSize = 10.sp
                            )
                        }
                    )
                }
                itemsIndexed(offlinePreview, key = { _, track -> "offline_${track.id}" }) { index, track ->
                    WearsicSongRow(
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        onClick = { onPlayOfflineTrack(offlineDownloads, index) },
                        testTag = "library_offline_${track.id}"
                    )
                }
            }

            // Recently Played Section
            if (recentPreview.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "Recently Played",
                        icon = Icons.Rounded.History,
                        iconContentDescription = null,
                        trailing = {
                            Text(
                                text = "${recentTracks.size} songs",
                                color = wearsicSecondaryText(),
                                fontFamily = WearsicBodyFont,
                                // Matches its own section label (10sp) so the
                                // row reads as one baseline, not two sizes.
                                fontSize = 10.sp
                            )
                        }
                    )
                }

                itemsIndexed(recentPreview, key = { _, track -> "recent_${track.id}" }) { _, track ->
                    RecentTrackRow(
                        track = track,
                        onClick = {
                            val trackIndex = recentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            onPlayRecentTrack(recentTracks, trackIndex)
                        }
                    )
                }
            }

            // Settings Action Pill
            item {
                WearsicSettingsActionPill(
                    label = "Settings",
                    icon = Icons.Rounded.Settings,
                    onClick = onNavigateToSettings,
                    testTag = "library_settings_button"
                )
            }

            // Bottom Spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}



@Composable
private fun RecentTrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    WearsicSongRow(
        title = track.title,
        artist = track.artist,
        artworkUrl = track.artworkUrl,
        onClick = onClick,
        modifier = modifier,
        testTag = "recent_track_${track.id}",
        trailing = {
            // Decorative play affordance — the whole row is tappable.
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(LocalWearsicAccent.current),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = WearsicTextPrimaryDark,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun LibraryScreenPreview() {
    WearsicTheme {
        LibraryScreen(
            onNavigateToSearch = {},
            onNavigateToDownloads = {},
            onNavigateToPlaylists = {},
            onNavigateToArtists = {},
            onNavigateToSettings = {},
            onNavigateToPlayer = {}
        )
    }
}
