package com.wearsic.app.media.crossfade

import kotlin.math.cos
import kotlin.math.sin

/**
 * CROSSFADE — the timing, guard and gain rules.
 *
 * Deliberately pure Kotlin: no Android, no Media3. Every rule that decides
 * whether two songs may overlap, and every rule that decides when an overlap
 * must be abandoned, is unit-tested on the JVM (see `WearsicCrossfadeTest`)
 * instead of being discovered with headphones on — the player side of this
 * feature is a volume ramp between two ExoPlayer instances, and a mistake
 * there is an audible one.
 *
 * Why two players at all: a single ExoPlayer plays one item at a time, so an
 * overlapping fade is impossible without a second decoder. The engine
 * ([WearsicCrossfadeEngine]) therefore keeps an idle standby player, hands the
 * MediaSession over to it when the fade completes, and mirrors the queue and
 * repeat mode onto it. Everything in THIS file is the arithmetic that
 * surrounds that hand-off.
 */

/**
 * User-selectable crossfade lengths, in milliseconds.
 *
 * [OFF_MS] is not just "0 seconds" — it is the feature switch: at OFF the
 * engine never creates the standby player, never touches audio focus and never
 * swaps the session, so playback takes exactly the single-player path it took
 * before this feature existed (plain ExoPlayer hand-off, which already gaplessly
 * chains the next item in the queue).
 */
object CrossfadeLengths {

    const val OFF_MS = 0L

    /**
     * Off / 2s / 4s / 6s / 8s. Eight seconds is the ceiling on purpose: a fade
     * window longer than this starts to overlap songs the user is actively
     * listening to, and on a watch it also means the incoming stream is being
     * decoded (and the CPU kept awake) for longer than it needs to be.
     */
    val SELECTABLE_MS = listOf(OFF_MS, 2_000L, 4_000L, 6_000L, 8_000L)

    /**
     * Default is OFF. Crossfade overlaps two decoders and two sockets for its
     * duration, so it is opt-in: an upgrade must not silently change how
     * playback sounds or what it costs on battery.
     */
    const val DEFAULT_MS = OFF_MS

    /**
     * Values that are not one of the offered lengths (an older/newer build's
     * preference, a corrupted DataStore entry) fall back to OFF rather than to
     * some arbitrary duration — "the feature is off" is the only safe reading
     * of a number we cannot explain.
     */
    fun sanitize(ms: Long): Long = if (ms in SELECTABLE_MS) ms else DEFAULT_MS

    /** Chip label for the Sound screen. */
    fun label(ms: Long): String = if (ms <= OFF_MS) "Off" else "${ms / 1000}s"
}

/**
 * Equal-power (constant-power) fade gains.
 *
 * A linear ramp is the obvious choice and the wrong one: halfway through, both
 * sources sit at 50% and the summed power dips audibly — the fade "ducks" in
 * the middle instead of sounding like one continuous performance. cos/sin keep
 * the sum of squares at 1 across the whole window, which is the standard fix.
 */
object CrossfadeCurve {

    /** Outgoing track: 1.0 at the start of the window, 0.0 at its end. */
    fun outgoingGain(progress: Float): Float =
        cos(progress.coerceIn(0f, 1f) * HALF_PI)

    /** Incoming track: 0.0 at the start of the window, 1.0 at its end. */
    fun incomingGain(progress: Float): Float =
        sin(progress.coerceIn(0f, 1f) * HALF_PI)

    private const val HALF_PI = (Math.PI / 2.0).toFloat()
}

/** Current position of a crossfade, and whether the outgoing tail is done. */
enum class CrossfadePhase {
    /** Nothing in flight; the standby player is idle (or does not exist yet). */
    IDLE,

    /**
     * The incoming track has been handed to the standby player and is
     * buffering, but NOTHING AUDIBLE HAS CHANGED YET: the queue, the session
     * player and the output device are all still the outgoing track's. This
     * phase exists so a slow stream (or a user who skips during it) can be
     * abandoned for free — see [CrossfadeRules.shouldAbortPrepare].
     */
    PREPARING,

    /** Both players are audible and the volume ramp is running. */
    FADING
}

/**
 * The guard rules. Each function is total (no side effects, no player access)
 * so the engine [WearsicCrossfadeEngine] only has to sequence them.
 */
object CrossfadeRules {

    /**
     * How far before the fade window the standby player is created and told to
     * buffer the next track. Without this lead the fade would start by waiting
     * for a stream to open — the outgoing track would run into its final
     * seconds in silence and the "crossfade" would open with a gap, i.e. worse
     * than the plain hand-off it replaced.
     */
    const val PREPARE_LEAD_MS = 4_000L

