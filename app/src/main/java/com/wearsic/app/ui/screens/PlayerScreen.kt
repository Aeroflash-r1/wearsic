package com.wearsic.app.ui.screens

import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.wear.compose.material3.CircularProgressIndicator
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.viewmodel.LyricsViewModel
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.wearsic.app.ui.components.WearsicMarqueeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicBlack
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicSurfaceBorder
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.ArtworkColors
import com.wearsic.app.ui.util.LocalRotaryDirectionInverted
import com.wearsic.app.ui.util.extractArtworkColors
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NOW PLAYING — composed for a ROUND Wear OS face (Galaxy Watch7 44mm).
 *
 * Layout rules that only make sense on a circle:
 *   · The widest part of the display is its MIDDLE, so the album art disc —
 *     not the title and not the transport — owns the centre. Title, artist and
 *     elapsed time then sit in the readable mid-band below it, and the compact
 *     transport hugs the lower third where the remaining chord is still wide
 *     enough for it. Nothing important is placed in the clipped top or the
 *     curved bottom edge.
 *   · Everything is measured from the space actually available
 *     ([BoxWithConstraints]) and switches to a compact scale on short
 *     viewports, so the composition can never clip or push a control off-face.
 *   · The backdrop is still the artwork (immersive), darkened behind the disc
 *     with a fixed scrim plus a cheap radial tint — no blur shaders, which the
 *     watch's SoC cannot afford.
 *   · Playback progress is a ring AROUND the disc, which suits a circular
 *     screen far better than a ring around the play button, and is drawn in
 *     the draw phase only: the 2 s position sweep never recomposes the screen.
 *   · Actions live on a swipeable second page (Wearsic sub-screen), reachable
 *     by swipe or by the one tappable page indicator.
 *
 * Motion is deliberate and finite: a track change plays one short entrance
 * (art scales up, text rises, staggered), the play/pause glyph pops in on
 * every change, and nothing animates while you simply listen. The rotary
 * bezel is UNUSED on Now Playing (the crown stays with lists).
 */
