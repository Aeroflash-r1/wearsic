package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import com.example.model.PlaybackUiState
import com.example.model.Track
import com.example.ui.components.WearsicPrimaryPillButton
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.components.WearsicSectionTitle
import com.example.ui.components.WearsicSecondaryPillButton
import com.example.ui.components.WearsicSettingsActionPill
import com.example.ui.components.WearsicSongRow
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender

import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun LibraryScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToAlbums: () -> Unit = {},
    onNavigateToArtists: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPlayer: () -> Unit,
    playbackState: PlaybackUiState = PlaybackUiState(),
    recentTracks: List<Track> = emptyList(),
    onPlayRecentTrack: (List<Track>, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .testTag("library_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
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

            // Now Playing hero — always the first thing on the home screen so
            // "continue listening" is one tap away and the top of the app
            // reads as a real home rather than a plain menu.
            item {
                NowPlayingMiniCard(
                    title = playbackState.currentTrack.title.ifBlank { "No Active Track" },
                    artist = playbackState.currentTrack.artist.ifBlank { "Wearsic Player" },
                    artworkUrl = playbackState.currentTrack.artworkUrl,
                    isPlaying = playbackState.isPlaying,
                    onClick = onNavigateToPlayer
                )
            }

            // Primary Search Action
            item {
                WearsicPrimaryPillButton(
                    label = "Search Music",
                    icon = Icons.Rounded.Search,
                    onClick = onNavigateToSearch,
                    backgroundColor = WearsicVibrantLavender,
                    contentColor = WearsicTextPrimaryDark,
                    testTag = "library_search_button"
                )
            }

            // Recently Played Section
            if (recentTracks.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "Recently Played",
                        icon = Icons.Rounded.History,
                        iconContentDescription = null,
                        trailing = {
                            Text(
                                text = "${recentTracks.size} songs",
                                color = WearsicTextSecondary,
                                fontSize = 9.sp
                            )
                        }
                    )
                }

                items(recentTracks.take(5), key = { it.id }) { track ->
                    RecentTrackRow(
                        track = track,
                        onClick = {
                            val index = recentTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            onPlayRecentTrack(recentTracks, index)
                        }
                    )
                }
            }

            // Downloads Action
            item {
                WearsicSecondaryPillButton(
                    label = "Downloads",
                    icon = Icons.Rounded.Download,
                    onClick = onNavigateToDownloads,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "library_downloads_button"
                )
            }

            // Secondary Actions: Playlists & Albums
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WearsicSecondaryPillButton(
                        label = "Playlists",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        onClick = onNavigateToPlaylists,
                        modifier = Modifier.weight(1f),
                        testTag = "library_playlists_button"
                    )
                    WearsicSecondaryPillButton(
                        label = "Albums",
                        icon = Icons.Rounded.Album,
                        onClick = onNavigateToAlbums,
                        modifier = Modifier.weight(1f),
                        testTag = "library_albums_button"
                    )
                }
            }

            // Artists
            item {
                WearsicSecondaryPillButton(
                    label = "Artists",
                    icon = Icons.Rounded.Person,
                    onClick = onNavigateToArtists,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "library_artists_button"
                )
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
private fun NowPlayingMiniCard(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Solid accent for the active hero — the purple multi-stop gradient was
    // replaced with a single flat colour.
    val heroColor = WearsicVibrantLavender
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            // Flat fills only — no drop shadow, no sheen gradient.
            .background(if (isPlaying) SolidColor(heroColor) else SolidColor(WearsicGlassFill))
            .border(
                1.dp,
                if (isPlaying) Color.Transparent else WearsicSurfaceBorderSubtle,
                CircleShape
            )
            .wearsicClickable(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag("now_playing_shortcut")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Real album art (or the music-note chip when there is none).
                val artModifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (isPlaying) Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        else Modifier.border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(12.dp))
                    )
                if (!artworkUrl.isNullOrBlank()) {
                    val artRequest = remember(artworkUrl) {
                        ImageRequest.Builder(context)
                            .data(artworkUrl)
                            .size(96)
                            .build()
                    }
                    AsyncImage(
                        model = artRequest,
                        contentDescription = "Now Playing",
                        contentScale = ContentScale.Crop,
                        modifier = artModifier
                    )
                } else {
                    Box(
                        modifier = artModifier.background(
                            if (isPlaying) Color.Black.copy(alpha = 0.25f) else WearsicLavenderContainer
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = if (isPlaying) WearsicTextPrimaryDark else WearsicVibrantLavender,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Production touch: a tiny uppercase kicker labels the
                    // hero while music is actually playing.
                    if (isPlaying) {
                        Text(
                            text = "NOW PLAYING",
                            color = WearsicTextPrimaryDark.copy(alpha = 0.65f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                    Text(
                        text = title,
                        color = if (isPlaying) WearsicTextPrimaryDark else WearsicTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        color = if (isPlaying) {
                            WearsicTextPrimaryDark.copy(alpha = 0.75f)
                        } else {
                            WearsicTextSecondary
                        },
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPlaying) Color.White.copy(alpha = 0.9f) else WearsicVibrantLavender
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Playing" else "Play",
                    tint = WearsicTextPrimaryDark,
                    modifier = Modifier.size(16.dp)
                )
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
                    .background(WearsicVibrantLavender),
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
