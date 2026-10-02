package com.example.ui.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import com.example.ui.theme.WearsicDimens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.example.media.AudioOutputHelper
import com.example.ui.components.WearsicCircularIconButton
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.theme.WearsicAccentSky
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.wearsicListContentPadding
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

/**
 * SOUND — output picker, volume and sleep timer.
 *
 * Rebuilt flat and Wear-native: one solid accent (no multi-colour gradients),
 * an output row where the ACTIVE device is a filled accent pill with a dark
 * glyph (the same tinted-control language as the player) and the inactive one
 * is a glass pill, a big readable volume number over a rounded bar, and the
 * sleep timer as a row of chips.
 */
@Composable
fun VolumeScreen(
    currentOutputDevice: String = "Watch Speaker",
    sleepRemainingMs: Long = 0L,
    onSleepTimerSet: (Int) -> Unit = {},
    onOutputDeviceChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    // Read initial system media volume
    val maxVol = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }
    val initialVolPercent = remember {
        val cur = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
        ((cur.toFloat() / maxVol.toFloat()) * 100).toInt()
    }

    var volumeLevel by remember { mutableIntStateOf(initialVolPercent) }
    val listState = rememberScalingLazyListState()

    // "Bluetooth Audio", "Bluetooth: Buds 2", … all count as the headphones
    // output; anything that isn't the built-in speaker is headphones.
    val isHeadphonesActive =
        currentOutputDevice.isNotBlank() && !currentOutputDevice.equals("Watch Speaker", ignoreCase = true)
    val activeDeviceLabel = if (isHeadphonesActive) "Headphones" else "Watch Speaker"

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Sound",
                    subtitle = activeDeviceLabel,
                )
            }

            // ── Output picker ─────────────────────────────────────────────
            item { SectionLabel("Audio Output") }

            item {
                DevicePill(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    label = "Watch Speaker",
                    isSelected = !isHeadphonesActive,
                    onClick = { onOutputDeviceChanged("Watch Speaker") },
                    testTag = "output_watch_speaker"
                )
            }

            item {
                DevicePill(
                    icon = Icons.Rounded.Headphones,
                    label = "Headphones",
                    isSelected = isHeadphonesActive,
                    onClick = {
                        onOutputDeviceChanged("Bluetooth Audio")
                        try {
                            context.startActivity(AudioOutputHelper.createBluetoothSettingsIntent())
                        } catch (_: Exception) {
                            // Intent fallback
                        }
                    },
                    testTag = "output_bluetooth_audio"
                )
            }

            // ── Volume ────────────────────────────────────────────────────
            item { SectionLabel("Volume") }

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("volume_controls_container"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        WearsicCircularIconButton(
                            icon = Icons.Rounded.Remove,
                            contentDescription = "Decrease Volume",
                            onClick = {
                                if (volumeLevel > 0) {
                                    volumeLevel = (volumeLevel - 10).coerceAtLeast(0)
                                    val streamVal = ((volumeLevel / 100f) * maxVol).toInt()
                                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, streamVal, 0)
                                }
                            },
                            size = 34.dp,
                            iconSize = 18.dp,
                            testTag = "volume_decrease_button"
                        )

                        Text(
                            text = if (volumeLevel == 0) "Muted" else "$volumeLevel%",
                            color = WearsicTextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        WearsicCircularIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Increase Volume",
                            onClick = {
                                if (volumeLevel < 100) {
                                    volumeLevel = (volumeLevel + 10).coerceAtMost(100)
                                    val streamVal = ((volumeLevel / 100f) * maxVol).toInt()
                                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, streamVal, 0)
                                }
                            },
                            size = 34.dp,
                            iconSize = 18.dp,
                            testTag = "volume_increase_button"
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    VolumeBar(fraction = volumeLevel / 100f)
                }
            }

            // ── Sleep Timer ───────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionLabel("Sleep Timer")
            }
            item {
                val options = listOf(15, 30, 45, 60)
                val activeMinutes = if (sleepRemainingMs > 0) (sleepRemainingMs / 60000).toInt() else 0
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { mins ->
                            val isActive = sleepRemainingMs > 0 && activeMinutes in (mins - 14)..mins
                            SleepChip(
                                label = "${mins}m",
                                isActive = isActive,
                                onClick = { onSleepTimerSet(mins) }
                            )
                        }
                        SleepChip(
                            label = "Off",
                            isActive = sleepRemainingMs == 0L,
                            onClick = { onSleepTimerSet(0) }
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
 * Output pill.
 *  · selected   — flat accent fill, dark icon + label (same language as the
 *                 player's tinted controls)
 *  · unselected — translucent glass fill with an accent icon and white label
 */
@Composable
private fun DevicePill(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val fill = if (isSelected) WearsicVibrantLavender else WearsicGlassFill
    val contentTint = if (isSelected) WearsicTextPrimaryDark else WearsicTextPrimary
    val iconTint = if (isSelected) WearsicTextPrimaryDark else WearsicVibrantLavender

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(CircleShape)
            .background(fill)
            .border(
                width = 1.dp,
                color = if (isSelected) Color.Transparent else WearsicGlassBorder,
                shape = CircleShape
            )
            .wearsicClickable(onClick = onClick)
            .semantics {
                contentDescription = if (isSelected) "$label, selected" else label
            }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(9.dp))
                Text(
                    text = label,
                    color = contentTint,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Rounded, single-colour volume level bar. */
@Composable
private fun VolumeBar(fraction: Float, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
    ) {
        if (clamped > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(WearsicVibrantLavender)
            )
        }
    }
}

/** Sleep-timer chip: flat accent when active, glass when not. */
@Composable
private fun SleepChip(label: String, isActive: Boolean, onClick: () -> Unit) {
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
