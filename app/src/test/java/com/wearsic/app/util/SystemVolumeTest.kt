package com.wearsic.app.util

import com.wearsic.app.ui.util.SystemVolumeState
import com.wearsic.app.ui.util.rotaryVolumeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Sound screen's volume logic (runs on every
 * architecture — no Robolectric). The state is exercised with a null
 * AudioManager, which mirrors a device without an accessible audio service:
 * all numeric behaviour must still be correct.
 */
class SystemVolumeTest {

    private fun state() = SystemVolumeState(null)

    @Test
    fun `default range is the platform media range`() {
        assertEquals(15, state().max)
    }

    @Test
    fun `step moves one index and reflects in the fraction`() {
        val volume = state()
        volume.step(1)
        assertEquals(1, volume.level)
        assertEquals(1f / 15f, volume.fraction, 0.0001f)
        assertEquals(7, volume.percent)
    }

    @Test
    fun `level clamps at both ends`() {
        val volume = state()
        volume.step(-5)
        assertEquals(0, volume.level)
        volume.step(100)
        assertEquals(15, volume.level)
    }

    @Test
    fun `mute then unmute restores the last audible level`() {
        val volume = state()
        volume.step(4)
        assertFalse(volume.isMuted)
        volume.toggleMute()
        assertTrue(volume.isMuted)
        assertEquals(0, volume.level)
        volume.toggleMute()
        assertEquals(4, volume.level)
        assertFalse(volume.isMuted)
    }

    @Test
    fun `unmute from silence restores a step, never silence`() {
        val volume = state()
        // Nothing has synced yet: level is 0. Unmuting must not be a no-op.
        volume.toggleMute()
        assertEquals(1, volume.level)
    }

    @Test
    fun `applying the current level is a no-op`() {
        val volume = state()
        volume.step(3)
        volume.step(0)
        assertEquals(3, volume.level)
    }

    @Test
    fun `audio availability is false without a service`() {
        assertFalse(state().isAvailable)
    }

    @Test
    fun `rotary maps every non-zero event to exactly one step`() {
        assertEquals(1, rotaryVolumeStep(24f))
        assertEquals(1, rotaryVolumeStep(0.5f))
        assertEquals(-1, rotaryVolumeStep(-24f))
        assertEquals(0, rotaryVolumeStep(0f))
    }
}
