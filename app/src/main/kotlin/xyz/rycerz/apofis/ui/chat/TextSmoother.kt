package xyz.rycerz.apofis.ui.chat

import android.icu.text.BreakIterator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import xyz.rycerz.apofis.ui.theme.Motion

/** Characters: a larger backlog is shown at once, so the reveal never lags far behind a burst. */
const val SMOOTH_LAG_BUDGET = 1200

/**
 * Characters: a shown text longer than this is no longer smoothed (every update shows the whole target, no tick runs),
 * so a tick never copies more than this. Known limit: a longer reply appears per update, not per tick.
 */
const val SMOOTH_MAX_SHOWN = 20_000

private const val MAX_SLICE = 240
private const val MEAN_OF = 10

/**
 * The streamed text revealed in slices, one [step] per [Motion.SMOOTH_TICK] ms. Presentation only: the shown text is
 * always a prefix of [target], and its length is a grapheme boundary of the whole target (as ICU segments it) or the
 * target's end. While the shown text stays a prefix its length only grows, so the renderer only ever sees appends. A
 * deterministic state machine: no clock.
 */
class TextSmoother(initial: String) {
    var target: String = initial
        private set

    /** How much of [target] is shown. */
    var shown: Int = initial.length
        private set

    val visible: String get() = if (shown == target.length) target else target.substring(0, shown)

    private val history = ArrayDeque<Int>()
    private var previous = 0

    /**
     * The one check after a target change: the shown length for [next], or null when [next] is shown whole (on [snap],
     * past [SMOOTH_MAX_SHOWN], or when the shown text is not a prefix of [next]). A kept length moves forward to the
     * first grapheme boundary of [next] at or after it, so text that joins the shown end (a second flag half, a
     * combining mark) is shown with it, never cut and never taken back. Pure.
     */
    private fun kept(next: String, snap: Boolean): Int? {
        if (snap || shown > SMOOTH_MAX_SHOWN) return null
        if (next === target) return shown
        if (shown > next.length || !next.regionMatches(0, target, 0, shown)) return null
        return boundaryAtOrAfter(next, shown)
    }

    /** What [accept] would show for [next], without changing anything (read in composition). */
    fun shownFor(next: String, snap: Boolean): Int = kept(next, snap) ?: next.length

    /** Takes [next] as the target: the shown length is revalidated by [kept]. */
    fun accept(next: String, snap: Boolean) {
        val k = kept(next, snap)
        if (k == null) showAll(next) else shown = k
        target = next
    }

    private fun showAll(next: String) {
        shown = next.length
        history.clear()
        previous = 0
    }

    /** One tick toward [target]; a backlog over [SMOOTH_LAG_BUDGET], or a cut past [SMOOTH_MAX_SHOWN], shows it whole. */
    fun step() {
        val backlog = target.length - shown
        if (backlog <= 0) return
        if (backlog > SMOOTH_LAG_BUDGET) return showAll(target)
        val raw = if (backlog <= 2) backlog else (backlog / 4.0).roundToInt().coerceIn(2, MAX_SLICE)
        history.addLast(raw)
        if (history.size > MEAN_OF) history.removeFirst()
        var slice = history.average().roundToInt()
        if (previous > 2) slice = minOf(slice, (previous * 1.5).toInt())
        slice = slice.coerceIn(minOf(2, backlog), minOf(MAX_SLICE, backlog))
        previous = slice
        val end = boundaryAtOrAfter(target, shown + slice)
        if (end > SMOOTH_MAX_SHOWN && end < target.length) return showAll(target)
        shown = end
    }

    /** [accept] then [step]; for tests. */
    fun step(next: String, snap: Boolean = false): String {
        accept(next, snap)
        step()
        return visible
    }
}

/** The first grapheme boundary of the whole [text] at or after [at], or its end: ICU segments with the left context. */
private fun boundaryAtOrAfter(text: String, at: Int): Int {
    if (at >= text.length) return text.length
    val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
    return if (graphemes.isBoundary(at)) at else graphemes.following(at)
}

/**
 * The live reply's text as shown: [target] revealed by one [TextSmoother] per [runId]. The first composition for a run
 * shows the text it has at once (also after rotation or process recreation: nothing is saved, nothing replays). Every
 * target change is revalidated in the same frame ([TextSmoother.accept]); [snap] (polling, reduced motion, a terminal
 * run, the live reply off screen) shows [target] whole. Ticks run only while behind, every [Motion.SMOOTH_TICK] ms of
 * frame time, and stop when the shown length equals the target's. The caller's list lives under the chat's
 * `key(sessionKey)`, so the run id alone tells runs apart.
 */
@Composable
internal fun rememberSmoothedText(runId: String, target: String, snap: Boolean): String {
    val smoother = remember(runId) { TextSmoother(target) }
    val ticks = remember(runId) { mutableIntStateOf(0) }
    val latest = rememberUpdatedState(target)
    val snapNow = rememberUpdatedState(snap)
    ticks.intValue // a tick recomposes the caller
    val shown = smoother.shownFor(target, snap)
    SideEffect { smoother.accept(target, snap) } // committed once the frame that shows it is applied
    LaunchedEffect(runId) {
        var last = Long.MIN_VALUE / 2 // spacing kept across wakes: a trickle that keeps catching up still ticks at most every 50 ms
        snapshotFlow { latest.value to snapNow.value }.collectLatest {
            while (!snapNow.value && smoother.shown < smoother.target.length) {
                val now = withFrameMillis { it }
                Work.smoothFrames++
                if (now - last >= Motion.SMOOTH_TICK) {
                    last = now
                    smoother.step()
                    ticks.intValue++
                }
            }
        }
    }
    return if (shown == target.length) target else target.substring(0, shown)
}
