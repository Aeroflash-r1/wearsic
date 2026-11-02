package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import com.wearsic.app.ui.util.wearsicRotaryScroll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

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
    // Long-press lifts a row (400 ms, timer-driven — see
    // WearsicLongPressDrag); dragging moves it through the queue. Near the
    // top/bottom edges the list auto-scrolls on the frame clock, so any
    // position in a long queue is reachable without lifting the finger.
    //
    // AUTO-SCROLL IS ARMED BY INTENT, NOT BY POSITION. The first version
    // derived scrolling from where the lifted row's centre WAS, so grabbing a
    // row near the top or bottom of the screen (where rows naturally sit on a
    // round watch) started scrolling the instant it lifted, in the direction
    // of that edge, and at a speed proportional to how deep it already was —
    // a fast, one-directional runaway that made the whole gesture unreliable.
    // Now the lift position only sets a BASELINE: the finger must push at
    // least one more entry margin (18dp) INTO the band, in the direction it
    // is already moving, and hold there for a short dwell (~180ms) before
    // anything scrolls. Grabbing a row at the top and dragging it down never
    // scrolls up; pushing to the edge and holding does, gradually.
    //
    // The lifted row is moved by ONE offset: finger travel + an equal
    // compensation for however far the list scrolled beneath it. Without
    // that compensation the row drifts off the finger as soon as
    // auto-scroll starts; with it, the row stays pinned under the fingertip.
    //
    // THE DROP SLOT IS NOT THAT OFFSET DIVIDED BY A ROW HEIGHT. It used to be
    // (offset / 72dp, rounded), which assumes every row on screen is exactly
    // one row pitch apart. A ScalingLazyColumn shrinks rows as they approach
    // the top and bottom edges, so the real on-screen pitch there is a
    // fraction of the layout pitch — at the edges, one row of finger travel is
    // less than 72dp, the division rounded down, and the drop landed a row or
    // two away from what the user saw. Here the lifted row's CENTRE is matched
    // against the centre of each actually laid-out row and the nearest one
    // wins, so one row of travel is one row at every scale.
    val density = LocalDensity.current
    // Fallback pitch only; a measured one is taken on lift.
    val defaultRowStepPx = with(density) { 72.dp.toPx() }
    val rowSpacingPx = with(density) { 8.dp.toPx() }

    // Stable keys shared by the list AND by the drop-target lookup. The list
    // also holds the header and the transport row, so a laid-out item's own
    // `index` is not a queue index — the key is the only safe join.
    val rowKeys = remember(queue) {
        val occurrences = HashMap<String, Int>()
        queue.map { track ->
            val seen = occurrences[track.id] ?: 0
            occurrences[track.id] = seen + 1
            "${track.id}#$seen"
        }
    }
    val queueIndexByKey = remember(rowKeys) {
        HashMap<String, Int>(rowKeys.size).apply {
            rowKeys.forEachIndexed { index, key -> put(key, index) }
        }
    }

    var dragIndex by remember { mutableStateOf<Int?>(null) }
    // Viewport-space centre of the lifted row at the moment it lifted.
    var dragStartCentrePx by remember { mutableFloatStateOf(0f) }
    var dragFingerDy by remember { mutableFloatStateOf(0f) }
    var dragScrollPx by remember { mutableFloatStateOf(0f) }
    // Measured row pitch, for the parting gap of the rows that make way.
    var dragStepPx by remember { mutableFloatStateOf(0f) }
    // How far into an edge band the row already was when it lifted. Auto-scroll
    // needs to get CLEARLY deeper than this before it arms (NaN = not lifted).
    var autoScrollStartDepthPx by remember { mutableFloatStateOf(Float.NaN) }
    var autoScrollPxPerFrame by remember { mutableFloatStateOf(0f) }

    // Visual offset of the lifted row (see comment above).
    val dragOffsetPx = dragFingerDy + dragScrollPx
    val rowStepPx = if (dragStepPx > 0f) dragStepPx else defaultRowStepPx

    // The queue row whose centre is nearest `centrePx`. Reading `layoutInfo`
    // here (rather than a value computed during composition) keeps the answer
    // live: the pointer-input coroutine holds lambdas from the composition
    // that started the gesture.
    fun dropTargetFor(from: Int, centrePx: Float): Int {
        val last = queue.lastIndex
        if (last < 1) return from
        var best = from
        var bestDistance = Float.MAX_VALUE
        for (item in listState.layoutInfo.visibleItemsInfo) {
            val key = item.key as? String ?: continue
            val queueIndex = queueIndexByKey[key] ?: continue
            val distance = kotlin.math.abs(item.offset + item.size / 2f - centrePx)
            if (distance < bestDistance) {
                bestDistance = distance
                best = queueIndex
            }
        }
        // "Stay put" must stay a no-op: clamping a result of 0 up to the first
        // droppable slot would otherwise turn an untouched drag into a move.
        if (best == from) return from
        // Index 0 is the already-played tail / the Now Playing card, which has
        // no row to open a gap for — never drop into it.
        return best.coerceIn(1, last)
    }

    // The slot the lifted row will land in. Held as state and recomputed
    // imperatively (lift, movement, auto-scroll) rather than derived during
    // composition: a scroll frame that does not change the slot must not
    // recompose the whole queue.
    var dragTarget by remember { mutableIntStateOf(-1) }

    fun recomputeDragTarget() {
        val from = dragIndex ?: return
        // Centre = lift centre + finger travel. The list's scroll moves the row
        // up by exactly the compensation its translation adds back, so the two
        // cancel and the centre follows the finger.
        val to = dropTargetFor(from, dragStartCentrePx + dragFingerDy)
        if (to != dragTarget) dragTarget = to
    }

    fun resetDrag() {
        dragIndex = null
        dragTarget = -1
        dragFingerDy = 0f
        dragScrollPx = 0f
        autoScrollStartDepthPx = Float.NaN
        autoScrollPxPerFrame = 0f
    }

    /**
     * How far the finger's centre has pushed past the top or bottom band line
     * (0 when it is nowhere near an edge). The band is a fraction of the
     * viewport so it needs no dp guess and survives short viewports.
     */
    fun edgeDepthPx(): Float {
        val vp = listState.layoutInfo.viewportSize.height.toFloat()
        if (vp <= 0f) return 0f
        val band = vp * AUTO_SCROLL_BAND_FRACTION
        val centre = dragStartCentrePx + dragFingerDy
        return maxOf(band - centre, centre - (vp - band), 0f)
    }

    // Auto-scroll speed, driven by how deep the finger is inside an edge
    // band. Immediate (no dwell) and direction-agnostic: pushing into the
    // top band scrolls up, pushing into the bottom band scrolls down, and
    // leaving the band stops. A speed of 0 keeps the loop idle.
    fun updateAutoScroll() {
        val vp = listState.layoutInfo.viewportSize.height.toFloat()
        if (vp <= 0f) {
            autoScrollPxPerFrame = 0f
            return
        }
        val band = vp * AUTO_SCROLL_BAND_FRACTION
        val centre = dragStartCentrePx + dragFingerDy
        val depthUp = band - centre
        val depthDown = centre - (vp - band)
        val direction = when {
            depthUp > 0f -> -1f
            depthDown > 0f -> 1f
            else -> 0f
        }
        if (direction == 0f) {
            autoScrollPxPerFrame = 0f
            return
        }
        val entry = with(density) { AUTO_SCROLL_ENTRY.toPx() }
        val depth = if (direction < 0f) depthUp else depthDown
        if (depth <= entry) {
            autoScrollPxPerFrame = 0f
            return
        }
        // Ramp from the entry threshold to the band's far edge, then hold at a
        // brisk-but-controllable cap (4 px/frame ≈ 1.7 rows/s at watch
        // density) instead of the old 8 px/frame runaway.
        val span = (band - entry).coerceAtLeast(1f)
        val t = ((depth - entry) / span).coerceIn(0f, 1f)
        autoScrollPxPerFrame = direction * (AUTO_SCROLL_MIN_SPEED + (AUTO_SCROLL_MAX_SPEED - AUTO_SCROLL_MIN_SPEED) * t)
    }

    fun commitDrag() {
        val from = dragIndex ?: return
        // Compute the destination from LIVE state: the pointer-input coroutine
        // keeps the lambdas from the composition that started the gesture, so
        // reading a value derived during composition here would use a stale
        // index. These state reads and the layout query are always current.
        val to = dropTargetFor(from, dragStartCentrePx + dragFingerDy)
        // Drop the drag visuals BEFORE mutating the queue: the rows are keyed
        // by track, so for one frame the lifted index would otherwise point at
        // a different song than the one under the finger.
        resetDrag()
        if (to != from && to in queue.indices) onMoveItem(from, to)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Whether a row needs to make room while a drag is over it.
    fun shiftFor(index: Int): Float {
        val from = dragIndex ?: return 0f
        val to = dragTarget
        if (from == to) return 0f
        return if (from < to) {
            // moving down: the rows it passes slide up to fill the gap
            if (index in (from + 1)..to) -rowStepPx else 0f
        } else {
            // moving up: the rows it passes slide down to fill the gap
            if (index in to until from) rowStepPx else 0f
        }
    }

    // Auto-scroll loop: runs only while a row is lifted. Each frame it
    // applies the current speed (0 = idle) and re-evaluates the drop slot
    // under the moving list.
    LaunchedEffect(dragIndex) {
        if (dragIndex == null) return@LaunchedEffect
        while (isActive) {
            val speed = autoScrollPxPerFrame
            if (speed == 0f) continue
            val consumed = listState.dispatchRawDelta(speed)
            if (consumed != 0f) {
                dragScrollPx += consumed
                // The list moved under a pinned row, so the slot under it may
                // have changed.
                recomputeDragTarget()
            }
        }
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) { padding ->
        // Drag-to-reorder is handled HERE, at the screen root, not on the
        // row: a lazy list recycles rows as it scrolls, and a gesture bound
        // to a recycled row loses the pointer mid-drag (the "drops after
        // ~3 songs" bug). Root-level capture survives any amount of
        // auto-scroll.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(queue.size, rowKeys, currentIndex) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startPos = down.position
                        val slop = viewConfiguration.touchSlop * 1.5f

                        // Hold phase: any decisive movement before the hold
                        // elapses hands the gesture back (scroll / click).
                        val aborted = withTimeoutOrNull(400L) {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull true
                                if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                                if ((c.position - startPos).getDistance() > slop) {
                                    return@withTimeoutOrNull true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE")
                            true
                        }
                        if (aborted != null) return@awaitEachGesture

                        // Hold achieved: find the queue row under the finger.
                        val row = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            startPos.y >= it.offset && startPos.y <= it.offset + it.size
                        }
                        val key = row?.key as? String
                        val idx = key?.let { queueIndexByKey[it] }
                        if (idx == null || idx == currentIndex) {
                            // Header / toggles / now-playing card: not draggable.
                            return@awaitEachGesture
                        }

                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        dragStartCentrePx = row!!.offset + row.size / 2f
                        dragStepPx = (row.size + rowSpacingPx).toFloat()
                        dragIndex = idx
                        dragFingerDy = 0f
                        dragScrollPx = 0f
                        autoScrollStartDepthPx = edgeDepthPx()
                        autoScrollPxPerFrame = 0f
                        recomputeDragTarget()
                        updateAutoScroll()
                        down.consume()

                        var prevY = startPos.y
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c == null) {
                                resetDrag()
                                break
                            }
                            if (!c.pressed) {
                                c.consume()
                                commitDrag()
                                break
                            }
                            c.consume()
                            val dy = c.position.y - prevY
                            prevY = c.position.y
                            if (dy != 0f) {
                                dragFingerDy += dy
                                recomputeDragTarget()
                                updateAutoScroll()
                            }
                        }
                    }
                }
        ) {
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .wearsicEntrance()
                    .wearsicRotaryScroll(listState),
                contentPadding = wearsicListContentPadding(padding),
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
                itemsIndexed(queue, key = { index, _ -> rowKeys[index] }) { index, track ->
                    if (index == currentIndex) {
                        QueueCurrentCard(
                            track = track,
                            isPlaying = playbackState.isPlaying
                        )
                    } else {
                        val isDragged = dragIndex == index
                        val shift = shiftFor(index)
                        // Other rows part with a spring — no instant snapping.
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
                            translationY = if (isDragged) 0f else animatedShift,
                            lifted = isDragged,
                            modifier = Modifier.zIndex(if (isDragged) 2f else 0f)
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

            // Floating lifted-row preview: rendered OUTSIDE the lazy list so
            // it survives recycling while the list auto-scrolls. Follows the
            // finger exactly (lift centre + accumulated finger delta).
            val draggedIndex = dragIndex
            if (draggedIndex != null) {
                val liftedTrack = queue.getOrNull(draggedIndex)
                if (liftedTrack != null) {
                    val overlayH = with(LocalDensity.current) { 64.dp.toPx() }
                    val overlayYDp = with(LocalDensity.current) {
                        (dragStartCentrePx + dragFingerDy - overlayH / 2f).toDp()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = overlayYDp)
                            .padding(horizontal = WearsicDimens.ScreenPaddingH)
                            .height(64.dp)
                            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
                            .background(WearsicSurfaceRaised.copy(alpha = 0.96f))
                            .border(1.dp, LocalWearsicAccent.current.copy(alpha = 0.6f), RoundedCornerShape(WearsicDimens.CardRadius))
                            .zIndex(3f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(liftedTrack.artworkUrl)
                                    .size(56)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = liftedTrack.title,
                                    color = WearsicTextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = liftedTrack.artist,
                                    color = WearsicTextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
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
    modifier: Modifier = Modifier,
    translationY: Float = 0f,
    lifted: Boolean = false
) {
    // Two boxes on purpose: the OUTER one owns the drag pointer input and
    // never moves, so pointer deltas stay in a stable coordinate space. The
    // INNER box carries the visual translation/shadow of a lifted row. If
    // both lived on one node the row would move with the finger and the
    // reported deltas would collapse to nearly zero.
    Box(modifier = modifier.fillMaxWidth()) {
        WearsicSongRow(
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.translationY = translationY
                    if (lifted) {
                        shadowElevation = 18f
                        alpha = 0.98f
                    }
                },
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
}

/**
 * Auto-scroll tuning, in one place because these three numbers decide whether
 * the gesture feels controlled or runaway.
 */
/** Fraction of the viewport that counts as the top/bottom edge band. */
private const val AUTO_SCROLL_BAND_FRACTION = 0.16f
/** How far past the lift point the finger must push in before scrolling arms. */
private val AUTO_SCROLL_ENTRY = 10.dp
/** Speeds in px per frame, ramped across the band. 4px/frame ≈ 1.7 rows/s. */
private const val AUTO_SCROLL_MIN_SPEED = 1.2f
private const val AUTO_SCROLL_MAX_SPEED = 4f

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