@Composable
fun PlayerScreen(
    playbackState: PlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekForward: () -> Unit = {},
    onSeekBack: () -> Unit = {},
    onToggleFavorite: () -> Unit,
    onNavigateToVolume: () -> Unit,
    onNavigateToQueue: () -> Unit = {},
    onDownloadTrack: (Track) -> Unit = {},
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    downloadProgress: Int = 0,
    /**
     * Hoists the extracted artwork palette (used by the dynamic-accent
     * feature). When null the palette stays internal to the player.
     */
    onArtworkColor: ((ArtworkColors) -> Unit)? = null,
    /** Whether the album-art disc rotates slowly with the song (setting). */
    rotateArtwork: Boolean = true,
    /** Drag the disc along the ring to scrub to a position (ms). */
    onSeekTo: (Long) -> Unit = {},
    /** Hook to save the current artwork accent as the app colour. */
    onSaveArtworkAccent: ((Color) -> Unit)? = null,
    /**
     * Tap on the disc = "open the album for this song". Null disables the
     * tap (a plain artwork disc) without touching the ring-drag scrub.
     */
    onOpenAlbum: ((Track) -> Unit)? = null,
    /** Lyrics sheet state (scroll-up reveal on the Now Playing page). */
    lyricsState: LyricsViewModel.LyricsUiState = LyricsViewModel.LyricsUiState(),
    /** Fired the first time the sheet is revealed for a track. */
    onRequestLyrics: () -> Unit = {},
    /** Retry after a failed load / unavailable state. */
    onRetryLyrics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val track = playbackState.currentTrack
    val hasTrack = track.id.isNotBlank()
    val haptic = LocalHapticFeedback.current
    var artworkColors by remember(track.artworkUrl) { mutableStateOf(ArtworkColors.Default) }
    val scope = rememberCoroutineScope()

    val progressTarget = if (playbackState.durationMs > 0L) {
        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Discrete 2 s position ticks are turned into a continuous sweep by a
    // linear tween of the same length. Held as State + read in the draw phase.
    val animatedProgress = key(track.id) {
        animateFloatAsState(
            targetValue = progressTarget,
            animationSpec = tween(
                durationMillis = if (playbackState.isPlaying && !playbackState.isBuffering) 2000 else 0,
                easing = LinearEasing
            ),
            label = "playerProgress"
        )
    }

    Box(modifier = modifier.fillMaxSize()) {

        // ── The artwork IS the background ────────────────────────────────
        ArtworkBackdrop(
            artworkUrl = track.artworkUrl,
            glowColor = artworkColors.accent,
            onColors = {
                artworkColors = it
                onArtworkColor?.invoke(it)
            }
        )

        val pagerState = rememberPagerState(pageCount = { 2 })

        // The page dots flip THROUGH THIS MIRROR, not through the pager's
        // own currentPage/targetPage reads: those can lag the real scroll
        // position (Robolectric and certain devices report a settled page
        // as current while the offset is elsewhere), which made a flip
        // compute "already there" and no-op. pageIndex is the single source
        // of truth, kept in sync with the pager by snapshotFlow.
        var pageIndex by remember { mutableIntStateOf(0) }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }.collect { pageIndex = it }
        }

        // No rotary owner on Now Playing: the bezel should do NOTHING here
        // (user: "doesn't need touch bezel scroll in nowplaying screen").
        // The Actions list owns the bezel when page 1 is visible; lyrics own
        // it while open. Once a node holds focus, rotary events bubble up
        // from it — with no focus target below, no scroll fires.

        // Natural screen-open: a soft zoom + fade across ~220ms. Runs once
        // per entry, layer-only (no recomposition), so it reads as a smooth,
        // reliable push into the player.
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            opened = true
        }
        val openProgress by animateFloatAsState(
            targetValue = if (opened) 1f else 0f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "playerOpen"
        )


        // ── LYRICS (hoisted) ─────────────────────────────────────────────
        // The fullscreen sheets must cover the WHOLE display — including the
        // content padding the pages live in — so its state lives HERE, above
        // the pager; the sheet itself renders as the last child of this Box,
        // covering the page dots while open.
        var lyricsMode by remember(track.id) { mutableStateOf(false) }

        val lyricsScrollState = rememberScrollState()
        val currentPos by rememberUpdatedState(playbackState.currentPositionMs)
        val currentPlaying by rememberUpdatedState(playbackState.isPlaying)
        var lyricsActiveIndex by remember(track.id) { mutableIntStateOf(-1) }
        var lyricAnchorPos by remember(track.id) { mutableLongStateOf(0L) }
        var lyricAnchorAtMs by remember(track.id) { mutableLongStateOf(0L) }
        var lyricLastEmitted by remember(track.id) { mutableLongStateOf(Long.MIN_VALUE) }
        var lyricPrevPlaying by remember(track.id) { mutableStateOf(false) }
        val lyricLinesSynced = lyricsState.mode == LyricsMode.Synced &&
            lyricsState.lines.isNotEmpty()
        LaunchedEffect(lyricsMode, track.id, lyricLinesSynced) {
            if (!lyricsMode || !lyricLinesSynced) {
                lyricsActiveIndex = -1
                return@LaunchedEffect
            }
            // Position is emitted only every ~1-2s; interpolate between
            // samples with the wall clock. Every emission (seek, pause,
            // play) re-anchors, so drift can never accumulate. While paused
            // the highlight simply follows the last emitted position.
            while (isActive) {
                val emitted = currentPos
                val now = SystemClock.elapsedRealtime()
                if (emitted != lyricLastEmitted) {
                    lyricLastEmitted = emitted
                    lyricAnchorPos = emitted
                    lyricAnchorAtMs = now
                }
                if (currentPlaying && !lyricPrevPlaying) {
                    lyricAnchorPos = emitted
                    lyricAnchorAtMs = now
                }
                lyricPrevPlaying = currentPlaying
                val pos = if (currentPlaying) lyricAnchorPos + (now - lyricAnchorAtMs) else emitted
                val idx = lyricsState.lines
                    .indexOfLast { it.timeMs != null && it.timeMs <= pos }.coerceAtLeast(0)
                if (idx != lyricsActiveIndex) lyricsActiveIndex = idx
                // Fast cadence while playing (a line can be as short as a
                // second); paused playback only re-anchors on emission, so
                // the poll can coast — idle/heat win.
                delay(if (currentPlaying) 150L else 600L)
            }
        }

        // The whole player sits inside a layer that animates the open.
        Box(modifier = Modifier.fillMaxSize().graphicsLayer {
            val p = openProgress
            val s = 0.96f + 0.04f * p
            scaleX = s
            scaleY = s
            alpha = 0.7f + 0.3f * p
        }) {

                ScreenScaffold(modifier = Modifier.fillMaxSize()) { contentPadding: PaddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> NowPlayingPage(
                            track = track,
                            hasTrack = hasTrack,
                            isPlaying = playbackState.isPlaying,
                            isBuffering = playbackState.isBuffering,
                            progress = animatedProgress,
                            positionMs = playbackState.currentPositionMs,
                            durationMs = playbackState.durationMs,
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            onSeekForward = onSeekForward,
                            onSeekBack = onSeekBack,
                            onSeekTo = onSeekTo,
                            haptic = haptic,
                            colors = artworkColors,
                            active = pagerState.currentPage == 0,
                            rotateArtwork = rotateArtwork,
                            onOpenAlbum = onOpenAlbum,
                            onOpenLyrics = { if (hasTrack) lyricsMode = true }
                        )
                        else -> ActionsPage(
                            hasTrack = hasTrack,
                            isFavorite = track.isFavorite,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            downloadProgress = downloadProgress,
                            onToggleFavorite = onToggleFavorite,
                            onDownloadTrack = { onDownloadTrack(track) },
                            onNavigateToQueue = onNavigateToQueue,
                            onNavigateToVolume = onNavigateToVolume,
                            active = pagerState.currentPage == 1,
                            artworkAccent = if (artworkColors.fromArtwork) artworkColors.accent else Color.Transparent,
                            onSaveAccent = onSaveArtworkAccent
                        )
                    }
                }

            }
        }
        } // end animated open wrapper

        // Page dots hug the VERY EDGE of the round face — outside the
        // scaffold's inset so they follow the display's curved boundary. The
        // 48dp touch target spans inward from the edge while the dots
        // themselves align to the outer edge (End), so they sit right on the
        // bezel instead of floating mid-screen. They vanish while the lyrics
        // sheet is over the screen.
        if (!lyricsMode) Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(min = WearsicDimens.TouchTarget)
                .semantics {
                    contentDescription =
                        if (pageIndex == 0) "Show player actions" else "Show now playing"
                }
                .wearsicClickable {
                    scope.launch {
                        // Flip from the MIRROR (see pageIndex above): the
                        // pager's own reads can lag the settled page and
                        // make this a no-op. scrollToPage snaps instantly —
                        // deterministic, and a crisp toggle on a watch.
                        pagerState.scrollToPage((pageIndex + 1) % 2)
                    }
                }
                .testTag("player_page_switch"),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.End
        ) {
            repeat(2) { index ->
                val active = pageIndex == index
                Box(
                    modifier = Modifier
                        .padding(end = 3.dp)
                        .size(width = 4.dp, height = if (active) 11.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) artworkColors.accent else Color.White.copy(alpha = 0.32f)
                        )
                )
            }
        }

        // LYRICS — THE LAST CHILD: drawn OVER the pager AND the page dots,
        // edge-to-edge with no content padding, so it reads as a true
        // fullscreen page, not a boxed card in the middle.
        if (lyricsMode && hasTrack) {
            LaunchedEffect(track.id) { onRequestLyrics() }
            LyricsFullscreen(
                track = track,
                lyricsState = lyricsState,
                activeIndex = lyricsActiveIndex,
                scrollState = lyricsScrollState,
                colors = artworkColors,
                onExit = { lyricsMode = false },
                onRetry = onRetryLyrics,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Page 0 — Now Playing: artwork disc, metadata, transport — and a pull-up
// lyrics view that LOCKS into a TRULY fullscreen page. The sheet is rendered
// as the LAST child of PlayerScreen's root Box, so it covers the pager, the
// page dots AND the scaffold's content padding edge-to-edge.
// ─────────────────────────────────────────────────────────────────────────

/** Upward travel (px) that opens the lyrics sheet. */
private const val LYRICS_UP_DRAG_PX = 120f
/** Downward travel (px) near the top of the sheet that returns to the player. */
private const val LYRICS_EXIT_DRAG_PX = 70f

/** Vertical gap between lyric rows (rows are natural-height, padding-based). */
internal val LYRICS_ROW_GAP = 6.dp

/**
 * Auto-center scroll target for the active lyric row, computed from the row's
 * MEASURED center (onGloballyPositioned) instead of uniform-pitch arithmetic.
 * Pure so unit tests can pin it without a composable: returns the scroll value
 * that moves [rowCenterY] onto [anchorY], clamped to the scrollable range.
 */
internal fun lyricsAutoCenterTarget(
    currentValue: Int,
    maxValue: Int,
    rowCenterY: Float,
    anchorY: Float,
): Int = (currentValue + rowCenterY - anchorY).roundToInt().coerceIn(0, maxValue)

@Composable
private fun NowPlayingPage(
    track: Track,
    hasTrack: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekTo: (Long) -> Unit = {},
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    colors: ArtworkColors,
    active: Boolean,
    rotateArtwork: Boolean = true,
    onOpenAlbum: ((Track) -> Unit)? = null,
    onOpenLyrics: () -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().testTag("nowplaying_page_root")) {
        val compact = maxHeight < 236.dp
        // Slightly smaller discs: the lyrics pill now lives in the mid-band
        // below the subtitle, and the whole fixed stack must leave the two
        // spacer weights alive on the tightest faces — borrowing a little
        // disc diameter is the cheapest guarantee that NOTHING clips.
        val discSize: Dp = (maxWidth * 0.34f).coerceIn(if (compact) 52.dp else 64.dp, if (compact) 64.dp else 80.dp)
        val playSize: Dp = if (compact) 48.dp else (maxWidth * 0.26f).coerceIn(50.dp, 60.dp)

        // ── Player block (full viewport; no outer scroll) ────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = WearsicDimens.ScreenPaddingH)
                .pointerInput(hasTrack, track.id) {
                    // Pull UP to lock into lyrics. Runs on the INITIAL (tunnel)
                    // pass so it sees drags BEFORE the disc's ring-scrub
                    // consumes them — opening lyrics works everywhere on this
                    // page, disc included. The accumulator only counts upward
                    // travel, so a circular ring-scrub (up-and-down in
                    // alternation) resets instead of drifting to a threshold.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var total = 0f
                        var last = down.position.y
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val dy = c.position.y - last
                            last = c.position.y
                            total = if (dy < 0f) total + dy else 0f
                            if (total < -LYRICS_UP_DRAG_PX) {
                                c.consume()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onOpenLyrics()
                                break
                            }
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.7f))

            key(track.id) {
            ArtworkDisc(
                artworkUrl = track.artworkUrl,
                hasTrack = hasTrack,
                progress = progress,
                colors = colors,
                size = discSize,
                rotateArtwork = rotateArtwork,
                onClick = onOpenAlbum?.let { { it(track) } },
                // Drag the disc along its ring to scrub: angular position
                // maps to the track duration (top = 0, clockwise).
                modifier = Modifier
                    .wearsicEntrance(fromScale = 0.90f, riseDp = 10f)
                    .pointerInput(hasTrack, track.id, durationMs) {
                        if (!hasTrack || durationMs <= 0L) return@pointerInput
                        detectDragGestures(
                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            onDrag = { change, _ ->
                                change.consume()
                                val sizePx = size.width.toFloat()
                                val cx = sizePx / 2f
                                val cy = size.height.toFloat() / 2f
                                val dx = change.position.x - cx
                                val dy = change.position.y - cy
                                val rad = kotlin.math.atan2(dy, dx)
                                // -90° (up) = 0: clockwise fraction.
                                val frac = ((rad * 180f / kotlin.math.PI.toFloat()) + 90f + 360f) % 360f / 360f
                                onSeekTo((durationMs * frac).toLong().coerceIn(0L, durationMs))
                            }
                        )
                    }
            )
            }

            Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

            // Single line, always. A long title used to wrap to two lines,
            // which pushed the artist line down and clipped at the curved
            // edge; now the full name slides sideways instead.
            WearsicMarqueeText(
                text = if (hasTrack) track.title else "Nothing playing",
                color = Color.White,
                fontSize = if (compact) 13.sp else 15.sp,
                lineHeight = if (compact) 15.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.2).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .wearsicEntrance(delayMillis = 50, fromScale = 0.98f, riseDp = 6f)
            )

            // One line: artist · elapsed/total. The time segment is dropped
            // when the server reported no duration — "Artist · 1:04 / --:--"
            // read as noise — and a blank artist never leaves a dangling "·".
            val subtitle = when {
                !hasTrack -> "Pick a song from Library"
                else -> listOf(
                    track.artist.trim(),
                    if (durationMs > 0L) "${formatTime(positionMs)} / ${formatTime(durationMs)}" else ""
                ).filter { it.isNotBlank() }.joinToString(" · ")
            }

            // The only lyrics affordance on Now Playing: a small, quiet icon —
            // no text, no pill, no bubble. Pull-up still works too.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .wearsicEntrance(delayMillis = 90, fromScale = 0.98f, riseDp = 6f)
            ) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 3.dp),
                )
                if (hasTrack) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .padding(start = 2.dp)
                            .wearsicClickable(onClick = onOpenLyrics)
                            .testTag("lyrics_open_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "Open Lyrics",
                            tint = if (colors.fromArtwork) colors.accent
                            else Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            TransportRow(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                progress = progress,
                playSize = playSize,
                onTogglePlayPause = onTogglePlayPause,
                onSkipPrevious = onSkipPrevious,
                onSkipNext = onSkipNext,
                colors = colors,
                enabled = hasTrack
            )

            Spacer(modifier = Modifier.height(if (compact) 10.dp else 12.dp))
        }
    }
}

