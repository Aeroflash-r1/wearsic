package com.wearsic.app.util

import com.wearsic.app.ui.screens.lyricsAutoCenterTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure auto-center math for the fullscreen lyrics screen. Runs on
 * every machine (plain JUnit — no Robolectric), including ARM Linux where the
 * Robolectric suite is excluded.
 */
class LyricsAutoCenterTargetTest {

    @Test
    fun `target moves the row center onto the anchor`() {
        // Row sits 40px below the anchor → scroll down 40.
        assertEquals(
            140,
            lyricsAutoCenterTarget(currentValue = 100, maxValue = 5000, rowCenterY = 300f, anchorY = 260f),
        )
        // Row sits 30px above the anchor → scroll up 30.
        assertEquals(
            70,
            lyricsAutoCenterTarget(currentValue = 100, maxValue = 5000, rowCenterY = 230f, anchorY = 260f),
        )
    }

    @Test
    fun `target clamps at the scrollable bounds`() {
        // Overscroll downward → pinned to max.
        assertEquals(
            5000,
            lyricsAutoCenterTarget(currentValue = 4900, maxValue = 5000, rowCenterY = 600f, anchorY = 260f),
        )
        // Overscroll upward → pinned to 0.
        assertEquals(
            0,
            lyricsAutoCenterTarget(currentValue = 10, maxValue = 5000, rowCenterY = 100f, anchorY = 260f),
        )
    }

    @Test
    fun `zero-size scrollable stays zero`() {
        assertEquals(
            0,
            lyricsAutoCenterTarget(currentValue = 0, maxValue = 0, rowCenterY = 300f, anchorY = 260f),
        )
    }

    @Test
    fun `no-op when the row is already at the anchor`() {
        val target = lyricsAutoCenterTarget(
            currentValue = 100,
            maxValue = 5000,
            rowCenterY = 260f,
            anchorY = 260f,
        )
        assertEquals(100, target)
    }
}
