package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * The fixed sizes of the chat's bottom area for one density and text style, px: the 48 dp target, the card's vertical
 * margins and padding together (32 dp), one text line, the field's padding above and below together, one notice row
 * (54 dp: the unsent notice's 48 dp buttons and its 6 dp top padding) and the transcript's share (64 dp: one 48 dp row with the list's padding).
 */
internal data class BottomDims(val target: Int, val cardInsets: Int, val line: Float, val fieldPadding: Int, val noticeRow: Int, val transcriptMin: Int) {
    /** The field for [lines] lines, with its padding. */
    fun field(lines: Int): Int = ceil(line * lines).toInt() + fieldPadding

    /** The card's row for [lines] lines: the field, at least one 48 dp target. */
    fun row(lines: Int): Int = maxOf(target, field(lines))

    /** The smallest composer: Send or Stop at 48 dp beside a one-line field, with the card's margins and padding. */
    val minimal: Int get() = cardInsets + row(1)

    /** The card's margins and padding at height [h]: they shrink first, so the row keeps its height. */
    fun insets(h: Int): Int = (h - row(1)).coerceIn(0, cardInsets)

    /** The composer's height in [room]: collapsed 1 to 5 lines of [lines], expanded the whole room. */
    fun composer(room: Int, lines: Int, expanded: Boolean): Int =
        if (expanded) room.coerceAtLeast(0) else minOf(room, cardInsets + row(lines.coerceIn(1, 5))).coerceAtLeast(0)

    /** The action column for a row of [row] px: Send or Stop, and Expand above it only when both 48 dp targets fit. */
    fun actions(row: Int, wantsExpand: Boolean): Int = if (wantsExpand && row >= 2 * target) 2 * target else minOf(row, target)
}

/** Field padding (above and below together): one line as tall as a 48 dp target, 4 to 14 dp a side (14 dp at scale 1). */
internal fun Density.fieldPaddingPx(line: Float): Int = (((48.dp.toPx() - line) / 2).coerceIn(4.dp.toPx(), 14.dp.toPx()) * 2).let { ceil(it).toInt() }

internal fun Density.lineHeightPx(style: TextStyle): Float =
    if (style.lineHeight.isEm) style.lineHeight.value * style.fontSize.toPx() else style.lineHeight.toPx()

internal fun Density.bottomDims(style: TextStyle): BottomDims {
    val line = lineHeightPx(style)
    // A notice row: its 48 dp buttons under the notice's 6 dp top padding.
    return BottomDims(48.dp.roundToPx(), 32.dp.roundToPx(), line, fieldPaddingPx(line), 54.dp.roundToPx(), 64.dp.roundToPx())
}

/** The heights of the parts, px; they sum to the available height. */
internal data class BottomShares(val barShown: Boolean, val bar: Int, val notes: Int, val composer: Int, val field: Int, val actions: Int, val transcript: Int)

/**
 * Shares the [available] height above the keyboard (and navigation bar) among the top [bar], the notes (natural height
 * [notes]; [unsent] when an unsent entry is among them), the composer and the transcript. [composer] gives the
 * composer's height for a room (the composer takes at most its room; [BottomDims.composer] models it from a line count
 * and expanded state). Priority when space is short, first to last:
 *  1. the smallest composer ([BottomDims.minimal]), or all the height when even that does not fit;
 *  2. the top bar, shown only when it fits beside the smallest composer;
 *  3. one row of notes (48 dp targets) when an unsent entry exists (they scroll inside it when taller);
 *  4. the transcript's 64 dp;
 *  5. the composer's further lines (collapsed, up to five) or the rest (expanded);
 *  6. the rest of the notes.
 * The transcript gets what is left. Nothing is negative and nothing exceeds its share.
 */
internal fun bottomShares(available: Int, bar: Int, notes: Int, unsent: Boolean, wantsExpand: Boolean, d: BottomDims, composer: (room: Int) -> Int): BottomShares {
    val total = available.coerceAtLeast(0)
    val first = minOf(total, d.minimal)
    val barShown = total - d.minimal >= bar
    var left = total - first - if (barShown) bar else 0
    val noticeRow = if (unsent) minOf(notes, d.noticeRow, left) else 0
    left -= noticeRow
    val reserve = minOf(d.transcriptMin, left)
    left -= reserve
    val card = composer(first + left).coerceIn(first, first + left)
    left -= card - first
    val moreNotes = minOf(notes - noticeRow, left).coerceAtLeast(0)
    left -= moreNotes
    val row = card - d.insets(card)
    return BottomShares(barShown, if (barShown) bar else 0, noticeRow + moreNotes, card, row, d.actions(row, wantsExpand), reserve + left)
}
