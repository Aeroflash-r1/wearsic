package com.wearsic.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the circular-screen geometry that keeps edge-hugging
 * chrome (Hub section rail, mini-player pill) inside the visible chord on
 * the Galaxy Watch 7's round 44 mm display.
 */
class WearsicGeometryTest {

    /** Galaxy Watch 7 44mm: 450×450 px at ~2.0 density. */
    private val gw7 = 450 to 450

    @Test
    fun `centre row uses full width`() {
        val chord = WearsicGeometry.chordWidth(gw7.first, gw7.second, 0f)
        assertEquals("centre chord equals face width", 450f, chord, 0.01f)
    }

    @Test
    fun `chord narrows toward the edge`() {
        val nearTop = WearsicGeometry.chordWidth(gw7.first, gw7.second, 200f)
        val mid = WearsicGeometry.chordWidth(gw7.first, gw7.second, 80f)
        assertTrue("rows near the top edge must be narrower ($nearTop vs $mid)", nearTop < mid)
        assertTrue("near-edge row has ~200px chord", nearTop < 300f)
    }

    @Test
    fun `chord beyond radius clamps to zero`() {
        assertEquals("past the circle there is no row", 0f, WearsicGeometry.chordWidth(450, 450, 1000f), 0.01f)
        assertEquals("exactly the radius is a single point", 0f, WearsicGeometry.chordWidth(450, 450, 225f), 0.01f)
    }

    @Test
    fun `negative distance treated like positive`() {
        assertEquals(
            "symmetric circle",
            WearsicGeometry.chordWidth(450, 450, 60f),
            WearsicGeometry.chordWidth(450, 450, -60f),
            0.01f
        )
    }

    @Test
    fun `radius follows the shorter axis`() {
        assertEquals("wide canvas clamps to height", 100f, WearsicGeometry.radiusPx(1000, 200), 0.01f)
    }

    @Test
    fun `bar at the bezel keeps clear of the curve`() {
        // A 34dp-ish bar (≈68px) that sits 20px inside the bottom edge must
        // NOT get the full 450px width — its outermost row is far from the
        // centre, so the chord is narrower.
        val max = WearsicGeometry.edgeBarMaxWidth(gw7.first, gw7.second, edgeInsetPx = 20f)
        assertTrue("edge bar shrinks ($max)", max < 260f)
        assertTrue("edge bar stays positive", max > 0f)
    }

    @Test
    fun `bar inset further inward gains width`() {
        val tight = WearsicGeometry.edgeBarMaxWidth(450, 450, 10f)
        val roomier = WearsicGeometry.edgeBarMaxWidth(450, 450, 40f)
        assertTrue("more inset from the edge = more room ($roomier > $tight)", roomier > tight)
    }

    @Test
    fun `safety cut shrinks symmetrically`() {
        val bare = WearsicGeometry.edgeBarMaxWidthSafe(450, 450, 20f, 0f)
        val withSafety = WearsicGeometry.edgeBarMaxWidthSafe(450, 450, 20f, 6f)
        assertEquals("safety reserves 2× the cut", bare - 12f, withSafety, 0.01f)
        assertTrue("never negative", withSafety >= 0f)
    }

    @Test
    fun `degenerate zero canvas does not crash or go negative`() {
        assertEquals(0f, WearsicGeometry.edgeBarMaxWidthSafe(0, 0, 20f, 8f), 0.01f)
        assertEquals(0f, WearsicGeometry.chordWidth(0, 0, 0f), 0.01f)
    }
    /** Galaxy Watch 7 44mm: 450x450 px face, 2.0 density. */
    @Test
    fun `centred panel keeps its bottom corners inside the circle`() {
        // A panel that nearly fills the height must be narrowed hard...
        val tall = WearsicGeometry.panelHorizontalPadding(450, 450, 400f)
        // ...while a short panel hugging the centre may stay wide.
        val short = WearsicGeometry.panelHorizontalPadding(450, 450, 120f)
        assertTrue("a tall sheet needs a much larger inset ($tall vs $short)", tall > short)
    }

    @Test
    fun `panel padding is clamped into sane bounds`() {
        val tall = WearsicGeometry.panelHorizontalPadding(450, 450, 450f)
        val short = WearsicGeometry.panelHorizontalPadding(450, 450, 10f)
        assertTrue("tall panel inset stays within the clamp ($tall)", tall <= 88f)
        assertTrue("short panel inset stays within the clamp ($short)", short >= 32f)
    }

    @Test
    fun `panel width at its bottom edge equals the chord there`() {
        val panelHeight = 300f
        val padding = WearsicGeometry.panelHorizontalPadding(450, 450, panelHeight)
        val panelWidth = 450f - 2f * padding
        val chord = WearsicGeometry.chordWidth(450, 450, panelHeight / 2f)
        // The panel may not exceed the chord at its bottom edge.
        assertTrue("panel width ($panelWidth) must not exceed the chord ($chord)", panelWidth <= chord + 0.01f)
    }

    @Test
    fun `a degenerate face yields the minimum padding rather than nonsense`() {
        assertEquals(32f, WearsicGeometry.panelHorizontalPadding(0, 0, 100f), 0.01f)
    }

}
