package com.wearsic.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.wearsic.app.media.AudioOutputHelper
import com.wearsic.app.media.crossfade.CrossfadeLengths
import com.wearsic.app.ui.components.WearsicCircularIconButton
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.theme.WearsicAccentSky
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.WearsicTextSecondary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.rememberSystemVolumeState
import com.wearsic.app.ui.util.rotaryVolumeStep
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import kotlin.math.roundToInt

/**
 * SOUND — volume first, then output, sleep timer and crossfade.
 *
 * The hero is a circular volume dial driven by the LIVE system stream volume
 * (a settings observer keeps it in sync when anything else changes it — the
 * system crown overlay, companion apps, or Bluetooth absolute volume from
 * connected earbuds). The physical crown adjusts volume right on this screen
 * with a haptic tick per step; touch drag still scrolls the rows below.
 *
 * The dial doubles as the mute control: tap to mute, tap again to restore
 * the previous level.
 */
@Composable
fun VolumeScreen(
    currentOutputDevice: String = "Watch Speaker",
    sleepRemainingMs: Long = 0L,
    /** Timer length the user picked (0 = off) — makes the active chip exact. */
    sleepSetMinutes: Int = 0,
    crossfadeMs: Long = CrossfadeLengths.DEFAULT_MS,
    onSleepTimerSet: (Int) -> Unit = {},
    onCrossfadeMsChanged: (Long) -> Unit = {},
    onOutputDeviceChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val volume = rememberSystemVolumeState()
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberScalingLazyListState()

    // The playback state reports the real Bluetooth product name when one is
    // connected ("Pixel Buds Pro"); anything that is not the built-in speaker
    // counts as the headphones output.
    val isHeadphonesActive = currentOutputDevice.isNotBlank() &&
        !currentOutputDevice.equals("Watch Speaker", ignoreCase = true)
    val activeDeviceLabel = if (isHeadphonesActive) currentOutputDevice else "Watch Speaker"

    // Crown focus: needed once for onRotaryScrollEvent to receive events.
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
            // No focusable window yet — buttons remain fully usable.
        }
    }

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
                .focusRequester(focusRequester)
                .onRotaryScrollEvent { event ->
                    val step = rotaryVolumeStep(event.verticalScrollPixels)
                    if (step != 0 && volume.isAvailable) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        volume.step(step)
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Sound",
                    subtitle = activeDeviceLabel,
                    icon = Icons.AutoMirrored.Rounded.VolumeUp
                )
            }

            // ── Volume (hero) ─────────────────────────────────────────────
            item { SectionLabel("Volume") }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("volume_controls_container"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    WearsicCircularIconButton(
                        icon = Icons.Rounded.Remove,
                        contentDescription = "Decrease Volume",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            volume.step(-1)
                        },
                        size = 40.dp,
                        iconSize = 20.dp,
                        testTag = "volume_decrease_button"
                    )

                    VolumeDial(
                        fraction = volume.fraction,
                        isMuted = volume.isMuted,
                        enabled = volume.isAvailable,
                        onToggleMute = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            volume.toggleMute()
                        }
                    )

                    WearsicCircularIconButton(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Increase Volume",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            volume.step(1)
                        },
                        size = 40.dp,
                        iconSize = 20.dp,
                        testTag = "volume_increase_button"
                    )
                }
            }

            if (volume.isAvailable) {
                item {
                    Text(
                        text = "Turn the crown to adjust",
                        color = WearsicTextMuted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Output picker ─────────────────────────────────────────────
            item { SectionLabel("Audio Output") }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DeviceChip(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        label = "Speaker",
                        semanticsLabel = "Watch Speaker",
                        isSelected = !isHeadphonesActive,
                        onClick = { onOutputDeviceChanged("Watch Speaker") },
                        testTag = "output_watch_speaker",
                        modifier = Modifier.weight(1f)
                    )

                    DeviceChip(
                        icon = Icons.Rounded.Headphones,
                        label = "Headphones",
                        semanticsLabel = if (isHeadphonesActive) {
                            "Headphones: $activeDeviceLabel"
                        } else {
                            "Headphones"
                        },
                        isSelected = isHeadphonesActive,
                        onClick = {
                            onOutputDeviceChanged("Bluetooth Audio")
                            try {
                                context.startActivity(AudioOutputHelper.createBluetoothSettingsIntent())
                            } catch (_: Exception) {
                                // Intent fallback — the selection still applies.
                            }
                        },
                        testTag = "output_bluetooth_audio",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Sleep Timer ───────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionLabel("Sleep Timer")
            }
            item {
                val options = listOf(15, 30, 45, 60)
                // Exact when the caller knows which length was set; a rough
                // proximity match only remains for old callers/previews that
                // pass just a remaining time.
                val approxActiveMinutes =
                    if (sleepRemainingMs > 0) (sleepRemainingMs / 60000).toInt() else 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { mins ->
                            val isActive = sleepRemainingMs > 0 && if (sleepSetMinutes > 0) {
                                sleepSetMinutes == mins
                            } else {
                                approxActiveMinutes in (mins - 14)..mins
                            }
                            OptionChip(
                                label = "${mins}m",
                                isActive = isActive,
                                onClick = { onSleepTimerSet(mins) },
                                testTag = "sleep_timer_${mins}m"
                            )
                        }
                        OptionChip(
                            label = "Off",
                            isActive = sleepRemainingMs == 0L,
                            onClick = { onSleepTimerSet(0) },
                            testTag = "sleep_timer_off"
                        )
                    }
                    if (sleepRemainingMs > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sleep in ${sleepRemainingMs / 60000}m ${(sleepRemainingMs % 60000) / 1000}s",
                            color = WearsicAccentSky,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── Crossfade ─────────────────────────────────────────────────
            // Opt-in and last on the screen on purpose: it is the one control
            // here that changes how music is decoded (two players overlap for
            // the length of the fade), so it sits below the everyday controls
            // instead of in front of them.
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionLabel("Crossfade")
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CrossfadeLengths.SELECTABLE_MS.forEach { ms ->
                            OptionChip(
                                label = CrossfadeLengths.label(ms),
                                isActive = ms == crossfadeMs,
                                onClick = { onCrossfadeMsChanged(ms) },
                                testTag = "crossfade_${ms}ms"
                            )
                        }
                    }
                    if (crossfadeMs > CrossfadeLengths.OFF_MS) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tracks fade into each other",
                            color = WearsicTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** Small muted section heading above a control group. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = WearsicTextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        textAlign = TextAlign.Center
    )
}

/**
 * Circular volume dial: a 270° arc (gap at the bottom) around the level.
 * The arc animates; the label reads the true value so the number never shows
 * a half-way tick it will not stop at. Tap toggles mute.
 */
@Composable
private fun VolumeDial(
    fraction: Float,
    isMuted: Boolean,
    enabled: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 108.dp
) {
    val animatedFraction by animateFloatAsState(
        targetValue = if (enabled) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "volumeArc"
    )

    val arcColor = when {
        !enabled -> WearsicTextMuted.copy(alpha = 0.4f)
        isMuted -> WearsicTextMuted
        else -> WearsicVibrantLavender
    }
    val trackColor = Color.White.copy(alpha = 0.08f)

    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .wearsicClickable(pressedScale = 0.95f, onClick = onToggleMute)
            .semantics {
                contentDescription = when {
                    !enabled -> "Volume unavailable"
                    isMuted -> "Muted. Tap to unmute"
                    else -> "Volume ${(fraction * 100f).roundToInt()} percent. Tap to mute"
                }
            }
            .testTag("volume_dial"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2f + 1.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val sweepTotal = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (animatedFraction > 0.004f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = sweepTotal * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    !enabled -> "—"
                    isMuted -> "Muted"
                    else -> "${(fraction * 100f).roundToInt()}%"
                },
                color = if (enabled) WearsicTextPrimary else WearsicTextMuted,
                fontSize = if (isMuted || !enabled) 17.sp else 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isMuted && enabled) "TAP TO RESUME" else "VOLUME",
                color = WearsicTextMuted,
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Output chip (compact, for the side-by-side row).
 *  · selected   — flat accent fill, dark glyph + label
 *  · unselected — translucent glass fill with an accent glyph and white label
 * The label always shows on BOTH states: the old pill hid its text when
 * unselected and read as an empty button.
 */
@Composable
private fun DeviceChip(
    icon: ImageVector,
    label: String,
    semanticsLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val fill = if (isSelected) WearsicVibrantLavender else WearsicGlassFill
    val contentTint = if (isSelected) WearsicTextPrimaryDark else WearsicTextPrimary
    val iconTint = if (isSelected) WearsicTextPrimaryDark else WearsicVibrantLavender

    Box(
        modifier = modifier
            .heightIn(min = 42.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else WearsicGlassBorder,
                shape = CircleShape
            )
            .wearsicClickable(onClick = onClick)
            .semantics {
                contentDescription = if (isSelected) "$semanticsLabel, selected" else semanticsLabel
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = contentTint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * Option chip (sleep timer, crossfade): flat accent when active, glass when
 * not. One composable for both rows so the two groups cannot drift apart.
 */
@Composable
private fun OptionChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    testTag: String = ""
) {
    Box(
        modifier = Modifier
            .heightIn(min = WearsicDimens.TouchTarget)
            .clip(CircleShape)
            .background(if (isActive) WearsicVibrantLavender else WearsicGlassFill)
            .border(
                width = 1.dp,
                color = if (isActive) Color.Transparent else WearsicGlassBorder,
                shape = CircleShape
            )
            .wearsicClickable(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            color = if (isActive) WearsicTextPrimaryDark else WearsicTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun VolumeScreenPreview() {
    WearsicTheme {
        VolumeScreen()
    }
}
