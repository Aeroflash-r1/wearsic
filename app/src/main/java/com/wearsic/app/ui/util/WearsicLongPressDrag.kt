package com.wearsic.app.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A long-press-then-drag detector built for watch lists.
 *
 * Why not [androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress]:
 * when a row lives inside a scroll container (ScalingLazyColumn), the
 * container's own vertical-scroll handler usually wins the arbitration before
 * the standard long-press detector can claim the gesture — the row never
 * lifts and the finger just scrolls the list. This implementation performs
 * the long-press wait itself and, once the hold exceeds the long-press
 * timeout, CONSUMES every subsequent pointer change so the container can
 * never steal the drag.
 *
 * It relies only on the stable [androidx.compose.ui.input.pointer.AwaitPointerEventScope]
 * primitives — no experimental gesture helpers, so it survives Compose
 * foundation/UI bumps.
 *
 * Contract (mirrors `detectDragGesturesAfterLongPress`):
 *  - touch down, hold still past [androidx.compose.ui.platform.ViewConfiguration.longPressTimeoutMillis]
 *    -> [onDragStart] fires; the drag is claimed from then on.
 *  - movement beyond touch slop BEFORE the timeout -> the gesture is
 *    abandoned unconsumed, so a normal tap/scroll proceeds.
 *  - the finger lifts after a started drag -> [onDragEnd].
 *  - any other exit -> [onDragCancel].
 */
fun Modifier.wearsicLongPressDrag(
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float, positionY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(Unit) {
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    val slop = viewConfiguration.touchSlop

    awaitPointerEventScope {
        while (true) {
            // Wait for a fresh press not already claimed by another handler.
            var down: PointerInputChange? = null
            while (down == null) {
                val event = awaitPointerEvent()
                down = event.changes.firstOrNull { it.pressed && !it.isConsumed }
                if (down == null && event.changes.isEmpty()) break
            }
            val first = down ?: break
            val start = first.position
            val startTime = first.uptimeMillis
            var started = false
            var previousY = start.y
            var done = false

            while (!done) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == first.id }

                if (change == null || !change.pressed) {
                    // Finger up (or pointer gone): end or cancel.
                    if (started) onDragEnd() else onDragCancel()
                    done = true
                    break
                }
                if (change.isConsumed) {
                    // Someone else claimed the gesture mid-flight.
                    if (started) onDragCancel()
                    done = true
                    break
                }

                val moved = (change.position - start).getDistance()
                if (!started) {
                    val heldLongEnough = change.uptimeMillis - startTime >= longPressMillis
                    if (heldLongEnough && moved <= slop) {
                        started = true
                        change.consume()
                        onDragStart()
                        onDrag(0f, change.position.y)
                    } else if (moved > slop) {
                        // Moved before the long press: hand the gesture to the
                        // scroll container (nothing consumed).
                        done = true
                        break
                    }
                } else {
                    change.consume()
                    onDrag(change.position.y - previousY, change.position.y)
                }
                previousY = change.position.y
            }
        }
    }
}
