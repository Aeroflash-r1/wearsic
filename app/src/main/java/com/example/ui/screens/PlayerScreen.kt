package com.example.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.theme.WearsicViolet
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Production-quality Player Screen for Wear OS
 * 
 * Features:
 * - Blurred album artwork backdrop with gradient scrim
 * - Clean material-you inspired color scheme
 * - Smooth animations and haptic feedback
 * - Proper visual hierarchy and spacing
 * - Accessible contrast ratios
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

    var showMoreSheet by remember { mutableStateOf(false) }

    ScreenScaffold(
        modifier = modifier
            .fillMaxSize()
            .background(WearsicBlack)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ========================================================================
            // BACKDROP: Blurred album artwork with gradient overlay
            // ========================================================================
            Crossfade(
                targetState = track.artworkUrl,
                animationSpec = tween(durationMillis = 300),
                label = "playerBackdrop"
            ) { artworkUrl ->
                if (!artworkUrl.isNullOrBlank()) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(artworkUrl)
                            .size(720)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(28.dp)
                            .scale(1.2f)
                    )
                } else {
                    // Default gradient backdrop when no artwork
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        WearsicViolet.copy(alpha = 0.15f),
                                        WearsicBlack,
                                        WearsicBlack
                                    )
                                )
                            )
                    )
                }
            }

            // Scrim for text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to WearsicBlack.copy(alpha = 0.6f),
                            0.3f to WearsicBlack.copy(alpha = 0.15f),
                            0.7f to WearsicBlack.copy(alpha = 0.2f),
                            1f to WearsicBlack.copy(alpha = 0.7f)
                        )
                    )
            )

            // ========================================================================
            // CONTENT: Clean, production-quality layout
            // ========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
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
                // 1. Live Clock - Subtle and clean
                LiveClock()

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Track Info Section - Primary content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Logo + Track Info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        // App icon badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(WearsicVibrantLavender)
                                .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = WearsicBlack,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = if (hasTrack) track.title else "No Active Track",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (hasTrack) track.artist else "Play from Library",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Transport Controls - Centerpiece
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        // Previous button
                        ControlButton(
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            onClick = onSkipPrevious,
                            testTag = "player_previous_button"
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        // Play/Pause blob - Signature element
                        WavyPlayBlob(
                            isPlaying = playbackState.isPlaying,
                            isBuffering = playbackState.isBuffering,
                            progress = if (playbackState.durationMs > 0L) {
                                (playbackState.currentPositionMs.toFloat() / playbackState.durationMs)
                                    .coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            onTogglePlayPause = onTogglePlayPause
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        // Next button
                        ControlButton(
                            icon = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            onClick = onSkipNext,
                            testTag = "player_next_button"
                        )
                    }
                }

                // 3. Bottom Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                ) {
                    // Output button - Wider pill
                    ActionPill(
                        icon = Icons.Rounded.Headphones,
                        secondaryIcon = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Audio Output",
                        onClick = onNavigateToVolume,
                        testTag = "player_output_button"
                    )

                    // More actions button
                    ActionPill(
                        icon = Icons.Rounded.MoreVert,
                        contentDescription = "More actions",
                        onClick = { showMoreSheet = true },
                        testTag = "player_more_button",
                        modifier = Modifier.size(width = 54.dp, height = 44.dp)
                    )
                }
            }

            // ========================================================================
            // MORE ACTION SHEET
            // ========================================================================
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
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleFavorite()
                    },
                    onDownload = {
                        showMoreSheet = false
                        if (!isDownloaded && !isDownloading) onDownloadTrack(track)
                    },
                    onQueue = {
                        showMoreSheet = false
                        onNavigateToQueue()
                    }
                )
            }
        }
    }
}

// ============================================================================
// COLOR SCHEME - Production quality
// ============================================================================

/** Primary control color - Clean white for buttons */
private val ControlColor = Color(0xFFFFFFFF)

/** Control background - Semi-transparent for glassmorphism effect */
private val ControlBackground = Color(0xE6FFFFFF) // White at 90% opacity

/** Progress indicator - Matches theme accent */
private val ProgressColor = WearsicVibrantLavender

// ============================================================================
// COMPOSABLE COMPONENTS
// ============================================================================

/**
 * Live clock showing current time
 */
@Composable
private fun LiveClock() {
    var text by remember { mutableStateOf(formatClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val fresh = formatClock()
            if (fresh != text) text = fresh
        }
    }
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center
    )
}

