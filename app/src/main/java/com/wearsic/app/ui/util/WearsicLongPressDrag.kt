package com.wearsic.app.ui.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A long-press-then-drag detector built for watch lists.
 *
 * Why not [androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress]:
 * when a row lives inside a scroll container (ScalingLazyColumn), the
 * container's own vertical-scroll handler usually wins the arbitration before
 * the standard long-press detector can claim the gesture — the row never
 * lifts and the finger just scrolls the list.
 *
 * Critical detail: all events here are read in [PointerEventPass.Initial],
 * which is dispatched BEFORE the Main pass where scroll containers consume
 * movement. Once the long-press hold is achieved we consume every change in
 * the Initial pass, so the lazy column's Main-pass handler sees an already
 * consumed change and can never turn our drag into a scroll. Before the
 * hold is achieved we consume nothing, so normal scrolling and taps work
 * exactly as before.
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
    // Long-press lift timing: must be clearly longer than a normal tap
    // (~150-250ms) so plain touches and scrolls never lift, but short enough
    // to feel responsive. 450ms reads right on a watch. Slop stays close to
    // stock so quick scroll movement is recognized as a scroll before the
    // timer can fire — a tiny allowance still covers finger micro-drift.
    val longPressMillis = 450L
    val slop = viewConfiguration.touchSlop * 1.2f

    awaitPointerEventScope {
        while (true) {
            // Wait for a fresh press. Some watch scaffolds consume the down
            // event before a row's pointer input sees it, so we accept ANY
            // pressed change — being strict here made the drag dead on-device.
            var down: PointerInputChange? = null
            while (down == null) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                down = event.changes.firstOrNull { it.pressed }
                if (down == null && event.changes.isEmpty()) break
            }
            val first = down ?: break
            val start = first.position
            val startTime = first.uptimeMillis
            var started = false
            var previousY = start.y
            var done = false

            while (!done) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == first.id }

                if (change == null || !change.pressed) {
                    // Finger up (or pointer gone): end or cancel.
                    if (started) onDragEnd() else onDragCancel()
                    done = true
                    break
                }
                if (change.isConsumed) {
                    // Someone else claimed the gesture before us (e.g. the
                    // scroll container decided it is a scroll). We never
                    // fight an already-lost gesture.
                    if (started) onDragCancel()
                    done = true
                    break
                }

                val moved = (change.position - start).getDistance()
                if (!started) {
                    val heldLongEnough = change.uptimeMillis - startTime >= longPressMillis
                    if (heldLongEnough && moved <= slop) {
                        // Long press achieved: claim NOW, in the Initial pass,
                        // so every later handler (including the column's
                        // scroll) sees the events as consumed. No synthetic
                        // drag event is emitted here — auto-scroll and row
                        // movement must react only to REAL finger movement,
                        // never to the press itself.
                        started = true
                        change.consume()
                        onDragStart()
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
