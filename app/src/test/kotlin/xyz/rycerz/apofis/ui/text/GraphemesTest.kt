package xyz.rycerz.apofis.ui.text

import android.icu.text.BreakIterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.ui.chat.GRAPHEME_SLACK
import xyz.rycerz.apofis.ui.chat.PAGE_CHARS

/**
 * [graphemeCut] against the two paging helpers it replaced, kept here verbatim as references. Both references run on
 * `android.icu` (on a phone `java.text` is ICU too); only Robolectric gives `java.text` the host JDK's segmenter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class GraphemesTest {

    /** `cutAt` of `ui/chat/Timeline.kt` before this change (already `android.icu`). */
    private fun oldCutAt(text: CharSequence, from: Int, cap: Int): Int {
        if (cap >= text.length) return text.length
        if (cap <= from) return from
        val after = cap + if (Character.isHighSurrogate(text[cap]) && cap + 1 < text.length) 2 else 1
        val graphemes = BreakIterator.getCharacterInstance()
        graphemes.setText(text.subSequence(from, after).toString())
        val boundary = graphemes.preceding(cap - from + 1)
        if (boundary != BreakIterator.DONE && boundary > 0) return from + boundary
        return if (Character.isLowSurrogate(text[cap]) && Character.isHighSurrogate(text[cap - 1])) cap - 1 else cap
    }

    /** `pieceEnd` of `ui/chat/MessageActions.kt` before this change, with only its `java.text` import made `android.icu`. */
    private fun oldPieceEnd(text: CharSequence, start: Int, cap: Int = PAGE_CHARS): Int {
        if (text.length - start <= cap) return text.length
        var windowEnd = minOf(text.length, start + cap + GRAPHEME_SLACK)
        if (windowEnd < text.length && text[windowEnd - 1].isHighSurrogate() && text[windowEnd].isLowSurrogate()) windowEnd++
        val bi = BreakIterator.getCharacterInstance().apply { setText(text.subSequence(start, windowEnd).toString()) }
        val b = bi.following(cap - 1)
        if (b != BreakIterator.DONE && (start + b < windowEnd || windowEnd == text.length)) return start + b
        val end = start + cap
        return if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end + 1 else end
    }

    private val pair = "󠄀" // U+E0100, a variation selector: an extending code point written as a surrogate pair

    /** The adversarial inputs: ZWJ families, flag pairs, a 2,000 code point combining run, surrogate edges, random mixes. */
    private fun inputs(): List<String> {
        val pad = { n: Int -> "x".repeat(n) }
        val out = ArrayList<String>()
        for (shift in 0..3) {
            out += pad(shift) + "👨‍👩‍👧‍👦".repeat(400) // ZWJ family, 11 units
            out += pad(shift) + "👩‍💻".repeat(900)
            out += pad(shift) + "🇵🇱".repeat(2500) // flag pairs shifted against every cap
            out += pad(shift) + "🇵🇱🇩🇪🇺" + "a".repeat(10) // an odd regional indicator
        }
        out += "e" + "́".repeat(1999) + "z" // 2,000 code points in one grapheme
        out += pad(PAGE_CHARS - 50) + "e" + "́".repeat(1999) + "tail"
        out += pad(100) + "e" + "́".repeat(1999) + "tail" + "😀".repeat(50)
        // The surrogate-only cuts, each on both parities: a cluster of surrogate pairs longer than either window (the
        // fallback lands inside a pair, or between pairs), a pair at the slack window's edge, lone surrogates.
        for (n in listOf(1500, 3000, 6000)) out += "a" + pair.repeat(n) + "b"
        for (marks in GRAPHEME_SLACK - 4..GRAPHEME_SLACK + 4) out += pad(PAGE_CHARS - 1) + "a" + "́".repeat(marks) + pair + "tail"
        for (shift in 0..2) out += pad(PAGE_CHARS + GRAPHEME_SLACK - shift - 1) + "😀".repeat(4) + "y"
        out += "\uD800".repeat(50) + "\uDC00".repeat(50) + "a\uDC00\uD800b" + "😀".repeat(20) + "\uD83D"
        out += "q\r\n".repeat(900) + "\r"
        val atoms = listOf(
            "a", " ", "\n", "\r", "\r\n", "é", "́", "😀", "👍🏽", "👩‍💻", "👨‍👩‍👧", "🇵🇱", "🇩🇪",
            "‍", "가", "각", "️", "\uD800", "\uDC00", pair,
        )
        val random = java.util.Random(11)
        repeat(30) { out += buildString { val n = 50 + random.nextInt(400); while (length < n) append(atoms[random.nextInt(atoms.size)]) } }
        repeat(6) { out += buildString { val n = 9000 + random.nextInt(3000); while (length < n) append(atoms[random.nextInt(atoms.size)]) } }
        return out
    }

    /** Where cuts start: 0, earlier cuts (grapheme boundaries), and a few code units that are not boundaries. */
    private fun froms(text: String): List<Int> {
        val bi = BreakIterator.getCharacterInstance().apply { setText(text) }
        val boundaries = listOf(1, 7, 100, 2001, 3000).map { bi.following(minOf(it, text.length) - 1).coerceAtLeast(0) }
        return (listOf(0, 1, 3) + boundaries).filter { it < text.length }.distinct()
    }

    private fun limits(text: String, from: Int): List<Int> {
        val span = text.length - from
        if (span <= 700) return (from..text.length + 1).toList()
        val near = listOf(PAGE_CHARS, GRAPHEME_SLACK, 1500, 2000, 4000).flatMap { (from + it - 3)..(from + it + 3) }
        return ((from..text.length + 1 step 29) + near + (text.length - 5..text.length + 1)).filter { it >= from }.distinct()
    }

    @Test
    fun downEqualsTheOldCutAtOnEveryInput() {
        var cases = 0
        for (text in inputs()) for (from in froms(text)) for (limit in limits(text, from)) {
            assertEquals("down from $from to $limit in ${text.take(20)} (${text.length})", oldCutAt(text, from, limit), graphemeCut(text, from, limit, up = false))
            cases++
        }
        assertTrue("$cases cases", cases > 20_000)
    }

    @Test
    fun upEqualsTheOldPieceEndOnEveryInput() {
        var cases = 0
        for (text in inputs()) for (from in froms(text)) for (limit in limits(text, from).filter { it > from }) {
            assertEquals("up from $from to $limit in ${text.take(20)} (${text.length})", oldPieceEnd(text, from, limit - from), graphemeCut(text, from, limit, up = true))
            cases++
        }
        assertTrue("$cases cases", cases > 20_000)
    }
}
