package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bottom area's one height budget, over a table of windows, fonts, drafts, notices, keyboards and insets. */
class BottomSharesTest {
    private val style = TextStyle(fontSize = 15.sp, lineHeight = 1.3.em) // the composer's text (bodyLarge)

    @Test
    fun everyRowFitsInItsOrder() {
        var rows = 0
        for (fontScale in listOf(1f, 2f)) {
            val density = Density(2f, fontScale) // xhdpi
            val d = density.bottomDims(style)
            fun dp(v: Int) = (v * 2f).toInt()
            for (status in listOf(0, 24)) for (keyboard in listOf(0, 200)) for (window in 110..800 step 10) {
                // The bar: a 56 dp row (taller at 2.0, measured about 66 dp) under the status inset; landscape is a short window.
                val bar = dp((if (fontScale == 1f) 56 else 66) + status)
                val available = dp(window - keyboard)
                for (lines in listOf(1, 3, 6, 40)) for (notes in listOf(0, 40, 120)) for (expanded in listOf(false, true)) {
                    val unsent = notes > 0
                    val n = dp(notes)
                    val s = bottomShares(available, bar, n, unsent, expanded || lines >= 3, d) { room -> d.composer(room, lines, expanded) }
                    val case = "scale $fontScale status $status keyboard $keyboard window $window lines $lines notes $notes expanded $expanded: $s"
                    val total = available.coerceAtLeast(0)
                    rows++
                    // Nothing negative, nothing larger than the height; the shares sum to what is occupied.
                    for (v in listOf(s.bar, s.notes, s.composer, s.field, s.actions, s.transcript)) assertTrue(case, v >= 0)
                    assertEquals(case, total, s.bar + s.notes + s.composer + s.transcript)
                    assertTrue(case, s.field <= s.composer && s.actions <= s.field) // the row inside the card, the column inside the row
                    // 1. The smallest composer comes first: Send or Stop at 48 dp with a one-line field whenever that fits.
                    assertTrue(case, s.composer >= minOf(total, d.minimal))
                    if (total >= d.row(1)) assertEquals(case, d.target, minOf(s.actions, d.target))
                    // 2. The bar is shown exactly when it fits beside the smallest composer, and is then never covered.
                    assertEquals(case, total - d.minimal >= bar, s.barShown)
                    assertEquals(case, if (s.barShown) bar else 0, s.bar)
                    var left = total - minOf(total, d.minimal) - s.bar
                    // 3. One notice row when an unsent entry exists.
                    val row = if (unsent) minOf(n, d.noticeRow, left) else 0
                    assertTrue(case, s.notes >= row)
                    left -= row
                    // 4. The transcript's 64 dp before any growth of the composer or more notes.
                    val reserve = minOf(d.transcriptMin, left)
                    assertTrue(case, s.transcript >= reserve)
                    if (s.transcript < d.transcriptMin) {
                        assertEquals(case, minOf(total, d.minimal), s.composer)
                        assertEquals(case, row, s.notes)
                    }
                    // 5. Then the composer: up to five lines collapsed, the rest expanded.
                    if (expanded) {
                        assertEquals(case, reserve, s.transcript)
                    } else {
                        assertTrue(case, s.composer <= maxOf(d.minimal, d.cardInsets + d.row(minOf(lines, 5))))
                    }
                    // Expand only beside Send when two 48 dp targets fit.
                    if (s.actions == 2 * d.target) assertTrue(case, s.field >= 2 * d.target)
                }
            }
        }
        assertTrue(rows > 10_000)
    }

    @Test
    fun aRoomyWindowGivesEveryPartItsFullShare() {
        val d = Density(2f, 1f).bottomDims(style)
        val s = bottomShares(1520, 160, 96, unsent = true, wantsExpand = false, d = d) { d.composer(it, 2, false) }
        assertTrue(s.barShown)
        assertEquals(96, s.notes)
        assertEquals(d.cardInsets + d.row(2), s.composer)
        assertEquals(1520 - 160 - 96 - s.composer, s.transcript)
        assertEquals(d.target, s.actions)
    }
}
