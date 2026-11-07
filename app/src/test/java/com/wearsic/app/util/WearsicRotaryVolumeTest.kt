package com.wearsic.app.util

import com.wearsic.app.ui.util.wearsicVolumeStepsForScrollDelta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the Now Playing bezel-volume mapping: the crown notch
 * translates to ONE volume step, a fast spin to several, and no movement to
 * nothing — so spinning the bezel feels like a volume dial, not a list.
 */
class WearsicRotaryVolumeTest {

    private val notch = 48f

    @Test
    fun `one notch maps to exactly one step up`() {
        assertEquals(1, wearsicVolumeStepsForScrollDelta(notch))
    }

    @Test
    fun `one notch down is one step down`() {
        assertEquals(-1, wearsicVolumeStepsForScrollDelta(-notch))
    }

    @Test
    fun `a sub-notch event still steps once in its direction`() {
        assertEquals(1, wearsicVolumeStepsForScrollDelta(24f))
        assertEquals(1, wearsicVolumeStepsForScrollDelta(6f))
        assertEquals(-1, wearsicVolumeStepsForScrollDelta(-24f))
    }

    @Test
    fun `a fast spin maps multiples of the notch to multiple steps`() {
        assertEquals(2, wearsicVolumeStepsForScrollDelta(notch * 2f))
        assertEquals(5, wearsicVolumeStepsForScrollDelta(notch * 5f))
        assertEquals(-3, wearsicVolumeStepsForScrollDelta(-(notch * 3f)))
    }

    @Test
    fun `no movement maps to no step`() {
        assertEquals(0, wearsicVolumeStepsForScrollDelta(0f))
        assertEquals(0, wearsicVolumeStepsForScrollDelta(0f, notchPx = notch * 2f))
    }

    @Test
    fun `a zero or negative notch distance can never divide by mistake`() {
        assertEquals(0, wearsicVolumeStepsForScrollDelta(notch, notchPx = 0f))
        assertEquals(0, wearsicVolumeStepsForScrollDelta(-notch, notchPx = -48f))
    }

    @Test
    fun `the notch distance is caller-configurable`() {
        assertEquals(1, wearsicVolumeStepsForScrollDelta(24f, notchPx = 24f))
        assertEquals(2, wearsicVolumeStepsForScrollDelta(48f, notchPx = 24f))
        assertEquals(-1, wearsicVolumeStepsForScrollDelta(-24f, notchPx = 24f))
    }
}
