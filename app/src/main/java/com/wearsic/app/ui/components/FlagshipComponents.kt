package com.wearsic.app.ui.components
import com.wearsic.app.ui.theme.LocalFlagshipColors

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import com.wearsic.app.ui.theme.FlagshipColors
import com.wearsic.app.ui.theme.FlagshipDisplayFont
import com.wearsic.app.ui.theme.FlagshipBodyFont
import com.wearsic.app.ui.theme.LocalWearsicAccent

/** Entrance used by flagship screens: fade + rise + gentle scale, all in
 * graphicsLayer (no recomposition per frame). */
@Composable
fun FlagshipEnter(
    modifier: Modifier = Modifier,
    delayMs: Int = 0,
    content: @Composable () -> Unit,
) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "flagshipEnter",
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 18f
            scaleX = 0.965f + 0.035f * progress
            scaleY = 0.965f + 0.035f * progress
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.35f)
        }
    ) { content() }
}

/** The two-stop aurora bar used as a signature underline on headers. */
@Composable
fun AuroraBar(
    modifier: Modifier = Modifier,
    accent: Color = LocalWearsicAccent.current,
    width: androidx.compose.ui.unit.Dp = 34.dp,
) {
    Box(
        modifier
            .width(width)
            .height(3.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(accent, LocalFlagshipColors.current.orchid, LocalFlagshipColors.current.lime)))
    )
}

/**
 * Flagship screen header: an animated aurora glow, a tiny uppercase eyebrow,
 * a large geometric title and an optional subtitle. Reads the app accent so
 * dynamic artwork colouring still works.
 */
@Composable
fun FlagshipGlowHeader(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    accentColor: Color? = null,
) {
    val accent = accentColor ?: LocalWearsicAccent.current
    var glowOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { glowOn = true }
    val glow by animateFloatAsState(
        targetValue = if (glowOn) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "headerGlow",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // A circular glow that fades fully to transparent before the
                // layout bounds — never a rectangle the round bezel has to
                // clip into a "square-screen" look.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.18f * glow),
                            FlagshipColors.GlowOrchid.copy(alpha = 0.08f * glow),
                            Color.Transparent,
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.5f,
                    ),
                    radius = size.minDimension * 0.5f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
            // Keep every line inside the round chord: at the top of the
            // circle the usable width is far narrower than the full canvas.
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                color = accent,
                fontFamily = FlagshipBodyFont,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
        } else {
            // Reserve the eyebrow's space so the title keeps its vertical
            // position on a round face — removing "WEARSIC" must not pull the
            // heading up into the top curve.
            Spacer(Modifier.height(11.dp))
        }
        Text(
            text = title,
            color = LocalFlagshipColors.current.textPrimary,
            fontFamily = FlagshipDisplayFont,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.3).sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        AuroraBar(accent = accent)
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = LocalFlagshipColors.current.textMuted,
                fontFamily = FlagshipBodyFont,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Signature flagship hero card: animated aurora glow, layered gradient fill.
 * [eyebrow] and the play glyph are optional so Home can show a quiet,
 * informational hero while the Now Playing card takes over during playback.
 */
@Composable
fun FlagshipHeroCard(
    title: String,
    caption: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    onClick: (() -> Unit)? = null,
    showPlay: Boolean = false,
    accent: Color = LocalWearsicAccent.current,
    testTag: String? = null,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "heroPress",
    )
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    val glow by animateFloatAsState(
        targetValue = if (ready) 1f else 0f,
        animationSpec = tween(520, easing = FastOutSlowInEasing),
        label = "heroGlow",
    )
    val shape = RoundedCornerShape(22.dp) // rounded rect, not a pill
    // Capture the palette BEFORE the modifier chain: drawBehind's DrawScope
    // is not a composable context, so it cannot read composition locals.
    val p = LocalFlagshipColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(104.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .drawBehind {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(
                            p.surfaceRaised,
                            p.surface,
                            p.surfaceLow,
                        )
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.26f * glow),
                            FlagshipColors.GlowOrchid.copy(alpha = 0.16f * glow),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.16f, size.height * 0.1f),
                        radius = size.maxDimension * 0.85f,
                    )
                )
            }
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                color = accent,
                fontFamily = FlagshipBodyFont,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            }
            Text(
                text = title,
                color = LocalFlagshipColors.current.textPrimary,
                fontFamily = FlagshipDisplayFont,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = caption,
                color = LocalFlagshipColors.current.textMuted,
                fontFamily = FlagshipBodyFont,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showPlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(FlagshipColors.AuroraCool)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.wear.compose.material3.Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = LocalFlagshipColors.current.textOnAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Flagship section heading with aurora-tinted icon and optional trailing slot. */
@Composable
fun FlagshipSectionTitle(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalWearsicAccent.current
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(13.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(accent, LocalFlagshipColors.current.orchid)))
        )
        Spacer(Modifier.width(7.dp))
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.height(13.dp),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label.uppercase(),
            color = LocalFlagshipColors.current.textSecondary,
            fontFamily = FlagshipDisplayFont,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}
