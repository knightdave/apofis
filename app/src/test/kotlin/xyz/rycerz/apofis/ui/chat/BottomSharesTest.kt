package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom area's one height budget, over a table of windows, fonts, drafts, notes, keyboards and insets. */
class BottomSharesTest {
    private val style = TextStyle(fontSize = 15.sp, lineHeight = 1.3.em) // the composer's text (bodyLarge)

    @Test
    fun everyRowKeepsThePriorityOrder() {
        var rows = 0
        for (fontScale in listOf(1f, 2f)) {
            val density = Density(2f, fontScale) // xhdpi
            val d = density.bottomDims(style)
            fun dp(v: Int) = v * 2
            // The bar's own row (without the status inset): 56 dp, about 66 dp at 2.0.
            val bar = dp(if (fontScale == 1f) 56 else 66)
            for (status in listOf(0, 24)) for (bottomInset in listOf(0, 24, 200)) for (window in 110..800 step 10) {
                val available = dp(window - status - bottomInset) // the safe band; landscape with a keyboard is a short window
                for (lines in listOf(1, 3, 6, 40)) for (notes in listOf(0, 30, 54, 120)) for (unsent in listOf(false, true)) for (expanded in listOf(false, true)) {
                    if (unsent && notes == 0) continue
                    val n = dp(notes)
                    val natural = d.field(minOf(lines, 5))
                    val wantsExpand = expanded || lines >= 3
                    val s = bottomShares(available, bar, n, unsent, wantsExpand, if (expanded) Expansion(1f, lines) else null, d) { room -> minOf(room, natural) }
                    val case = "scale $fontScale status $status inset $bottomInset window $window lines $lines notes $notes unsent $unsent expanded $expanded: $s"
                    rows++
                    val total = available.coerceAtLeast(0)
                    val deco = s.marginTop + s.paddingTop + s.paddingBottom + s.marginBottom
                    // Nothing negative; the shares sum to the band; the field and the action column lie in the row.
                    for (v in listOf(s.bar, s.notes, s.marginTop, s.paddingTop, s.row, s.field, s.actions, s.paddingBottom, s.marginBottom, s.transcript)) assertTrue(case, v >= 0)
                    assertEquals(case, total, s.bar + s.notes + s.composer + s.transcript)
                    assertTrue(case, s.field <= s.row && s.actions <= s.row && deco <= d.cardInsets)

                    // The order, step by step: each share is full before the next gets anything.
                    var left = total
                    val row1 = minOf(left, d.row1) // 1. the bare row
                    left -= row1
                    assertTrue(case, s.row >= row1)
                    if (total >= d.row1) assertEquals("$case: Send keeps 48 dp", d.target, minOf(s.actions, d.target))
                    assertEquals("$case: 2. the bar, exactly when it fits", left >= bar, s.barShown) // 2. the bar
                    if (s.barShown) left -= bar
                    val noticeRow = if (unsent) minOf(n, d.noticeRow, left) else 0 // 3. the notice row
                    left -= noticeRow
                    val reserve = minOf(d.transcriptMin, left) // 4. the transcript's 64 dp
                    left -= reserve
                    assertTrue(case, s.transcript >= reserve)
                    val more = minOf(n - noticeRow, left) // 5. the rest of the notes, at their natural height
                    left -= more
                    assertEquals("$case: 3 and 5. the notes", noticeRow + more, s.notes)
                    if (expanded) {
                        // Expanded = the collapsed layout with the transcript's surplus over 64 dp in the field.
                        val c = bottomShares(available, bar, n, unsent, wantsExpand, null, d) { room -> minOf(room, natural) }
                        for ((name, e, col) in listOf(
                            Triple("bar", s.bar, c.bar), Triple("notes", s.notes, c.notes), Triple("row", s.row, c.row), Triple("field", s.field, c.field),
                            Triple("actions", s.actions, c.actions), Triple("decoration", deco, c.marginTop + c.paddingTop + c.paddingBottom + c.marginBottom),
                        )) assertTrue("$case: expanding shrank the $name ($col to $e)", e >= col)
                        assertEquals("$case: the bar stays", c.barShown, s.barShown)
                        assertTrue("$case: the transcript keeps its 64 dp", s.transcript >= minOf(c.transcript, d.transcriptMin))
                        if (c.actions == 2 * d.target) {
                            assertEquals("$case: Collapse shown while expanded", 2 * d.target, s.actions)
                            assertEquals("$case: the surplus goes to the field", minOf(c.transcript, d.transcriptMin), s.transcript)
                        } else {
                            assertEquals("$case: no room for Collapse: shown collapsed", c, s)
                        }
                    } else {
                        // 6. further lines and Expand's room, then 7. decoration.
                        val wanted = maxOf(natural, if (wantsExpand) 2 * d.target else d.target)
                        val grow = (minOf(wanted, row1 + left) - row1).coerceAtLeast(0)
                        left -= grow
                        assertEquals("$case: 6. the row", row1 + grow, s.row)
                        val decoration = minOf(d.cardInsets, left)
                        assertEquals("$case: 7. decoration only from what is left", decoration, deco)
                        assertEquals(case, reserve + left - decoration, s.transcript)
                    }
                    // Decoration gets nothing while a ruled share is short.
                    val short = reserve < d.transcriptMin || noticeRow < (if (unsent) minOf(n, d.noticeRow) else 0) || more < n - noticeRow
                    if (short) assertEquals("$case: decoration while a ruled share is short", 0, deco)
                    // Expand only beside Send when both 48 dp targets fit.
                    assertEquals(case, wantsExpand && s.row >= 2 * d.target, s.actions == 2 * d.target)
                }
            }
        }
        assertTrue(rows > 40_000)
    }

