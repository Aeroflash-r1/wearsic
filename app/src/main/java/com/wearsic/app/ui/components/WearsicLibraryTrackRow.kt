package com.wearsic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicSuccess
import com.wearsic.app.ui.theme.WearsicTextMuted

/**
 * Library row (favorites, playlists/albums, artists). The song name must stay
 * readable on a ~240dp round watch screen, so the ONLY inline action is the
 * ⋯ More button — every secondary action (play, queue, download, remove,
 * add-to-playlist) lives in the long-press / ⋯ action sheet instead of
 * crowding the card with a row of tiny buttons.
 *
 * When [isDownloaded] is true a small sage "saved offline" dot is shown next
 * to the More button, so offline availability is visible without a second
 * text line.
 */
@Composable
fun WearsicLibraryTrackRow(
    track: Track,
    onPlay: () -> Unit,
    onLongPress: () -> Unit = {},
    onMore: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    testTagPrefix: String = "library_track",
    modifier: Modifier = Modifier
) {
    WearsicSongRow(
        title = track.title,
        artist = track.artist,
        artworkUrl = track.artworkUrl,
        onClick = onPlay,
        modifier = modifier,
        onLongClick = onLongPress,
        testTag = "${testTagPrefix}_${track.id}",
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isDownloaded) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(WearsicSuccess)
                            .semantics { contentDescription = "Saved offline" }
                    )
                }
                if (onMore != null) {
                    WearsicSongRowActionButton(
                        icon = Icons.Rounded.MoreHoriz,
                        contentDescription = "More actions",
                        onClick = onMore,
                        testTag = "${testTagPrefix}_more_${track.id}",
                        tint = WearsicTextMuted
                    )
                }
            }
        }
    )
}
