package com.wearsic.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.LocalWearsicAccent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.components.WearsicSongRowActionButton
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTheme

import com.wearsic.app.ui.util.WearsicMotion
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicLongPressDrag
import com.wearsic.app.ui.util.wearsicRotaryScroll
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueScreen(
    playbackState: PlaybackUiState,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit = { _, _ -> },
    onClearQueue: () -> Unit,
    shuffleEnabled: Boolean = false,
    repeatMode: Int = 0,
    onToggleShuffle: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    radioState: com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState = com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState.Idle,
    onStartRadio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    val queue = playbackState.playlist
    val currentIndex = playbackState.currentTrackIndex
    val upcoming = queue.drop(currentIndex + 1)
    var showClearConfirmation by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // ── Drag-to-reorder ──────────────────────────────────────────────────
    // Long-press lifts a row; dragging up/down moves it through the queue.
    // Near the top/bottom edges the list auto-scrolls so the drop can be
    // placed precisely anywhere in a long queue. On release the move
    // commits. No ⋯ button — rows keep their full width for the song name.
    val dragRowHeight = 64.dp
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragTarget by remember { mutableStateOf(-1) }
    var dragDy by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var lastAutoScrollMs by remember { mutableStateOf(0L) }
    var edgeHoldSinceMs by remember { mutableStateOf(0L) }
    val density = LocalDensity.current

    fun commitDrag() {
        val from = dragIndex ?: return
        val to = dragTarget.coerceIn(1, queue.lastIndex)
        if (to != from) onMoveItem(from, to)
        dragIndex = null
        dragTarget = -1
        dragDy = 0f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Whether a row needs to make room while a drag is over it.
    fun shiftFor(index: Int): Float {
        val from = dragIndex ?: return 0f
        val to = dragTarget
        if (from == to) return 0f
        return if (from < to) {
            // moving down: items above target shift up to fill the gap
            if (index in (from + 1)..to) -with(density) { dragRowHeight.toPx() } else 0f
        } else {
            // moving up: items below target shift down to fill the gap
            if (index in to until from) with(density) { dragRowHeight.toPx() } else 0f
        }
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
                    title = "Queue",
                    subtitle = "${upcoming.size} Up Next"
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (shuffleEnabled) LocalWearsicAccent.current.copy(alpha = 0.25f)
                                else WearsicGlassFill
                            )
                            .border(
                                1.dp,
                                if (shuffleEnabled) LocalWearsicAccent.current else WearsicGlassBorder,
                                CircleShape
                            )
                            .wearsicClickable(onClick = onToggleShuffle),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = if (shuffleEnabled) "Shuffle on" else "Shuffle off",
                            tint = if (shuffleEnabled) LocalWearsicAccent.current else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable(
                                enabled = radioState !is com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState.Loading,
                                onClick = onStartRadio
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (radioState is com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState.Loading) {
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (repeatMode != 0) LocalWearsicAccent.current.copy(alpha = 0.25f)
                                else WearsicGlassFill
                            )
                            .border(
                                1.dp,
                                if (repeatMode != 0) LocalWearsicAccent.current else WearsicGlassBorder,
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
                            contentDescription = when (repeatMode) { 1 -> "Repeat One"; 2 -> "Repeat All"; else -> "Repeat off" },
                            tint = if (repeatMode != 0) LocalWearsicAccent.current else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (queue.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "Queue is empty",
                        message = "Tap a search result to queue that whole list, or add songs from any track's ⋯ menu.",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic
                    )
                }
            } else {
                // The queue is one stable-keyed list. Each row is keyed by its
                // track id PLUS an occurrence number (so duplicate tracks in
                // the queue can't collide on a key). Keys stay with the item
                // as it moves, so `animateItem` can slide reorders and fade
                // removals instead of teleporting them. Advancing to the next
                // track never remounts rows (ids don't change).
                itemsIndexed(queue, key = { index, track ->
                    val occurrence = queue.subList(0, index).count { it.id == track.id }
                    "${track.id}#$occurrence"
                }) { index, track ->
                    if (index == currentIndex) {
                        QueueCurrentCard(
                            track = track,
                            isPlaying = playbackState.isPlaying
                        )
                    } else {
                        val isDragged = dragIndex == index
                        val shift = shiftFor(index)
                        // Rows part with a spring — no instant snapping.
                        val animatedShift by animateFloatAsState(
                            targetValue = if (isDragged) 0f else shift,
                            animationSpec = WearsicMotion.Gentle,
                            label = "queueShift",
                        )
                        QueueTrackItem(
                            track = track,
                            // While a drag is in progress the release must not
                            // also fire the row click (play/remove).
                            onClick = { if (dragIndex == null) onPlayItem(index) },
                            onRemove = { if (dragIndex == null) onRemoveItem(index) },
                            modifier = Modifier
                                .then(
                                    if (isDragged) {
                                        Modifier
                                            .zIndex(2f)
                                            .graphicsLayer {
                                                translationY = dragDy
                                                shadowElevation = 16f
                                                alpha = 0.96f
                                            }
                                    } else if (shift != 0f) {
                                        Modifier.graphicsLayer {
                                            translationY = animatedShift
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                                .wearsicLongPressDrag(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragIndex = index
                                        dragTarget = index
                                        dragDy = 0f
                                    },
                                    onDrag = { deltaY, positionY ->
                                        dragDy += deltaY
                                        val rowPx = with(density) { dragRowHeight.toPx() }
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        // The pointer's Y is relative to THIS row,
                                        // not the viewport — convert to screen
                                        // space using the row's own offset, or
                                        // the edge bands (and thus auto-scroll
                                        // direction) are wrong on a round watch.
                                        val selfOffset = visible.firstOrNull { it.index == index }?.offset ?: 0
                                        val screenY = selfOffset + positionY
                                        val vp = listState.layoutInfo.viewportSize.height
                                        // Drop slot tracks whatever row is under
                                        // the finger right now (screen-space), so
                                        // while auto-scrolling the hovered row is
                                        // the target.
                                        val under = visible.firstOrNull {
                                            screenY >= it.offset && screenY <= it.offset + it.size
                                        }
                                        val target = if (under != null) {
                                            under.index.coerceIn(1, queue.lastIndex)
                                        } else {
                                            (index + (dragDy / rowPx).toInt()).coerceIn(1, queue.lastIndex)
                                        }
                                        dragTarget = target

                                        // Auto-scroll only while the finger is
                                        // parked in a REAL edge band — and only
                                        // after it has sat there a moment. A
                                        // hold anywhere else NEVER scrolls, and a
                                        // quick touch never scrolls. Direction is
                                        // driven by the band the finger is in
                                        // (up at the top, down at the bottom);
                                        // steps are a mild 1 row per tick using
                                        // scrollBy (incremental — no animation
                                        // cancel-churn).
                                        val edgePx = with(density) { 44.dp.toPx() }
                                        val atTopEdge = screenY < edgePx && visible.minByOrNull { it.index }?.index != 0
                                        val atBottomEdge = screenY > vp - edgePx && visible.maxByOrNull { it.index }?.index != queue.lastIndex
                                        val seekDir = when {
                                            atTopEdge -> -1
                                            atBottomEdge -> 1
                                            else -> 0
                                        }
                                        val nowMs = System.currentTimeMillis()
                                        if (seekDir == 0) {
                                            edgeHoldSinceMs = 0L
                                        } else {
                                            if (edgeHoldSinceMs == 0L) edgeHoldSinceMs = nowMs
                                            if (nowMs - edgeHoldSinceMs >= 160L && nowMs - lastAutoScrollMs >= 140L) {
                                                val firstVisible = visible.minByOrNull { it.index }?.index ?: 0
                                                val lastVisible = visible.maxByOrNull { it.index }?.index ?: firstVisible
                                                scope.launch {
                                                    if (seekDir < 0) {
                                                        listState.scrollToItem((firstVisible - 1).coerceAtLeast(0))
                                                    } else {
                                                        listState.scrollToItem((lastVisible + 1).coerceAtMost(queue.lastIndex))
                                                    }
                                                }
                                                lastAutoScrollMs = nowMs
                                            }
                                        }
                                    },
                                    onDragEnd = { commitDrag() },
                                    onDragCancel = {
                                        dragIndex = null
                                        dragTarget = -1
                                        dragDy = 0f
                                    },
                                )
                        )
                    }
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
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
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
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
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
            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
            .background(WearsicSurfaceRaised)
            .border(1.dp, LocalWearsicAccent.current.copy(alpha = 0.5f), RoundedCornerShape(WearsicDimens.CardRadius))
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
                    .clip(RoundedCornerShape(WearsicDimens.ArtRadius))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(WearsicDimens.ArtRadius))
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
                            tint = LocalWearsicAccent.current,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = track.title.ifBlank { "No Active Track" },
                        color = WearsicTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isPlaying) "Now Playing" else "Paused",
                        color = if (isPlaying) LocalWearsicAccent.current else WearsicTextMuted,
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
            // Only the remove action stays inline — the row keeps its full
            // width for the song name; reorder is drag-only.
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