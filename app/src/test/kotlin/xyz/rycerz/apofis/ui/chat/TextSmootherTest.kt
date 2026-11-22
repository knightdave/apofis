package xyz.rycerz.apofis.ui.chat

import java.text.BreakIterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TextSmoother] stepped tick by tick: no clock, every boundary exact. Robolectric for the platform's ICU (in [cutAt]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
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
        // A rewrite of the part not yet shown keeps what is shown (still a prefix) and reveals the new text from there.
        val hidden = TextSmoother("")
        val first = "q".repeat(600)
        hidden.step(first)
        val shown = hidden.visible
        assertEquals(150, shown.length)
        val rewritten = "q".repeat(300) + "R".repeat(400)
        hidden.accept(rewritten, snap = false)
        assertEquals(shown, hidden.visible)
        hidden.step()
        assertTrue(rewritten.startsWith(hidden.visible) && hidden.visible.length > shown.length)
        // A rewrite of what is shown shows the new text at once, whatever the timer.
        val r2 = "q".repeat(100) + "Z".repeat(10)
        hidden.accept(r2, snap = false)
        assertEquals(r2, hidden.visible)
        // A snap is committed: turning it off again never takes back what was shown.
        val s = TextSmoother("")
        val t = "s".repeat(800)
        s.accept(t, snap = true)
        assertEquals(t, s.visible)
        s.accept(t + "more", snap = false)
        assertEquals(t, s.visible)
    }

    @Test
    fun theMeanEvictsTheOldestAndASnapClearsIt() {
        // Ten slices of 2, then a backlog of 1000 (raw 240): mean of the last ten = (9 x 2 + 240) / 10 = 25.8 -> 26.
        // Without eviction it would be (10 x 2 + 240) / 11 -> 24; with the cap also applied after a slice of 2, 3.
        val s = TextSmoother("")
        for (i in 1..10) assertEquals(2 * i, s.step("x".repeat(2 * i)).length)
        assertEquals(20 + 26, s.step("x".repeat(1020)).length)
        // After a snap the history is empty: a backlog of 12 then shows its own raw slice, 3.
        s.accept("x".repeat(1100), snap = true)
        assertEquals(1100 + 3, s.step("x".repeat(1112)).length)
    }

    @Test
    fun longShownTextIsNoLongerSmoothed() {
        // Up to SMOOTH_MAX_SHOWN shown characters a new target keeps the shown length; past it every update shows all.
        for ((n, kept) in listOf(19_999 to true, 20_000 to true, 20_001 to false)) {
            val s = TextSmoother("y".repeat(n))
            val next = "y".repeat(n) + "z".repeat(600)
            s.accept(next, snap = false)
            assertEquals("$n", if (kept) n else next.length, s.shown)
        }
        // A tick never copies more than SMOOTH_MAX_SHOWN: a cut past it shows the target itself.
        val s = TextSmoother("y".repeat(19_999))
        val next = "y".repeat(19_999) + "z".repeat(600)
        s.step(next)
        assertSame(next, s.visible)
    }

    @Test
    fun appendedTextNeverLeavesACutInsideAGrapheme() {
        // Caught up on one regional indicator, then five more arrive: the old end is now inside a flag.
        val ri = "\uD83C\uDDF5"
        val s = TextSmoother(ri)
        val next = ri.repeat(6)
        s.accept(next, snap = false)
        val whole = BreakIterator.getCharacterInstance().apply { setText(next) }
        assertTrue("clamped to ${s.shown}", whole.isBoundary(s.shown))
        // Every step and every new target of a growing mixed text ends on a boundary of that text.
        val unit = "\uD83C\uDDF5\uD83C\uDDF1e\u0301\u0301\uD83D\uDC68\u200D\uD83D\uDC69\r\nab"
        val full = unit.repeat(40)
        val g = TextSmoother("")
        var end = 1
        while (end <= full.length) {
            val target = full.substring(0, end)
            g.accept(target, snap = false)
            val b = BreakIterator.getCharacterInstance().apply { setText(target) }
            assertTrue("accept $end -> ${g.shown}", b.isBoundary(g.shown))
            g.step()
            assertTrue("step $end -> ${g.shown}", b.isBoundary(g.shown))
            end += 3
        }
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

    private fun boundaryOf(text: String, at: Int) = BreakIterator.getCharacterInstance().apply { setText(text) }.isBoundary(at)

    @Test
    fun aClampOntoItsAnchorStillChecksTheNextTarget() {
        // Re-review I2 (a): the clamp retreats onto its anchor, then another target arrives before any tick.
        val s = TextSmoother("a")
        assertEquals("ab", s.step("ab"))
        s.accept("ab\u0301", snap = false) // the b takes a mark: back to "a"
        assertEquals("a", s.visible)
        val next = "a\u0301z" // now the a takes a mark: 1 is no boundary any more
        s.accept(next, snap = false)
        assertTrue("shown ${s.shown} of '$next'", boundaryOf(next, s.shown))
    }

    @Test
    fun aTickNeverCutsALongCluster() {
        // Re-review I2 (b): a slice of 4 after a slice of 3, into "b" plus 1099 combining marks (backlog 1100, under the
        // budget, longer than the cut's lookahead).
        val s = TextSmoother("")
        assertEquals(3, s.step("a".repeat(12)).length)
        val next = "aaa" + "b" + "\u0301".repeat(1099)
        s.step(next)
        assertTrue("shown ${s.shown} of ${next.length}", boundaryOf(next, s.shown))
    }
}