/**
 * The signature wavy play/pause blob with progress ring
 */
@Composable
private fun WavyPlayBlob(
    isPlaying: Boolean,
    isBuffering: Boolean,
    progress: Float,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "blobPress"
    )

    Box(
        modifier = modifier
            .size(66.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTogglePlayPause()
            }
            .testTag("player_play_pause_button"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) * 0.46f
            val strokeW = 2.dp.toPx()
            val path = Path()

            // Create wavy blob shape (8 lobes)
            val steps = 180
            val scallops = 8
            val wave = 0.07f
            for (i in 0..steps) {
                val theta = i.toFloat() / steps * 2f * kotlin.math.PI.toFloat()
                val ripple = 1f + wave * cos(scallops * theta)
                val x = size.width / 2f + radius * ripple * cos(theta)
                val y = size.height / 2f + radius * ripple * sin(theta)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()

            // Fill with clean white
            drawPath(path = path, color = ControlBackground)

            // Outline
            drawPath(
                path = path,
                color = Color.Black.copy(alpha = 0.12f),
                style = Stroke(width = strokeW, cap = StrokeCap.Round)
            )

            // Progress ring in theme accent color
            if (progress > 0f) {
                val measure = PathMeasure()
                measure.setPath(path, false)
                val total = measure.length
                if (total > 0f) {
                    val trace = Path()
                    measure.getSegment(0f, total * progress, trace, true)
                    drawPath(
                        path = trace,
                        color = ProgressColor,
                        style = Stroke(width = strokeW + 0.5f, cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Play/Pause/Buffering icon
        Icon(
            imageVector = when {
                isBuffering -> Icons.Rounded.HourglassEmpty
                isPlaying -> Icons.Rounded.Pause
                else -> Icons.Rounded.PlayArrow
            },
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = WearsicViolet,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Circular control button for skip previous/next
 */
@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "player_control_button"
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "controlPress"
    )

    Box(
        modifier = modifier
            .size(52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(ControlBackground)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = WearsicViolet,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Action pill button for bottom bar
 */
@Composable
private fun ActionPill(
    icon: ImageVector,
    secondaryIcon: ImageVector? = null,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "pillPress"
    )

    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(24.dp))
            .background(ControlBackground)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        if (secondaryIcon != null) {
            // Composite icon layout
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = WearsicViolet,
                modifier = Modifier.size(20.dp)
            )
            Icon(
                imageVector = secondaryIcon,
                contentDescription = null,
                tint = WearsicViolet,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 5.dp)
                    .size(12.dp)
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = WearsicViolet,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * More actions bottom sheet
 */
@Composable
private fun MoreSheet(
    isFavorite: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    hasTrack: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WearsicBlack.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(WearsicSurface)
                .border(1.dp, WearsicSurfaceBorder, RoundedCornerShape(28.dp))
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MoreSheetRow(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFavorite) "Favorited" else "Favorite",
                tint = if (isFavorite) WearsicVibrantLavender else Color.White.copy(alpha = 0.92f),
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
                    isDownloaded -> "Downloaded"
                    else -> "Download"
                },
                tint = if (isDownloaded || isDownloading) WearsicVibrantLavender else Color.White.copy(alpha = 0.92f),
                enabled = hasTrack && !isDownloaded && !isDownloading,
                onClick = onDownload,
                testTag = "player_download_button"
            )
            MoreSheetRow(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                label = "Queue",
                tint = WearsicVibrantLavender,
                enabled = true,
                onClick = onQueue,
                testTag = "player_queue_button"
            )
        }
    }
}

/**
 * Individual row in the more sheet
 */
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
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else WearsicTextMuted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            color = if (enabled) Color.White.copy(alpha = 0.96f) else WearsicTextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatClock(): String {
    val now = LocalTime.now()
    return String.format("%02d:%02d", now.hour, now.minute)
}

// ============================================================================
// PREVIEW
// ============================================================================

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun PlayerScreenPreview() {
    WearsicTheme {
        PlayerScreen(
            playbackState = PlaybackUiState(
                currentTrack = Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                isPlaying = true,
                durationMs = 240_000L,
                currentPositionMs = 95_000L,
                playlist = listOf(
                    Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                    Track(id = "2", title = "Don't Dream It's Over", artist = "Crowded House")
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
