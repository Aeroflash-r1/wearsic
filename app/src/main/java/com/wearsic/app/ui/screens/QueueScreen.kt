package com.wearsic.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.components.WearsicSongRowActionButton
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceActive
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import kotlin.math.abs
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    val defaultRowStepPx = with(density) { 72.dp.toPx() }
    val rowSpacingPx = with(density) { 8.dp.toPx() }
    val slotHysteresisPx = with(density) { 4.dp.toPx() }

    // Stable keys shared by the list AND by hit-testing / drop-target lookup.
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

    // LIVE views consumed by the root drag gesture below.
    val liveQueue by rememberUpdatedState(queue)
    val liveRowKeys by rememberUpdatedState(rowKeys)
    val liveIndexByKey by rememberUpdatedState(queueIndexByKey)
    val liveCurrentIndex by rememberUpdatedState(currentIndex)

    // Measured bounds of each visible QueueTrackItem in gesture-root coordinates.
    // ScalingLazyListItemInfo.offset is center-relative & anchor-adjusted, which
    // mis-mapped touch coordinates to the previous row; measuring boundsInRoot()
    // relative to the gesture root keeps touch hit-testing and drop targeting
    // in the exact same coordinate frame.
    val rowSlots = remember { HashMap<String, QueueRowSlot>() }
    var gestureRootTopInRoot by remember { mutableFloatStateOf(0f) }

    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragSessionId by remember { mutableIntStateOf(0) }
    var dragLastCommitted by remember { mutableStateOf(false) }
    var dragStartCentrePx by remember { mutableFloatStateOf(0f) }
    var dragFingerDy by remember { mutableFloatStateOf(0f) }
    var dragScrollPx by remember { mutableFloatStateOf(0f) }
    var dragStepPx by remember { mutableFloatStateOf(0f) }
    var dragCardHeightPx by remember { mutableFloatStateOf(0f) }
    var dragCardWidthPx by remember { mutableFloatStateOf(0f) }
    var autoScrollPxPerFrame by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(-1) }

    // Drop-settle animation state: smoothly glides the dropped card from the
    // finger release position into its destination slot without snapping.
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var settlingStartOffsetY by remember { mutableFloatStateOf(0f) }
    var settlingNonce by remember { mutableIntStateOf(0) }

    val rowStepPx = if (dragStepPx > 0f) dragStepPx else defaultRowStepPx

    fun findHitQueueItem(touchY: Float): Pair<Int, QueueRowSlot>? {
        val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
        // First pass: exact visual card bounds hit.
        for (key in visibleKeys) {
            val idx = liveIndexByKey[key] ?: continue
            if (idx == liveCurrentIndex) continue
            val slot = rowSlots[key] ?: continue
            if (touchY in slot.topY..slot.bottomY) {
                return idx to slot
            }
        }
        // Second pass: if the finger landed right in the 8dp gap between two cards,
        // pick the closest card within half a gap.
        val gapTolerance = rowSpacingPx * 0.55f
        var best: Pair<Int, QueueRowSlot>? = null
        var bestDist = gapTolerance
        for (key in visibleKeys) {
            val idx = liveIndexByKey[key] ?: continue
            if (idx == liveCurrentIndex) continue
            val slot = rowSlots[key] ?: continue
            val d = when {
                touchY < slot.topY -> slot.topY - touchY
                touchY > slot.bottomY -> touchY - slot.bottomY
                else -> 0f
            }
            if (d <= bestDist) {
                bestDist = d
                best = idx to slot
            }
        }
        return best
    }

    fun dropTargetFor(from: Int, centrePx: Float): Int {
        val last = liveQueue.lastIndex
        if (last < 0) return from
        val cur = liveCurrentIndex
        val visibleKeys = listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
        var best = from
        var bestDistance = Float.MAX_VALUE
        var currentTargetDistance = Float.MAX_VALUE
        for (key in visibleKeys) {
            val queueIndex = liveIndexByKey[key] ?: continue
            if (queueIndex == cur) continue
            // Keep upcoming tracks on the upcoming side of Now Playing (and vice versa).
            if (cur >= 0) {
                if (from > cur && queueIndex < cur) continue
                if (from < cur && queueIndex > cur) continue
            }
            val slot = rowSlots[key] ?: continue
            val distance = abs(slot.centerY - centrePx)
            if (queueIndex == dragTarget) {
                currentTargetDistance = distance
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = queueIndex
            }
        }
        if (dragTarget in liveQueue.indices &&
            dragTarget != cur &&
            best != dragTarget &&
            currentTargetDistance < Float.MAX_VALUE &&
            bestDistance + slotHysteresisPx >= currentTargetDistance
        ) {
            best = dragTarget
        }
        return best
    }

    fun recomputeDragTarget() {
        val from = dragIndex ?: return
        val to = dropTargetFor(from, dragStartCentrePx + dragFingerDy)
        if (to != dragTarget) dragTarget = to
    }

    fun resetDrag(committed: Boolean) {
        dragLastCommitted = committed
        dragIndex = null
        dragTarget = -1
        dragFingerDy = 0f
        dragScrollPx = 0f
        autoScrollPxPerFrame = 0f
    }

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
        val span = (band - entry).coerceAtLeast(1f)
        val t = ((depth - entry) / span).coerceIn(0f, 1f)
        autoScrollPxPerFrame = direction * (AUTO_SCROLL_MIN_SPEED + (AUTO_SCROLL_MAX_SPEED - AUTO_SCROLL_MIN_SPEED) * t)
    }

    fun commitDrag() {
        val from = dragIndex ?: return
        val to = dropTargetFor(from, dragStartCentrePx + dragFingerDy)
        val movedKey = liveRowKeys.getOrNull(from)
        val destKey = liveRowKeys.getOrNull(to)
        val destCentreY = destKey?.let { rowSlots[it]?.centerY } ?: dragStartCentrePx
        val releaseCentreY = dragStartCentrePx + dragFingerDy
        val residualOffsetY = releaseCentreY - destCentreY
        val didMove = to != from &&
            from in liveQueue.indices &&
            to in liveQueue.indices &&
            to != liveCurrentIndex

        if (movedKey != null) {
            settlingKey = movedKey
            settlingStartOffsetY = residualOffsetY
            settlingNonce++
        }
        resetDrag(committed = didMove)
        if (didMove) {
            onMoveItem(from, to)
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun cancelDrag() {
        val from = dragIndex ?: return
        val movedKey = liveRowKeys.getOrNull(from)
        if (movedKey != null && dragFingerDy != 0f) {
            settlingKey = movedKey
            settlingStartOffsetY = dragFingerDy
            settlingNonce++
        }
        resetDrag(committed = false)
    }

    // Whether a row needs to make room while a drag is over it.
    fun shiftFor(index: Int): Float {
        val from = dragIndex ?: return 0f
        val to = dragTarget
        if (to < 0 || from == to) return 0f
        return if (from < to) {
            if (index in (from + 1)..to) -rowStepPx else 0f
        } else {
            if (index in to until from) rowStepPx else 0f
        }
    }

    // Auto-scroll loop: runs only while a row is lifted.
    LaunchedEffect(dragIndex) {
        if (dragIndex == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val speed = autoScrollPxPerFrame
            if (speed == 0f) continue
            val consumed = listState.dispatchRawDelta(speed)
            if (consumed != 0f) {
                dragScrollPx += consumed
                recomputeDragTarget()
            }
        }
    }

    val liftProgress by animateFloatAsState(
        targetValue = if (dragIndex != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 1800f),
        label = "queueDragLift",
    )

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    gestureRootTopInRoot = coords.boundsInRoot().top
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val startPos = down.position
                        val slop = viewConfiguration.touchSlop * 1.5f

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

                        if (listState.isScrollInProgress) {
                            return@awaitEachGesture
                        }
                        val hit = findHitQueueItem(startPos.y) ?: return@awaitEachGesture
                        val (idx, slot) = hit
                        val hitKey = liveRowKeys.getOrNull(idx) ?: return@awaitEachGesture
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            it.key == hitKey
                        }
                        val unscaledH = itemInfo?.unadjustedSize?.toFloat()
                            ?.takeIf { it > 0f }
                            ?: rowSlots.values.maxOfOrNull { it.heightPx }
                            ?: slot.heightPx

                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        settlingKey = null
                        dragLastCommitted = false
                        dragSessionId++
                        dragStartCentrePx = slot.centerY
                        dragCardHeightPx = slot.heightPx
                        dragCardWidthPx = slot.widthPx
                        dragStepPx = unscaledH + rowSpacingPx
                        dragIndex = idx
                        dragTarget = idx
                        dragFingerDy = 0f
                        dragScrollPx = 0f
                        autoScrollPxPerFrame = 0f
                        updateAutoScroll()
                        down.consume()

                        var prevY = startPos.y
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id }
                            if (c == null) {
                                cancelDrag()
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
                            .size(WearsicDimens.TouchTarget)
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
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(WearsicDimens.TouchTarget)
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = "Radio: queue similar songs",
                                tint = WearsicTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(WearsicDimens.TouchTarget)
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
                            modifier = Modifier.size(18.dp)
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
                        val rowKey = rowKeys[index]
                        DisposableEffect(rowKey) {
                            onDispose { rowSlots.remove(rowKey) }
                        }

                        val isDragged = dragIndex == index
                        val isSettling = dragIndex == null && settlingKey == rowKey
                        val shiftTarget = if (isDragged) 0f else shiftFor(index)

                        val shiftAnim = remember { Animatable(0f) }
                        LaunchedEffect(dragIndex != null, dragLastCommitted, dragSessionId, shiftTarget) {
                            if (dragIndex != null) {
                                shiftAnim.animateTo(
                                    targetValue = shiftTarget,
                                    animationSpec = QueueReorderSpring,
                                )
                            } else if (dragLastCommitted) {
                                // Snap immediately so neighbors don't bounce backward after
                                // ScalingLazyColumn places them in their reordered slots.
                                shiftAnim.snapTo(0f)
                            } else if (shiftAnim.value != 0f) {
                                shiftAnim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = QueueReorderSpring,
                                )
                            }
                        }

                        val settleOffsetAnim = remember { Animatable(0f) }
                        val settleScaleAnim = remember { Animatable(1f) }
                        var appliedSettleNonce by remember { mutableIntStateOf(0) }
                        LaunchedEffect(isSettling, settlingNonce) {
                            if (isSettling && appliedSettleNonce != settlingNonce) {
                                settleOffsetAnim.snapTo(settlingStartOffsetY)
                                settleScaleAnim.snapTo(1.04f)
                                appliedSettleNonce = settlingNonce
                                val offsetJob = launch {
                                    settleOffsetAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = QueueSettleSpring,
                                    )
                                }
                                val scaleJob = launch {
                                    settleScaleAnim.animateTo(
                                        targetValue = 1f,
                                        animationSpec = QueueSettleSpring,
                                    )
                                }
                                offsetJob.join()
                                scaleJob.join()
                                if (settlingKey == rowKey) {
                                    settlingKey = null
                                }
                            } else if (!isSettling && (settleOffsetAnim.value != 0f || settleScaleAnim.value != 1f)) {
                                settleOffsetAnim.snapTo(0f)
                                settleScaleAnim.snapTo(1f)
                            }
                        }

                        val effectiveTranslationY = when {
                            isDragged -> 0f
                            dragIndex != null -> shiftAnim.value
                            isSettling && appliedSettleNonce != settlingNonce -> settlingStartOffsetY
                            isSettling -> settleOffsetAnim.value
                            !dragLastCommitted && shiftAnim.isRunning -> shiftAnim.value
                            else -> 0f
                        }
                        val effectiveScale = when {
                            isSettling && appliedSettleNonce != settlingNonce -> 1.04f
                            isSettling -> settleScaleAnim.value
                            else -> 1f
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isSettling) 2f else 0f)
                                .onGloballyPositioned { coords ->
                                    if (!coords.isAttached) return@onGloballyPositioned
                                    val b = coords.boundsInRoot()
                                    if (b.height > 1f) {
                                        rowSlots[rowKey] = QueueRowSlot(
                                            topY = b.top - gestureRootTopInRoot,
                                            bottomY = b.bottom - gestureRootTopInRoot,
                                            heightPx = b.height,
                                            widthPx = b.width,
                                        )
                                    } else {
                                        rowSlots.remove(rowKey)
                                    }
                                }
                        ) {
                            QueueTrackItem(
                                track = track,
                                // While a drag is in progress the release must not
                                // also fire the row click (play/remove).
                                onClick = { if (dragIndex == null) onPlayItem(index) },
                                onRemove = { if (dragIndex == null) onRemoveItem(index) },
                                translationY = effectiveTranslationY,
                                scale = effectiveScale,
                                hiddenForDrag = isDragged,
                            )
                        }
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
            // it survives recycling while the list auto-scrolls, hides the
            // underlying slot cleanly so adjacent cards never pass behind a
            // dark duplicate, and follows the fingertip at 1:1.
            val draggedIndex = dragIndex
            if (draggedIndex != null) {
                val liftedTrack = queue.getOrNull(draggedIndex)
                if (liftedTrack != null) {
                    val context = LocalContext.current
                    val accent = LocalWearsicAccent.current
                    val fallbackHeightPx = with(density) { watchScaled(64.dp).toPx() }
                    val overlayHeightPx = if (dragCardHeightPx > 0f) dragCardHeightPx else fallbackHeightPx
                    val overlayYDp = with(density) {
                        (dragStartCentrePx + dragFingerDy - overlayHeightPx / 2f).toDp()
                    }
                    val overlayHeightDp = with(density) { overlayHeightPx.toDp() }
                    val widthModifier = if (dragCardWidthPx > 0f) {
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(with(density) { dragCardWidthPx.toDp() })
                    } else {
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = WearsicDimens.ScreenPaddingH)
                    }
                    val cardShape = RoundedCornerShape(WearsicDimens.CardRadius)
                    val liftScale = 1f + 0.04f * liftProgress
                    val liftedArtRequest = remember(liftedTrack.artworkUrl) {
                        ImageRequest.Builder(context)
                            .data(liftedTrack.artworkUrl)
                            .size(96)
                            .crossfade(false)
                            .build()
                    }
                    Box(
                        modifier = widthModifier
                            .offset(y = overlayYDp)
                            .height(overlayHeightDp)
                            .zIndex(3f)
                            .graphicsLayer {
                                scaleX = liftScale
                                scaleY = liftScale
                            }
                            .clip(cardShape)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        WearsicSurfaceActive,
                                        WearsicSurfaceRaised,
                                    )
                                )
                            )
                            .background(accent.copy(alpha = 0.12f * liftProgress))
                            .border(
                                width = 1.5.dp,
                                color = accent.copy(alpha = 0.45f + 0.45f * liftProgress),
                                shape = cardShape,
                            )
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("queue_drag_floating_card"),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                val artShape = RoundedCornerShape(WearsicDimens.ArtRadius)
                                if (!liftedTrack.artworkUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = liftedArtRequest,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(watchScaled(WearsicDimens.RowThumbnail))
                                            .clip(artShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(watchScaled(WearsicDimens.RowThumbnail))
                                            .clip(artShape)
                                            .background(accent.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = liftedTrack.title,
                                        color = WearsicTextPrimary,
                                        fontFamily = WearsicDisplayFont,
                                        fontSize = 14.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = liftedTrack.artist,
                                        color = WearsicTextMuted,
                                        fontFamily = WearsicBodyFont,
                                        fontSize = 12.sp,
                                        lineHeight = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Rounded.DragHandle,
                                contentDescription = null,
                                tint = accent.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .size(20.dp),
                            )
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
    val cardShape = RoundedCornerShape(WearsicDimens.CardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Brush.verticalGradient(listOf(WearsicSurfaceRaised, WearsicSurface)))
            .border(1.dp, LocalWearsicAccent.current.copy(alpha = 0.5f), cardShape)
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
                            .size(96)
                            .crossfade(false)
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
                        fontFamily = WearsicDisplayFont,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isPlaying) "Now Playing" else "Paused",
                        color = if (isPlaying) LocalWearsicAccent.current else WearsicTextMuted,
                        fontFamily = WearsicBodyFont,
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
    scale: Float = 1f,
    hiddenForDrag: Boolean = false,
) {
    // Keep the outer wrapper fixed for hit-testing/slot measurement; animate
    // translation/scale on the inner card and hide it cleanly while lifted so
    // shifting neighbors never slide behind an opaque ghost card.
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
                    this.scaleX = scale
                    this.scaleY = scale
                    this.alpha = if (hiddenForDrag) 0f else 1f
                },
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
}

private data class QueueRowSlot(
    val topY: Float,
    val bottomY: Float,
    val heightPx: Float,
    val widthPx: Float,
) {
    val centerY: Float get() = (topY + bottomY) * 0.5f
}

private val QueueReorderSpring = spring<Float>(dampingRatio = 0.84f, stiffness = 1200f)
private val QueueSettleSpring = spring<Float>(dampingRatio = 0.80f, stiffness = 1500f)

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