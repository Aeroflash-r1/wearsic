package com.wearsic.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.wearsic.app.ui.theme.LocalFlagshipColors
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance

/**
 * The safe drawing column for a round Wear face.
 *
 * A centred column of 68% of the display's short side, inset 34 dp from the
 * top and bottom, is guaranteed to sit inside the circle: at those extremes
 * the chord of the round face is still wider than the column. That means a
 * full-screen sub-page can use normal full-width rows and never get sliced
 * by the bezel, no matter how its body scrolls.
 *
 * This replaces the old per-row "chord" maths, which measured its own height
 * as if it were the display and therefore left the header/edges unprotected.
 */
@Composable
fun WearsicRoundColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val side = if (maxWidth < maxHeight) maxWidth else maxHeight
        val safeWidth = side * 0.68f
        Column(
            modifier = Modifier
                .width(safeWidth)
                .fillMaxHeight()
                .padding(vertical = 34.dp),
            content = content,
        )
    }
}

/**
 * A full-screen Wear OS sub-page (create / rename / delete / confirm).
 *
 * Header with a close target, a vertically-centred scrollable body, and an
 * optional fixed footer for the primary action. Everything lives inside
 * [WearsicRoundColumn], so the round bezel can never cut a corner off.
 */
@Composable
fun WearsicDialogPanel(
    onDismiss: () -> Unit,
    title: String,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    WearsicRoundColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .wearsicEntrance(riseDp = 8f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = LocalFlagshipColors.current.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .wearsicClickable(pressedScale = 0.86f, onClick = onDismiss)
                    .testTag("dialog_close"),
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
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                content()
            }
        }

        if (footer != null) {
            Spacer(modifier = Modifier.height(6.dp))
            footer()
        }
    }
}

/**
 * The one pill button used by every sub-page footer, so Create / Delete /
 * Cancel are always the same size and shape.
 */
@Composable
fun WearsicDialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalFlagshipColors.current
    val accent = LocalWearsicAccent.current
    val background = when {
        destructive -> WearsicError.copy(alpha = 0.24f)
        filled -> accent.copy(alpha = if (enabled) 1f else 0.35f)
        else -> colors.glassFill
    }
    val borderColor = when {
        destructive -> WearsicError
        filled -> Color.Transparent
        else -> colors.glassBorder
    }
    val textColor = when {
        destructive -> WearsicError
        filled -> colors.textOnAccent
        else -> colors.textPrimary
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, borderColor, CircleShape)
            .wearsicClickable(enabled = enabled, pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) textColor else textColor.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
