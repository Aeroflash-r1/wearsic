package com.wearsic.app.util

import androidx.compose.ui.unit.Density
import com.wearsic.app.ui.screens.LYRICS_ROW_GAP
import com.wearsic.app.ui.screens.LYRICS_ROW_HEIGHT
import com.wearsic.app.ui.screens.lyricsCenterScroll
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pins the fullscreen lyrics auto-centering arithmetic: with half the body
 * viewport reserved as top/bottom padding, the active line i must centre at
 * scroll target `i * pitch + rowHeight/2` EXACTLY. The padding identity makes
 * the mapping true on every screen, so a regression in either the uniform-row
 * metrics or the formula is caught long before a watch shows formulas that
 * drift a highlight off-centre.
 *
 * Plain JUnit on purpose: [Density]/Dp math is pure Kotlin — no Robolectric
 * needed, so this runs locally on ARM too (CI runs the full Roborazzi suite).
 */
class LyricsCenteringTest {

    // Any consistent density works; the identity is scale-independent.
    private val density = Density(2.35f)

    private val pitchPx: Float =
        with(density) { (LYRICS_ROW_HEIGHT + LYRICS_ROW_GAP).toPx() }
    private val linePx: Float =
        with(density) { LYRICS_ROW_HEIGHT.toPx() }

    @Test
    fun `active line zero centres at exactly half the row height`() {
        val target = lyricsCenterScroll(0, bodyPx = 580f, pitchPx, linePx)
        assertEquals((linePx / 2f).roundToInt(), target)
    }

    @Test
    fun `each active line i centres at i times pitch plus half the row`() {
        for (i in 0..40) {
            val expected = (i * pitchPx + linePx / 2f).roundToInt()
            assertEquals("line $i", expected, lyricsCenterScroll(i, bodyPx = 580f, pitchPx, linePx))
        }
    }

    @Test
    fun `target is independent of body height`() {
        // The pads are half the body, so the body cancels out of the answer —
        // identical mapping on short and tall faces.
        assertEquals(
            lyricsCenterScroll(7, 400f, pitchPx, linePx),
            lyricsCenterScroll(7, 900f, pitchPx, linePx),
        )
    }

    @Test
    fun `row pitch constant equals row height plus gap`() {
        // The pitch the formula assumes must equal what the composable lays
        // out (uniform rows + separator): otherwise lines drift apart.
        assertEquals(with(density) { LYRICS_ROW_GAP.toPx() }, pitchPx - linePx, 1e-3f)
    }
}