    /**
     * Can these two songs overlap at all? Structural questions only — timing is
     * handled by [shouldPrepare] and [shouldBeginFade].
     *
     * @param fadeMs configured window; OFF means the feature is disabled.
     * @param queueSize number of items the session player holds.
     * @param hasNextItem whether ExoPlayer reports a next item.
     * @param repeatOne REPEAT_MODE_ONE: "next" is this same song again.
     * @param durationMs the OUTGOING track's duration; `C.TIME_UNSET` (<= 0)
     *   means we have no idea where the end is and therefore no window to arm
     *   in. A stream whose length is unknown falls back to the plain hand-off.
     */
    fun isEligible(
        fadeMs: Long,
        queueSize: Int,
        hasNextItem: Boolean,
        repeatOne: Boolean,
        durationMs: Long
    ): Boolean {
        if (fadeMs <= CrossfadeLengths.OFF_MS) return false
        if (queueSize <= 1 || !hasNextItem) return false
        // Repeat-one would fade a song into itself (the same bytes twice, one
        // slightly behind the other) — a phasey mess, not a transition.
        if (repeatOne) return false
        if (durationMs <= 0L) return false
        // A track shorter than the window has no "start of the fade"; let it
        // play out and hand off normally.
        if (durationMs <= fadeMs) return false
        return true
    }

    /**
     * Time to create/arm the standby player: either side of the fade window
     * plus the buffering lead, while there is still something to fade into.
     *
     * @param remainingMs distance to the end of the outgoing track.
     * @param standbyBusy true while a previous fade is still winding down, so
     *   a new one can never be armed on a player that is still audible.
     */
    fun shouldPrepare(
        fadeMs: Long,
        remainingMs: Long,
        eligible: Boolean,
        standbyBusy: Boolean
    ): Boolean {
        if (!eligible || standbyBusy) return false
        if (remainingMs <= 0L) return false
        return remainingMs <= fadeMs + PREPARE_LEAD_MS
    }

    /**
     * Time to actually overlap: the incoming stream must be READY (buffered
     * past its own start-up threshold) and the outgoing must be inside the
     * window. If the stream is still buffering when the window opens, the
     * engine waits — a late fade is correct, an audible stall is not.
     */
    fun shouldBeginFade(
        fadeMs: Long,
        remainingMs: Long,
        standbyReady: Boolean
    ): Boolean {
        if (!standbyReady) return false
        if (fadeMs <= CrossfadeLengths.OFF_MS) return false
        return remainingMs in 1..fadeMs
    }

    /**
     * The window a fade is actually allowed to run for.
     *
     * A tick lands every second, so the window usually opens a little late, and
     * a queue can hand over a track that is barely longer than the configured
     * fade. Either way the ramp is compressed to the audio that genuinely
     * remains: the outgoing track must reach its own end exactly as the ramp
     * finishes, never after, or the tail would keep playing under the incoming
     * song.
     */
    fun effectiveFadeMs(fadeMs: Long, remainingMs: Long): Long =
        fadeMs.coerceAtMost(remainingMs).coerceAtLeast(0L)

    /** Fraction of the fade completed, clamped to 0..1. */
    fun progress(elapsedMs: Long, fadeMs: Long): Float =
        if (fadeMs <= 0L) 1f else (elapsedMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)

    /** True once the ramp has run its full (already compressed) window. */
    fun isFadeComplete(elapsedMs: Long, fadeMs: Long): Boolean =
        elapsedMs >= fadeMs

    /**
     * A PREPARE must be dropped when the world moves underneath it: the user
     * paused, stopped or cleared the queue, skipped to another song, or the
     * queue was replaced (which changes its size). Nothing is audible during
     * PREPARING, so dropping it costs nothing — this is the reason the engine
     * arms the incoming track in a separate phase instead of arming and fading
     * in one step.
     *
     * @param activeIndex still the OUTGOING track's index here.
     * @param preparedIndex the index the standby player is holding, which must
     *   be exactly one ahead of [activeIndex].
     */
    fun shouldAbortPrepare(
        isActivePlaying: Boolean,
        activeIndex: Int,
        preparedIndex: Int,
        activeQueueSize: Int,
        preparedQueueSize: Int
    ): Boolean {
        if (!isActivePlaying) return true
        if (activeQueueSize <= 0) return true
        if (activeQueueSize != preparedQueueSize) return true
        return preparedIndex != activeIndex + 1
    }

    /**
     * A running FADE must be dropped when the user takes over (pause, stop,
     * skip, clear) or when the session moved to a different item. The engine
     * then restores the volume scale the ramp was bending, so the next play
     * press is not silently quiet.
     *
     * After the hand-off the session player IS the incoming track, so here the
     * active index must equal [preparedIndex] — the opposite of
     * [shouldAbortPrepare].
     */
    fun shouldAbortFade(
        isActivePlaying: Boolean,
        activeIndex: Int,
        preparedIndex: Int,
        activeQueueSize: Int,
        preparedQueueSize: Int
    ): Boolean {
        if (!isActivePlaying) return true
        if (activeQueueSize <= 0) return true
        if (activeQueueSize != preparedQueueSize) return true
        return activeIndex != preparedIndex
    }
}
