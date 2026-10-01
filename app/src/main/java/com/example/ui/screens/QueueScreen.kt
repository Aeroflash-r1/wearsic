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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlaybackUiState
import com.example.model.Track
import com.example.ui.components.WearsicEmptyState
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.components.WearsicSongRow
import com.example.ui.components.WearsicSongRowActionButton
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicError
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
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
fun QueueScreen(
    playbackState: PlaybackUiState,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onClearQueue: () -> Unit,
    shuffleEnabled: Boolean = false,
    repeatMode: Int = 0,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    radioState: com.example.ui.viewmodel.LibraryViewModel.RadioState = com.example.ui.viewmodel.LibraryViewModel.RadioState.Idle,
    onStartRadio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    val queue = playbackState.playlist
    val currentIndex = playbackState.currentTrackIndex
    val upcoming = queue.drop(currentIndex + 1)
    var showClearConfirmation by remember { mutableStateOf(false) }

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
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Queue",
                    subtitle = "${upcoming.size} Up Next",
                )
            }

            // Shuffle / Repeat toggles (styled to match app icons)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (shuffleEnabled) WearsicVibrantLavender.copy(alpha = 0.25f)
                                else WearsicGlassFill
                            )
                            .border(
                                1.dp,
                                if (shuffleEnabled) WearsicVibrantLavender else WearsicGlassBorder,
                                CircleShape
                            )
                            .wearsicClickable(onClick = onToggleShuffle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleEnabled) WearsicVibrantLavender else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable(onClick = onStartRadio),
                        contentAlignment = Alignment.Center
                    ) {
                        if (radioState is com.example.ui.viewmodel.LibraryViewModel.RadioState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = "Radio: queue similar songs",
                                tint = WearsicTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (repeatMode != 0) WearsicVibrantLavender.copy(alpha = 0.25f)
                                else WearsicGlassFill
                            )
                            .border(
                                1.dp,
                                if (repeatMode != 0) WearsicVibrantLavender else WearsicGlassBorder,
                                CircleShape
                            )
                            .wearsicClickable(onClick = onCycleRepeat),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (repeatMode == 1) {
                                androidx.compose.material.icons.Icons.Rounded.RepeatOne
                            } else {
                                androidx.compose.material.icons.Icons.Rounded.Repeat
                            },
                            contentDescription = if (repeatMode == 1) "Repeat One" else "Repeat All",
                            tint = if (repeatMode != 0) WearsicVibrantLavender else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (queue.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "Queue is empty",
                        message = "Tap a search result to queue that whole list, or use the ⋯ menu on a track to add one song.",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic
                    )
                }
            } else {
                // Now Playing card
                item {
                    QueueCurrentCard(
                        track = queue.getOrNull(currentIndex) ?: queue.first(),
                        isPlaying = playbackState.isPlaying
                    )
                }

                // Up Next items. Keyed by ABSOLUTE queue position: relative
                // offsets shift every time the current track advances or an
                // item is removed, which would break lazy-item identity and
                // reuse (items re-animate / state is lost on each advance).
                itemsIndexed(
                    upcoming,
                    key = { offset, track -> "queue_${currentIndex + 1 + offset}_${track.id}" }
                ) { offset, _ ->
                    val absoluteIndex = currentIndex + 1 + offset
                    QueueTrackItem(
                        track = upcoming[offset],
                        onClick = { onPlayItem(absoluteIndex) },
                        onRemove = { onRemoveItem(absoluteIndex) }
                    )
                }

                // Clear Queue action
                item {
                    if (showClearConfirmation) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicError.copy(alpha = 0.2f))
                                .border(1.dp, WearsicError, CircleShape)
                                .wearsicClickable {
                                    onClearQueue()
                                    showClearConfirmation = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("confirm_clear_queue"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Confirm Clear Queue",
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
                                .wearsicClickable { showClearConfirmation = true }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("clear_queue_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Clear Queue",
                                color = WearsicTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun QueueCurrentCard(
    track: Track,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WearsicVibrantLavender)
            .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("queue_current_track")
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
                val art = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                if (!track.artworkUrl.isNullOrBlank()) {
                    val artRequest = remember(track.artworkUrl) {
                        ImageRequest.Builder(context)
                            .data(track.artworkUrl)
                            .size(112)
                            .build()
                    }
                    AsyncImage(
                        model = artRequest,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = art
                    )
                } else {
                    Box(
                        modifier = art.background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = WearsicTextPrimaryDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = track.title.ifBlank { "No Active Track" },
                        color = WearsicTextPrimaryDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isPlaying) "Now Playing" else "Paused",
                        color = if (isPlaying) WearsicTextPrimaryDark else WearsicTextPrimaryDark.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTrackItem(
    track: Track,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    WearsicSongRow(
        title = track.title,
        artist = track.artist,
        artworkUrl = track.artworkUrl,
        onClick = onClick,
        modifier = modifier,
        testTag = "queue_track_${track.id}",
        trailing = {
            WearsicSongRowActionButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Remove from Queue",
                onClick = onRemove,
                testTag = "queue_remove_${track.id}",
                tint = WearsicTextMuted
            )
        }
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun QueueScreenPreview() {
    WearsicTheme {
        QueueScreen(
            playbackState = PlaybackUiState(
                currentTrack = Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                isPlaying = true,
                currentTrackIndex = 0,
                playlist = listOf(
                    Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                    Track(id = "2", title = "Don't Dream It's Over", artist = "Crowded House"),
                    Track(id = "3", title = "Four Seasons in One Day", artist = "Crowded House")
                )
            ),
            onPlayItem = {},
            onRemoveItem = {},
            onClearQueue = {}
        )
    }
}