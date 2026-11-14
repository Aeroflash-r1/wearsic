package com.wearsic.app.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ScreenScaffold
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.WearsicTabularStyle
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicViolet
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

/**
 * Local-music storage, mathematically consistent with the one-file-per-track
 * architecture: every physical file belongs to exactly one bucket (AUTO or
 * MANUAL), so `total local music = auto + manual`. There is no stream-cache
 * bucket — playback buffers in memory only.
 */
@Composable
fun StorageStatsContent(
    autoCount: Int,
    autoMb: Double,
    manualCount: Int,
    manualMb: Double,
    onClearAutoCached: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalMb = autoMb + manualMb
    val listState = rememberScalingLazyListState()
    val cardShape = RoundedCornerShape(WearsicDimens.CardRadius)

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .wearsicRotaryScroll(listState),
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Storage",
                    subtitle = "Total local music • %.1f MB".format(totalMb)
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(Brush.verticalGradient(listOf(WearsicSurfaceRaised, WearsicSurface)))
                        .border(1.dp, WearsicSurfaceBorderSubtle, cardShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatRow(
                        label = "Auto-saved songs",
                        detail = "$autoCount songs • %.1f MB".format(autoMb),
                        mb = autoMb,
                        totalMb = totalMb,
                        color = LocalWearsicAccent.current
                    )
                    StatRow(
                        label = "Manual downloads",
                        detail = "$manualCount songs • %.1f MB".format(manualMb),
                        mb = manualMb,
                        totalMb = totalMb,
                        color = WearsicViolet
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Total local music",
                            color = WearsicTextPrimary,
                            fontFamily = WearsicDisplayFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "%.1f MB".format(totalMb),
                            color = LocalWearsicAccent.current,
                            style = WearsicTabularStyle.merge(
                                TextStyle(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        )
                    }
                }
            }

            item {
                ActionRow(
                    icon = Icons.Rounded.Delete,
                    label = "Clear auto-saved ($autoCount)",
                    onClick = onClearAutoCached
                )
            }
            item {
                Text(
                    text = "Manual downloads are kept until you remove them.",
                    color = WearsicTextMuted,
                    fontFamily = WearsicBodyFont,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    detail: String,
    mb: Double,
    totalMb: Double,
    color: Color
) {
    // Bar = this bucket's share of total local music; full bar when it is all.
    val fill = if (totalMb > 0.0) (mb / totalMb).toFloat() else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = WearsicTextPrimary,
                fontFamily = WearsicBodyFont,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = detail,
                color = wearsicSecondaryText(),
                style = WearsicTabularStyle.merge(
                    TextStyle(fontSize = 10.sp)
                )
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            if (fill > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fill.coerceIn(0.05f, 1f))
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WearsicDimens.TouchTarget)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, WearsicGlassBorder, CircleShape)
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = LocalWearsicAccent.current, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = label,
            color = WearsicTextPrimary,
            fontFamily = WearsicDisplayFont,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
