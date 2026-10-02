package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.model.Track
import com.example.ui.theme.WearsicTextMuted

/**
 * Library row (favorites, playlists/albums, artists). The song name must stay
 * readable on a ~240dp round watch screen, so the ONLY inline action is the
 * ⋯ More button — every secondary action (play, queue, download, remove,
 * add-to-playlist) lives in the long-press / ⋯ action sheet instead of
 * crowding the card with a row of tiny buttons.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun WearsicLibraryTrackRow(
    track: Track,
    onPlay: () -> Unit,
    onLongPress: () -> Unit = {},
    onMore: (() -> Unit)? = null,
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
        trailing = if (onMore != null) {
            {
                WearsicSongRowActionButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = "More actions",
                    onClick = onMore,
                    testTag = "${testTagPrefix}_more_${track.id}",
                    tint = WearsicTextMuted
                )
            }
        } else {
            {}
        }
    )
}