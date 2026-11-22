package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pointers that went down on an element that owns sideways drags (a sideways scroller that can scroll, an editable
 * field). The push drawer reads this when the same down reaches it (children see an event first) and leaves that whole
 * gesture alone, whatever its angle and whatever happens to the element meanwhile.
 * shortcut: one set for the process, keyed by pointer id; two windows reusing an id at once could collide, never seen with one drawer.
 */
internal object SidewaysClaims {
    private val claimed = HashSet<PointerId>()

    fun isClaimed(id: PointerId): Boolean = id in claimed

    fun claim(id: PointerId) {
        claimed += id
    }

    fun release(id: PointerId) {
        claimed -= id
    }
}

/** This element owns every sideways drag that starts on it: the push drawer never moves for that gesture. */
fun Modifier.claimSideways(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val id = awaitFirstDown(requireUnconsumed = false).id
        SidewaysClaims.claim(id)
        try {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.id == id && it.pressed })
        } finally {
            SidewaysClaims.release(id)
        }
    }
}

/**
 * A sideways scroller (code row, table, approval command). It takes sideways drags only when its content is really
 * wider than it is ([canScroll]); then it owns every drag that starts on it. A row that fits leaves the drag to the
 * push drawer around it.
 */
fun Modifier.sidewaysScroll(state: ScrollState, canScroll: Boolean = state.maxValue > 0): Modifier =
    (if (canScroll) claimSideways() else this).horizontalScroll(state, enabled = canScroll)
