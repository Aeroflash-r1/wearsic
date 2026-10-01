package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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
import com.example.ui.components.AmbientBlurTransformation
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTheme
import com.example.ui.util.ArtworkColors
import com.example.ui.util.extractArtworkColors
import com.example.ui.util.wearsicClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * NOW PLAYING — modelled on the real Pixel Watch 4 media controls.
 *
 * Everything here comes from pixel measurements of an actual Pixel Watch 4
 * media screen (`Pixel-Watch-4-media-controls-1.png`, 426x426):
 *
 *   · the album art is blurred and pushed dark with the artwork's OWN hue
 *     (measured backdrop #4E260A from warm-orange artwork) — never flat black
 *   · the skip buttons are a light, saturated tint of that hue (#FFB68A) with
 *     near-black glyphs; the centre play blob is a LIGHTER tint (#FFDBC8)
 *   · the centre blob is scalloped with a subtle dark outline that doubles as
 *     the playback-progress ring
 *   · three translucent "glass" pills sit along the bottom of the round
 *     display — queue, output and more — on a gentle arc, white glyphs
 *   · title is large and bold, the artist sits just below it
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

    // ── Artwork → Pixel Watch palette ───────────────────────────────────
    var artworkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val colors by produceState(ArtworkColors.Default, artworkBitmap) {
        value = withContext(Dispatchers.Default) { extractArtworkColors(artworkBitmap) }
    }
    val accent = colors.accent
    val blobTint = colors.blobTint
    val onAccent = colors.onAccent
    val backdrop = colors.backdrop

    val progressTarget = if (playbackState.durationMs > 0L) {
        (playbackState.currentPositionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    // The position tracker emits one tick every 2s; a linear tween of the same
    // length turns those discrete steps into a continuously sweeping ring, the
    // way Wear OS draws media progress. Held as a State and read inside the
    // Canvas, so the 60fps interpolation redraws only the blob layer instead
    // of recomposing the whole player.
    val animatedProgress = animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(durationMillis = 2000, easing = LinearEasing),
        label = "playerProgress"
    )

    // Gentle "breathing" halo behind the play button — created ONLY while
    // audio is actually running. An always-alive infinite transition keeps the
    // frame clock ticking every single frame; on a watch that is pure jank and
    // battery drain, so a paused player has no continuous animation at all.
    //
    // Held as a State and read only in the DRAW phase (see WavyPlayBlob):
    // reading it during composition instead would recompose the entire player
    // 60 times a second while playing — the single heaviest thing this screen
    // could do on a watch SoC.
    val breatheScale: State<Float> = if (playbackState.isPlaying) {
        val breathe = rememberInfiniteTransition(label = "playBreathe")
        breathe.animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breatheScale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    // The two full-screen scrims are constant per track colour; building the
    // brushes once (instead of every draw) keeps the layered background cheap.
    val washBrush = remember(backdrop) {
        Brush.verticalGradient(
            0.00f to backdrop.copy(alpha = 0.90f),
            0.34f to backdrop.copy(alpha = 0.78f),
            0.72f to backdrop.copy(alpha = 0.80f),
            1.00f to backdrop.copy(alpha = 0.92f)
        )
    }
    val edgeBrush = remember {
        Brush.verticalGradient(
            0.00f to Color.Black.copy(alpha = 0.34f),
            0.24f to Color.Transparent,
            0.80f to Color.Transparent,
            1.00f to Color.Black.copy(alpha = 0.34f)
        )
    }

    Box(modifier = modifier.fillMaxSize().background(WearsicBlack)) {

        // ── Blurred artwork, tinted dark with its own hue ───────────────
        Crossfade(
            targetState = track.artworkUrl,
            animationSpec = tween(durationMillis = 340),
            label = "playerBackdrop"
        ) { artworkUrl ->
            if (!artworkUrl.isNullOrBlank()) {
                // Remembered per URL: the player recomposes on every 2s position
                // tick, and a fresh ImageRequest each time would make Coil
                // restart the (blurred) load instead of showing the cached one.
                val request = remember(artworkUrl) {
                    ImageRequest.Builder(context)
                        .data(artworkUrl)
                        // 256 is plenty: the watch panel is ~450px and this
                        // layer is blurred anyway. Keeps bitmap memory small
                        // on a watch heap.
                        .size(256)
                        .transformations(AmbientBlurTransformation(scaleFactor = 10))
                        // No coil crossfade: the Crossfade above already animates
                        // the swap — two overlapping fades read as mush.
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { state ->
                        artworkBitmap = (state.result.drawable as? BitmapDrawable)?.bitmap
                    },
                    onError = { artworkBitmap = null },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                artworkBitmap = null
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Solid hue-matched dark, no two-colour gradient.
                        .background(backdrop)
                )
            }
        }

        // Hue-matched dark wash + extra top/bottom darkening, drawn as TWO
        // rects inside ONE modifier node rather than two stacking full-screen
        // composables: same pixels, one fewer layer for the GPU to blend.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(brush = washBrush)
                    drawRect(brush = edgeBrush)
                }
        )

        // ── Content ─────────────────────────────────────────────────────
        ScreenScaffold(modifier = Modifier.fillMaxSize()) { contentPadding: PaddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(horizontal = 10.dp)
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

                // ── Metadata ────────────────────────────────────────────
                Text(
                    text = if (hasTrack) track.title else "Nothing playing",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
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
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Transport ───────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    TintedRoundButton(
                        icon = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous Track",
                        fill = accent,
                        contentColor = onAccent,
                        onClick = onSkipPrevious,
                        testTag = "player_previous_button"
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    WavyPlayBlob(
                        isPlaying = playbackState.isPlaying,
                        isBuffering = playbackState.isBuffering,
                        progress = animatedProgress,
                        fill = blobTint,
                        contentColor = onAccent,
                        glowColor = colors.accentStrong,
                        glowScale = breatheScale,
                        onTogglePlayPause = onTogglePlayPause
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TintedRoundButton(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = "Next Track",
                        fill = accent,
                        contentColor = onAccent,
                        onClick = onSkipNext,
                        testTag = "player_next_button"
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Bottom glass pills, on a gentle arc ─────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp, Alignment.CenterHorizontally)
                ) {
                    GlassPill(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "Queue",
                        onClick = onNavigateToQueue,
                        testTag = "player_queue_shortcut_button"
                    )
                    GlassPill(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Audio Output",
                        onClick = onNavigateToVolume,
                        modifier = Modifier.offset(y = 9.dp),
                        testTag = "player_output_button"
                    )
                    GlassPill(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "More actions",
                        onClick = { showMoreSheet = true },
                        testTag = "player_more_button"
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
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
 * Round skip button. Measured on the Pixel Watch: a light saturated tint of
 * the artwork hue (#FFB68A for warm artwork) with a near-black glyph, and a
 * diameter around 23% of the display.
 */
@Composable
private fun TintedRoundButton(
    icon: ImageVector,
    contentDescription: String,
    fill: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "player_skip_button"
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(fill)
            .wearsicClickable(pressedScale = 0.90f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** Translucent "glass" pill used for the bottom row (queue / output / more). */
@Composable
private fun GlassPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 30.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .wearsicClickable(pressedScale = 0.92f, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.94f),
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * The scalloped play/pause blob, wrapped in its playback-progress ring. On
 * the Pixel Watch the progress is drawn as an arc around the play button, and
 * the blob itself is a *lighter* tint than the skip buttons.
 */
@Composable
private fun WavyPlayBlob(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: State<Float>,
    fill: Color,
    contentColor: Color,
    glowColor: Color,
    glowScale: State<Float>,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(74.dp)
            .wearsicClickable(pressedScale = 0.93f, onClick = onTogglePlayPause)
            .testTag("player_play_pause_button"),
        contentAlignment = Alignment.Center
    ) {
        // ONE Canvas: breathing halo (scaled inside the draw scope, so its
        // animated value is read at draw time only), then ring + progress +
        // scalloped blob. Previously this was two Canvas nodes plus a
        // scale() graphicsLayer, and the animated value was read during
        // composition — 60 recompositions/second while playing.
        // drawWithCache: the silhouette, the glow brush and the reusable
        // progress path are built ONCE per size/colour change, then only the
        // draw phase runs per frame. Reading progress/glowScale inside
        // onDrawBehind (never inside the cache block) is what keeps the 60fps
        // interpolation from recomposing anything.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val minSide = size.minDimension
                    val stroke = 3.dp.toPx()
                    val steps = 180
                    val scallops = 8
                    val wave = 0.075f
                    val radius = minSide * 0.335f
                    // Start at 12 o'clock and sweep CLOCKWISE so progress fills
                    // the way every Wear OS media control does.
                    val startAngle = -kotlin.math.PI.toFloat() / 2f
                    val xs = FloatArray(steps + 1)
                    val ys = FloatArray(steps + 1)
                    for (i in 0..steps) {
                        val theta = startAngle + i.toFloat() / steps * 2f * kotlin.math.PI.toFloat()
                        val ripple = 1f + wave * cos(scallops * theta)
                        xs[i] = centerX + radius * ripple * cos(theta)
                        ys[i] = centerY + radius * ripple * sin(theta)
                    }
                    // The button shape and the progress track are the SAME
                    // silhouette — the ring follows the wavy play button
                    // instead of switching to a plain circle.
                    val outline = Path().apply {
                        moveTo(xs[0], ys[0])
                        for (j in 1..steps) lineTo(xs[j], ys[j])
                        close()
                    }
                    val headPath = Path()
                    val glowBrush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = 0.45f), Color.Transparent),
                        radius = minSide * 0.58f
                    )

                    onDrawBehind {
                        scale(scale = glowScale.value, pivot = center) {
                            drawCircle(brush = glowBrush, radius = minSide * 0.58f)
                        }

                        // Button body.
                        drawPath(path = outline, color = fill)

                        // Faint track hugging the button edge.
                        drawPath(
                            path = outline,
                            color = Color.White.copy(alpha = 0.20f),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )

                        // Filled progress along the same silhouette.
                        val sweepPoints = (progress.value * steps).toInt().coerceIn(0, steps)
                        if (sweepPoints > 0) {
                            headPath.rewind()
                            headPath.moveTo(xs[0], ys[0])
                            for (j in 1..sweepPoints) headPath.lineTo(xs[j], ys[j])
                            drawPath(
                                path = headPath,
                                color = Color.White.copy(alpha = 0.88f),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
        )

        Icon(
            imageVector = when {
                isBuffering -> Icons.Rounded.HourglassEmpty
                isPlaying -> Icons.Rounded.Pause
                else -> Icons.Rounded.PlayArrow
            },
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = contentColor,
            modifier = Modifier.size(25.dp)
        )
    }
}

/** ⋮ More bottom sheet — favourite / download.
 *
 *  The Queue action deliberately lives ONLY on the bottom glass pill: having
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
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
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
            .height(46.dp)
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
