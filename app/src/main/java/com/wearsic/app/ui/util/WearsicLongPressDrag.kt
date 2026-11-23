package com.wearsic.app.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A long-press-then-drag detector built for watch lists.
 *
 * Why not [androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress]:
 * when a row lives inside a scroll container (ScalingLazyColumn), the
 * container's own vertical-scroll handler wins the arbitration before the
 * standard long-press detector can claim the gesture — the row never lifts
 * and the finger just scrolls the list.
 *
 * WHY THE HOLD IS TIMED, NOT MOVEMENT-DRIVEN: the previous version only
 * checked "has the finger been still for N ms?" when a move event arrived.
 * A real drag sends its first movement as one large jump (the finger leaves
 * the glass before the next sample), so that event always exceeded touch
 * slop and the gesture was handed to the scroll container — the row could
 * never lift unless the user crept away at a few pixels per event. Here the
 * hold is a coroutine timeout: after [holdMillis] with the finger still
 * within [slop], the row lifts on its own, and the *same* movement that
 * would have cancelled the old detector becomes the first drag delta.
 *
 * All events are read in [PointerEventPass.Initial], which is dispatched
 * BEFORE the Main pass where scroll containers consume movement. Once the
 * hold is achieved we consume every change in the Initial pass, so the lazy
 * column's Main-pass handler sees an already-consumed change and can never
 * turn our drag into a scroll. Before the hold is achieved we consume
 * nothing, so normal scrolling and taps work exactly as before.
 *
 * IMPORTANT: the modifier must be attached to a node that does NOT itself
 * move while dragging (e.g. an outer wrapper), otherwise the locals of
 * [PointerInputChange.position] move with the row and deltas collapse to
 * zero. Translate an inner child, not this node.
 *
 * Contract:
 *  - touch down, hold still past [holdMillis] -> [onDragStart] fires; the
 *    drag is claimed from then on.
 *  - decisive movement before the timeout -> abandoned unconsumed, so a
 *    normal tap/scroll proceeds.
 *  - the finger lifts after a started drag -> [onDragEnd].
 *  - any other exit -> [onDragCancel].
 */
fun Modifier.wearsicLongPressDrag(
    key: Any? = null,
    holdMillis: Long = 400L,
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier = pointerInput(holdMillis, key) {
    // Generous slop: a watch finger drifts while holding still. Slow scrolling
    // still exceeds this within the hold window, so the list keeps winning
    // real scrolls; only deliberate holds survive to the timeout.
    val slop = viewConfiguration.touchSlop * 2f

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val start = down.position

        // Watch the finger until the hold elapses. Anything decisive before
        // then is a scroll or a tap — hand it back untouched.
        val aborted = withTimeoutOrNull(holdMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull false
                if (!change.pressed || change.isConsumed) return@withTimeoutOrNull false
                if ((change.position - start).getDistance() > slop) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        if (aborted != null) {
            // Released or moved early: nothing was consumed, so the row's
            // click (or the list's scroll) proceeds normally.
            onDragCancel()
            return@awaitEachGesture
        }

        // Hold achieved. Claim every following movement in the Initial pass
        // so the lazy column's scroll handler can never see it.
        var previousY = start.y
        onDragStart()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                onDragCancel()
                return@awaitEachGesture
            }
            if (!change.pressed) {
                // Consume the UP too. The row's own click detector lives on a
                // node that never moved, so an unconsumed release counts as a
                // legal tap on it — dropping a row would also PLAY that row.
                change.consume()
                onDragEnd()
                return@awaitEachGesture
            }
            change.consume()
            onDrag(change.position.y - previousY)
            previousY = change.position.y
        }
    }
}
