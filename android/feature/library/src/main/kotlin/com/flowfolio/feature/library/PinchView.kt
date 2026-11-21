package com.flowfolio.feature.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.flowfolio.core.model.LibraryView
import kotlin.math.hypot

/** Views from the smallest to the biggest books; a pinch out moves toward the covers, a pinch in toward the list. */
private val ZOOM_ORDER = listOf(
    LibraryView.CONDENSED,
    LibraryView.LIST,
    LibraryView.SHELF,
    LibraryView.GRID,
    LibraryView.COVERS
)
private const val PINCH_OUT = 1.25f
private const val PINCH_IN = 0.8f

/**
 * Two fingers on the library change its view one step. It watches without consuming, so scrolling and taps work
 * exactly as before; the view changes once, when the fingers lift.
 */
internal fun Modifier.pinchToChangeView(view: LibraryView, onView: (LibraryView) -> Unit): Modifier = composed {
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
                    val a = down[0].position
                    val b = down[1].position
                    last = hypot(a.x - b.x, a.y - b.y)
                    if (start == 0f) start = last
                }
            } while (event.changes.any { it.pressed })
            if (start > 0f) {
                val scale = last / start
                val at = ZOOM_ORDER.indexOf(current.value)
                val to = when {
                    scale > PINCH_OUT -> at + 1
                    scale < PINCH_IN -> at - 1
                    else -> at
                }
                ZOOM_ORDER.getOrNull(to)?.takeIf { it != current.value }?.let(change.value)
            }
        }
    }
}