/**
 * THE fullscreen lyrics page. The active line is auto-centered by MEASURED
 * position (onGloballyPositioned) at 60% of the body, so wrapped lines stay
 * fully visible below the header band. Manual scrolling (touch, bezel)
 * always wins: auto-centering pauses while any scroll is in progress, and
 * resumes when the list settles. This composable is internal so the
 * Roborazzi gallery can render the real page.
 */
@Composable
internal fun LyricsFullscreen(
    track: Track,
    lyricsState: LyricsViewModel.LyricsUiState,
    activeIndex: Int,
    scrollState: androidx.compose.foundation.ScrollState,
    colors: ArtworkColors,
    onExit: () -> Unit,
    onRetry: () -> Unit,
) {
    val backdrop = if (colors.fromArtwork) {
        lerp(colors.backdrop, colors.accentStrong, 0.12f)
    } else {
        colors.backdrop
    }
    val activeColor = if (colors.fromArtwork) {
        lerp(colors.accent, Color.White, 0.10f)
    } else {
        Color.White
    }
    val synced = lyricsState.mode == com.wearsic.app.model.LyricsMode.Synced &&
        lyricsState.lines.isNotEmpty()
    val density = LocalDensity.current

    // Framework rotary owns the crown while the lyrics are open: the notch
    // scrolls this body (framework routing, old-style haptics) exactly like
    // a built-in app. Taking focus here also blocks the bezel from reaching
    // the Actions list behind the sheet.
    val lyricsRotaryFocus = remember { FocusRequester() }
    LaunchedEffect(lyricsRotaryFocus) {
        repeat(5) {
            if (runCatching { lyricsRotaryFocus.requestFocus() }.getOrNull() == true) {
                return@LaunchedEffect
            }
            delay(40)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdrop)
            .testTag("lyrics_screen"),
    ) {
        // Soft accent glow from the top edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(colors.accent.copy(alpha = 0.14f), Color.Transparent),
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Compact header: "Lyrics" + exit affordance ONLY. The song
            // name/artist context line was removed — every pixel above the
            // body is a pixel taken from lyric space, and the player screen
            // already identifies the track (user request, 1.9.45). The title
            // is centered over the lyric column (which centers its lines) so
            // the header reads balanced; the chevron keeps its 30dp touch
            // target at the trailing edge, still inside the top chord.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Lyrics",
                    color = if (colors.fromArtwork) colors.accent else Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
                // Exit affordance: a small, bare chevron — no bubble, no
                // border (the translucent circle read as UI noise on the
                // full-white lyrics canvas). The 30dp invisible touch target
                // keeps the tap reliable on a sweaty wrist.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(30.dp)
                        .wearsicClickable(onClick = onExit)
                        .testTag("lyrics_back_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Back to Now Playing",
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // No source attribution line: on a 454px round face it pushed
            // into the body and read as an edge-cut "lyrics by …" label.
            // The "Lyrics" title is all this header needs.

            // The lyrics body — a BoxWithConstraints so centre math uses the
            // REAL body height, not the whole screen.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val bodyPx = with(density) { maxHeight.toPx() }
                val halfBodyPx = bodyPx / 2f

                // Viewport-center Y of the ACTIVE row, reported by its
                // onGloballyPositioned on every layout (only the active row
                // carries the observer; equal-value writes are free).
                // ROOT coordinates — the anchor below must share this frame.
                // EPOCH tags the value with the row index that wrote it: the
                // auto-center below must anchor the CURRENTLY-active row, and
                // the previous row's leftover value is NOT NaN (it is simply
                // last), so timing by NaN alone let every line change anchor
                // against the PREVIOUS row's stale position — the highlight
                // drifted lower line after line until it left the visible
                // band (real-device report), and cold opens could center a
                // row that was not singing at all.
                var activeRowCenterY by remember { mutableFloatStateOf(Float.NaN) }
                var activeRowHeightPx by remember { mutableFloatStateOf(0f) }
                var activeRowEpoch by remember { mutableIntStateOf(-1) }

                // Root-space Y where the body begins (i.e. directly below the
                // header). Coordinates only compare correctly within one
                // frame of reference: the first 1.9.44 fix anchored the row
                // center at 60% of the BODY measured in ROOT coords, which
                // silently parked tall wrapped lines up under the header
                // ("lengthy sentence still hidden by the above bar").
                // Anchoring at bodyTop + 0.60 × bodyHeight in the SAME
                // root frame fixes the reference mismatch exactly.
                var bodyTopInRoot by remember { mutableFloatStateOf(0f) }

                // Auto-center the active line, MEASURED, not pitch-math'd:
                // row heights vary with wrapping (active lines grow), so the
                // old uniform-pitch arithmetic drifted. Event-triggered —
                // runs only on active-line change. Manual scroll always
                // wins; the NEXT line change re-centres.
                LaunchedEffect(activeIndex, synced, track.id) {
                    if (!synced || activeIndex < 0) {
                        return@LaunchedEffect
                    }
                    // Two attempts per activation: cold entries sometimes miss
                    // the first measurement window entirely.
                    var attempt = 0
                    while (attempt < 2) {
                        attempt++
                        // Let any in-flight scroll finish BEFORE measuring —
                        // activating mid-animation is normal (fast lines); the
                        // old one-shot skip meant those activations never
                        // re-anchored at all. Capped, so a continuous manual
                        // drag is never fought longer than ~1.5s.
                        var settleFrames = 0
                        while (scrollState.isScrollInProgress && settleFrames < 90) {
                            withFrameNanos { }
                            settleFrames++
                        }
                        // Wait until the CURRENTLY-active row itself reports
                        // (epoch match), capped at ~12 frames.
                        var measureFrames = 0
                        while (activeRowEpoch != activeIndex && measureFrames < 12) {
                            withFrameNanos { }
                            measureFrames++
                        }
                        val rowY = activeRowCenterY
                        if (bodyTopInRoot <= 0f || activeRowEpoch != activeIndex || rowY.isNaN()) {
                            if (attempt == 1) {
                                delay(300)
                                continue
                            }
                            return@LaunchedEffect
                        }
                        // Park the active row a little BELOW the body midline: on
                        // the round face the header zone above can cover the top
                        // of a tall highlighted row when centered exactly at 50%
                        // (real-device report: "upper part hidden by the lyrics UI
                        // design"). 60% keeps the whole highlight visible.
                        val anchorY = bodyTopInRoot + bodyPx * 0.60f
                        val centerTarget = lyricsAutoCenterTarget(
                            currentValue = scrollState.value,
                            maxValue = scrollState.maxValue,
                            rowCenterY = rowY,
                            anchorY = anchorY,
                        )
                        // Hard clearance guarantee: the highlight's TOP must land
                        // below the body's top edge. Centering alone can't do
                        // that for the tallest (3-line) rows if the body is
                        // short — re-anchor so the row TOP sits at the clearance
                        // line instead. (8dp margin below the header band.)
                        // min(): healthy rows keep the 60% center; an oversize
                        // row gets pushed DOWN until its top just clears.
                        val clearanceTop = bodyTopInRoot +
                            with(density) { 8.dp.toPx() }
                        val minTopTarget = lyricsAutoCenterTarget(
                            currentValue = scrollState.value,
                            maxValue = scrollState.maxValue,
                            rowCenterY = rowY,
                            anchorY = clearanceTop + activeRowHeightPx / 2f,
                        )
                        val target = minOf(centerTarget, minTopTarget)
                        if (target != scrollState.value) {
                            // Defensive: a racing manual scroll/disposal must
                            // never crash out of the scroll math (real-device
                            // reports of a crash while scrolling during load).
                            runCatching {
                                scrollState.animateScrollTo(
                                    target,
                                    tween(340, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                        return@LaunchedEffect
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 38.dp)
                        .onGloballyPositioned { c ->
                            // Root-space top of the padded body = where the
                            // lyric content becomes visible below the header.
                            bodyTopInRoot = c.boundsInRoot().top
                        }
                        // Notches scroll this ScrollState through the
                        // framework's rotary handler — replaces the hand-rolled
                        // onRotaryScrollEvent dispatcher that intermittently
                        // left the crown dead ("haptic tick, no scroll").
                        // Haptic step-ticks stay ON (user: feedback was back);
                        // direction follows the Settings preference, aligned
                        // with touch scrolling by default.
                        .rotaryScrollable(
                            RotaryScrollableDefaults.behavior(
                                scrollState,
                                null,
                                true,
                            ),
                            lyricsRotaryFocus,
                            LocalRotaryDirectionInverted.current,
                        )
                        .pointerInput(track.id) {
                            // Pull DOWN near the very top of the lyrics →
                            // back to the player. Tunnel pass + own
                            // consumption makes this deterministic even
                            // while the scrollable is listening.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                var total = 0f
                                var last = down.position.y
                                while (true) {
                                    val e = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    val dy = c.position.y - last
                                    last = c.position.y
                                    total = if (dy > 0f) total + dy else 0f
                                    if (total > LYRICS_EXIT_DRAG_PX && scrollState.value <= 8f) {
                                        c.consume()
                                        onExit()
                                        break
                                    }
                                }
                            }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    ) {
                        val bodyHeight = with(density) { bodyPx.toDp() }
                        when {
                            lyricsState.isLoading -> {
                                // Loading/placeholder fill the WHOLE body —
                                // no half-body top pad (that pad belongs to
                                // line modes only; stacking it here made the
                                // content 1.5× the viewport and pinned the
                                // text at the bottom edge).
                                LyricsLoading(bodyHeight)
                            }
                            lyricsState.errorMessage != null -> {
                                LyricsPlaceholder(
                                    lyricsState.errorMessage ?: "Could not load lyrics",
                                    isError = true,
                                    onRetry = onRetry,
                                    bodyHeight = bodyHeight,
                                )
                            }
                            lyricsState.mode == com.wearsic.app.model.LyricsMode.Unavailable -> {
                                LyricsPlaceholder(
                                    "No lyrics found for this song",
                                    isError = false,
                                    onRetry = onRetry,
                                    bodyHeight = bodyHeight,
                                )
                            }
                            lyricsState.mode == com.wearsic.app.model.LyricsMode.Plain -> {
                                lyricsState.lines.forEach { line ->
                                    Text(
                                        text = line.text,
                                        color = Color.White.copy(alpha = 0.80f),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                    )
                                }
                            }
                            else -> {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { halfBodyPx.toDp() })
                                )
                                lyricsState.lines.forEachIndexed { index, line ->
                                    SyncedLyricLine(
                                        text = line.text,
                                        active = index == activeIndex,
                                        activeColor = activeColor,
                                        modifier = if (index == activeIndex) {
                                            Modifier.onGloballyPositioned { c ->
                                                // Placement is per-frame during
                                                // scroll; equal-value writes are
                                                // free, so this is nothing.
                                                val b = c.boundsInRoot()
                                                activeRowCenterY = b.center.y
                                                activeRowHeightPx = b.height.toFloat()
                                                // The epoch tells the
                                                // auto-center THAT THIS ROW's
                                                // fresh report has landed: with
                                                // it, anchoring never reads the
                                                // previous row's stale value.
                                                activeRowEpoch = index
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(with(density) { halfBodyPx.toDp() })
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricLine(
    text: String,
    active: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
) {
    // NATURAL heights: the old fixed 32dp row assumed a uniform pitch
    // (it fed the centering math) and clipped wrapped lines mid-glyph on
    // the round face. Padding-driven spacing keeps a stable gap while the
    // text sizes the row. Static colours — no per-line animators, so a
    // line change never animates the whole column frame-by-frame.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LYRICS_ROW_GAP / 2)
            .then(if (active) Modifier.testTag("lyrics_active_line") else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) activeColor else Color.White.copy(alpha = 0.55f),
            fontSize = (if (active) 16 else 13).sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
            lineHeight = if (active) 19.sp else 15.5.sp,
            maxLines = if (active) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LyricsLoading(bodyHeight: Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(bodyHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Loading lyrics…",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun LyricsPlaceholder(message: String, isError: Boolean, onRetry: (() -> Unit)?, bodyHeight: Dp) {
    Box(
        modifier = Modifier.fillMaxWidth().height(bodyHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (isError) Icons.Rounded.Warning else Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(30.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .wearsicClickable(onClick = onRetry)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Retry",
                        color = LocalWearsicAccent.current,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkDisc(
    artworkUrl: String?,
    hasTrack: Boolean,
    progress: State<Float>,
    colors: ArtworkColors,
    size: Dp,
    modifier: Modifier = Modifier,
    rotateArtwork: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val ringStroke = 3f
    val context = LocalContext.current
    val request = remember(context, artworkUrl) {
        ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(160)
            .crossfade(200)
            .build()
    }

    Box(
        modifier = modifier
            .size(size + ringStroke.dp * 2)
            .then(
                // Tap = "open the album". The ring-drag scrub (attached by
                // the caller) consumes real drags, so a scrub never triggers
                // this; a plain tap does.
                if (onClick != null && hasTrack) {
                    Modifier.wearsicClickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Radial accent halo behind the disc — depth without a blur shader.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.22f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(size + ringStroke.dp * 2)
                .drawBehind {
                    val stroke = Stroke(width = ringStroke.dp.toPx(), cap = StrokeCap.Round)
                    val inset = ringStroke.dp.toPx()
                    val arcSize = androidx.compose.ui.geometry.Size(
                        this.size.width - 2 * inset,
                        this.size.height - 2 * inset
                    )
                    val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(
                        color = Color.White.copy(alpha = 0.14f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                    val sweep = if (hasTrack) progress.value * 360f else 0f
                    if (sweep > 0.5f) {
                        drawArc(
                            color = colors.accent,
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                }
        )
        if (!artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    // The record spins slowly over the song (one revolution per
                    // track). The angle is read in the layer phase from the
                    // same animated progress the ring uses, so this never
                    // adds recompositions — and it freezes when paused. The
                    // motion is a setting (rotateArtwork).
                    .graphicsLayer {
                        rotationZ = if (rotateArtwork) discRotation(progress.value) else 0f
                    }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                    .graphicsLayer {
                        rotationZ = if (rotateArtwork) discRotation(progress.value) else 0f
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(size * 0.34f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Page 1 — Actions sub-screen (favourite / download / queue / output)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionsPage(
    hasTrack: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onToggleFavorite: () -> Unit,
    onDownloadTrack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToVolume: () -> Unit,
    active: Boolean,
    artworkAccent: Color = Color.Transparent,
    onSaveAccent: ((Color) -> Unit)? = null
) {
    val listState = rememberScalingLazyListState()
    var savedFlash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("actions_page_root").wearsicRotaryScroll(listState, enabled = active),
        contentPadding = wearsicListContentPadding(PaddingValues(bottom = 44.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            ActionRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "Favorited" else "Favorite",
                enabled = hasTrack,
                onClick = onToggleFavorite,
                testTag = "player_favorite_button"
            )
        }
        item {
            ActionRow(
                icon = when {
                    isDownloading -> Icons.Rounded.HourglassEmpty
                    isDownloaded -> Icons.Rounded.CheckCircle
                    else -> Icons.Rounded.Download
                },
                label = when {
                    isDownloading -> "Downloading… $downloadProgress%"
                    isDownloaded -> "Downloaded Offline"
                    else -> "Download"
                },
                enabled = hasTrack && !isDownloaded && !isDownloading,
                onClick = onDownloadTrack,
                testTag = "player_download_button"
            )
        }
        item {
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Queue",
                enabled = true,
                onClick = onNavigateToQueue,
                testTag = "player_queue_shortcut_button"
            )
        }
        item {
            ActionRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                label = "Audio Output",
                enabled = true,
                onClick = onNavigateToVolume,
                testTag = "player_output_button"
            )
        }
        item {
            ActionRow(
                icon = Icons.Rounded.Palette,
                label = when {
                    savedFlash -> "Colour saved ✓"
                    else -> "Set app colour"
                },
                enabled = hasTrack && onSaveAccent != null &&
                    artworkAccent != Color.Transparent,
                onClick = {
                    onSaveAccent?.invoke(artworkAccent)
                    savedFlash = true
                    scope.launch {
                        delay(1600)
                        savedFlash = false
                    }
                },
                testTag = "player_save_accent_button"
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Control building blocks
// ─────────────────────────────────────────────────────────────────────────

/**
 * The artwork backdrop: one full-bleed crop under a fixed dark scrim, darkened
 * further behind the disc so the artwork itself stays readable as the art.
 * Decoded once per artwork at 256 px — cheap on memory, no blur passes.
 */
@Composable
private fun ArtworkBackdrop(
    artworkUrl: String?,
    glowColor: Color,
    onColors: (ArtworkColors) -> Unit
) {
    var bitmap by remember(artworkUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(bitmap, artworkUrl) {
        onColors(withContext(Dispatchers.Default) { extractArtworkColors(bitmap) })
    }
    if (artworkUrl.isNullOrBlank()) {
        Box(modifier = Modifier.fillMaxSize().background(WearsicBlack))
        return
    }
    val context = LocalContext.current
    val request = remember(context, artworkUrl) {
        ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(256)
            .allowHardware(false)
            .crossfade(180)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onSuccess = { bitmap = (it.result.drawable as? BitmapDrawable)?.bitmap },
        modifier = Modifier.fillMaxSize().background(WearsicBlack)
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.78f),
                    0.45f to Color.Black.copy(alpha = 0.48f),
                    1f to Color.Black.copy(alpha = 0.88f)
                )
            )
    )
    // A soft, hue-matched "aurora" glow behind the middle of the screen so
    // the flat black scrim reads as depth instead of a dead void. The centre
    // sits above the disc (the visual focus), and it is drawn from the same
    // artwork accent as the ring and controls — one cheap radial pass.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val center = with(density) {
            Offset((maxWidth * 0.5f).toPx(), (maxHeight * 0.32f).toPx())
        }
        val radius = with(density) { (maxWidth * 0.95f).toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.15f), Color.Transparent),
                        center = center,
                        radius = radius
                    )
                )
        )
    }
}

/**
 * Previous / play / next. Skip targets keep a full 44 dp touch box (they are
 * the controls most often hit blind, while walking) but a quieter visual disc,
 * so the play button still carries the weight.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    playSize: Dp,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    colors: ArtworkColors,
    enabled: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SkipButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = "Previous Track",
            onClick = onSkipPrevious,
            accent = colors.accent,
            enabled = enabled,
            testTag = "player_previous_button"
        )
        Spacer(modifier = Modifier.weight(1f))
        PlayButton(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            progress = progress,
            size = playSize,
            onClick = onTogglePlayPause,
            colors = colors,
            enabled = enabled && !isBuffering
        )
        Spacer(modifier = Modifier.weight(1f))
        SkipButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "Next Track",
            onClick = onSkipNext,
            accent = colors.accent,
            enabled = enabled,
            testTag = "player_next_button"
        )
    }
}

@Composable
private fun SkipButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    accent: Color,
    enabled: Boolean
) {
    Box(
        modifier = Modifier.size(WearsicDimens.TouchTarget),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (enabled) 0.20f else 0.08f))
                .border(1.dp, Color.White.copy(alpha = if (enabled) 0.12f else 0.06f), CircleShape)
                .wearsicClickable(enabled = enabled, pressedScale = 0.88f, onClick = onClick)
                .testTag(testTag),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.40f),
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * The primary control: an artwork-tinted disc with a near-black glyph. The
 * glyph pops in on every play/pause/buffer change (a keyed one-shot scale),
 * and the disc keeps a hairline ring so it never melts into the backdrop.
 */
@Composable
private fun PlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    size: Dp,
    onClick: () -> Unit,
    colors: ArtworkColors,
    enabled: Boolean
) {
    val glyph = when {
        isBuffering -> Icons.Rounded.HourglassEmpty
        isPlaying -> Icons.Rounded.Pause
        else -> Icons.Rounded.PlayArrow
    }
    Box(
        modifier = Modifier
            .size(size)
            .wearsicClickable(enabled = enabled, pressedScale = 0.92f, onClick = onClick)
            .testTag("player_play_pause_button"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(colors.blobTint.copy(alpha = if (enabled || isBuffering) 1f else 0.4f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // Keyed so the glyph pops in on every play/pause/buffer change.
            key(glyph) {
            Icon(
                imageVector = glyph,
                contentDescription = when {
                    isBuffering -> "Buffering"
                    isPlaying -> "Pause"
                    else -> "Play"
                },
                tint = WearsicBlack,
                modifier = Modifier
                    .size(size * 0.40f)
                    .wearsicEntrance(fromScale = 0.70f, riseDp = 0f)
            )
            }
        }
    }
}

/**
 * One row of the Actions sub-screen: 44 dp+ touch box, icon + label, and the
 * same flat glass language as the list rows.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WearsicDimens.SheetRowMinHeight)
            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, WearsicSurfaceBorder, RoundedCornerShape(WearsicDimens.CardRadius))
            .wearsicClickable(enabled = enabled, pressedScale = 0.98f, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.White.copy(alpha = 0.92f) else WearsicTextMuted,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = if (enabled) Color.White.copy(alpha = 0.94f) else WearsicTextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One clockwise revolution of the disc per full track. */
private fun discRotation(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f

/** m:ss (or h:mm:ss) for the player's elapsed/total read-out. */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun PlayerScreenPreview() {
    WearsicTheme {
        PlayerScreen(
            playbackState = PlaybackUiState(
                currentTrack = Track(
                    id = "1",
                    title = "Walcott",
                    artist = "Vampire Weekend",
                    artworkUrl = null
                ),
                isPlaying = true,
                durationMs = 220_000L,
                currentPositionMs = 84_000L,
                playlist = listOf(
                    Track(id = "1", title = "Walcott", artist = "Vampire Weekend"),
                    Track(id = "2", title = "A-Punk", artist = "Vampire Weekend")
                )
            ),
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onToggleFavorite = {},
            onNavigateToVolume = {}
        )
    }
}