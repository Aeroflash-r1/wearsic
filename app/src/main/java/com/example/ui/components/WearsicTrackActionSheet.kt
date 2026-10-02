package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.WearsicSurfaceRaised
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.util.wearsicEntrance
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
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
import com.example.model.Playlist
import com.example.model.Track
import com.example.ui.theme.WearsicDimens
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.util.wearsicClickable

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
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf("menu") } // menu | pick | create
    var newName by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss)
            .testTag("track_action_sheet")
    ) {
        // The sheet is HEIGHT-CAPPED and scrollable: on a 44mm round display
        // an unbounded menu pushes its last actions below the curved edge
        // where they can never be tapped. Centering keeps it in the circular
        // safe area at any font scale.
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 12.dp)
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.84f)
                .wearsicEntrance(riseDp = 12f)
                .clip(RoundedCornerShape(26.dp))
                .background(WearsicSurfaceRaised)
                .border(1.dp, WearsicGlassBorder, RoundedCornerShape(26.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // Consume taps on the sheet body so they never reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track.title,
                color = WearsicTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.size(8.dp))

            when (mode) {
                "menu" -> {
                    SheetAction(Icons.Rounded.PlayArrow, "Play now") { onDismiss(); onPlay() }
                    SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, "Queue next") { onDismiss(); onQueue() }
                    SheetAction(Icons.Rounded.Add, "To playlist") { mode = "pick" }
                    SheetAction(Icons.Rounded.Download, "Download") { onDismiss(); onDownload() }
                    if (onRemove != null) {
                        SheetAction(Icons.Rounded.Delete, removeLabel) { onDismiss(); onRemove() }
                    }
                    SheetAction(Icons.Rounded.Close, "Close") { onDismiss() }
                }
                "pick" -> {
                    playlists.forEach { playlist ->
                        SheetAction(Icons.AutoMirrored.Rounded.QueueMusic, playlist.name) {
                            onDismiss()
                            onAddToPlaylist(playlist.id)
                        }
                    }
                    SheetAction(Icons.Rounded.Add, "New playlist") { mode = "create" }
                    SheetAction(Icons.Rounded.Close, "Back") { mode = "menu" }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, WearsicVibrantLavenderCompat, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = WearsicTextPrimary,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(WearsicVibrantLavenderCompat),
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
            .heightIn(min = WearsicDimens.SheetRowMinHeight)
            .clip(CircleShape)
            .wearsicClickable(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = WearsicVibrantLavenderCompat,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = WearsicTextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val WearsicVibrantLavenderCompat = WearsicVibrantLavender