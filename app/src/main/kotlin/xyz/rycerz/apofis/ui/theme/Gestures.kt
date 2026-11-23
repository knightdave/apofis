package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

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

/**
 * This element owns every sideways drag that starts on it, while [owns] says so at the down: the push drawer never
 * moves for that gesture.
 */
fun Modifier.claimSideways(owns: () -> Boolean = { true }): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val id = awaitFirstDown(requireUnconsumed = false).id
        if (!owns()) return@awaitEachGesture
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
 * The one rule for every sideways scroller: it scrolls, and owns the swipe, only when its measured range is known (not
 * the unmeasured Int.MAX_VALUE) and larger than a 2 dp rounding tolerance ([tolerancePx]).
 */
internal fun ScrollState.scrollsSideways(tolerancePx: Float): Boolean = maxValue != Int.MAX_VALUE && maxValue > tolerancePx

/**
 * A sideways scroller (code row, table, approval command) under [scrollsSideways]: the same answer drives scrolling
 * (read in composition) and the claim (read live at the down). A disabled horizontalScroll still measures and publishes
 * its range, so the answer cannot flip back and forth.
 */
fun Modifier.sidewaysScroll(state: ScrollState, density: Density): Modifier {
    val tolerance = with(density) { 2.dp.toPx() }
    return claimSideways { state.scrollsSideways(tolerance) }.horizontalScroll(state, enabled = state.scrollsSideways(tolerance))
}
