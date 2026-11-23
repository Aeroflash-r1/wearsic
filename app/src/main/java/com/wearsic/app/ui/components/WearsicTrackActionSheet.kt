package com.wearsic.app.ui.components
import com.wearsic.app.ui.theme.LocalFlagshipColors
import com.wearsic.app.ui.theme.FlagshipDisplayFont
import com.wearsic.app.ui.theme.FlagshipBodyFont

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.util.wearsicEntrance
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.util.wearsicClickable

/**
 * Long-press action sheet for any track row (glassmorphism overlay):
 *   ▶ Play now · ＋ Queue · ＋ To Playlist ▸ · ⬇ Download · 🗑 Remove? · ✕
 * Playlist mode lists server playlists plus "New playlist" (inline name input).
 * [onRemove] is optional — pass it (e.g. from favorites/playlist detail) to
 * surface the destructive action here instead of as an inline row button.
 */
@Composable
fun WearsicTrackActionSheet(
    track: Track,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove",
    onToggleFavorite: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf("menu") } // menu | pick | create
    var newName by remember { mutableStateOf("") }

    // FULL-SCREEN Wear OS page: no floating card. The whole face is the
    // screen, with a proper header, scrollable rows and a round-safe column
    // (see WearsicRoundColumn) so nothing is ever sliced by the bezel.
    WearsicRoundColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("track_action_sheet")
            .wearsicEntrance(riseDp = 8f)
    ) {
        // Header row: title + optional subtitle + close.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = LocalWearsicAccent.current,
                    fontFamily = FlagshipDisplayFont,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    color = wearsicSecondaryText(),
                    fontFamily = FlagshipBodyFont,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .wearsicClickable(pressedScale = 0.86f, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = LocalFlagshipColors.current.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LocalFlagshipColors.current.glassBorder)
        )

        // Body: scrollable, one full-width action per row, centred when it
        // is shorter than the viewport.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {

            when (mode) {
                "menu" -> {
                    SheetAction(Icons.Rounded.PlayArrow, "Play now") { onDismiss(); onPlay() }
                    SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue next") { onDismiss(); onQueue() }
                    SheetAction(Icons.Rounded.Add, "To playlist") { mode = "pick" }
                    SheetAction(Icons.Rounded.Download, "Download") { onDismiss(); onDownload() }
                    if (onToggleFavorite != null) {
                        SheetAction(
                            if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (isFavorite) "Remove favorite" else "Add favorite"
                        ) { onDismiss(); onToggleFavorite() }
                    }
                    if (onRemove != null) {
                        SheetAction(Icons.Rounded.Delete, removeLabel) { onDismiss(); onRemove() }
                    }
                }
                "pick" -> {
                    playlists.forEach { playlist ->
                        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, playlist.name) {
                            onDismiss()
                            onAddToPlaylist(playlist.id)
                        }
                    }
                    SheetAction(Icons.Rounded.Add, "New playlist") { mode = "create" }
                    Spacer(modifier = Modifier.size(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(1.dp)
                            .background(LocalFlagshipColors.current.glassBorder)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    SheetAction(Icons.Rounded.Close, "Back") { mode = "menu" }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, LocalWearsicAccent.current, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = LocalFlagshipColors.current.textPrimary,
                                fontFamily = FlagshipBodyFont,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(LocalWearsicAccent.current),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val n = newName.trim()
                                    if (n.isNotBlank()) {
                                        onDismiss()
                                        onCreatePlaylistAndAdd(n)
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    SheetAction(Icons.Rounded.Add, "Create & add song") {
                        val n = newName.trim()
                        if (n.isNotBlank()) {
                            onDismiss()
                            onCreatePlaylistAndAdd(n)
                        }
                    }
                    SheetAction(Icons.Rounded.Close, "Back") { mode = "pick" }
                }
            }
        }
    }
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Comfortable one-finger touch height on the 44mm display; grows
            // with font scale instead of clipping larger text.
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(18.dp))
            .wearsicClickable(pressedScale = 0.97f, onClick = onClick)
            .background(LocalFlagshipColors.current.surface)
            .border(1.dp, LocalFlagshipColors.current.hairline, RoundedCornerShape(18.dp))
            .padding(horizontal = 9.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(LocalWearsicAccent.current.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = LocalWearsicAccent.current,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = LocalFlagshipColors.current.textPrimary,
            fontFamily = FlagshipBodyFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

