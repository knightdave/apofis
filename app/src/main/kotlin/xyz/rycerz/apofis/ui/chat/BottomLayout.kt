package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * The fixed sizes of the chat's bottom area for one density and text style, px: the 48 dp target, the card's vertical
 * margins and padding together (32 dp, padding 16 of it), one text line, the field's padding above and below together,
 * one notice row (54 dp: the unsent notice's 48 dp buttons and its 6 dp top padding) and the transcript's share
 * (64 dp: one 48 dp row with the list's padding; 0 for a composer on its own).
 */
internal data class BottomDims(
    val target: Int,
    val cardInsets: Int,
    val cardPadding: Int,
    val line: Float,
    val fieldPadding: Int,
    val noticeRow: Int,
    val transcriptMin: Int,
) {
    /** The field for [lines] lines, with its padding; the text rounds each line's height up to a whole pixel. */
    fun field(lines: Int): Int = ceil(line).toInt() * lines + fieldPadding

    /** The bare row: Send or Stop at 48 dp beside a one-line field, no card margins or padding. */
    val row1: Int get() = maxOf(target, field(1))
}

/** Field padding (above and below together): one line as tall as a 48 dp target, 4 to 14 dp a side (14 dp at scale 1). */
internal fun Density.fieldPaddingPx(line: Float): Int = ceil(((48.dp.toPx() - line) / 2).coerceIn(4.dp.toPx(), 14.dp.toPx())).toInt() * 2 // each side a whole pixel, as Compose rounds it

internal fun Density.lineHeightPx(style: TextStyle): Float =
    if (style.lineHeight.isEm) style.lineHeight.value * style.fontSize.toPx() else style.lineHeight.toPx()

internal fun Density.bottomDims(style: TextStyle, transcript: Boolean = true): BottomDims {
    val line = lineHeightPx(style)
    return BottomDims(48.dp.roundToPx(), 32.dp.roundToPx(), 16.dp.roundToPx(), line, fieldPaddingPx(line), 54.dp.roundToPx(), if (transcript) 64.dp.roundToPx() else 0)
}

/** An expanded editor: [progress] 0 to 1 of the growth from the collapsed field ([lines] of text) to all the rest, at most [cap] px. */
internal data class Expansion(val progress: Float, val lines: Int, val cap: Int = Int.MAX_VALUE)

/**
 * The heights of every part, px, in the safe band (status inset to keyboard or navigation inset). The composer is, top
 * to bottom: [marginTop], the card ([paddingTop], the draft photos' [strip], the [row] holding the [field] and the
 * action column [actions], [paddingBottom]) and [marginBottom]. [bar] + [notes] + [composer] + [transcript] is the
 * band's height.
 */
internal data class BottomShares(
    val barShown: Boolean,
    val bar: Int,
    val notes: Int,
    val marginTop: Int,
    val paddingTop: Int,
    val row: Int,
    val field: Int,
    val actions: Int,
    val paddingBottom: Int,
    val marginBottom: Int,
    val transcript: Int,
    val strip: Int = 0,
) {
    val card: Int get() = paddingTop + strip + row + paddingBottom
    val composer: Int get() = marginTop + card + marginBottom
}

/**
 * Shares the [available] height of the safe band among the top [bar] (its own row, without the status inset), the notes
 * (natural height [notes]; [unsent] when an unsent entry is among them), the composer and the transcript. Collapsed, bare
 * content comes before decoration; first to last:
 *  1. the bare row: Send or Stop at 48 dp with a one-line field (all the band when even that does not fit);
 *  2. the top bar, shown only when it fits beside the bare row;
 *  3. one notice row when an unsent entry exists;
 *  4. the transcript's 64 dp;
 *  5. the rest of the notes, at their natural height (they scroll inside their share when capped);
 *  6. further field lines up to five ([field] gives the field's natural height for a room) and the room for Expand above
 *     Send when [wantsExpand] (both 48 dp targets);
 *  7. the card's padding, then its margins (up to 32 dp together; odd pixels go to the bottom);
 *  8. the draft photos' [strip] (its natural height, 0 without photos), whole or not at all: a strip cut partway would
 *     cut its 48 dp remove targets.
 * The transcript gets what is left. Expanded ([expansion]), the layout is the collapsed one with the transcript's surplus
 * over its 64 dp moved into the field (growing with the expansion's progress, at most its cap), so expanding never makes
 * anything smaller and Collapse stands where Expand stood; where the collapsed layout has no room for Expand, an
 * expanded editor is shown collapsed. Nothing is negative; the shares sum to the band.
 */
internal fun bottomShares(
    available: Int,
    bar: Int,
    notes: Int,
    unsent: Boolean,
    wantsExpand: Boolean,
    expansion: Expansion?,
    d: BottomDims,
    strip: Int = 0,
    field: (room: Int) -> Int,
): BottomShares {
    // Expanded, the collapsed field is the text's 1 to 5 lines (the field itself is measured once, at the result).
    val collapsedField: (Int) -> Int = if (expansion == null) field else { room -> minOf(room, d.field(expansion.lines.coerceIn(1, 5))) }
    val c = collapsedShares(available, bar, notes, unsent, wantsExpand, d, strip, collapsedField)
    if (expansion == null || c.actions < 2 * d.target) return c
    val surplus = minOf((c.transcript - d.transcriptMin).coerceAtLeast(0), (expansion.cap - c.row).coerceAtLeast(0))
    val grow = (surplus * expansion.progress.coerceIn(0f, 1f)).toInt()
    return c.copy(row = c.row + grow, field = c.row + grow, transcript = c.transcript - grow)
}

private fun collapsedShares(available: Int, bar: Int, notes: Int, unsent: Boolean, wantsExpand: Boolean, d: BottomDims, strip: Int, field: (room: Int) -> Int): BottomShares {
    val total = available.coerceAtLeast(0)
    var row = minOf(total, d.row1)
    var left = total - row
    val barShown = barFits(available, bar, d)
    if (barShown) left -= bar
    val noticeRow = if (unsent) minOf(notes, d.noticeRow, left) else 0
    left -= noticeRow
    val reserve = minOf(d.transcriptMin, left)
    left -= reserve
    val moreNotes = minOf(notes - noticeRow, left).coerceAtLeast(0)
    left -= moreNotes
    val fieldHeight = field(row + left).coerceIn(0, row + left) // 1 to 5 lines, measured once
    val wanted = maxOf(fieldHeight, if (wantsExpand) 2 * d.target else d.target)
    val grow = (minOf(wanted, row + left) - row).coerceAtLeast(0)
    row += grow
    left -= grow
    val padding = minOf(d.cardPadding, left)
    left -= padding
    val margin = minOf(d.cardInsets - d.cardPadding, left)
    left -= margin
    val stripShare = if (strip <= left) strip else 0
    left -= stripShare
    val actions = if (wantsExpand && row >= 2 * d.target) 2 * d.target else minOf(row, d.target)
    return BottomShares(
        barShown, if (barShown) bar else 0, noticeRow + moreNotes,
        margin / 2, padding / 2, row, minOf(fieldHeight, row), actions, padding - padding / 2, margin - margin / 2, reserve + left, stripShare,
    )
}

/** Step 2 of [bottomShares]: the bar is shown exactly when it fits beside the bare row in the [available] safe band. */
internal fun barFits(available: Int, bar: Int, d: BottomDims): Boolean = available.coerceAtLeast(0) - d.row1 >= bar
