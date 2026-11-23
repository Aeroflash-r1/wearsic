package com.wearsic.app.ui.screens
import com.wearsic.app.ui.theme.LocalFlagshipColors
import com.wearsic.app.ui.theme.FlagshipColors
import com.wearsic.app.ui.theme.FlagshipDisplayFont
import com.wearsic.app.ui.theme.FlagshipBodyFont

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicTabularStyle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.util.rememberSystemVolumeState
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import kotlin.math.roundToInt

/**
 * SOUND — output device, sleep timer and crossfade. Volume lives on the
 * Now Playing page now: a dial-only hero mirrors the LIVE system stream
 * (a settings observer keeps it in sync when anything changes it — the
 * bezel on Now Playing, the system crown overlay, companion apps, or
 * Bluetooth absolute volume from connected earbuds).
 *
 * The dial doubles as the mute control: tap to mute, tap again to restore
 * the previous level. No +/− buttons — the old-style clicks were dropped
 * when the crown became the app-wide volume control.
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
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

    // The playback state reports the real Bluetooth product name when one is
    // connected ("Pixel Buds Pro"); anything that is not the built-in speaker
    // counts as the headphones output.
    val isHeadphonesActive = currentOutputDevice.isNotBlank() &&
        !currentOutputDevice.equals("Watch Speaker", ignoreCase = true)
    val activeDeviceLabel = if (isHeadphonesActive) currentOutputDevice else "Watch Speaker"

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            // No auto-centering: the framework otherwise anchors item 1 to the
            // viewport centre, pushing the header (and the clock) off the top
            // of the round face. We pin the list with an explicit content
            // padding instead.
            autoCentering = null,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Sound",
                    subtitle = activeDeviceLabel
                )
            }

            // ── Volume (hero) ─────────────────────────────────────────────
            item { SectionLabel("Volume") }

            item {
                // The dial alone: live, tap-to-mute. Volume steps now belong
                // to the bezel on Now Playing — no +/− buttons here anymore.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("volume_controls_container"),
                    contentAlignment = Alignment.Center
                ) {
                    VolumeDial(
                        fraction = volume.fraction,
                        isMuted = volume.isMuted,
                        enabled = volume.isAvailable,
                        onToggleMute = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            volume.toggleMute()
                        }
                    )
                }
            }

            // ── Output picker (Material 3 selectable rows) ────────────────
            item { SectionLabel("Audio Output") }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeviceChip(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        label = if (isHeadphonesActive) "Headphones" else "Speaker",
                        semanticsLabel = "Watch Speaker",
                        isSelected = !isHeadphonesActive,
                        onClick = { onOutputDeviceChanged("Watch Speaker") },
                        testTag = "output_watch_speaker",
                        modifier = Modifier.fillMaxWidth()
                    )

                    DeviceChip(
                        icon = Icons.Rounded.Headphones,
                        label = if (isHeadphonesActive) activeDeviceLabel else "Headphones",
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
                        modifier = Modifier.fillMaxWidth()
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
                    // Five chips cannot fit inside a 240dp round viewport — the
                    // last option used to get clipped at the curved edge. The
                    // row scrolls horizontally instead so every option stays
                    // reachable (touch-swipe; the crown still adjusts volume).
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        options.forEach { mins ->
                            item(key = "sleep_$mins") {
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
                        }
                        item(key = "sleep_off") {
                            OptionChip(
                                label = "Off",
                                isActive = sleepRemainingMs == 0L,
                                onClick = { onSleepTimerSet(0) },
                                testTag = "sleep_timer_off"
                            )
                        }
                    }
                    if (sleepRemainingMs > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Sleep in ${sleepRemainingMs / 60000}m ${(sleepRemainingMs % 60000) / 1000}s",
                            color = wearsicSecondaryText(),
                            style = WearsicTabularStyle.merge(
                                TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp)
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
                    // Same scrollable treatment as the sleep-timer row: five
                    // chips overflow the round viewport, so the row scrolls.
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CrossfadeLengths.SELECTABLE_MS.forEach { ms ->
                            item(key = "crossfade_$ms") {
                                OptionChip(
                                    label = CrossfadeLengths.label(ms),
                                    isActive = ms == crossfadeMs,
                                    onClick = { onCrossfadeMsChanged(ms) },
                                    testTag = "crossfade_${ms}ms"
                                )
                            }
                        }
                    }
                    if (crossfadeMs > CrossfadeLengths.OFF_MS) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tracks fade into each other",
                            color = wearsicSecondaryText(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(56.dp)) }
        }
    }
}

/** Small muted section heading above a control group. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalFlagshipColors.current.textMuted,
        fontFamily = FlagshipDisplayFont,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
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
    diameter: Dp = 104.dp
) {
    val animatedFraction by animateFloatAsState(
        targetValue = if (enabled) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "volumeArc"
    )

    val arcColor = when {
        !enabled -> LocalFlagshipColors.current.textMuted.copy(alpha = 0.4f)
        isMuted -> LocalFlagshipColors.current.textMuted
        else -> LocalWearsicAccent.current
    }
    val trackColor = LocalFlagshipColors.current.hairline

    Box(
        modifier = modifier
            .size(watchScaled(diameter))
            .clip(CircleShape)
            .background(LocalFlagshipColors.current.surface)
            .border(1.dp, LocalFlagshipColors.current.hairline, CircleShape)
            .wearsicClickable(pressedScale = 0.95f, onClick = onToggleMute)
            .semantics {
                contentDescription = when {
                    !enabled -> "Volume unavailable"
                    isMuted -> "Muted. Tap to unmute"
                    else -> "Volume ${((fraction * 10f).roundToInt() * 10).coerceIn(0, 100)} percent. Tap to mute"
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
                    else -> "${((fraction * 10f).roundToInt() * 10).coerceIn(0, 100)}%"
                },
                color = if (enabled) LocalFlagshipColors.current.textPrimary else LocalFlagshipColors.current.textMuted,
                style = TextStyle(
                    fontFamily = FlagshipDisplayFont,
                    fontSize = if (isMuted || !enabled) 17.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                )
            )
            Text(
                text = if (isMuted && enabled) "TAP TO RESUME" else "VOLUME",
                color = LocalFlagshipColors.current.textMuted,
                fontFamily = FlagshipBodyFont,
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
    val accent = LocalWearsicAccent.current
    val fill = if (isSelected) accent.copy(alpha = 0.22f) else LocalFlagshipColors.current.surface

    Box(
        modifier = modifier
            .heightIn(min = WearsicDimens.TouchTarget)
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .border(
                width = 1.dp,
                color = if (isSelected) accent else LocalFlagshipColors.current.hairline,
                shape = RoundedCornerShape(18.dp)
            )
            .wearsicClickable(pressedScale = 0.97f, onClick = onClick)
            .semantics {
                contentDescription = if (isSelected) "$semanticsLabel, selected" else semanticsLabel
            }
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // M3 filled-tonal leading icon.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) accent else LocalFlagshipColors.current.surfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) LocalFlagshipColors.current.textOnAccent else accent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                color = LocalFlagshipColors.current.textPrimary,
                fontFamily = FlagshipDisplayFont,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // M3 trailing selection indicator.
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (isSelected) accent else LocalFlagshipColors.current.textMuted, CircleShape)
                    .background(if (isSelected) accent else Color.Transparent)
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
            .background(if (isActive) LocalWearsicAccent.current else LocalFlagshipColors.current.glassFill)
            .border(
                width = 1.dp,
                color = if (isActive) Color.Transparent else LocalFlagshipColors.current.glassBorder,
                shape = CircleShape
            )
            .wearsicClickable(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag(testTag),
        // The Box is taller than the label (the touch target) — without an
        // explicit center the label sat glued to the TOP edge, reading as an
        // off-center number inside the circle (Volume screen, sleep timer +
        // crossfade chips).
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) LocalFlagshipColors.current.textOnAccent else LocalFlagshipColors.current.textPrimary,
            fontFamily = FlagshipDisplayFont,
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