    /** The reviewer's cases: 200 dp with an 80 dp bar leaves the transcript 64 dp; 220 dp with status and navigation leaves the whole 54 dp row. */
    @Test
    fun theBoundaryCases() {
        val d = Density(2f, 1f).bottomDims(style)
        val one = d.field(1)
        // 200 dp above the keyboard, 24 dp status + 56 dp bar, no notice, one line.
        var s = bottomShares(2 * (200 - 24), 112, 0, false, false, null, d) { minOf(it, one) }
        assertTrue(s.barShown)
        assertEquals(128, s.transcript) // 64 dp
        assertEquals(96, s.row)
        assertEquals(16, s.marginTop + s.paddingTop + s.paddingBottom + s.marginBottom) // 8 dp of decoration left over
        // 220 dp window, 24 dp status, 24 dp navigation, a failed send.
        s = bottomShares(2 * (220 - 48), 112, 108, true, false, null, d) { minOf(it, one) }
        assertTrue(s.barShown)
        assertEquals(108, s.notes) // the whole 54 dp row
        assertEquals(96, s.row)
        assertEquals(2 * (220 - 48) - 112 - 108 - 96, s.transcript) // 14 dp: what the 64 dp share can get
        assertEquals(0, s.marginTop + s.paddingTop + s.paddingBottom + s.marginBottom)
        // A roomy window: every part at its full share.
        s = bottomShares(1520, 112, 96, true, false, null, d) { minOf(it, d.field(2)) }
        assertTrue(s.barShown)
        assertEquals(96, s.notes)
        assertEquals(d.field(2), s.row)
        assertEquals(d.cardInsets, s.marginTop + s.paddingTop + s.paddingBottom + s.marginBottom)
        // The expanded editor never takes the notes' height.
        // 216 dp of safe band, six lines: collapsed, a 96 dp row with both targets and no decoration; expanding keeps them.
        val six = d.field(5)
        val collapsed = bottomShares(432, 112, 0, false, true, null, d) { minOf(it, six) }
        assertEquals(192, collapsed.row)
        assertEquals(192, collapsed.actions)
        assertEquals(128, collapsed.transcript)
        val expanded = bottomShares(432, 112, 0, false, true, Expansion(1f, 6), d) { error("expanded measures after") }
        assertEquals("nothing to grow into: the same layout", collapsed.copy(field = 192), expanded.copy(field = 192))
        assertEquals("Collapse stays", 192, expanded.actions)
        // 247 dp: collapsed shows five lines with 3 px of padding; expanded keeps them (no surplus over the 64 dp).
        val roomy = bottomShares(494, 112, 0, false, true, Expansion(1f, 6), d) { error("expanded measures after") }
        assertEquals(128, roomy.transcript)
        assertTrue(roomy.field > collapsed.row)
        s = bottomShares(1520, 112, 60, false, true, Expansion(1f, 3), d) { error("expanded measures after") }
        assertEquals(60, s.notes)
        assertEquals(128, s.transcript)
    }

