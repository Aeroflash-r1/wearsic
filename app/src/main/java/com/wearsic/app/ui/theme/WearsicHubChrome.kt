package com.wearsic.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Screen-level chrome the Wear app floats over content — declared so
 * [rememberWearsicListContentPadding] can reserve space for it in ONE place, and no
 * screen has to hardcode custom bottom/top insets.
 *
 * - [wearsicMiniPlayerReserve]: height of the persistent mini-player pill
 *   shallows content pushes to keep the pill clear of the last list row.
 * - [wearsicTopChromeReserve]: height of the Hub's section rail (the four
 *   top-level chips over tab content).
 *
 * Defaults are zero: destinations outside the Hub (Player, Queue, Settings…)
 * have no floating chrome and keep the classic full-bleed layout.
 */
val wearsicMiniPlayerReserve: Dp = 0.dp
val wearsicTopChromeReserve: Dp = 0.dp

val LocalWearsicMiniPlayerReserve = staticCompositionLocalOf { wearsicMiniPlayerReserve }
val LocalWearsicTopChromeReserve = staticCompositionLocalOf { wearsicTopChromeReserve }
