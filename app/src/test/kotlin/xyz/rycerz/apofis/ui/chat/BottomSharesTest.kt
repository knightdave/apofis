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
                    if (!expanded) {
                        // 6. further lines and Expand's room, then 7. decoration.
                        val wanted = maxOf(natural, if (wantsExpand) 2 * d.target else d.target)
                        val grow = (minOf(wanted, row1 + left) - row1).coerceAtLeast(0)
                        left -= grow
                        assertEquals("$case: 6. the row", row1 + grow, s.row)
                        val decoration = minOf(d.cardInsets, left)
                        assertEquals("$case: 7. decoration only from what is left", decoration, deco)
                        assertEquals(case, reserve + left - decoration, s.transcript)
                    } else {
                        // 7. decoration, then 8. the expanded field takes the rest; the transcript keeps its 64 dp.
                        assertEquals("$case: 7. decoration", minOf(d.cardInsets, left), deco)
                        assertEquals("$case: 8. the rest", reserve, s.transcript)
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
        s = bottomShares(1520, 112, 60, false, true, Expansion(1f, 3), d) { error("expanded measures after") }
        assertEquals(60, s.notes)
        assertEquals(128, s.transcript)
    }
}
