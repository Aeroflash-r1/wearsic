package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlaybackUiState
import com.example.model.Track
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicDimens
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTheme
import com.example.ui.util.wearsicClickable

/**
 * NOW PLAYING — a Wear OS-native media screen built as SCREEN + SUB-SCREEN.
 *
 * Design rules (all deliberate):
 *   · The ALBUM ARTWORK IS THE BACKGROUND of the whole screen (cropped,
 *     full-bleed) under a fixed dark scrim gradient, so every track owns the
 *     display while white text stays readable. With no artwork the screen is
 *     pure black (OLED pixels off). No blur — the scrim is one gradient, and
 *     the artwork decodes once at 256px.
 *   · SCREENS, NOT SCROLLING: the player is a 2-page HorizontalPager.
 *     Page 0 "Now Playing" holds everything that matters — title, artist and
 *     the complete transport — ALWAYS fully visible, never scrolled to.
 *     Page 1 "Actions" is the sub-screen with favourite / download / queue /
 *     audio output. Swipe horizontally (or tap the page dots) to move
 *     between them — exactly how Wear OS apps separate screens.
 *   · NO continuous animation — nothing animates while the user just
 *     listens; the progress ring interpolates only between 2s position ticks
 *     and redraws in the DRAW phase (zero recomposition).
 *   · neutral monochrome transport (white play, quiet grey skips) with the
 *     one brand accent: the signature lavender progress sweep around the
 *     play disc.
 *   · transport sizing adapts to the available width, so the skip buttons
 *     can never be pushed off a 44mm round display.
 *
 * The rotary bezel scrubs seek on the Now Playing page.
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

    val progressTarget = if (playbackState.durationMs > 0L) {
        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    // The position tracker emits one tick every 2s; a linear tween of the same
    // length turns those discrete steps into a continuously sweeping ring.
    // Held as a State and read inside the draw phase, so the interpolation
    // redraws only the play button instead of recomposing the player.
    val animatedProgress = animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "playerProgress"
    )

    Box(modifier = modifier.fillMaxSize()) {

        // ── The artwork IS the background ────────────────────────────────
        ArtworkBackdrop(artworkUrl = track.artworkUrl)

        ScreenScaffold(modifier = Modifier.fillMaxSize()) { contentPadding: PaddingValues ->
            // Two screens side by side: 0 = Now Playing, 1 = Actions.
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
                            onTogglePlayPause = onTogglePlayPause,
                            onSkipPrevious = onSkipPrevious,
                            onSkipNext = onSkipNext,
                            onSeekForward = onSeekForward,
                            onSeekBack = onSeekBack,
                            haptic = haptic
                        )
                        else -> ActionsPage(
                            track = track,
                            hasTrack = hasTrack,
                            isFavorite = track.isFavorite,
                            isDownloaded = isDownloaded,
                            isDownloading = isDownloading,
                            downloadProgress = downloadProgress,
                            onToggleFavorite = onToggleFavorite,
                            onDownloadTrack = { onDownloadTrack(track) },
                            onNavigateToQueue = onNavigateToQueue,
                            onNavigateToVolume = onNavigateToVolume
                        )
                    }
                }

                // Page dots — the standard Wear hint that a sub-screen exists.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(2) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(width = if (active) 12.dp else 5.dp, height = 5.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) WearsicVibrantLavender
                                    else Color.White.copy(alpha = 0.35f)
                                )
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Screen 0 — Now Playing (title + full transport, nothing scrolls)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun NowPlayingPage(
    track: Track,
    hasTrack: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekBack: () -> Unit,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .onRotaryScrollEvent { event ->
                if (event.verticalScrollPixels == 0f) {
                    false
                } else {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (event.verticalScrollPixels > 0f) onSeekForward() else onSeekBack()
                    true
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(6.dp))

        // ── Metadata (top of screen — transport keeps the bottom) ───────
        Text(
            text = if (hasTrack) track.title else "Nothing playing",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (!hasTrack) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = if (hasTrack) track.artist else "Pick a song from Library",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Transport (adaptive: always fits the round face) ─────────────
        TransportRow(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            progress = progress,
            onTogglePlayPause = onTogglePlayPause,
            onSkipPrevious = onSkipPrevious,
            onSkipNext = onSkipNext
        )

        // Bottom room for the page dots (drawn by the host).
        Spacer(modifier = Modifier.height(18.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Screen 1 — Actions sub-screen (favourite / download / queue / output)
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionsPage(
    track: Track,
    hasTrack: Boolean,
    isFavorite: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    onToggleFavorite: () -> Unit,
    onDownloadTrack: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToVolume: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ActionRow(
            icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = if (isFavorite) "Favorited" else "Favorite",
            enabled = hasTrack,
            onClick = onToggleFavorite,
            testTag = "player_favorite_button"
        )
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
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            label = "Queue",
            enabled = true,
            onClick = onNavigateToQueue,
            testTag = "player_queue_shortcut_button"
        )
        ActionRow(
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            label = "Audio Output",
            enabled = true,
            onClick = onNavigateToVolume,
            testTag = "player_output_button"
        )
        Spacer(modifier = Modifier.height(18.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Control building blocks
// ─────────────────────────────────────────────────────────────────────────

/**
 * The artwork backdrop: one full-bleed crop of the album cover under a fixed
 * dark scrim gradient (stronger where the title and the transport sit).
 * Decoded once per artwork at 256px — cheap on memory, no blur passes.
 * Falls back to pure black when there is no artwork.
 */