    /** The draft strip gets space last, after the card's decoration, and whole or not at all: the first piece to go. */
    @Test
    fun theStripGetsSpaceLast() {
        val d = Density(2f, 1f).bottomDims(style)
        val one = d.field(1)
        val strip = 2 * (88 + 8)
        val bare = bottomShares(1520, 112, 60, false, false, null, d) { minOf(it, one) }
        val roomy = bottomShares(1520, 112, 60, false, false, null, d, strip) { minOf(it, one) }
        assertEquals(strip, roomy.strip)
        assertEquals(bare.card + strip, roomy.card)
        assertEquals(bare.transcript - strip, roomy.transcript)
        assertEquals(1520, roomy.bar + roomy.notes + roomy.composer + roomy.transcript)
        // Exactly the room the strip needs beyond every other piece's full share: it is shown.
        val needed = bare.bar + bare.notes + bare.composer + 2 * 64 + strip
        assertEquals(strip, bottomShares(needed, 112, 60, false, false, null, d, strip) { minOf(it, one) }.strip)
        // One pixel less: the strip goes whole, everything else keeps the share it had without a strip.
        val short = bottomShares(needed - 1, 112, 60, false, false, null, d, strip) { minOf(it, one) }
        assertEquals(0, short.strip)
        assertEquals(bottomShares(needed - 1, 112, 60, false, false, null, d) { minOf(it, one) }, short)
    }

    /** The slash palette gets space after the strip, so it is the first piece to give way; it may be cut partway, never beyond half the band. */
    @Test
    fun thePaletteGetsSpaceLast() {
        val d = Density(2f, 1f).bottomDims(style)
        val one = d.field(1)
        val strip = 2 * (88 + 8 + 11)
        val palette = 9 * 96 / 2 + 4 // 4.5 rows of 48 dp and two 1 dp borders
        val withStrip = bottomShares(1520, 112, 60, false, false, null, d, strip) { minOf(it, one) }
        val roomy = bottomShares(1520, 112, 60, false, false, null, d, strip, palette) { minOf(it, one) }
        assertEquals(palette, roomy.palette)
        assertEquals(withStrip.copy(transcript = withStrip.transcript - palette, palette = palette), roomy)
        assertEquals(1520, roomy.bar + roomy.notes + roomy.palette + roomy.composer + roomy.transcript)
        // Short of room: the palette is cut first, partway, while the strip and everything else keep their shares.
        val needed = withStrip.bar + withStrip.notes + withStrip.composer + 2 * 64
        for (extra in listOf(0, 1, palette / 2, palette - 1)) {
            val s = bottomShares(needed + extra, 112, 60, false, false, null, d, strip, palette) { minOf(it, one) }
            assertEquals("extra $extra", extra, s.palette)
            assertEquals("extra $extra", bottomShares(needed + extra, 112, 60, false, false, null, d, strip) { minOf(it, one) }, s.copy(palette = 0, transcript = s.transcript + extra))
            assertEquals(needed + extra, s.bar + s.notes + s.palette + s.composer + s.transcript)
        }
        // Never beyond half the band.
        val tall = bottomShares(1520, 112, 60, false, false, null, d, strip, 2000) { minOf(it, one) }
        assertEquals(760, tall.palette)
        assertEquals(1520, tall.bar + tall.notes + tall.palette + tall.composer + tall.transcript)
        // Without a palette: the old result.
        assertEquals(withStrip, bottomShares(1520, 112, 60, false, false, null, d, strip, 0) { minOf(it, one) })
    }
}
