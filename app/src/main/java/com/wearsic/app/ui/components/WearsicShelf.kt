package com.wearsic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.watchScaled

/**
 * Horizontal discovery shelf (scrolling artwork carousel) for the
 * Home / Mix tabs: rounded-square artwork cards with one typed line under
 * each. Round-screen safe — the horizontal padding reserves the curved
 * chord at the shelf's mid rows so end cards are never bevel-clipped.
 *
 * Reuses the shared song-row artwork loader (Coil, cached, small request)
 * so shelf cards cost ~nothing extra in memory or bandwidth.
 */
@Composable
fun WearsicTrackShelf(
    tracks: List<Track>,
    onPlayTrack: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Rounded.Schedule,
) {
    val context = LocalContext.current
    val accent = LocalWearsicAccent.current
    val artSize = watchScaled(WearsicDimens.ShelfCardArt)
    val cardWidth = artSize
    // A wider flat chord keeps the last card inside the curve instead of
    // clipping it against the bezel.
    val shelfPadding = watchScaled(14.dp)
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = shelfPadding),
        horizontalArrangement = Arrangement.spacedBy(watchScaled(8.dp))
    ) {
        items(tracks.size) { index ->
            val track = tracks[index]
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onPlayTrack(tracks, index) }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(artSize)
                        .clip(RoundedCornerShape(WearsicDimens.ArtRadius))
                        .background(WearsicGlassFill)
                        .border(1.dp, WearsicGlassBorder, RoundedCornerShape(WearsicDimens.ArtRadius))
                ) {
                    if (track.artworkUrl != null) {
                        val request = remember(context, track.artworkUrl) {
                            ImageRequest.Builder(context)
                                .data(track.artworkUrl)
                                .size(112)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = track.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(artSize)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(artSize)
                                .background(accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = placeholderIcon,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(watchScaled(20.dp))
                            )
                        }
                    }
                }
                Spacer(Modifier.height(watchScaled(3.dp)))
                Text(
                    text = track.title,
                    color = WearsicTextPrimary,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = track.artist,
                    color = wearsicSecondaryText(),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * One mood chip for the discovery rows — compact, glassy, label-only. The
 * row itself is horizontally scrollable, so an unbounded mood budget is
 * safe on the round face.
 */
@Composable
fun WearsicMoodChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalWearsicAccent.current
) {
    // Each mood carries its own hue (passed in by the caller) so the chip
    // row reads as a colorful mood strip instead of five identical pills.
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.22f))
            .border(1.dp, tint.copy(alpha = 0.55f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = watchScaled(10.dp), vertical = watchScaled(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(watchScaled(12.dp))
            )
            Spacer(Modifier.width(watchScaled(4.dp)))
            Text(
                text = label,
                color = WearsicTextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
