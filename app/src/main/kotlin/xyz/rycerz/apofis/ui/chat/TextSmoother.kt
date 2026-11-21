package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import xyz.rycerz.apofis.ui.theme.Motion

/** Characters: a larger backlog is shown at once, so the reveal never lags far behind a burst. */
const val SMOOTH_LAG_BUDGET = 1200

private const val MAX_SLICE = 240
private const val MEAN_OF = 10

/**
 * The streamed text revealed in slices, one [step] per [Motion.SMOOTH_TICK] ms. Presentation only: [visible] is always
 * a prefix of the last target, cut with [pieceEnd] (a grapheme boundary). A deterministic state machine: no clock.
 */
class TextSmoother(initial: String) {
    var visible: String = initial
        private set
    private var target: String = initial
    private val history = ArrayDeque<Int>()
    private var previous = 0

    /** [next] rewrites the last target instead of extending it: shown at once. One comparison per new target. */
    fun corrects(next: String): Boolean = next !== target && !next.startsWith(target)

    /** One tick toward [next]; snaps on [snap], a correction or a backlog over [SMOOTH_LAG_BUDGET]. */
    fun step(next: String, snap: Boolean = false): String {
        val backlog = next.length - visible.length
        if (snap || corrects(next) || backlog > SMOOTH_LAG_BUDGET || backlog <= 0) {
            target = next
            visible = next
            history.clear()
            previous = 0
            return visible
        }
        target = next
        val raw = if (backlog <= 2) backlog else (backlog / 4.0).roundToInt().coerceIn(2, MAX_SLICE)
        history.addLast(raw)
        if (history.size > MEAN_OF) history.removeFirst()
        var slice = history.average().roundToInt()
        if (previous > 2) slice = minOf(slice, (previous * 1.5).toInt())
        slice = slice.coerceIn(minOf(2, backlog), minOf(MAX_SLICE, backlog))
        previous = slice
        val end = pieceEnd(next, visible.length, slice)
        visible = if (end == next.length) next else next.substring(0, end) // caught up means the same instance
        return visible
    }
}

/**
 * The live reply's text as shown: [target] revealed by one [TextSmoother] per [runId]. The first composition for a run
 * shows the text it has at once (also after rotation or process recreation: nothing is saved, nothing replays). A
 * correction or [snap] (polling, reduced motion) shows [target] in the same frame. The caller's list lives under the
 * chat's `key(sessionKey)`, so the run id alone tells runs apart. Per tick: one slice, cut in a window of at most
 * [MAX_SLICE] + [GRAPHEME_SLACK] characters, plus one copy of the shown prefix (the cost of one streamed token).
 */
@Composable
internal fun rememberSmoothedText(runId: String, target: String, snap: Boolean): String {
    val smoother = remember(runId) { TextSmoother(target) }
    val shown = remember(runId) { mutableStateOf(target) }
    val latest = rememberUpdatedState(target)
    val snapNow = rememberUpdatedState(snap)
    LaunchedEffect(runId) {
        while (true) {
            snapshotFlow { latest.value !== shown.value }.first { it } // idle, requesting no frames, until there is more
            var last = Long.MIN_VALUE // the first slice goes out on the next frame, the rest every tick
            while (latest.value !== shown.value) {
                val now = withFrameMillis { it }
                if (snapNow.value || last == Long.MIN_VALUE || now - last >= Motion.SMOOTH_TICK) {
                    last = now
                    shown.value = smoother.step(latest.value, snapNow.value)
                }
            }
        }
    }
    val visible = shown.value
    return if (snap || smoother.corrects(target)) target else visible
}
