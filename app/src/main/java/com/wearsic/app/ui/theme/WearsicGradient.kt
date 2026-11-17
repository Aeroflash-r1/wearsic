package com.wearsic.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Wearsic gradient language for the discovery surfaces (Home/Mix/shelves).
 *
 * Solar Ember OLED: warm obsidian charcoal fading to near-black rather than
 * loud colour slabs. Brushes are static values (no allocation at draw time)
 * so long lists stay cheap to scroll.
 */

/** Card body: warm obsidian fading to near-black, left → right. */
val WearsicHeroCardBrush: Brush =
    Brush.horizontalGradient(
        listOf(
            Color(0xFF282020),
            Color(0xFF151112)
        )
    )

/** Top scrim under floating chrome: near-opaque black → transparent. */
val WearsicTopScrimBrush: Brush =
    Brush.verticalGradient(
        listOf(
            Color.Black.copy(alpha = 0.94f),
            Color.Black.copy(alpha = 0.55f),
            Color.Transparent
        )
    )

/** Glass pill fill (mini-player and empty-file chips). */
val WearsicPillGlassBrush: Brush =
    Brush.horizontalGradient(
        listOf(
            Color.Black.copy(alpha = 0.92f),
            Color.Black.copy(alpha = 0.80f)
        )
    )

/** Accent bloom: a soft lavender wash laid across a card's leading edge. */
fun wearsicAccentBloomBrush(accent: Color): Brush =
    Brush.horizontalGradient(
        listOf(
            accent.copy(alpha = 0.16f),
            Color.Transparent
        )
    )
