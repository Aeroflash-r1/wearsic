package com.wearsic.app.ui.util

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

/**
 * Live media volume for the Sound screen.
 *
 * The old screen read the stream volume exactly once at first composition and
 * kept it in a local `remember` — so anything that changed the volume from
 * outside (the system crown overlay, connected earbuds with absolute volume,
 * another audio app) left the screen showing a wrong number until it was
 * recreated. This state watches the system setting instead and re-reads it on
 * every change, on the UI thread.
 *
 * All writes go through [setLevel], which clamps to the device range and
 * updates this state immediately — the observer confirmation that follows is
 * a no-op, so the UI never lags behind the button.
 */
@Stable
class SystemVolumeState(private val audioManager: AudioManager?) {

    /** Device media range; at least 1 so callers can never divide by zero. */
    val max: Int = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
        .coerceAtLeast(1)

    /** Current stream index. Kept live by the settings observer. */
    var level by mutableIntStateOf(0)
        private set

    /** Latest non-zero level, so unmuting restores what the user had. */
    private var lastAudibleLevel = 1

    val fraction: Float get() = if (max <= 0) 0f else level.toFloat() / max.toFloat()

    val percent: Int get() = (fraction * 100f).roundToInt()

    val isMuted: Boolean get() = level == 0

    /** True when the device actually exposed an audio service. */
    val isAvailable: Boolean get() = audioManager != null

    /** Re-reads the stream volume; called on entry and by the observer. */
    fun syncFromSystem() {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return
        val clamped = current.coerceIn(0, max)
        level = clamped
        if (clamped > 0) lastAudibleLevel = clamped
    }

    /** Absolute set, clamped; writes through to the system stream. */
    fun applyLevel(newLevel: Int) {
        val clamped = newLevel.coerceIn(0, max)
        if (clamped == level) return
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        // Immediate UI update; the observer confirms shortly after.
        level = clamped
        if (clamped > 0) lastAudibleLevel = clamped
    }

    fun step(delta: Int) {
        applyLevel(level + delta)
    }

    /** Tap on the dial: mute, or restore the last audible level. */
    fun toggleMute() {
        if (level > 0) applyLevel(0) else applyLevel(lastAudibleLevel.coerceAtLeast(1))
    }
}

/**
 * One crown detent = exactly one volume step, in the same direction the
 * player screen already uses for seeking (positive scroll = increase).
 * Clamped per event so a fast crown spin stays precise instead of jumping.
 */
fun rotaryVolumeStep(verticalScrollPixels: Float): Int = when {
    verticalScrollPixels > 0f -> 1
    verticalScrollPixels < 0f -> -1
    else -> 0
}

/**
 * Remembers [SystemVolumeState] and keeps it live for as long as the calling
 * composable is in composition. Observing `Settings.System` (with
 * descendants) catches every path that can change media volume on a watch:
 * the crown volume overlay, the system settings UI, and Bluetooth
 * absolute-volume adjustments from connected earbuds.
 */
@Composable
fun rememberSystemVolumeState(): SystemVolumeState {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val state = remember(audioManager) { SystemVolumeState(audioManager) }

    DisposableEffect(context, state) {
        state.syncFromSystem()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                state.syncFromSystem()
            }
        }
        var registered = false
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer
            )
            registered = true
        } catch (_: Exception) {
            // No settings provider (e.g. a bare unit-test sandbox): live
            // sync is unavailable, buttons still work via setStreamVolume.
        }
        onDispose {
            if (registered) {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (_: Exception) {
                    // Already gone — nothing to do.
                }
            }
        }
    }

    return state
}
