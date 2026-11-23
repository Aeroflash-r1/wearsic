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
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.viewmodel.LyricsViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
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
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceBorder
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.rememberWatchScale
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.util.ArtworkColors
import com.wearsic.app.ui.util.LocalRotaryDirectionInverted
import com.wearsic.app.ui.util.RotaryVolumeAccumulator
import com.wearsic.app.ui.util.extractArtworkColors
import com.wearsic.app.ui.util.rememberSystemVolumeState
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.util.wearsicRotaryVolumePixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NOW PLAYING — composed for a ROUND Wear OS face (Galaxy Watch7 44mm).
 *
 * Layout rules that only make sense on a circle:
 *   · The widest part of the display is its MIDDLE, and it belongs to the
 *     transport: one large play/pause glyph with the progress ring around
 *     it. The artwork is the FULL-bleed background — there is no separate
 *     song disc. Title and artist sit in the readable upper chord; the
 *     elapsed/total duration sits just BELOW the transport.
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
 * every change, and nothing animates while you simply listen. On Now Playing,
 * scrolling the touch bezel or physical crown adjusts system media volume
 * directly via a curved, Spotify-style bezel HUD that auto-hides after use;
 * the Actions page and the lyrics sheet reclaim the crown while they are
 * visible.
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

    // Power-savvy progress. The old 2 s tween ran at 60 fps for the whole
    // track, continuously rotating a full-size artwork bitmap on the GPU —
    // a measurable heat source on a watch. Interpolate at 20 fps instead:
    // the disc turns ~1 revolution per song, so 20 fps is visually
    // indistinguishable, but continuous layer work drops by ~3x.
    // State is read only in the draw/layer phase, so no recomposition per tick.
    val animatedProgress = remember(track.id) { mutableStateOf(progressTarget) }
    LaunchedEffect(track.id, progressTarget, playbackState.isPlaying, playbackState.isBuffering) {
        if (!playbackState.isPlaying || playbackState.isBuffering) {
            animatedProgress.value = progressTarget
            return@LaunchedEffect
        }
        val from = animatedProgress.value
        val start = SystemClock.elapsedRealtime()
        while (isActive) {
            kotlinx.coroutines.delay(50L)
            val t = ((SystemClock.elapsedRealtime() - start) / 2000f).coerceIn(0f, 1f)
            animatedProgress.value = from + (progressTarget - from) * t
            if (t >= 1f) break
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {

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

        // Touch-bezel / crown rotary volume control on Now Playing (page 0,
        // lyrics closed). Page 1 owns the bezel for the Actions list, and
        // LyricsFullscreen owns it while lyrics are open.
        val systemVolume = rememberSystemVolumeState()
        val rotaryInverted = LocalRotaryDirectionInverted.current
        val volumeAccumulator = remember { RotaryVolumeAccumulator() }
        var volumeHudVisible by remember { mutableStateOf(false) }
        var volumeHudPulseToken by remember { mutableIntStateOf(0) }
        // Volume visual is FIXED on the left edge; the bezel control (right
        // rim) drives it. No side switching.
        val volumeArcSide = VolumeEdgeSide.LEFT

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

        val nowPlayingRotaryActive = pageIndex == 0 && !lyricsMode
        LaunchedEffect(volumeHudVisible, volumeHudPulseToken, nowPlayingRotaryActive) {
            if (!nowPlayingRotaryActive) {
                if (volumeHudVisible) volumeHudVisible = false
                volumeAccumulator.reset()
                return@LaunchedEffect
            }
            if (!volumeHudVisible) return@LaunchedEffect
            delay(1850L)
            volumeHudVisible = false
            volumeAccumulator.reset()
        }

        val volumeHudProgress by animateFloatAsState(
            targetValue = if (volumeHudVisible && nowPlayingRotaryActive) 1f else 0f,
            animationSpec = if (volumeHudVisible && nowPlayingRotaryActive) {
                spring(dampingRatio = 0.82f, stiffness = 2200f)
            } else {
                tween(durationMillis = 240, easing = FastOutSlowInEasing)
            },
            label = "volumeHudVisibility"
        )

        val lyricsScrollState = key(track.id) { rememberScrollState() }
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
                // 250 ms is well under the shortest meaningful lyric line
                // while cutting idle wakeups (and heat) by ~40% vs 150 ms.
                delay(if (currentPlaying) 250L else 800L)
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
                            active = nowPlayingRotaryActive,
                            onOpenAlbum = onOpenAlbum,
                            onOpenLyrics = { if (hasTrack) lyricsMode = true },
                            onRotaryVolumePixels = { rawPixels ->
                                if (rawPixels == 0f) return@NowPlayingPage false
                                val delta = volumeAccumulator.onRotaryDelta(
                                    verticalScrollPixels = rawPixels,
                                    inverted = rotaryInverted
                                )
                                if (delta != 0) {
                                    val prevLevel = systemVolume.level
                                    systemVolume.step(delta)
                                    if (systemVolume.level != prevLevel) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                volumeHudVisible = true
                                volumeHudPulseToken++
                                true
                            }
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
                            active = pageIndex == 1 && !lyricsMode,
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
        // bezel instead of floating mid-screen. They crossfade out while the
        // Spotify-style curved volume arc occupies the right bezel, and vanish
        // while the lyrics sheet is over the screen.
        // Page indicator at the bottom centre. It used to live on the right
        // edge at transport height, where its 48 dp touch target overlapped
        // the next-track button (and its dot pulled the eye off-centre).
        if (!lyricsMode) Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .widthIn(min = WearsicDimens.TouchTarget)
                .heightIn(min = WearsicDimens.TouchTarget)
                .graphicsLayer {
                    alpha = (1f - volumeHudProgress).coerceIn(0f, 1f)
                }
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
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(2) { index ->
                val active = pageIndex == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = if (active) 11.dp else 5.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) artworkColors.accent else Color.White.copy(alpha = 0.32f)
                        )
                )
            }
        }

        // Spotify-style bezel volume HUD: full-bleed curved outer arc +
        // animated glass status pill, kept non-blocking so taps still work.
        if (nowPlayingRotaryActive && (volumeHudVisible || volumeHudProgress > 0.01f)) {
            NowPlayingVolumeOverlay(
                fraction = systemVolume.fraction,
                percent = systemVolume.percentStep10,
                isMuted = systemVolume.isMuted,
                accent = artworkColors.accent,
                hudProgress = volumeHudProgress,
                pulseToken = volumeHudPulseToken,
                side = volumeArcSide,
                modifier = Modifier.fillMaxSize()
            )
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
                onSeekTo = onSeekTo,
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
    onOpenAlbum: ((Track) -> Unit)? = null,
    onOpenLyrics: () -> Unit = {},
    onRotaryVolumePixels: (Float) -> Boolean = { false },
) {
    val latestRotaryHandler by rememberUpdatedState(onRotaryVolumePixels)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Bezel/crown = VOLUME on this page. The modifier owns the rotary
            // focus while `active`; the Actions list and the lyrics sheet
            // reclaim the bezel the moment they take over.
            .wearsicRotaryVolumePixels(
                onVolumePixels = { px -> latestRotaryHandler(px) },
                enabled = active
            )
            .testTag("nowplaying_page_root")
    ) {
        val compact = maxHeight < 236.dp

        // ── Player block (full viewport; no outer scroll) ────────────────
        // Box overlay: the text band pins to the top edge while the
        // transport sits at the EXACT geometric centre of the round face.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = watchScaled(WearsicDimens.ScreenPaddingH))
                .pointerInput(hasTrack, track.id) {
                    // Pull UP to lock into lyrics. Runs on the INITIAL (tunnel)
                    // pass so it sees drags before the transport consumes them.
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
                }
        ) {
            // ── Top band pinned to the TOP edge: SONG NAME, then artist ───
            // Spotify-style hierarchy; the duration lives BELOW the play
            // button. The full-bleed artwork stays the whole screen.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = if (compact) 2.dp else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Single line, always. A long title used to wrap to two lines,
                // which pushed the artist line down and clipped at the curved
                // edge; now the full name slides sideways instead.
                WearsicMarqueeText(
                    text = if (hasTrack) track.title else "Nothing playing",
                    color = Color.White,
                    fontSize = if (compact) 14.sp else 15.sp,
                    lineHeight = if (compact) 17.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.2).sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wearsicEntrance(delayMillis = 60, fromScale = 0.98f, riseDp = 5f)
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (hasTrack) track.artist.trim() else "Pick a song from Library",
                    color = Color.White.copy(alpha = 0.68f),
                    style = TextStyle(
                        fontFamily = WearsicBodyFont,
                        fontSize = if (compact) 10.sp else 11.sp,
                        lineHeight = if (compact) 12.sp else 13.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wearsicEntrance(delayMillis = 90, fromScale = 0.98f, riseDp = 5f)
                )
            }

            // ── Transport: play at the EXACT screen centre ─────────────────
            // Play/pause is pinned to the geometric centre; previous and next
            // are placed at exactly mirrored offsets. The offset includes a
            // real gap, so the 48 dp skip touch targets can never overlap the
            // play button (the old equal-weight row let them do exactly that,
            // which made the controls feel and look misaligned).
            val playSize: Dp = if (compact) 78.dp else 98.dp
            val skipOffset: Dp = playSize / 2f + 6.dp + WearsicDimens.TouchTarget / 2f

            AlignedSkipButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = "Previous Track",
                onClick = onSkipPrevious,
                enabled = hasTrack,
                testTag = "player_previous_button",
                tint = colors.accent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = -skipOffset),
            )
            PlayButtonWithRing(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                progress = progress,
                size = playSize,
                onClick = onTogglePlayPause,
                colors = colors,
                enabled = hasTrack && !isBuffering,
                modifier = Modifier.align(Alignment.Center),
            )
            AlignedSkipButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Next Track",
                onClick = onSkipNext,
                enabled = hasTrack,
                testTag = "player_next_button",
                tint = colors.accent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = skipOffset),
            )
        }
    }
}

