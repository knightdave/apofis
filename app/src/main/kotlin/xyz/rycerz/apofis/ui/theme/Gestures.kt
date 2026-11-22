package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier

/**
 * A sideways scroller (code row, table, approval command) that takes a sideways drag only when its content is wider than
 * it is: a row that fits leaves the drag to the push drawer around it; one that scrolls keeps it, also at its start.
 */
fun Modifier.sidewaysScroll(state: ScrollState): Modifier = horizontalScroll(state, enabled = state.maxValue > 0)

private val Ignored = DraggableState { }

/** An editable field keeps its sideways drags: nothing around it (the push drawer) starts a drag there. */
fun Modifier.keepSidewaysDrags(): Modifier = draggable(Ignored, Orientation.Horizontal)
