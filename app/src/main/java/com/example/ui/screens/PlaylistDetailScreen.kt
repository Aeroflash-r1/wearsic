package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.example.model.Track
import com.example.ui.components.WearsicEmptyState
import com.example.ui.components.WearsicLibraryTrackRow
import com.example.ui.components.WearsicLoadingState
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState

import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    playlistName: String,
    detailState: PlaylistDetailUiState,
    onLoadTracks: (String) -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onRemoveTrack: (String, String) -> Unit,
    onQueue: (Track) -> Unit = {},
    playlists: List<com.example.model.Playlist> = emptyList(),
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    var actionTrack by remember { mutableStateOf<Track?>(null) }

    LaunchedEffect(playlistId) {
        onLoadTracks(playlistId)
    }

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
                .wearsicRotaryScroll(listState),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = playlistName,
                    subtitle = "${detailState.tracks.size} Tracks",
                )
            }

            if (detailState.isLoading && detailState.tracks.isEmpty()) {
                item {
                    WearsicLoadingState(label = "Loading tracks...")
                }
            }

            if (detailState.tracks.isEmpty() && !detailState.isLoading) {
                item {
                    WearsicEmptyState(
                        title = "Playlist is empty",
                        message = "Add tracks to this playlist on your server.",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic
                    )
                }
            }

            itemsIndexed(detailState.tracks, key = { _, track -> track.id }) { index, track ->
                WearsicLibraryTrackRow(
                    track = track,
                    onPlay = { onPlayTrack(detailState.tracks, index) },
                    onLongPress = { actionTrack = track },
                    onMore = { actionTrack = track },
                    testTagPrefix = "playlist_track"
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        actionTrack?.let { t ->
            com.example.ui.components.WearsicTrackActionSheet(
                track = t,
                playlists = playlists,
                onDismiss = { actionTrack = null },
                onPlay = { onPlayTrack(detailState.tracks, detailState.tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) },
                onQueue = { onQueue(t) },
                onDownload = { onDownloadTrack(t) },
                onAddToPlaylist = { pid -> onAddToPlaylist(pid, t) },
                onCreatePlaylistAndAdd = { name -> onCreatePlaylistAndAdd(name, t) },
                onRemove = { onRemoveTrack(playlistId, t.id) },
                removeLabel = "Remove from playlist"
            )
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun PlaylistDetailScreenPreview() {
    WearsicTheme {
        PlaylistDetailScreen(
            playlistId = "p1",
            playlistName = "My Mix",
            detailState = PlaylistDetailUiState(
                tracks = listOf(
                    Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                    Track(id = "2", title = "Don't Dream It's Over", artist = "Crowded House")
                )
            ),
            onLoadTracks = {},
            onPlayTrack = { _, _ -> },
            onDownloadTrack = {},
            onRemoveTrack = { _, _ -> }
        )
    }
}
