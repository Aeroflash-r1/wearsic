package com.wearsic.app.media.crossfade

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.wearsic.app.StartupDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CROSSFADE ENGINE — two players, one session.
 *
 * A single ExoPlayer plays one item at a time, so an *overlapping* fade is
 * impossible without a second decoder. This engine therefore drives a pair:
 *
 *  - the **session player** is whatever [MediaSession.getPlayer] currently
 *    returns — the one the watch UI, the media notification and every
 *    MediaController command act on;
 *  - the **standby player** is idle, muted and silent until a fade needs it —
 *    and after a hand-off it is the player the session was taken from, kept
 *    for the next transition (see the `standby` field's invariant).
 *
 * A transition runs in two phases so that nothing audible can go wrong cheaply
 * (all rules live in [CrossfadeRules], which is unit-tested on the JVM):
 *
 *  1. **PREPARING** — one queue-length before the end of the outgoing track,
 *     the standby is given a copy of the session's queue, parked on the next
 *     item and told to buffer. Nothing audible has changed yet: the session,
 *     the queue, the output device and audio focus are all still the outgoing
 *     track's, so if the user pauses or skips during this phase the engine
 *     simply drops the standby.
 *  2. **FADING** — once the standby is READY and the track is inside the fade
 *     window, focus and the session move to the incoming player, the outgoing
 *     player is told to PAUSE at the end of its own item instead of rolling on
 *     into the next one, and both volumes are ramped along
 *     [CrossfadeCurve]'s equal-power curve.
 *
 * Everything here is opt-in: with the preference at [CrossfadeLengths.OFF_MS]
 * the engine never creates a standby player, never requests a second audio
 * focus and never touches the session — playback is exactly the single-player
 * path it was before this feature existed.
 *
 * Cost note: the outgoing and incoming streams overlap for the fade's
 * duration, which means two decoders and (briefly) two sockets. That is why
 * the window is capped at [CrossfadeLengths.SELECTABLE_MS]'s maximum and why
 * the feature ships OFF: the extra work is the user's to opt into.
 */
@OptIn(UnstableApi::class)
class WearsicCrossfadeEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaSession,
    private val audioAttributes: AudioAttributes,
    /** Creates the standby on first use — never called while the feature is off. */
    private val standbyFactory: () -> ExoPlayer,
    private val crossfadeMsFlow: Flow<Long>
) {

    private companion object {
        /** Ramp cadence: 50 ms keeps a 2 s fade smooth (40 steps). */
        const val FADE_TICK_MS = 50L

        /**
         * Watch cadence while no ramp is running. The window opens one tick
         * late at worst, and [CrossfadeRules.effectiveFadeMs] compresses the
         * ramp to the audio that is actually left, so a coarser tick costs
         * nothing audible and saves ~20 wakeups/second on a watch.
         */
        const val WATCH_TICK_MS = 1_000L
    }

    private var configuredFadeMs = CrossfadeLengths.DEFAULT_MS
    private var phase = CrossfadePhase.IDLE

    /**
     * The player that is NOT bound to the session right now — always, in every
     * phase. While a transition is being armed that is the incoming player
     * being buffered; once the session has been handed over (FADING) it is the
     * outgoing tail being faded down and then demoted to the next transition's
     * standby.
     *
     * The invariant is what makes the rest of this file safe: [cancel] and
     * [finishFade] stop and clear *this* player, never the session player, so
     * the hand-off cannot tear down the player it just handed the session to.
     */
    private var standby: ExoPlayer? = null
    private var preparedIndex = -1
    private var preparedQueueSize = -1
    private var fadeMs = 0L
    private var fadeStartedAtMs = 0L

    /**
     * The user's volume scale (the one the app writes through
     * MediaController.volume) captured when a fade begins, so the ramp bends
     * relative to it and can restore it exactly on abort or completion.
     */
    private var userVolume = 1f

    private var tickerJob: Job? = null
    private var configJob: Job? = null

    /**
     * Who the session is bound to right now. Read through the session rather
     * than cached, because the engine is the thing that moves it — a cached
     * copy could disagree with the watch UI after a hand-off.
     */
    private val active: ExoPlayer get() = session.player as ExoPlayer

    fun start() {
        if (configJob != null) return
        configJob = scope.launch {
            crossfadeMsFlow.collect { stored ->
                val sanitized = CrossfadeLengths.sanitize(stored)
                if (sanitized == configuredFadeMs) return@collect
                configuredFadeMs = sanitized
                // Turning the feature off mid-transition must not leave a
                // half-faded pair behind.
                if (configuredFadeMs <= CrossfadeLengths.OFF_MS) cancel()
                syncTicker()
            }
        }
        syncTicker()
    }

    fun release() {
        configJob?.cancel()
        configJob = null
        tickerJob?.cancel()
        tickerJob = null
        phase = CrossfadePhase.IDLE
        preparedIndex = -1
        preparedQueueSize = -1
    }

    /**
     * Stop any in-flight transition and leave both players quiet and clean.
     * Called when the user takes over (the media session is being torn down),
     * when the preference is switched off, and whenever a rule says the world
     * moved underneath a transition. Idempotent.
     */
    fun cancel() {
        // Journalled because a dropped transition is otherwise invisible: the
        // only other lines in the log are the ones written once a fade has
        // already started. Silence plus a phase here says "the window closed
        // while this was still being armed".
        if (phase != CrossfadePhase.IDLE) {
            StartupDiagnostics.log(context, "crossfade-cancelled phase=$phase")
        }
        try {
            if (phase == CrossfadePhase.FADING) {
                // The ramp was bending the session player's volume; restoring
                // it here is what keeps the next play press at full loudness.
                active.volume = userVolume
            }
            standby?.let { tail ->
                tail.stop()
                tail.volume = 1f
                tail.setPauseAtEndOfMediaItems(false)
                tail.clearMediaItems()
            }
        } catch (_: Exception) {
            // A crossfade is an enhancement; it must never take playback down.
        }
        preparedIndex = -1
        preparedQueueSize = -1
        phase = CrossfadePhase.IDLE
    }

    /**
     * The ticker only exists while the feature is enabled, so a watch with
     * crossfade off pays nothing at all for it — not even a wakeup.
     */
    private fun syncTicker() {
        val wanted = configuredFadeMs > CrossfadeLengths.OFF_MS
        if (wanted && tickerJob == null) {
            tickerJob = scope.launch {
                while (currentCoroutineContext().isActive) {
                    tick()
                    delay(if (phase == CrossfadePhase.FADING) FADE_TICK_MS else WATCH_TICK_MS)
                }
            }
        } else if (!wanted) {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    private fun tick() {
        try {
            val player = active
            when (phase) {
                CrossfadePhase.IDLE -> maybePrepare(player)
                CrossfadePhase.PREPARING -> maybeBeginFade(player)
                CrossfadePhase.FADING -> advanceFade(player)
            }
        } catch (_: Exception) {
            // Same contract as cancel(): never let this feature fail playback.
            cancel()
        }
    }

    // ============================================================================
    // Phase 1 — arm the standby (nothing audible yet)
    // ============================================================================

    private fun maybePrepare(player: ExoPlayer) {
        val eligible = CrossfadeRules.isEligible(
            fadeMs = configuredFadeMs,
            queueSize = player.mediaItemCount,
            hasNextItem = player.hasNextMediaItem(),
            repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            durationMs = player.duration
        )
        if (!CrossfadeRules.shouldPrepare(
                fadeMs = configuredFadeMs,
                remainingMs = remainingMs(player),
                eligible = eligible,
                // Only reachable in IDLE, so no ramp of ours is still audible;
                // a standby that is winding down is never re-armed.
                standbyBusy = phase != CrossfadePhase.IDLE
            )
        ) {
            return
        }

        val nextIndex = player.currentMediaItemIndex + 1
        if (nextIndex !in 0 until player.mediaItemCount) return

        val incoming = standbyPlayer()
        // The standby holds the WHOLE queue, not just the next song: after the
        // hand-off it becomes the session player, and the watch UI derives its
        // position, "has next" and queue screen from that timeline.
        incoming.setMediaItems(queueSnapshot(player), nextIndex, 0L)
        incoming.volume = 0f
        // Repeat/shuffle are per-player, so the incoming inherits them here or
        // the mode would silently reset mid-album at the first crossfade.
        incoming.repeatMode = player.repeatMode
        incoming.shuffleModeEnabled = player.shuffleModeEnabled
        incoming.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
        incoming.prepare()

        preparedIndex = nextIndex
        preparedQueueSize = player.mediaItemCount
        phase = CrossfadePhase.PREPARING
        StartupDiagnostics.log(context, "crossfade-preparing index=$nextIndex fade=${configuredFadeMs}ms")
    }

    // ============================================================================
    // Phase 2 — overlap the two players
    // ============================================================================

    private fun maybeBeginFade(player: ExoPlayer) {
        val incoming = standby ?: return cancel()

        if (CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = player.isPlaying,
                activeIndex = player.currentMediaItemIndex,
                preparedIndex = preparedIndex,
                activeQueueSize = player.mediaItemCount,
                preparedQueueSize = preparedQueueSize
            )
        ) {
            return cancel()
        }

        val remaining = remainingMs(player)
        // The window closed while the incoming stream was still buffering: the
        // plain hand-off takes over rather than fading into a silent stream.
        if (remaining <= 0L) return cancel()

        if (!CrossfadeRules.shouldBeginFade(
                fadeMs = configuredFadeMs,
                remainingMs = remaining,
                standbyReady = incoming.playbackState == Player.STATE_READY
            )
        ) {
            return
        }

        userVolume = player.volume
        fadeMs = CrossfadeRules.effectiveFadeMs(configuredFadeMs, remaining)

        // Focus moves BEFORE the incoming player starts. Both players live in
        // this process and would each request focus for the same audio usage;
        // Media3 hands focus loss to the older request, which would pause the
        // outgoing player at the fade's first frame and cut the ramp off.
        player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
        incoming.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

        // Without this the outgoing player would roll on into the NEXT queue
        // item the moment its own track ends — the same song the incoming
        // player is already playing, i.e. a doubled, slightly-offset track.
        player.setPauseAtEndOfMediaItems(true)

        incoming.play()
        // The session moves at the START of the ramp: the watch should show the
        // new song (artwork, title, notification, progress) while the old one
        // is still fading out, which is the order a crossfade is read in.
        session.setPlayer(incoming)
        // The session is now the incoming player, so the player it was just
        // taken from is the idle one: `standby` must follow the session across
        // the hand-off. Leaving this field on `incoming` made [finishFade] stop
        // and clear the queue of the player that had just become the session —
        // every crossfade played its overlap and then went silent with an
        // empty queue, which is exactly what the watch did before this line
        // existed. The demoted player is reused as the standby by the next
        // PREPARE (it is already built, focus-free and quiet).
        standby = player

        fadeStartedAtMs = SystemClock.elapsedRealtime()
        phase = CrossfadePhase.FADING
        StartupDiagnostics.log(context, "crossfade-began fade=${fadeMs}ms")
    }

    private fun advanceFade(player: ExoPlayer) {
        // `standby` is the outgoing tail at this point, not an incoming stream:
        // the session was handed to the other player when the ramp started.
        val outgoing = standby ?: return cancel()

        if (CrossfadeRules.shouldAbortFade(
                isActivePlaying = player.isPlaying,
                activeIndex = player.currentMediaItemIndex,
                preparedIndex = preparedIndex,
                activeQueueSize = player.mediaItemCount,
                preparedQueueSize = preparedQueueSize
            )
        ) {
            return cancel()
        }

        val elapsed = SystemClock.elapsedRealtime() - fadeStartedAtMs
        val progress = CrossfadeRules.progress(elapsed, fadeMs)
        player.volume = scale(userVolume, CrossfadeCurve.incomingGain(progress))
        outgoing.volume = scale(userVolume, CrossfadeCurve.outgoingGain(progress))

        if (CrossfadeRules.isFadeComplete(elapsed, fadeMs)) {
            finishFade()
        }
    }

    private fun finishFade() {
        val incoming = active
        incoming.volume = userVolume

        standby?.let { tail ->
            tail.stop()
            tail.volume = 1f
            // Reset with the player: this one leads the NEXT transition, where
            // it does need to roll into the following item.
            tail.setPauseAtEndOfMediaItems(false)
            tail.clearMediaItems()
        }

        preparedIndex = -1
        preparedQueueSize = -1
        phase = CrossfadePhase.IDLE
        StartupDiagnostics.log(context, "crossfade-complete")
    }

    // ============================================================================
    // Helpers
    // ============================================================================

    /** The standby is created on first use, so the feature costs nothing while off. */
    private fun standbyPlayer(): ExoPlayer = standby ?: standbyFactory().also { standby = it }

    private fun queueSnapshot(player: ExoPlayer): List<MediaItem> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    private fun remainingMs(player: ExoPlayer): Long {
        val duration = player.duration
        // C.TIME_UNSET and 0 both mean "no known end", which is also why such a
        // track is not eligible: there is no window to arm against.
        if (duration <= 0L) return 0L
        return duration - player.currentPosition
    }

    private fun scale(volume: Float, gain: Float): Float =
        (volume * gain).coerceIn(0f, 1f)
}