/** Which bezel edge the curved volume arc hugs. */
private enum class VolumeEdgeSide { LEFT, RIGHT }

/**
 * Spotify-style Wear OS volume HUD for Now Playing.
 *
 * A curved ARC hugs the round bezel on the chosen edge — by default the
 * RIGHT edge, or the edge OPPOSITE the rim the finger is touching. Zero
 * volume anchors at the BOTTOM end of the arc and the fill travels UP the
 * curve as volume grows (and back down as it shrinks), so up = louder,
 * down = quieter, exactly like Spotify's Wear volume bar. A floating glass
 * pill shows the quantized percentage and auto-hides.
 */
@Composable
private fun NowPlayingVolumeOverlay(
    fraction: Float,
    percent: Int,
    isMuted: Boolean,
    accent: Color,
    hudProgress: Float,
    pulseToken: Int,
    side: VolumeEdgeSide = VolumeEdgeSide.RIGHT,
    modifier: Modifier = Modifier
) {
    val scale = rememberWatchScale()
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 1800f),
        label = "nowPlayingVolumeFraction"
    )
    val pillBump = remember { Animatable(1f) }
    LaunchedEffect(pulseToken) {
        if (pulseToken > 0) {
            pillBump.snapTo(1.07f)
            pillBump.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 1800f)
            )
        }
    }

    val barTint = if (isMuted) WearsicTextMuted else accent
    val speakerIcon = when {
        isMuted -> Icons.AutoMirrored.Rounded.VolumeOff
        fraction < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    val label = if (isMuted) "Muted" else "$percent%"

    BoxWithConstraints(
        modifier = modifier
            .semantics {
                contentDescription = if (isMuted) "Volume muted" else "Volume $percent percent"
            }
            .testTag("player_volume_overlay")
    ) {
        val compact = maxHeight < 236.dp
        val stroke = (if (compact) 7.dp else 9.dp) * scale
        // 90° arc centred on the horizontal midline (3 / 9 o'clock): it
        // reads as a SIDE bar, never dipping toward the bottom edge.
        val arcSweep = 90f
        val edgeInset = (if (compact) 1.5.dp else 2.dp) * scale

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val alphaClamped = hudProgress.coerceIn(0f, 1f)
                    alpha = alphaClamped
                    // Slide in from the side the arc appears on.
                    val slide = (1f - alphaClamped) * 8.dp.toPx()
                    translationX = if (side == VolumeEdgeSide.RIGHT) slide else -slide
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(size.width, size.height) / 2f -
                edgeInset.toPx() - stroke.toPx() / 2f
            val arcBounds = Offset(cx - radius, cy - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Arc angles (canvas: 0° = 3 o'clock, clockwise positive).
            // Both arcs keep their BOTTOM end as the zero-volume anchor and
            // travel UP toward the top end as volume increases.
            val trackStart: Float
            val fillStart: Float
            val fillSweepSign: Float
            when (side) {
                VolumeEdgeSide.RIGHT -> {
                    trackStart = 45f   // just below 3 o'clock → just above it
                    fillStart = 45f
                    fillSweepSign = -1f
                }
                VolumeEdgeSide.LEFT -> {
                    trackStart = 135f  // just below 9 o'clock → just above it
                    fillStart = 135f
                    fillSweepSign = 1f
                }
            }

            // Dim track (only while interacting) — follows the round bezel.
            if (hudProgress > 0.01f) {
                drawArc(
                    color = Color.White.copy(alpha = 0.10f),
                    startAngle = trackStart,
                    sweepAngle = arcSweep,
                    useCenter = false,
                    topLeft = arcBounds,
                    size = arcSize,
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
                )
            }

            // Active fill from the bottom-edge zero point, growing up the arc.
            val fillSweep = fillSweepSign * arcSweep * animatedFraction
            if (abs(fillSweep) > 0.5f) {
                drawArc(
                    color = barTint.copy(alpha = if (isMuted) 0.55f else 1f),
                    startAngle = fillStart,
                    sweepAngle = fillSweep,
                    useCenter = false,
                    topLeft = arcBounds,
                    size = arcSize,
                    style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
                )
                // Specular highlight at the fill head (the moving tip).
                val headRadians = Math.toRadians((fillStart + fillSweep).toDouble())
                drawCircle(
                    color = Color.White.copy(alpha = if (isMuted) 0.35f else 0.85f),
                    radius = stroke.toPx() * 0.22f,
                    center = Offset(
                        cx + radius * cos(headRadians).toFloat(),
                        cy + radius * sin(headRadians).toFloat()
                    )
                )
            }
        }

        // Floating percentage pill — on the SAME SIDE as the arc, vertically
        // centred (the widest chord of the round face), just inside the arc.
        // Never at the bottom: the whole HUD reads as a side control.
        val pillEdgePadding = (if (compact) 13.dp else 16.dp) * scale
        val pillHeight = (if (compact) 26.dp else 30.dp) * scale
        val pillHorizontalPadding = (if (compact) 9.dp else 11.dp) * scale
        val maxPillWidth = (maxWidth * 0.40f).coerceIn(72.dp, 108.dp)

        Box(
            modifier = Modifier
                .align(if (side == VolumeEdgeSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(
                    start = if (side == VolumeEdgeSide.LEFT) pillEdgePadding else 0.dp,
                    end = if (side == VolumeEdgeSide.RIGHT) pillEdgePadding else 0.dp,
                )
                .graphicsLayer {
                    val alphaClamped = hudProgress.coerceIn(0f, 1f)
                    alpha = alphaClamped
                    val s = (0.90f + 0.10f * alphaClamped) * pillBump.value
                    scaleX = s
                    scaleY = s
                    val slide = (1f - alphaClamped) * 6.dp.toPx()
                    translationX = if (side == VolumeEdgeSide.RIGHT) slide else -slide
                }
                .height(pillHeight)
                .widthIn(max = maxPillWidth)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.40f))
                .padding(horizontal = pillHorizontalPadding),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = speakerIcon,
                    contentDescription = null,
                    tint = barTint,
                    modifier = Modifier.size((if (compact) 14.dp else 16.dp) * scale)
                )
                Text(
                    text = label,
                    color = if (isMuted) Color.White.copy(alpha = 0.78f) else Color.White,
                    style = TextStyle(
                        fontFamily = WearsicDisplayFont,
                        fontSize = if (compact) 12.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("player_volume_percent")
                )
            }
        }
    }
}

