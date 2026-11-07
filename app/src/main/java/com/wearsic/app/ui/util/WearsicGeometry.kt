package com.wearsic.app.ui.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure geometry for round Wear OS screens.
 *
 * On a circular display the usable width of a row depends on how far that
 * row sits from the centre of the circle. UI chrome that hugs the top or
 * bottom edge (the Hub section rail, the mini-player pill) must never be
 * wider than the chord of the narrowest row it occupies, or its corners
 * get clipped by the curved bezel — the exact bug class this file exists
 * to prevent.
 *
 * All values are in PIXELS (density-free) so the maths stays testable on
 * the JVM; Composable callers convert dp → px with the local density.
 */
object WearsicGeometry {

    /**
     * Radius of the circle inscribed on the smallest screen axis.
     * A square face (typical round watch) has r = size/2; a wider-but-short
     * virtual canvas is clamped to the shorter side.
     */
    fun radiusPx(widthPx: Int, heightPx: Int): Float = min(widthPx.toFloat(), heightPx.toFloat()) / 2f

    /**
     * Width of the chord of the inscribed circle at the parallel row
     * [distanceFromCenterPx] from the vertical centre. Clamped so a distance
     * beyond the radius returns 0 (no negative-width rows).
     */
    fun chordWidth(widthPx: Int, heightPx: Int, distanceFromCenterPx: Float): Float {
        val r = radiusPx(widthPx, heightPx)
        val d = kotlin.math.abs(distanceFromCenterPx).coerceIn(0f, r)
        return 2f * sqrt(max(0f, r * r - d * d))
    }

    /**
     * Widest usable content width for a bar hugging the top or bottom edge,
     * inset [edgeInsetPx] from that edge. The bar's outermost row is its
     * narrowest, so its full width must fit that row's chord.
     *
     * The result is also clamped to the physical screen width — on a
     * elongated (non-square) canvas the chord maths alone could exceed it.
     */
    fun edgeBarMaxWidth(widthPx: Int, heightPx: Int, edgeInsetPx: Float): Float {
        val r = radiusPx(widthPx, heightPx)
        // Distance from the vertical centre to the bar's outermost row.
        val offset = (r - edgeInsetPx.coerceIn(0f, r)).coerceAtLeast(0f)
        return min(widthPx.toFloat(), chordWidth(widthPx, heightPx, offset))
    }

    /** Same as [edgeBarMaxWidth] but reserves [safetyPx] of extra breathing room on each side. */
    fun edgeBarMaxWidthSafe(widthPx: Int, heightPx: Int, edgeInsetPx: Float, safetyPx: Float): Float {
        val usable = edgeBarMaxWidth(widthPx, heightPx, edgeInsetPx) - 2f * safetyPx
        return usable.coerceAtLeast(0f)
    }
}
