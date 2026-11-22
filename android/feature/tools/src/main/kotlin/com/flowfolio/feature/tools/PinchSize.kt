package com.flowfolio.feature.tools

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.hypot

private const val PINCH_OUT = 1.25f
private const val PINCH_IN = 0.8f
private const val LAST_VIEW = 3

/**
 * Two fingers change the tools' layout one step (0 icons, 1 compact, 2 list, 3 large): pinch out for bigger, in for
 * smaller. It only watches, so scrolling and taps work as before, and it acts once when the fingers lift.
 */
internal fun Modifier.pinchToResize(view: Int, onView: (Int) -> Unit): Modifier = composed {
    val current = rememberUpdatedState(view)
    val change = rememberUpdatedState(onView)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var start = 0f
            var last = 0f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.filter { it.pressed }
                if (down.size >= 2) {
                    last = hypot(down[0].position.x - down[1].position.x, down[0].position.y - down[1].position.y)
                    if (start == 0f) start = last
                }
            } while (event.changes.any { it.pressed })
            if (start > 0f) {
                val scale = last / start
                val to = when {
                    scale > PINCH_OUT -> current.value + 1
                    scale < PINCH_IN -> current.value - 1
                    else -> current.value
                }
                if (to in 0..LAST_VIEW && to != current.value) change.value(to)
            }
        }
    }
}
