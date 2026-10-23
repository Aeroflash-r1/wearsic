package com.wearsic.app.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
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
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicLibraryTrackRow
import com.wearsic.app.ui.components.WearsicLoadingState
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicBlack
import com.wearsic.app.ui.theme.WearsicLavenderContainer
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextSecondary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState

import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

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
    onToggleFavorite: (Track) -> Unit = {},
    favoriteTrackIds: Set<String> = emptySet(),
    playlists: List<com.wearsic.app.model.Playlist> = emptyList(),
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadAllTracks: (List<Track>) -> Unit = {},
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
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = playlistName,
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

            if (detailState.tracks.isNotEmpty() && !detailState.isLoading) {
                item {
                    // "Download all" — one tap to pull the whole album/playlist
                    // offline. Idempotent: tracks already saved are skipped.
                    val notDownloaded = detailState.tracks.count { it.id !in downloadedTrackIds }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicVibrantLavender.copy(alpha = 0.16f))
                            .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.45f), CircleShape)
                            .wearsicClickable {
                                if (notDownloaded > 0) {
                                    onDownloadAllTracks(detailState.tracks)
                                }
                            }
                            .heightIn(min = WearsicDimens.TouchTarget)
                            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 9.dp)
                            .testTag("playlist_download_all"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                tint = if (notDownloaded > 0) {
                                    WearsicVibrantLavender
                                } else {
                                    WearsicTextMuted
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    notDownloaded == 0 -> "All songs saved offline"
                                    notDownloaded == detailState.tracks.size -> "Download all (${notDownloaded})"
                                    else -> "Download remaining (${notDownloaded})"
                                },
                                color = if (notDownloaded > 0) {
                                    WearsicTextPrimary
                                } else {
                                    WearsicTextMuted
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            itemsIndexed(detailState.tracks, key = { _, track -> track.id }) { index, track ->
                WearsicLibraryTrackRow(
                    track = track,
                    onPlay = { onPlayTrack(detailState.tracks, index) },
                    onLongPress = { actionTrack = track },
                    onMore = { actionTrack = track },
                    isDownloaded = track.id in downloadedTrackIds,
                    testTagPrefix = "playlist_track"
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        actionTrack?.let { t ->
            com.wearsic.app.ui.components.WearsicTrackActionSheet(
                track = t,
                playlists = playlists,
                onDismiss = { actionTrack = null },
                onPlay = { onPlayTrack(detailState.tracks, detailState.tracks.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) },
                onQueue = { onQueue(t) },
                onDownload = { onDownloadTrack(t) },
                onAddToPlaylist = { pid -> onAddToPlaylist(pid, t) },
                onCreatePlaylistAndAdd = { name -> onCreatePlaylistAndAdd(name, t) },
                onRemove = { onRemoveTrack(playlistId, t.id) },
                removeLabel = "Remove from playlist",
                onToggleFavorite = { onToggleFavorite(t) },
                isFavorite = t.id in favoriteTrackIds
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