@Composable
private fun ArtworkBackdrop(artworkUrl: String?) {
    if (artworkUrl.isNullOrBlank()) {
        Box(modifier = Modifier.fillMaxSize().background(WearsicBlack))
        return
    }
    val context = LocalContext.current
    val request = remember(artworkUrl) {
        ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(256)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
    // One gradient = the whole readability story. Top and bottom (where the
    // text and controls live) are darkest; the middle shows the art.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.74f),
                    0.45f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Black.copy(alpha = 0.82f)
                )
            )
    )
}

/**
 * Previous / play-next transport. Sizes derive from the width actually
 * available on the (round) display, so on a 44mm watch the next button can
 * never sit outside the visible area — the row always fits exactly.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth
        val playSize = (available * 0.36f).coerceIn(58.dp, 72.dp)
        val skipSize = (available * 0.235f).coerceIn(42.dp, 50.dp)
        val gap = ((available - playSize - skipSize * 2) / 2f).coerceIn(6.dp, 18.dp)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            SkipButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = "Previous Track",
                size = skipSize,
                onClick = onSkipPrevious,
                testTag = "player_previous_button"
            )
            Spacer(modifier = Modifier.width(gap))
            PlayButton(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                progress = progress,
                size = playSize,
                onClick = onTogglePlayPause
            )
            Spacer(modifier = Modifier.width(gap))
            SkipButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Next Track",
                size = skipSize,
                onClick = onSkipNext,
                testTag = "player_next_button"
            )
        }
    }
}

/**
 * Quiet monochrome skip button: translucent grey disc, white glyph. The
 * visual weight sits on the play button — like every premium media player.
 */
@Composable
private fun SkipButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    testTag: String
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .wearsicClickable(pressedScale = 0.90f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.size(size * 0.44f)
        )
    }
}

/**
 * The primary play/pause control: a solid white disc with a near-black
 * glyph, wrapped by a thin playback-progress ring. One small Canvas draws
 * the ring (track + filled arc); the animated progress value is read in the
 * DRAW phase only, so the 2s sweep never recomposes the screen.
 */
@Composable
private fun PlayButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    size: Dp,
    onClick: () -> Unit
) {
    // Thin progress ring drawn around the white disc (NOT on it — white on
    // white is invisible): quiet track + solid sweep from 12 o'clock, in one
    // draw phase so the 2s interpolation never recomposes anything.
    val ringStroke = 2.5f
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                val stroke = Stroke(width = ringStroke.dp.toPx(), cap = StrokeCap.Round)
                val inset = ringStroke.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(
                    this.size.width - 2 * inset,
                    this.size.height - 2 * inset
                )
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(
                    color = Color.White.copy(alpha = 0.16f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke
                )
                val sweep = progress.value * 360f
                if (sweep > 0.5f) {
                    // The one piece of brand colour on the screen: the
                    // signature lavender sweeps around the play disc —
                    // instantly recognizable as Wearsic, costs one arc.
                    drawArc(
                        color = WearsicVibrantLavender,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke
                    )
                }
            }
            .wearsicClickable(pressedScale = 0.93f, onClick = onClick)
            .testTag("player_play_pause_button"),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.82f)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    isBuffering -> Icons.Rounded.HourglassEmpty
                    isPlaying -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = WearsicBlack,
                modifier = Modifier.size(size * 0.38f)
            )
        }
    }
}

/**
 * One row of the Actions sub-screen: 44dp+ touch box, icon + label, and the
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, WearsicSurfaceBorder, RoundedCornerShape(16.dp))
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

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun PlayerScreenPreview() {
    WearsicTheme {
        PlayerScreen(
            playbackState = PlaybackUiState(
                currentTrack = Track(id = "1", title = "Walcott", artist = "Vampire Weekend"),
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
