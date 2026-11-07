package com.wearsic.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicPillGlassBrush
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.util.WearsicGeometry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The persistent mini-player pill.
 *
 * A compact, round-safe bar that floats above Hub navigation on every
 * discover screen — one artwork card, the active title (marquee), a
 * play/pause control and a hairline progress line. The pill never covers
 * the curved edge: its width is the chord of its outermost row (see
 * [WearsicGeometry.edgeBarMaxWidthSafe]) reduced by safety margins.
 *
 * It owns NO playback surface of its own — it renders the shared
 * Media3-backed [PlaybackUiState] and forwards actions through the same
 * lambdas the fullscreen Player uses. A ticking position is interpolated
 * locally at 500ms steps so the progress line moves smoothly without
 * extra ViewModel ticks (the controller ticks every 2s).
 *
 * Deliberately: no click on artwork, no drag, no seek — small target,
 * restrained behaviour.
 */
@Composable
fun WearsicMiniPlayer(
    playbackState: State<PlaybackUiState>,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outer = playbackState.value
    val hasTrack = outer.currentTrack.id.isNotBlank()

    // Reserves list bottom space exactly once per pill — read by
    // wearsicListContentPadding on every tab screen through the Local.
    val reserve = wearsicMiniPlayerReserve()
    if (!hasTrack) return

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val accent = LocalWearsicAccent.current
        val barHeight = watchScaled(WearsicDimens.MiniPlayerBarHeight)
        val edgeInset = watchScaled(WearsicDimens.MiniPlayerEdgeInset)
        val artRounded = watchScaled(WearsicDimens.ArtRadius)

        // Pixel geometry: the bar's widest allowed width is the chord of
        // the row it touches the bezel curve with, minus a safety skirt.
        val barHpx = with(density) { barHeight.toPx() }
        val insetPx = with(density) { edgeInset.toPx() }
        val safetyPx = with(density) { 2.dp.toPx() }
        val desiredWidthPx = with(density) { watchScaled(132.dp).toPx() }
        val allowedPx = WearsicGeometry.edgeBarMaxWidthSafe(
            widthPx = constraints.maxWidth,
            heightPx = constraints.maxHeight,
            edgeInsetPx = insetPx,
            safetyPx = safetyPx
        )
        val barWidthPx = min(desiredWidthPx, allowedPx)

        // Position interpolation: the shared controller pushes a tick every
        // 2s; a 500ms local stepper keeps the progress line continuous at
        // zero extra ViewModel cost. Reset on track change.
        var smoothOffsetMs by remember { mutableLongStateOf(0L) }
        LaunchedEffect(outer.currentTrack.id) { smoothOffsetMs = 0L }
        LaunchedEffect(outer.isPlaying) {
            if (outer.isPlaying) {
                var last = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(500)
                    val now = SystemClock.elapsedRealtime()
                    smoothOffsetMs += (now - last).coerceAtLeast(0L)
                    last = now
                }
            }
        }
        val durationMs = outer.durationMs
        val positionMs = min(
            outer.currentPositionMs + smoothOffsetMs,
            if (durationMs > 0) durationMs else outer.currentPositionMs + smoothOffsetMs
        )
        val progressFraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0.01f, 1f)
        } else 0f

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = edgeInset)
        ) {
            // ---- The pill ----
            Box(
                modifier = Modifier
                    .width(with(density) { barWidthPx.toDp() })
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(WearsicPillGlassBrush)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenPlayer
                    )
                    .semantics { contentDescription = "Open Now Playing" }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Artwork (or accent note placeholder)
                    val context = LocalContext.current
                    val artSize = barHeight - 10.dp
                    if (outer.currentTrack.artworkUrl != null) {
                        val request = remember(context, outer.currentTrack.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(outer.currentTrack.artworkUrl)
                                .size(96)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier
                                .size(artSize)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.14f))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(artSize)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.wear.compose.material3.Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // Title — one marquee line keeps the pill quiet.
                    WearsicMarqueeText(
                        text = outer.currentTrack.title.ifBlank { "—" },
                        color = WearsicTextPrimary,
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    // Play / pause — small but paced inside the 34dp pill.
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.20f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTogglePlayPause
                            )
                            .semantics { contentDescription = if (outer.isPlaying) "Pause" else "Play" },
                        contentAlignment = Alignment.Center
                    ) {
                        if (outer.isBuffering && !outer.isPlaying) {
                            androidx.wear.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            androidx.wear.compose.material3.Icon(
                                imageVector = if (outer.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                // Hairline progress line hugging the pill's bottom curve.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(progressFraction)
                        .height(2.dp)
                        .background(accent.copy(alpha = 0.85f))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(WearsicGlassBorder.copy(alpha = 0.24f))
                )
            }
        }
    }
}

/**
 * Total bottom list reserve the mini-player requires: pill height + its
 * edge inset + a small visual gap under the pill. Mirrors the layout maths
 * in [WearsicMiniPlayer], provided to [com.wearsic.app.ui.theme.wearsicListContentPadding]
 * through [com.wearsic.app.ui.theme.LocalWearsicMiniPlayerReserve].
 */
@Composable
fun wearsicMiniPlayerReserve(): androidx.compose.ui.unit.Dp =
    watchScaled(WearsicDimens.MiniPlayerBarHeight) +
        watchScaled(WearsicDimens.MiniPlayerEdgeInset) +
        watchScaled(WearsicDimens.MiniPlayerBelowGap)
