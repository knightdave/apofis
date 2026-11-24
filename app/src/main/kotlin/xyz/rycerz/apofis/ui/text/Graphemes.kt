package xyz.rycerz.apofis.ui.text

import android.icu.text.BreakIterator
import xyz.rycerz.apofis.ui.chat.GRAPHEME_SLACK

/**
 * The one paging cut: a grapheme boundary of [text] near the absolute [limit], segmented by ICU over a window that
 * begins at [from] (0 or an earlier cut), so flags, ZWJ sequences, combining marks and CRLF stay whole.
 *
 * Down (`up = false`): the last boundary at or before [limit], so a cap is never exceeded; reads [from] to one code
 * point past [limit]. When no boundary lies after [from] (one cluster longer than the window), the last code point
 * boundary at or before [limit].
 *
 * Up (`up = true`): the first boundary at or after [limit]; reads at most to [limit] + [GRAPHEME_SLACK], widened to a
 * whole code point, and the window's end counts as a boundary only at the end of [text]. Shortcut: when no boundary is
 * found in the window (a grapheme longer than [GRAPHEME_SLACK]), the cut is at [limit] on a code point boundary, so a
 * grapheme spanning more than one window may also be cut at code point boundaries that are not grapheme boundaries.
 */
fun graphemeCut(text: CharSequence, from: Int, limit: Int, up: Boolean): Int {
    if (limit >= text.length) return text.length
    if (limit <= from) return from
    if (up) {
        var windowEnd = limit + minOf(GRAPHEME_SLACK, text.length - limit) // never past Int range
        if (windowEnd < text.length && text[windowEnd - 1].isHighSurrogate() && text[windowEnd].isLowSurrogate()) windowEnd++
        val bi = BreakIterator.getCharacterInstance().apply { setText(text.subSequence(from, windowEnd).toString()) }
        val b = bi.following(limit - from - 1)
        if (b != BreakIterator.DONE && (from + b < windowEnd || windowEnd == text.length)) return from + b
        return if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit + 1 else limit
    }
    val after = limit + if (text[limit].isHighSurrogate() && limit + 1 < text.length) 2 else 1 // what follows the limit
    val bi = BreakIterator.getCharacterInstance().apply { setText(text.subSequence(from, after).toString()) }
    val b = bi.preceding(limit - from + 1)
    if (b != BreakIterator.DONE && b > 0) return from + b
    return if (text[limit].isLowSurrogate() && text[limit - 1].isHighSurrogate()) limit - 1 else limit
}
