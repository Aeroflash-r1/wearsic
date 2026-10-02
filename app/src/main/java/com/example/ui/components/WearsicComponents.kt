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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import com.example.ui.theme.WearsicAccentSky
import com.example.ui.theme.WearsicAccentMint
import com.example.ui.theme.WearsicAccentPeach
import com.example.ui.theme.WearsicAccentRose
import com.example.ui.theme.WearsicAccentAmber
import com.example.ui.theme.WearsicSurfaceRaised
import com.example.ui.theme.WearsicDimens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.example.ui.theme.WearsicBlack
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
import com.example.ui.theme.WearsicTextWhite60
import com.example.ui.theme.WearsicTextWhite80
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.util.WearsicMotion
import com.example.ui.util.wearsicEntrance

/**
 * High-contrast vibrant primary pill button (e.g. "Downloads")
 * Styled with solid vibrant lavender #D0BCFF, dark icon container, and bold black text.
 */
@Composable
fun WearsicPrimaryPillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "primary_pill_button",
    subtitle: String? = null,
    badgeText: String? = null,
    backgroundColor: Color = WearsicVibrantLavender,
    contentColor: Color = WearsicTextPrimaryDark,
    iconBgColor: Color = Color(0x1A000000)
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "primaryPillPress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Minimum height (not fixed): the pill grows with font scale
            // instead of clipping larger accessibility text.
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(backgroundColor, backgroundColor.copy(alpha = 0.86f))))
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = label,
                        color = contentColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            color = contentColor.copy(alpha = 0.7f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (badgeText != null) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x26000000))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = contentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Compact pill button for secondary actions (e.g. "Playlists", "Artists")
 * Styled with #1C1B1F surface, subtle border, vibrant lavender icon, and crisp white text.
 */
@Composable
fun WearsicSecondaryPillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "secondary_pill_button"
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "secondaryPillPress"
    )

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(20.dp))
            .background(WearsicSurfaceRaised)
            .border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 14.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = WearsicVibrantLavender,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                color = WearsicTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Settings pill button styled with #1C1B1F, border, white/60 icon, and white/80 tracking text.
 */
@Composable
fun WearsicSettingsActionPill(
    label: String = "Settings",
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "settings_action_pill"
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "settingsPillPress"
    )

    Box(
        modifier = modifier
            .heightIn(min = WearsicDimens.TouchTarget)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .background(WearsicSurface)
            .border(1.dp, WearsicSurfaceBorder, CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = WearsicTextWhite60,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label.uppercase(),
                color = WearsicTextWhite80,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Circular Icon Action Button. Visual size may be small, but the touchable
 * area is always at least 48dp (Wear OS touch-target guideline) and presses
 * give haptic feedback.
 */
@Composable
fun WearsicCircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    backgroundColor: Color = WearsicSurface,
    iconTint: Color = WearsicVibrantLavender,
    borderColor: Color = WearsicSurfaceBorderSubtle,
    testTag: String = "circular_icon_button"
) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = if (pressed) WearsicMotion.Snappy else WearsicMotion.Bouncy,
        label = "circularIconPress"
    )
    Box(
        modifier = modifier
            .size(if (size < 48.dp) 48.dp else size)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .testTag(testTag),
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
                .background(backgroundColor)
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * Frosted-glass surface: translucent fill, vertical sheen, hairline border.
 * The building block of the glassmorphism look.
 */
@Composable
fun WearsicGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, shape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Screen title header styled for round Wear OS displays.
 *
 * A small section-colored marker and generous type establish hierarchy.
 * The surrounding canvas stays OLED black; decoration is local to content.
 */
@Composable
fun WearsicScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accentColor: Color = wearsicSectionAccent(title)
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.width(22.dp).height(3.dp).clip(CircleShape).background(accentColor)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = WearsicTextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.4).sp,
            // Long dynamic titles (playlist names…) wrap to two lines max,
            // then ellipsize — a header must never push the list off-screen.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = WearsicTextWhite60,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Section subheading used inside lists (e.g. "Recently Played").
 *
 * Quiet by design: a small muted icon plus an uppercase, letter-spaced label
 * in secondary white — the way production music apps separate sections
 * without shouting. Optional trailing content (a count, a "See all", …).
 */
@Composable
fun WearsicSectionTitle(
    label: String,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = WearsicTextWhite60,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label.uppercase(),
            color = WearsicTextWhite60,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(6.dp))
            trailing()
        }
    }
}

/**
 * Canonical empty state used by every list screen (Downloads, Queue,
 * Favorites, Playlists, playlist detail, Artists…). One implementation so
 * each screen's "nothing here yet" moment looks and reads identically:
 * a lavender icon medallion, a bold primary title and a muted hint line.
 */
@Composable
fun WearsicEmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconContentDescription: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(WearsicLavenderContainer)
                .border(1.dp, WearsicGlassBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = WearsicVibrantLavender,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = WearsicTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            color = WearsicTextMuted,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

private fun wearsicSectionAccent(title: String): Color = when (title) {
    "Search", "Albums" -> WearsicAccentSky
    "Downloads", "Storage" -> WearsicAccentMint
    "Favorites" -> WearsicAccentRose
    "Artists" -> WearsicAccentPeach
    "Queue", "Audio Output" -> WearsicAccentAmber
    else -> WearsicVibrantLavender
}

/**
 * Canonical inline loading state (spinner + label) used by list screens
 * while their first page of content is being fetched.
 */
@Composable
fun WearsicLoadingState(
    label: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = WearsicTextSecondary,
            fontSize = 11.sp
        )
    }
}
