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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.db.DownloadState
import com.example.data.db.WearsicDownloadEntity
import com.example.model.Track
import com.example.ui.components.WearsicEmptyState
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.components.WearsicSongRow
import com.example.ui.components.WearsicSongRowActionButton
import com.example.ui.components.WearsicSongRowPlayButton
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicDimens
import com.example.ui.theme.wearsicListContentPadding
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicError
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender

import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun DownloadsScreen(
    downloads: List<WearsicDownloadEntity>,
    onPlayTrack: (Track) -> Unit,
    onDeleteDownload: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (Track) -> Unit = {},
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    var trackToDeleteId by remember { mutableStateOf<String?>(null) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }

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
            // Header
            item {
                WearsicScreenHeader(
                    title = "Downloads",
                    subtitle = "${downloads.count { it.isCompleted() }} Offline Tracks",
                )
            }

            // Empty State
            if (downloads.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "No offline tracks",
                        message = "Search & download music to listen offline without internet.",
                        icon = Icons.Rounded.Download
                    )
                }
            }

            // Download items. Only real states render: QUEUED/DOWNLOADING show
            // progress, FAILED shows retry, COMPLETED shows the playable card.
            // Anything else (legacy CANCELLED/NOT_DOWNLOADED rows that startup
            // cleanup reconciles) is skipped so it can never masquerade as a
            // normal offline download.
            items(downloads, key = { it.trackId }) { item ->
                when (item.downloadState) {
                    DownloadState.DOWNLOADING.name, DownloadState.QUEUED.name -> {
                        DownloadingItemCard(
                            entity = item,
                            onCancel = { onCancelDownload(item.trackId) }
                        )
                    }
                    DownloadState.FAILED.name -> {
                        FailedDownloadItemCard(
                            entity = item,
                            onRetry = { onRetryDownload(item.toDomainTrack()) },
                            onDelete = { onDeleteDownload(item.trackId) }
                        )
                    }
                    DownloadState.COMPLETED.name -> {
                        DownloadedTrackItemCard(
                            entity = item,
                            onPlay = { onPlayTrack(item.toDomainTrack()) },
                            onDelete = { onDeleteDownload(item.trackId) }
                        )
                    }
                    else -> {
                        // Obsolete states (e.g. CANCELLED from old builds) are
                        // hidden; startup cleanup removes or rescues them.
                    }
                }
            }

            // Clear All Button (if downloads exist)
            if (downloads.isNotEmpty()) {
                item {
                    if (showClearAllConfirmation) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicError.copy(alpha = 0.2f))
                                .border(1.dp, WearsicError, CircleShape)
                                .wearsicClickable {
                                    onClearAllDownloads()
                                    showClearAllConfirmation = false
                                }
                                .heightIn(min = WearsicDimens.TouchTarget)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("confirm_clear_all_downloads"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Confirm Clear All",
                                color = WearsicError,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicSurface)
                                .border(1.dp, WearsicSurfaceBorderSubtle, CircleShape)
                                .wearsicClickable { showClearAllConfirmation = true }
                                .heightIn(min = WearsicDimens.TouchTarget)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("clear_all_downloads_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Clear All",
                                    tint = WearsicTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Clear All Downloads",
                                    color = WearsicTextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DownloadedTrackItemCard(
    entity: WearsicDownloadEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sizeMb = if (entity.fileSizeBytes > 0) {
        String.format("%.1f MB", entity.fileSizeBytes / (1024.0 * 1024.0))
    } else {
        "Offline"
    }

    WearsicSongRow(
        title = entity.title,
        artist = "${entity.artist} • $sizeMb",
        artworkUrl = entity.artworkUrl,
        onClick = onPlay,
        modifier = modifier,
        testTag = "downloaded_track_${entity.trackId}",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WearsicSongRowPlayButton(onClick = onPlay)
                Spacer(modifier = Modifier.width(6.dp))
                WearsicSongRowActionButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = "Delete",
                    onClick = onDelete,
                    testTag = "delete_download_${entity.trackId}",
                    tint = WearsicTextMuted
                )
            }
        }
    )
}

@Composable
private fun DownloadingItemCard(
    entity: WearsicDownloadEntity,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WearsicSurface)
            .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("downloading_track_${entity.trackId}")
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(WearsicLavenderContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HourglassEmpty,
                        contentDescription = "Downloading",
                        tint = WearsicVibrantLavender,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = entity.title,
                        color = WearsicTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Downloading ${entity.progress}%",
                        color = WearsicVibrantLavender,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(WearsicSurface)
                    .border(1.dp, WearsicSurfaceBorder, CircleShape)
                    .wearsicClickable(onClick = onCancel)
                    .testTag("cancel_download_${entity.trackId}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cancel",
                    tint = WearsicTextMuted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun FailedDownloadItemCard(
    entity: WearsicDownloadEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WearsicSurface)
            .border(1.dp, WearsicError.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = "Failed",
                    tint = WearsicError,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = entity.title,
                        color = WearsicTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entity.errorMessage ?: "Download failed",
                        color = WearsicError,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Retry button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(WearsicVibrantLavender.copy(alpha = 0.15f))
                        .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.4f), CircleShape)
                        .wearsicClickable(onClick = onRetry)
                        .testTag("retry_download_${entity.trackId}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Retry",
                        tint = WearsicVibrantLavender,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Delete button
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(WearsicSurface)
                        .wearsicClickable(onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Remove",
                        tint = WearsicError,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun DownloadsScreenPreview() {
    WearsicTheme {
        DownloadsScreen(
            downloads = listOf(
                WearsicDownloadEntity(
                    trackId = "1",
                    title = "Weather with You",
                    artist = "Crowded House",
                    album = "Woodface",
                    artworkUrl = null,
                    durationMs = 6000L,
                    localFilePath = "/data/downloads/1.mp3",
                    originalStreamUrl = "https://example.com/1.mp3",
                    downloadState = DownloadState.COMPLETED.name,
                    fileSizeBytes = 3400000L
                )
            ),
            onPlayTrack = {},
            onDeleteDownload = {},
            onCancelDownload = {},
            onClearAllDownloads = {}
        )
    }
}
