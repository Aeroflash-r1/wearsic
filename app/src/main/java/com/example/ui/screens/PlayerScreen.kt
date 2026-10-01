package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.MoreVert
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
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTheme
import com.example.ui.util.wearsicClickable

/**
 * NOW PLAYING — a production-grade Wear OS media screen.
 *
 * Design rules (all deliberate):
 *   · PURE BLACK background — OLED pixels are off, so playback costs less
 *     battery and the watch runs cooler than with any artwork-blur backdrop
 *   · NO continuous animation — nothing on this screen animates while the
 *     user is just listening (the old breathing halo kept the frame clock
 *     ticking 60x/s and made the watch hot)
 *   · neutral monochrome transport (white play, quiet grey skips) — the
 *     premium look of every serious music app, and no per-track colour
 *     extraction (which burned CPU on every track change)
 *   · transport sizing adapts to the available width, so the skip buttons
 *     can never be pushed off a 44mm round display
 *
 * The rotary bezel scrubs seek.
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
    val context = LocalContext.current

    var showMoreSheet by remember { mutableStateOf(false) }

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

    Box(modifier = modifier.fillMaxSize().background(WearsicBlack)) {

        ScreenScaffold(modifier = Modifier.fillMaxSize()) { contentPadding: PaddingValues ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                // Fit the whole control stack INSIDE the round display on a
                // 44mm watch (~170-190dp of usable height): the artwork
                // thumbnail only appears when there is room for it.
                // Artwork is the first thing to go when the viewport is tight:
            // controls and text always keep their room on smaller round faces.
            val showArtwork = maxHeight >= 190.dp && !track.artworkUrl.isNullOrBlank()

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
                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Artwork (compact, flat, never blurred) ───────────
                    if (showArtwork) {
                        val request = remember(track.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(track.artworkUrl)
                                .size(128)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(13.dp))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // ── Metadata ────────────────────────────────────────
                    Text(
                        text = if (hasTrack) track.title else "Nothing playing",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp,
                        maxLines = 1,
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

                    // ── Transport (adaptive: always fits the round face) ─
                    TransportRow(
                        isPlaying = playbackState.isPlaying,
                        isBuffering = playbackState.isBuffering,
                        progress = animatedProgress,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipPrevious = onSkipPrevious,
                        onSkipNext = onSkipNext
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // ── Bottom pills: queue / output / more ─────────────
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        FlatPill(
                            icon = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Queue",
                            onClick = onNavigateToQueue,
                            testTag = "player_queue_shortcut_button"
                        )
                        FlatPill(
                            icon = Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = "Audio Output",
                            onClick = onNavigateToVolume,
                            testTag = "player_output_button"
                        )
                        FlatPill(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = "More actions",
                            onClick = { showMoreSheet = true },
                            testTag = "player_more_button"
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        // ── ⋮ More action sheet ─────────────────────────────────────────
        if (showMoreSheet) {
            MoreSheet(
                isFavorite = track.isFavorite,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                downloadProgress = downloadProgress,
                hasTrack = hasTrack,
                onDismiss = { showMoreSheet = false },
                onToggleFavorite = {
                    showMoreSheet = false
                    onToggleFavorite()
                },
                onDownload = {
                    showMoreSheet = false
                    if (!isDownloaded && !isDownloading) onDownloadTrack(track)
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Control building blocks
// ─────────────────────────────────────────────────────────────────────────

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

/** Flat translucent pill used for the bottom row (queue / output / more). */
@Composable
private fun FlatPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.dp)
    // 44dp-tall touch box around the compact visual pill: secondary controls
    // stay comfortably tappable without competing with Play/Pause.
    Box(
        modifier = modifier
            .size(width = 46.dp, height = WearsicDimens.TouchTarget)
            .wearsicClickable(pressedScale = 0.92f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 30.dp)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/** ⋮ More bottom sheet — favourite / download.
 *
 *  The Queue action deliberately lives ONLY on the bottom pill: having
 *  it in two places made the ⋮ menu redundant and was removed. */
@Composable
private fun MoreSheet(
    isFavorite: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    hasTrack: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearsicBlack.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 10.dp, end = 10.dp, bottom = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(WearsicSurface)
                .border(1.dp, WearsicSurfaceBorder, RoundedCornerShape(24.dp))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MoreSheetRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "Favorited" else "Favorite",
                tint = Color.White.copy(alpha = 0.92f),
                enabled = hasTrack,
                onClick = onToggleFavorite,
                testTag = "player_favorite_button"
            )
            MoreSheetRow(
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
                tint = Color.White.copy(alpha = 0.92f),
                enabled = hasTrack && !isDownloaded && !isDownloading,
                onClick = onDownload,
                testTag = "player_download_button"
            )
        }
    }
}

@Composable
private fun MoreSheetRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(16.dp))
            .wearsicClickable(enabled = enabled, pressedScale = 0.98f, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else WearsicTextMuted,
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
