package com.wearsic.app.media.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the crossfade rules.
 *
 * These are the parts of the crossfade feature that decide WHEN two songs may
 * overlap and when an overlap must be abandoned, so they are the parts that
 * can (and must) be pinned down off-device — the rest of the feature is a
 * volume ramp between two ExoPlayer instances, which no JVM test can hear.
 *
 * Plain JUnit: no Robolectric, no Media3 and no Android types are involved, so
 * this file runs in full on any host (including an ARM Linux box where
 * Robolectric's native runtime is unavailable).
 */
class WearsicCrossfadeTest {

    private companion object {
        /** Media3's C.TIME_UNSET: "this stream never told us its length". */
        const val TIME_UNSET = -9223372036854775807L

        /** Comfortably longer than any configured fade. */
        const val FOUR_MINUTES = 240_000L
    }

    // ============================================================================
    // Selectable lengths
    // ============================================================================

    @Test
    fun `off is the default so an upgrade never changes how playback sounds`() {
        assertEquals(CrossfadeLengths.OFF_MS, CrossfadeLengths.DEFAULT_MS)
        assertEquals(0L, CrossfadeLengths.DEFAULT_MS)
    }

    @Test
    fun `every offered length survives sanitising`() {
        CrossfadeLengths.SELECTABLE_MS.forEach { ms ->
            assertEquals(ms, CrossfadeLengths.sanitize(ms))
        }
    }

    @Test
    fun `an unexplained stored value falls back to off`() {
        // A preference written by another build, or a corrupted entry: the only
        // safe reading of a number we did not offer is "feature off".
        listOf(-1L, 1_500L, 60_000L, Long.MAX_VALUE).forEach { ms ->
            assertEquals(
                "unexpected sanitise($ms)",
                CrossfadeLengths.OFF_MS,
                CrossfadeLengths.sanitize(ms)
            )
        }
    }

    @Test
    fun `labels read as seconds with off spelled out`() {
        assertEquals("Off", CrossfadeLengths.label(0L))
        assertEquals("2s", CrossfadeLengths.label(2_000L))
        assertEquals("8s", CrossfadeLengths.label(8_000L))
    }

    // ============================================================================
    // Equal-power gain curve
    // ============================================================================

    @Test
    fun `gains start and end at the right extremes`() {
        assertEquals(1f, CrossfadeCurve.outgoingGain(0f), 0.0001f)
        assertEquals(0f, CrossfadeCurve.outgoingGain(1f), 0.0001f)
        assertEquals(0f, CrossfadeCurve.incomingGain(0f), 0.0001f)
        assertEquals(1f, CrossfadeCurve.incomingGain(1f), 0.0001f)
    }

    @Test
    fun `the curve keeps total power constant across the whole window`() {
        // This is the entire point of cos/sin over a linear ramp: a linear ramp
        // dips in the middle, which reads as a volume wobble rather than a
        // seamless hand-off.
        var progress = 0f
        while (progress <= 1f) {
            val power = CrossfadeCurve.outgoingGain(progress) * CrossfadeCurve.outgoingGain(progress) +
                CrossfadeCurve.incomingGain(progress) * CrossfadeCurve.incomingGain(progress)
            assertTrue(
                "power dipped to $power at progress $progress",
                abs(power - 1f) < 0.0005f
            )
            progress += 0.05f
        }
    }

    @Test
    fun `the outgoing track only ever gets quieter and the incoming only louder`() {
        var progress = 0f
        var previousOutgoing = CrossfadeCurve.outgoingGain(0f)
        var previousIncoming = CrossfadeCurve.incomingGain(0f)
        while (progress <= 1f) {
            val outgoing = CrossfadeCurve.outgoingGain(progress)
            val incoming = CrossfadeCurve.incomingGain(progress)
            assertTrue("outgoing rose at $progress", outgoing <= previousOutgoing + 0.0001f)
            assertTrue("incoming fell at $progress", incoming >= previousIncoming - 0.0001f)
            previousOutgoing = outgoing
            previousIncoming = incoming
            progress += 0.05f
        }
    }

    @Test
    fun `gains clamp instead of overshooting on a late or early tick`() {
        assertEquals(1f, CrossfadeCurve.outgoingGain(-0.5f), 0.0001f)
        assertEquals(0f, CrossfadeCurve.outgoingGain(1.5f), 0.0001f)
        assertEquals(0f, CrossfadeCurve.incomingGain(-0.5f), 0.0001f)
        assertEquals(1f, CrossfadeCurve.incomingGain(1.5f), 0.0001f)
    }

    // ============================================================================
    // Eligibility — may these two songs overlap at all?
    // ============================================================================

    @Test
    fun `a normal queue with a real duration is eligible`() {
        assertTrue(
            CrossfadeRules.isEligible(
                fadeMs = 4_000L,
                queueSize = 12,
                hasNextItem = true,
                repeatOne = false,
                durationMs = FOUR_MINUTES
            )
        )
    }

    @Test
    fun `off short-circuits eligibility completely`() {
        assertFalse(
            CrossfadeRules.isEligible(
                fadeMs = CrossfadeLengths.OFF_MS,
                queueSize = 12,
                hasNextItem = true,
                repeatOne = false,
                durationMs = FOUR_MINUTES
            )
        )
    }

    @Test
    fun `a single-item queue has nothing to fade into`() {
        assertFalse(
            CrossfadeRules.isEligible(4_000L, queueSize = 1, hasNextItem = true, repeatOne = false, durationMs = FOUR_MINUTES)
        )
    }

    @Test
    fun `repeat-one is never crossfaded with itself`() {
        // REPEAT_MODE_ONE reports a next item (this same song), so it must be
        // excluded explicitly — otherwise the same bytes overlap themselves.
        assertFalse(
            CrossfadeRules.isEligible(4_000L, queueSize = 12, hasNextItem = true, repeatOne = true, durationMs = FOUR_MINUTES)
        )
    }

    @Test
    fun `an unknown stream length cannot be faded against`() {
        // No duration means no end-of-track to arm the window against, so the
        // track takes the plain hand-off path.
        assertFalse(
            CrossfadeRules.isEligible(4_000L, queueSize = 12, hasNextItem = true, repeatOne = false, durationMs = TIME_UNSET)
        )
        assertFalse(
            CrossfadeRules.isEligible(4_000L, queueSize = 12, hasNextItem = true, repeatOne = false, durationMs = 0L)
        )
    }

    @Test
    fun `a track no longer than the fade is played out normally`() {
        assertFalse(
            CrossfadeRules.isEligible(8_000L, queueSize = 12, hasNextItem = true, repeatOne = false, durationMs = 8_000L)
        )
        assertFalse(
            CrossfadeRules.isEligible(8_000L, queueSize = 12, hasNextItem = true, repeatOne = false, durationMs = 6_000L)
        )
    }

    // ============================================================================
    // Arming the standby player
    // ============================================================================

    @Test
    fun `the standby is armed inside the window plus its buffering lead`() {
        assertTrue(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 7_000L, eligible = true, standbyBusy = false)
        )
        assertTrue(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 4_000L, eligible = true, standbyBusy = false)
        )
    }

    @Test
    fun `the standby is not armed while the track is still early`() {
        assertFalse(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 30_000L, eligible = true, standbyBusy = false)
        )
    }

    @Test
    fun `nothing is armed when the pair is ineligible or a fade is still winding down`() {
        assertFalse(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 5_000L, eligible = false, standbyBusy = false)
        )
        assertFalse(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 5_000L, eligible = true, standbyBusy = true)
        )
    }

    @Test
    fun `nothing is armed once the track has already ended`() {
        assertFalse(
            CrossfadeRules.shouldPrepare(fadeMs = 4_000L, remainingMs = 0L, eligible = true, standbyBusy = false)
        )
    }

    // ============================================================================
    // Starting the fade
    // ============================================================================

    @Test
    fun `the fade begins only with a buffered incoming stream inside the window`() {
        assertTrue(
            CrossfadeRules.shouldBeginFade(fadeMs = 4_000L, remainingMs = 3_000L, standbyReady = true)
        )
    }

    @Test
    fun `an incoming stream that is still buffering holds the fade back`() {
        // A late fade is correct; starting one the standby cannot fill would
        // leave a silent hole exactly where the crossfade was supposed to be.
        assertFalse(
            CrossfadeRules.shouldBeginFade(fadeMs = 4_000L, remainingMs = 3_000L, standbyReady = false)
        )
    }

    @Test
    fun `the fade does not begin before the window or after the end`() {
        assertFalse(
            CrossfadeRules.shouldBeginFade(fadeMs = 4_000L, remainingMs = 9_000L, standbyReady = true)
        )
        assertFalse(
            CrossfadeRules.shouldBeginFade(fadeMs = 4_000L, remainingMs = 0L, standbyReady = true)
        )
    }

    @Test
    fun `a disabled fade never begins`() {
        assertFalse(
            CrossfadeRules.shouldBeginFade(fadeMs = CrossfadeLengths.OFF_MS, remainingMs = 0L, standbyReady = true)
        )
    }

    // ============================================================================
    // Window compression, progress
    // ============================================================================

    @Test
    fun `a late tick compresses the window to the audio that is left`() {
        // The outgoing track must reach its end exactly as the ramp finishes:
        // a window longer than the remaining audio would leave the old song
        // audible under the new one.
        assertEquals(1_200L, CrossfadeRules.effectiveFadeMs(fadeMs = 4_000L, remainingMs = 1_200L))
        assertEquals(4_000L, CrossfadeRules.effectiveFadeMs(fadeMs = 4_000L, remainingMs = 30_000L))
        assertEquals(0L, CrossfadeRules.effectiveFadeMs(fadeMs = 4_000L, remainingMs = 0L))
    }

    @Test
    fun `progress runs from zero to one and clamps`() {
        assertEquals(0f, CrossfadeRules.progress(0L, 4_000L), 0.0001f)
        assertEquals(0.5f, CrossfadeRules.progress(2_000L, 4_000L), 0.0001f)
        assertEquals(1f, CrossfadeRules.progress(4_000L, 4_000L), 0.0001f)
        assertEquals(1f, CrossfadeRules.progress(9_999L, 4_000L), 0.0001f)
    }

    @Test
    fun `the fade completes exactly at the end of its window`() {
        assertFalse(CrossfadeRules.isFadeComplete(elapsedMs = 3_999L, fadeMs = 4_000L))
        assertTrue(CrossfadeRules.isFadeComplete(elapsedMs = 4_000L, fadeMs = 4_000L))
    }

    // ============================================================================
    // Abandoning the in-flight work
    // ============================================================================

    @Test
    fun `a healthy prepare survives every tick`() {
        assertFalse(
            CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = true,
                activeIndex = 3,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a prepare is dropped the moment the user takes over`() {
        assertTrue(
            CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = false,
                activeIndex = 3,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a cleared queue drops the prepare`() {
        assertTrue(
            CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = false,
                activeIndex = 0,
                preparedIndex = 4,
                activeQueueSize = 0,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a replaced queue drops the prepare`() {
        // playTracks() swaps the whole queue: the standby is holding media
        // items the session no longer has.
        assertTrue(
            CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = true,
                activeIndex = 3,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 7
            )
        )
    }

    @Test
    fun `skipping a song drops the prepare`() {
        assertTrue(
            CrossfadeRules.shouldAbortPrepare(
                isActivePlaying = true,
                activeIndex = 6,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a healthy fade survives every tick`() {
        assertFalse(
            CrossfadeRules.shouldAbortFade(
                isActivePlaying = true,
                activeIndex = 4,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a fade is dropped if the user pauses mid-ramp`() {
        // Aborting restores the volume scale the ramp was bending, so the next
        // play press is not quiet.
        assertTrue(
            CrossfadeRules.shouldAbortFade(
                isActivePlaying = false,
                activeIndex = 4,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a fade is dropped when the session moves to another item`() {
        assertTrue(
            CrossfadeRules.shouldAbortFade(
                isActivePlaying = true,
                activeIndex = 5,
                preparedIndex = 4,
                activeQueueSize = 20,
                preparedQueueSize = 20
            )
        )
    }

    @Test
    fun `a fade is dropped when the queue changes size underneath it`() {
        assertTrue(
            CrossfadeRules.shouldAbortFade(
                isActivePlaying = true,
                activeIndex = 4,
                preparedIndex = 4,
                activeQueueSize = 21,
                preparedQueueSize = 20
            )
        )
    }
}