/**
 * THE fullscreen lyrics page. The active line is auto-centered by MEASURED
 * position (onGloballyPositioned) at the watch screen's true vertical center
 * (clamped safely below the header for tall multi-line wraps), so highlighted
 * lyrics sit across the widest chord of the round display rather than drifting
 * toward the bottom bezel. Manual scrolling (touch, bezel) always wins:
 * auto-centering pauses while any scroll is in progress, and resumes on the
 * next line change.
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
    /** Seek to a lyric line's timestamp when the user taps it. */
    onSeekTo: (Long) -> Unit = {},
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
    // scrolls this body (framework routing, short per-step haptics) exactly
    // like a built-in app. Taking focus here also blocks the bezel from
    // reaching Now Playing / Actions behind the sheet.
    val lyricsRotaryFocus = remember { FocusRequester() }

    // Root-space vertical center of the entire watch face, used so the active
    // lyric sits dead-center on the screen rather than in the lower third.
    var screenCenterY by remember { mutableFloatStateOf(Float.NaN) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .hierarchicalFocusGroup(active = true)
            .background(backdrop)
            .onGloballyPositioned { c ->
                screenCenterY = c.boundsInRoot().center.y
            }
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
            // Compact header: truly centered "Lyrics" title + 48dp exit button
            // on the trailing chord.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Lyrics",
                    color = if (colors.fromArtwork) colors.accent else Color.White.copy(alpha = 0.9f),
                    fontFamily = WearsicDisplayFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(WearsicDimens.TouchTarget)
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

            // The lyrics body — a BoxWithConstraints so spacer sizing and
            // header clearance use the REAL body height.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val bodyPx = with(density) { maxHeight.toPx() }

                // Unclipped Y center of the ACTIVE row inside the scrollable
                // Column content, reported by its onGloballyPositioned on every
                // layout. Using positionInParent() avoids viewport clipping from
                // verticalScroll (which shrinks or zeros boundsInRoot() when the
                // active line starts partially or wholly offscreen).
                var activeRowCenterInContentY by remember { mutableFloatStateOf(Float.NaN) }
                var activeRowHeightPx by remember { mutableFloatStateOf(0f) }
                var activeRowEpoch by remember { mutableIntStateOf(-1) }

                // Root-space Y where the body begins (directly below header).
                var bodyTopInRoot by remember { mutableFloatStateOf(0f) }

                val centerOffsetInBody = if (!screenCenterY.isNaN() && bodyTopInRoot > 0f) {
                    (screenCenterY - bodyTopInRoot).coerceAtLeast(0f)
                } else {
                    bodyPx * 0.34f
                }
                val topSpacerPx = (centerOffsetInBody - with(density) { 12.dp.toPx() })
                    .coerceAtLeast(bodyPx * 0.16f)
                val bottomSpacerPx = (bodyPx - centerOffsetInBody + with(density) { 20.dp.toPx() })
                    .coerceAtLeast(bodyPx * 0.68f)

                // Auto-center the active line at the watch screen's true center
                // (clamped below header clearance for tall wrapped rows).
                LaunchedEffect(
                    activeIndex,
                    synced,
                    track.id,
                    lyricsState.lines.size,
                    topSpacerPx.roundToInt(),
                ) {
                    if (!synced || activeIndex < 0) {
                        return@LaunchedEffect
                    }
                    var attempt = 0
                    while (attempt < 2) {
                        attempt++
                        var settleFrames = 0
                        while (scrollState.isScrollInProgress && settleFrames < 90) {
                            withFrameNanos { }
                            settleFrames++
                        }
                        var measureFrames = 0
                        while ((activeRowEpoch != activeIndex || bodyTopInRoot <= 0f || screenCenterY.isNaN()) && measureFrames < 12) {
                            withFrameNanos { }
                            measureFrames++
                        }
                        val contentCenterY = activeRowCenterInContentY
                        if (bodyTopInRoot <= 0f || screenCenterY.isNaN() || activeRowEpoch != activeIndex || contentCenterY.isNaN()) {
                            if (attempt == 1) {
                                delay(300)
                                continue
                            }
                            return@LaunchedEffect
                        }
                        val rowY = bodyTopInRoot + contentCenterY - scrollState.value.toFloat()
                        val clearanceTop = bodyTopInRoot + with(density) { 6.dp.toPx() }
                        val safeAnchorY = maxOf(
                            screenCenterY,
                            clearanceTop + activeRowHeightPx / 2f
                        )
                        val target = lyricsAutoCenterTarget(
                            currentValue = scrollState.value,
                            maxValue = scrollState.maxValue,
                            rowCenterY = rowY,
                            anchorY = safeAnchorY,
                        )
                        if (target != scrollState.value) {
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
                            bodyTopInRoot = c.boundsInRoot().top
                        }
                        .requestFocusOnHierarchyActive()
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
                                LyricsLoading(bodyHeight)
                            }
                            lyricsState.errorMessage != null -> {
                                LyricsPlaceholder(
                                    message = lyricsState.errorMessage ?: "Could not load lyrics",
                                    isError = !lyricsState.isOffline,
                                    isOffline = lyricsState.isOffline,
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
                                Spacer(modifier = Modifier.height(12.dp))
                                lyricsState.lines.forEach { line ->
                                    Text(
                                        text = line.text,
                                        color = Color.White.copy(alpha = 0.80f),
                                        fontFamily = WearsicBodyFont,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(48.dp))
                            }
                            else -> {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { topSpacerPx.toDp() })
                                )
                                lyricsState.lines.forEachIndexed { index, line ->
                                    SyncedLyricLine(
                                        text = line.text,
                                        active = index == activeIndex,
                                        activeColor = activeColor,
                                        timeMs = line.timeMs,
                                        onClick = { timeMs ->
                                            onSeekTo(timeMs)
                                        },
                                        modifier = if (index == activeIndex) {
                                            Modifier.onGloballyPositioned { c ->
                                                val h = c.size.height.toFloat()
                                                activeRowCenterInContentY = c.positionInParent().y + h / 2f
                                                activeRowHeightPx = h
                                                activeRowEpoch = index
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(with(density) { bottomSpacerPx.toDp() })
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
private fun SyncedLyricLine(
    text: String,
    active: Boolean,
    activeColor: Color,
    timeMs: Long? = null,
    onClick: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // NATURAL heights: the old fixed 32dp row assumed a uniform pitch
    // (it fed the centering math) and clipped wrapped lines mid-glyph on
    // the round face. Padding-driven spacing keeps a stable gap while the
    // text sizes the row. Static colours — no per-line animators, so a
    // line change never animates the whole column frame-by-frame.
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LYRICS_ROW_GAP / 2)
            .wearsicClickable(
                enabled = timeMs != null && onClick != null,
                pressedScale = 0.98f,
            ) {
                if (timeMs != null && onClick != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick(timeMs)
                }
            }
            .then(if (active) Modifier.testTag("lyrics_active_line") else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) activeColor else Color.White.copy(alpha = 0.55f),
            fontFamily = if (active) WearsicDisplayFont else WearsicBodyFont,
            fontSize = (if (active) 16 else 13).sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
            lineHeight = if (active) 19.sp else 15.5.sp,
            maxLines = if (active) 3 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Lyrics placeholder pulse: alpha floor/ceiling, rise time and full period.
 * The period is 420 ms up + 760 ms down — deliberately asymmetric so the
 * three rows read as a wave travelling down the body rather than a blink.
 */
private const val SHIMMER_MIN_ALPHA = 0.10f
private const val SHIMMER_MAX_ALPHA = 0.26f
private const val SHIMMER_RISE_MS = 420
private const val SHIMMER_PERIOD_MS = 1180

@Composable
private fun LyricsLoading(bodyHeight: Dp) {
    val accent = LocalWearsicAccent.current
    Box(
        modifier = Modifier.fillMaxWidth().height(bodyHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            // No spinner: just the shimmer rows (centered), promising the
            // lyric layout instead of an abstract loader.
            Spacer(modifier = Modifier.height(6.dp))
            // Shimmer placeholder rows: lyric-line-shaped bars breathing with
            // the app's shimmer language (staggered pulse). They promise the
            // layout the user is about to see instead of an abstract spinner
            // alone — the loading state stops reading as "frozen".
            // Driven by rememberInfiniteTransition, the same pattern as the
            // search placeholders: a manual animation coroutine here wrote
            // state every frame FOREVER, Compose never went idle, and
            // Espresso AppNotIdleException'd ScreenGalleryTest.lyrics_loading
            // in CI (infinite transitions themselves stay idle-safe — the M3
            // spinner passed this exact test in v1.9.45).
            // ONE transition drives all three rows (not one per row), and the
            // alpha is read in the DRAW phase — the pulse then invalidates a
            // draw, never a recomposition, so the loading state cannot cost a
            // frame of composition work per tick.
            val shimmer = rememberInfiniteTransition(label = "lyricsShimmer")
            val fractions = listOf(0.86f, 1f, 0.72f)
            val stagger = listOf(0, 170, 340)
            fractions.forEachIndexed { i, fraction ->
                val pulse = shimmer.animateFloat(
                    initialValue = SHIMMER_MIN_ALPHA,
                    targetValue = SHIMMER_MAX_ALPHA,
                    // RepeatMode.Reverse reuses ONE tween in both directions,
                    // so it can only breathe symmetrically and it silently
                    // dropped the asymmetry this placeholder is built on:
                    // rise fast (420 ms), fall slowly (760 ms). Keyframes keeps
                    // that rhythm inside a single infinite animation (which,
                    // unlike a hand-rolled animate() loop, stays idle-safe).
                    animationSpec = infiniteRepeatable(
                        keyframes {
                            durationMillis = SHIMMER_PERIOD_MS
                            SHIMMER_MAX_ALPHA at SHIMMER_RISE_MS using FastOutSlowInEasing
                            SHIMMER_MIN_ALPHA at SHIMMER_PERIOD_MS
                        },
                        // Delay, not the default FastForward: FastForward would
                        // start each row ALREADY partway through the cycle, so
                        // the last row would lead and the wave would travel UP
                        // instead of down the body.
                        initialStartOffset = StartOffset(stagger[i], StartOffsetType.Delay),
                    ),
                    label = "lyricsShimmerPulse$i",
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth(fraction)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .drawBehind {
                            drawRect(color = Color.White.copy(alpha = pulse.value))
                        },
                )
                if (i < fractions.lastIndex) Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun LyricsPlaceholder(
    message: String,
    isError: Boolean,
    onRetry: (() -> Unit)?,
    bodyHeight: Dp,
    isOffline: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(bodyHeight),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when {
                    isOffline -> Icons.Rounded.CloudOff
                    isError -> Icons.Rounded.Warning
                    else -> Icons.Rounded.MusicNote
                },
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(30.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (isOffline) "Offline" else message,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (isOffline) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Saved lyrics still work.",
                    color = Color.White.copy(alpha = 0.40f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
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
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var savedFlash by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScalingLazyColumn(
        state = listState,
            autoCentering = null,
        modifier = Modifier.fillMaxSize().testTag("actions_page_root").wearsicRotaryScroll(listState, enabled = active),
        contentPadding = rememberWearsicListContentPadding(PaddingValues(bottom = 44.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            ActionRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "Favorited" else "Favorite",
                enabled = hasTrack,
                highlighted = isFavorite,
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
                highlighted = isDownloaded || isDownloading,
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
                highlighted = savedFlash,
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
 * Decoded once per artwork at 224 px — cheap on memory, no blur passes.
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
    val request = remember(artworkUrl) {
        ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(224)
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
/**
 * Plain Material 3 icon skip button — intentionally NO glass circle/background
 * so the transport reads clean next to the big centred play button. The icon
 * takes the artwork accent colour (fallback lavender) instead of white.
 */
@Composable
private fun AlignedSkipButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(WearsicDimens.TouchTarget)
            .wearsicClickable(enabled = enabled, pressedScale = 0.82f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(26.dp)
        )
    }
}

/**
 * The primary, centred control — Spotify Wear OS style, but tinted from the
 * ARTWORK. The album art is already the full screen, so there is no
 * photo/song disc: one large plain play/pause/buffering glyph, coloured
 * with the artwork accent (light tint of the song's hue), with the progress
 * ring drawn around it. A soft radial scrim keeps the glyph legible over
 * bright artwork without adding a visible circle. The glyph pops in on
 * every play/pause/buffer change (a keyed one-shot scale); the ring uses
 * the same [progress] state as before, so it is always in sync and reads in
 * the draw phase.
 */
@Composable
private fun PlayButtonWithRing(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    size: Dp,
    onClick: () -> Unit,
    colors: ArtworkColors,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val glyph = when {
        isBuffering -> Icons.Rounded.HourglassEmpty
        isPlaying -> Icons.Rounded.Pause
        else -> Icons.Rounded.PlayArrow
    }
    val ringAccent = colors.accent
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val s = if (isPlaying) 1f else 0.97f
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                // Soft legibility scrim behind the transport (a glow, not a
                // visible disc — the artwork stays the hero of the screen).
                val px = size.toPx()
                val center = Offset(px / 2f, px / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.Black.copy(alpha = 0.42f), Color.Transparent)
                    ),
                    radius = px * 0.62f,
                    center = center
                )
                // Progress ring around the button.
                val strokePx = size.toPx() * 0.030f
                val inset = strokePx / 2f + 2.dp.toPx()
                val arcSizePx = size.toPx() - inset * 2f
                val topLeft = Offset(inset, inset)
                // Full track ring (dim).
                drawArc(
                    color = Color.White.copy(alpha = 0.14f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSizePx, arcSizePx),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                val sweep = progress.value.coerceIn(0f, 1f) * 360f
                if (sweep > 0.5f) {
                    drawArc(
                        color = ringAccent,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = Size(arcSizePx, arcSizePx),
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            }
            .wearsicClickable(enabled = enabled, pressedScale = 0.90f, onClick = onClick)
            .testTag("player_play_pause_button"),
        contentAlignment = Alignment.Center
    ) {
        key(glyph) {
            Icon(
                imageVector = glyph,
                contentDescription = when {
                    isBuffering -> "Buffering"
                    isPlaying -> "Pause"
                    else -> "Play"
                },
                // Custom artwork colour on the play/pause glyph (light tint
                // of the song's hue; lavender fallback) — not plain white.
                tint = ringAccent.copy(alpha = if (enabled || isBuffering) 1f else 0.45f),
                modifier = Modifier
                    .size(size * 0.70f)
                    .wearsicEntrance(fromScale = 0.70f, riseDp = 0f)
            )
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
    testTag: String,
    highlighted: Boolean = false
) {
    val accent = LocalWearsicAccent.current
    val cardShape = RoundedCornerShape(WearsicDimens.CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WearsicDimens.SheetRowMinHeight)
            .clip(cardShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(
                1.dp,
                if (highlighted) accent.copy(alpha = 0.35f) else WearsicSurfaceBorder,
                cardShape
            )
            .wearsicClickable(enabled = enabled, pressedScale = 0.98f, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (highlighted) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    highlighted -> accent
                    enabled -> Color.White.copy(alpha = 0.92f)
                    else -> WearsicTextMuted
                },
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = if (enabled || highlighted) Color.White.copy(alpha = 0.94f) else WearsicTextMuted,
            style = TextStyle(
                fontFamily = WearsicBodyFont,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** m:ss (or h:mm:ss) for the player's elapsed/total read-out. */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    // Locale.US, not the default: `%d` under an Arabic/Persian locale emits
    // Eastern-Arabic digits, and a clock read-out flipping numeral systems (or
    // mixing them with the `--:--` placeholder) is a bug, not localisation.
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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