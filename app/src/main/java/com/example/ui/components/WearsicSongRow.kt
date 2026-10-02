package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.WearsicDimens
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceBorder
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.util.WearsicMotion
import com.example.ui.util.wearsicClickable
import com.example.ui.theme.WearsicSurfaceRaised
import com.example.ui.theme.WearsicTextWhite80

/**
 * The canonical song card used across every list (search, queue, downloads,
 * library, favorites, playlists, artists). Sized for round watch screens
 * (~240dp usable width): 40dp artwork, a 2-line title at 14sp and a 12sp
 * artist line, so song names are actually readable instead of being ellipsized
 * to ~12 characters by 30dp thumbs + 10sp text + a row of tiny buttons.
 *
 * Secondary actions (queue, download, playlist…) live in the long-press
 * action sheet; rows keep at most one or two inline actions so the title
 * keeps the width.
 *
 * Touch feedback: the whole card subtly scales down while pressed (90ms tween)
 * so every row in the app feels alive without continuous animation cost.
 */
@Composable
fun WearsicSongRow(
    title: String,
    artist: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "song_row",
    titleColor: Color = WearsicTextPrimary,
    artistColor: Color = WearsicTextWhite80,
    titleMaxLines: Int = 2,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Rows enter with the list, not every time lazy scrolling recycles them.
            .clip(RoundedCornerShape(20.dp))
            .background(WearsicSurface)
            .border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(20.dp))
            .wearsicClickable(pressedScale = 0.975f, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .testTag(testTag)
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
                WearsicSongRowArtwork(artworkUrl = artworkUrl, contentDescription = title)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = artist,
                        color = artistColor,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            trailing()
        }
    }
}

/**
 * 40dp ROUNDED-SQUARE artwork (or lavender music-note placeholder) with a
 * hairline ring. Rounded squares — not circles — are the album-art language
 * of every serious music app (Spotify, Apple Music, YTM); circles read as a
 * generic template and crop away the album cover's own composition.
 */
@Composable
fun WearsicSongRowArtwork(
    artworkUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(11.dp)
    val ring = Modifier.border(1.dp, WearsicSurfaceBorderSubtle, shape)
    if (!artworkUrl.isNullOrBlank()) {
        // Built once per URL: a fresh ImageRequest on every row recomposition
        // (rows redraw during a press and on list updates) makes Coil restart
        // the load instead of reusing the already-decoded bitmap.
        val request = remember(context, artworkUrl) {
            // ~80px at watch density; 112 leaves no visible headroom for
            // scaling-based blurs/shaders but still cuts the decoded bitmap
            // roughly in half versus the old 160px request.
            ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(112)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
                .then(ring)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(WearsicLavenderContainer)
                .then(ring),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = WearsicVibrantLavender,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Small circular icon action button for song-row trailing actions.
 * Touchable area is at least 40dp on a watch (rows are tall enough that
 * adjacent targets don't collide); visual size defaults to 28dp. Squishes
 * + ticks on press.
 */
@Composable
fun WearsicSongRowActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    visualSize: Dp = 28.dp,
    tint: Color = WearsicTextMuted,
    background: Color = WearsicSurface,
    borderColor: Color = WearsicSurfaceBorder,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "songRowActionPress"
    )

    // 44dp touch box around a smaller visual disc: comfortable one-finger
    // tapping on the watch without crowding the row's text width.
    Box(
        modifier = modifier
            .size(WearsicDimens.TouchTarget)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(background)
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** Solid lavender play button used as the row's primary trailing action. */
@Composable
fun WearsicSongRowPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    size: Dp = 28.dp,
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "songRowPlayPress"
    )

    // Same 44dp touch box as the other row actions.
    Box(
        modifier = modifier
            .size(WearsicDimens.TouchTarget)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(WearsicVibrantLavender),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = WearsicTextPrimaryDark,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
