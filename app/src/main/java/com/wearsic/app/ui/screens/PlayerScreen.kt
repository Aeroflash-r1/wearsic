package com.wearsic.app.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.wearsic.app.ui.components.WearsicMarqueeText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.wearsic.app.ui.util.extractArtworkColors
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import kotlinx.coroutines.Dispatchers
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
 * bezel scrubs seek on the Now Playing page.
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
        ArtworkBackdrop(artworkUrl = track.artworkUrl, onColors = { artworkColors = it })

        ScreenScaffold(modifier = Modifier.fillMaxSize()) { contentPadding: PaddingValues ->
            val pagerState = rememberPagerState(pageCount = { 2 })

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
                            haptic = haptic,
                            colors = artworkColors,
                            active = pagerState.currentPage == 0
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
                            active = pagerState.currentPage == 1
                        )
                    }
                }

                // One generous touch target for the sub-screen, not two tiny dots.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .heightIn(min = WearsicDimens.TouchTarget)
                        .semantics {
                            contentDescription =
                                if (pagerState.currentPage == 0) "Show player actions" else "Show now playing"
                        }
                        .wearsicClickable {
                            scope.launch { pagerState.animateScrollToPage(1 - pagerState.currentPage) }
                        }
                        .testTag("player_page_switch"),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(width = if (active) 11.dp else 5.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) artworkColors.accent else Color.White.copy(alpha = 0.32f)
                                )
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Page 0 — Now Playing: artwork disc, metadata, transport (never scrolls)
// ─────────────────────────────────────────────────────────────────────────

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
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    colors: ArtworkColors,
    active: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    // Rotary seek needs focus. The pager may not have attached this page's
    // focus node on the very first frame, so retry across a couple of frames
    // instead of throwing (the same defensive pattern as wearsicRotaryScroll).
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        repeat(3) {
            try {
                focusRequester.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                withFrameNanos { }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Short viewports (a clipped scrim, or a smaller round face) get a
        // tighter scale instead of a clipped control.
        val compact = maxHeight < 236.dp
        val discSize: Dp = (maxWidth * 0.36f).coerceIn(if (compact) 56.dp else 64.dp, if (compact) 72.dp else 92.dp)
        val playSize: Dp = if (compact) 48.dp else (maxWidth * 0.26f).coerceIn(50.dp, 60.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp)
                .focusRequester(focusRequester)
                .onRotaryScrollEvent { event ->
                    if (!active || !hasTrack || event.verticalScrollPixels == 0f) {
                        false
                    } else {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (event.verticalScrollPixels > 0f) onSeekForward() else onSeekBack()
                        true
                    }
                }
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Weighted top space keeps the artwork slightly above centre —
            // the composition reads as a disc with text beneath it.
            Spacer(modifier = Modifier.weight(0.7f))

            key(track.id) {
            ArtworkDisc(
                artworkUrl = track.artworkUrl,
                hasTrack = hasTrack,
                progress = progress,
                colors = colors,
                size = discSize,
                // One-shot entrance, replayed only when the track changes:
                // `key` recreates the composable (and its entrance state) per
                // track instead of replaying on every recomposition.
                modifier = Modifier.wearsicEntrance(fromScale = 0.90f, riseDp = 10f)
            )
            }

            Spacer(modifier = Modifier.height(if (compact) 4.dp else 8.dp))

            // Single line, always. A long title used to wrap to two lines,
            // which pushed the artist line down and clipped at the curved
            // edge; now the full name slides sideways instead.
            WearsicMarqueeText(
                text = if (hasTrack) track.title else "Nothing playing",
                color = Color.White,
                fontSize = if (compact) 14.sp else 15.sp,
                lineHeight = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.2).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .wearsicEntrance(delayMillis = 50, fromScale = 0.98f, riseDp = 6f)
            )

            Text(
                text = when {
                    !hasTrack -> "Pick a song from Library"
                    else -> "${track.artist} · ${formatTime(positionMs)} / ${formatTime(durationMs)}"
                },
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .wearsicEntrance(delayMillis = 90, fromScale = 0.98f, riseDp = 6f)
            )

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

            // Room for the overlaid page indicator in the safe area.
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * The album art as a circular focal point, wrapped by the playback progress
 * ring and a soft radial accent tint. One draw pass for ring + tint; the
 * animated progress is read in the DRAW phase only, so ticking never
 * recomposes the player.
 */
@Composable
private fun ArtworkDisc(
    artworkUrl: String?,
    hasTrack: Boolean,
    progress: State<Float>,
    colors: ArtworkColors,
    size: Dp,
    modifier: Modifier = Modifier
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
        modifier = modifier.size(size + ringStroke.dp * 2),
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
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
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
    active: Boolean
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().wearsicRotaryScroll(listState, enabled = active),
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
private fun ArtworkBackdrop(artworkUrl: String?, onColors: (ArtworkColors) -> Unit) {
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
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, WearsicSurfaceBorder, RoundedCornerShape(18.dp))
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