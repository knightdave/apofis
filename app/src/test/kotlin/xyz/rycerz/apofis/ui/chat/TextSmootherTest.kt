package xyz.rycerz.apofis.ui.chat

import java.text.BreakIterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TextSmoother] stepped tick by tick: no clock, every boundary exact. */
class TextSmootherTest {
    @Test
    fun firstTextShownAtOnce() {
        assertEquals("hello", TextSmoother("hello").visible)
    }

    @Test
    fun revealsInBoundedSlices() {
        val target = "x".repeat(1000)
        val s = TextSmoother("")
        var previous = 0
        var steps = 0
        while (s.visible.length < target.length) {
            val before = s.visible.length
            s.step(target)
            steps++
            val slice = s.visible.length - before
            assertTrue("strictly growing", slice >= 1)
            assertTrue("slice $slice at most 240", slice <= 240)
            if (previous > 2) assertTrue("slice $slice at most 1.5 x $previous", slice <= previous * 1.5)
            assertTrue(target.startsWith(s.visible))
            previous = slice
        }
        assertTrue("reached in $steps steps", steps <= 40)
        assertSame("caught up is the target itself", target, s.visible)
    }

    @Test
    fun exactSlicesAtTheBoundaries() {
        // backlog 2 shows 2; backlog 3 shows max(2, round(3 / 4)); 1000 shows the 240 cap; 1200 is still smoothed, 1201 snaps.
        assertEquals(2, TextSmoother("").step("ab").length)
        assertEquals(2, TextSmoother("").step("abc").length)
        assertEquals(240, TextSmoother("").step("y".repeat(1000)).length)
        assertEquals(240, TextSmoother("").step("y".repeat(SMOOTH_LAG_BUDGET)).length)
        assertEquals(SMOOTH_LAG_BUDGET + 1, TextSmoother("").step("y".repeat(SMOOTH_LAG_BUDGET + 1)).length)
        // A final slice of one character is legal: backlog 1 shows 1.
        assertEquals(1, TextSmoother("").step("a").length)
        // Growth: after a slice of 3, a sudden backlog of 997 gets at most 1.5 x 3 = 4 (the mean alone would be 122).
        val growing = TextSmoother("")
        assertEquals(3, growing.step("c".repeat(12)).length)
        assertEquals(7, growing.step("c".repeat(1000)).length)
    }

    @Test
    fun largeBurstSnaps() {
        val target = "a" + "b".repeat(99_999)
        val s = TextSmoother("a")
        assertSame(target, s.step(target))
    }

    @Test
    fun correctionsSnap() {
        val shorter = TextSmoother("abcdef")
        assertEquals("abc", shorter.step("abc"))
        val rewrite = TextSmoother("abcdef")
        val other = "abX" + "y".repeat(500)
        assertEquals(other, rewrite.step(other))
        val forced = TextSmoother("abc")
        val long = "abc" + "z".repeat(500)
        assertEquals(long, forced.step(long, snap = true))
        // A rewrite of the part not yet shown is a correction too: the new target shows at once.
        val hidden = TextSmoother("")
        val first = "q".repeat(600)
        hidden.step(first)
        assertTrue(hidden.visible.length < first.length)
        val rewritten = "q".repeat(300) + "R".repeat(400)
        assertTrue(hidden.corrects(rewritten))
        assertEquals(rewritten, hidden.step(rewritten))
        assertFalse(hidden.corrects(rewritten + "more"))
    }

    @Test
    fun neverSplitsSurrogates() {
        val target = "😀".repeat(300)
        val s = TextSmoother("")
        while (s.visible.length < target.length) {
            s.step(target)
            assertFalse("ends on a high surrogate at ${s.visible.length}", s.visible.isNotEmpty() && s.visible.last().isHighSurrogate())
        }
    }

    @Test
    fun cutsOnlyAtGraphemeBoundaries() {
        // Flags, a ZWJ family, combining marks and CRLF: every shown end is a grapheme boundary of the target.
        val unit = "🇵🇱" + "👨‍👩‍👧" + "é́" + "\r\n" + "ab"
        val target = unit.repeat(60)
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(target) }
        val s = TextSmoother("")
        while (s.visible.length < target.length) {
            s.step(target)
            assertTrue("cut at ${s.visible.length}", boundaries.isBoundary(s.visible.length))
        }
    }

    @Test
    fun neverOvershoots() {
        val full = "w".repeat(2000)
        var length = 7
        val s = TextSmoother("")
        while (length <= full.length) {
            val target = full.substring(0, length)
            s.step(target)
            assertTrue("${s.visible.length} <= $length", s.visible.length <= length)
            assertTrue(target.startsWith(s.visible))
            length += 7
        }
    }
}
