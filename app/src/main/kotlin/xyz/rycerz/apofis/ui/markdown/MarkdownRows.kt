package xyz.rycerz.apofis.ui.markdown

import java.text.BreakIterator
import xyz.rycerz.apofis.ui.chat.CHUNK
import xyz.rycerz.apofis.ui.chat.Work
import xyz.rycerz.apofis.ui.chat.chunkRanges

enum class BlockKind { TEXT, TABLE, CODE }
enum class BlockMode { PARSED, LITERAL }

/**
 * One logical block of a reply: a paragraph group, a table or a fenced code block. [start] and [end] bound its content
 * in the reply text; [stable]: nothing appended to the text can change it; [fenceClosed] (CODE only): a closing fence
 * line was seen, even if that line is not finished yet; [next]: where parsing resumes after a stable block.
 */
data class LogicalBlock(
    val index: Int,
    val start: Int,
    val end: Int,
    val kind: BlockKind,
    val lang: String,
    val stable: Boolean,
    val fenceClosed: Boolean,
    val mode: BlockMode,
    val next: Int,
)

/** An open TEXT or TABLE block longer than this is LITERAL until it is stable. */
const val OPEN_PARSE_LIMIT = 8000
const val TABLE_MAX_CHARS = 20000
const val TABLE_MAX_ROWS = 500
const val TABLE_MAX_CELLS = 4000
const val TABLE_ROWS_PER_CHUNK = 40

/** A table wider than this is LITERAL: even one row of it would be too many cells for one display row. */
const val TABLE_MAX_COLUMNS = 100

/** Cells one table slice lays out at most (fewer rows per slice for wide tables). */
private const val SLICE_CELLS = 400

/** What a block or list item costs in a display row besides its characters: a whole row holds at most about 50. */
private const val NODE_COST = 30

/** What one display row shows. */
sealed interface DisplayChunk {
    /** Whole [MdBlock]s, or one slice of a long paragraph or list (spans clipped, numbering kept). */
    data class Blocks(val blocks: List<MdBlock>) : DisplayChunk

    /** Rows [from, to) of [table]; [widths] in characters, computed once from all cells and shared by every slice. */
    data class TableSlice(val table: MdBlock.Table, val from: Int, val to: Int, val widths: List<Int>, val first: Boolean, val last: Boolean) : DisplayChunk

    /** Plain text, as milestone 1 showed every paragraph. */
    data class Literal(val text: String) : DisplayChunk

    /** Monospace code; [start] is the raw offset of this slice in the reply text. */
    data class Code(val text: String, val start: Int, val first: Boolean, val last: Boolean) : DisplayChunk
}

/**
 * The display chunks of [block] in [source]. A PARSED block is parsed whole once and then sliced, so styles, list
 * numbering and table widths survive the cuts; LITERAL and CODE blocks are cut as raw text by [rawPieces].
 */
fun displayChunks(source: String, block: LogicalBlock): List<DisplayChunk> {
    if (block.kind == BlockKind.CODE || block.mode == BlockMode.LITERAL) {
        val out = ArrayList<DisplayChunk>()
        rawPieces(source, block, block.start) { s, e, _, last -> out += rawChunk(source, block, s, e, last) }
        return out
    }
    Work.markdownParses++
    val chunks = sliceBlocks(parseMarkdown(source.substring(block.start, block.end), tables = block.kind == BlockKind.TABLE))
    // A block that cannot be cut small enough (one huge list item, say) shows as literal text instead.
    if (chunks.any { it is DisplayChunk.Blocks && it.blocks.sumOf(::cost) > 2 * CHUNK }) {
        return displayChunks(source, block.copy(mode = BlockMode.LITERAL))
    }
    return chunks
}

/** The chunk for piece [s, e) of a LITERAL or CODE block. */
internal fun rawChunk(source: String, block: LogicalBlock, s: Int, e: Int, last: Boolean): DisplayChunk =
    if (block.kind == BlockKind.CODE) DisplayChunk.Code(source.substring(s, e), s, first = s == block.start, last = last)
    else DisplayChunk.Literal(source.substring(s, e))

/**
 * Cuts a LITERAL or CODE block from [from] (a piece start) to its end. [emit] gets each piece, the offset up to which
 * its cut was decided (it stays the same while the text up to there and an end beyond it stay), and whether it is last.
 */
internal fun rawPieces(source: String, block: LogicalBlock, from: Int, emit: (start: Int, end: Int, decided: Int, last: Boolean) -> Unit) {
    if (block.kind == BlockKind.CODE) codeRanges(source, from, block.end, emit)
    else chunkRanges(source, from, block.end) { s, e, last -> emit(s, e, if (last) block.end else s + CHUNK, last) }
}

/**
 * Like [chunkRanges], but a line longer than [CHUNK] stays whole up to [TABLE_MAX_CHARS], so code is cut only at line
 * breaks (the break is dropped). One forward pass.
 */
internal fun codeRanges(text: String, start: Int, end: Int, emit: (start: Int, end: Int, decided: Int, last: Boolean) -> Unit) {
    var s = start
    var i = start
    var lastBreak = -1
    while (end - s > CHUNK) {
        val limit = s + CHUNK
        while (i <= limit) {
            if (text[i] == '\n') lastBreak = i
            i++
            Work.chunkSteps++
        }
        if (lastBreak > s) {
            emit(s, withoutCr(text, s, lastBreak), limit, false)
            s = lastBreak + 1
            continue
        }
        val far = minOf(end, s + TABLE_MAX_CHARS + 1) // a break right at the limit still ends the line
        while (i < far && text[i] != '\n') {
            i++
            Work.chunkSteps++
        }
        if (i < far) { // the long line ends here
            emit(s, withoutCr(text, s, i), i, false)
            s = i + 1
            i = s
        } else if (end - s > TABLE_MAX_CHARS) {
            val bound = s + TABLE_MAX_CHARS
            val cut = if (Character.isHighSurrogate(text[bound - 1])) bound - 1 else bound
            emit(s, cut, bound, false)
            s = cut
        } else {
            break
        }
    }
    emit(s, end, end, true)
}

/** [end], or one less when a CR stands before the line break there. */
private fun withoutCr(text: String, start: Int, end: Int) = if (end > start && text[end - 1] == '\r') end - 1 else end

/**
 * Packs whole blocks into chunks costing about [CHUNK] (characters plus [NODE_COST] per block and list item); a long
 * paragraph, list or quote is sliced, and every table.
 */
internal fun sliceBlocks(blocks: List<MdBlock>): List<DisplayChunk> {
    val out = ArrayList<DisplayChunk>()
    val pending = ArrayList<MdBlock>()
    var pendingCost = 0
    fun flush() {
        if (pending.isEmpty()) return
        out += DisplayChunk.Blocks(pending.toList())
        pending.clear()
        pendingCost = 0
    }
    for (b in blocks) {
        val cost = cost(b)
        when {
            b is MdBlock.Table -> {
                flush()
                out += tableSlices(b)
            }
            b is MdBlock.Paragraph && cost > CHUNK -> {
                flush()
                sliceText(b.text).mapTo(out) { DisplayChunk.Blocks(listOf(MdBlock.Paragraph(it))) }
            }
            b is MdBlock.ListBlock && cost > CHUNK && b.items.size > 1 -> {
                flush()
                sliceList(b).mapTo(out) { DisplayChunk.Blocks(listOf(it)) }
            }
            b is MdBlock.Quote && cost > CHUNK -> {
                flush()
                sliceBlocks(b.blocks).mapTo(out) { DisplayChunk.Blocks(listOf(MdBlock.Quote((it as DisplayChunk.Blocks).blocks))) }
            }
            else -> {
                if (pendingCost + cost > CHUNK) flush()
                pending += b
                pendingCost += cost
            }
        }
    }
    flush()
    return out
}

/** What a block costs in one display row: its characters and [NODE_COST] for it and each block and item inside. */
internal fun cost(b: MdBlock): Int = NODE_COST + when (b) {
    is MdBlock.Paragraph -> b.text.text.length
    is MdBlock.Heading -> b.text.text.length
    is MdBlock.Quote -> b.blocks.sumOf(::cost)
    is MdBlock.ListBlock -> b.items.sumOf(::cost)
    is MdBlock.Table -> b.header.sumOf { it.text.length } + b.rows.sumOf { r -> r.sumOf { it.text.length } }
    is MdBlock.Image -> b.alt.length
    MdBlock.Rule -> 0
}

private fun cost(item: MdListItem) = NODE_COST + item.blocks.sumOf(::cost)

/** [text] in slices of at most [CHUNK] characters, cut after the last space or line break before the bound. */
internal fun sliceText(text: MdText): List<MdText> {
    val s = text.text
    val out = ArrayList<MdText>()
    var a = 0
    while (s.length - a > CHUNK) {
        val bound = a + CHUNK
        var cut = bound
        while (cut > a && s[cut - 1] != ' ' && s[cut - 1] != '\n') cut--
        if (cut == a) cut = graphemeCut(s, a, bound)
        out += clip(text, a, cut)
        a = cut
    }
    out += clip(text, a, s.length)
    return out
}

/** [bound], moved back to the start of the character (with its marks or joined parts) it would split, when within 32. */
private fun graphemeCut(s: String, a: Int, bound: Int): Int {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(s)
    if (breaks.isBoundary(bound)) return bound
    val back = breaks.preceding(bound)
    return if (back > a && bound - back <= 32) back else if (Character.isHighSurrogate(s[bound - 1])) bound - 1 else bound
}

private fun clip(text: MdText, a: Int, b: Int) = MdText(
    text.text.substring(a, b),
    text.spans.mapNotNull { if (it.start < b && it.end > a) it.copy(start = maxOf(it.start, a) - a, end = minOf(it.end, b) - a) else null },
)

/** [list] cut between items; each later slice starts at its first item's number. */
private fun sliceList(list: MdBlock.ListBlock): List<MdBlock.ListBlock> {
    val out = ArrayList<MdBlock.ListBlock>()
    var first = 0
    var size = 0
    list.items.forEachIndexed { i, item ->
        val s = cost(item)
        if (i > first && size + s > CHUNK) {
            out += MdBlock.ListBlock(list.ordered, list.start + first, list.items.subList(first, i).toList())
            first = i
            size = 0
        }
        size += s
    }
    out += MdBlock.ListBlock(list.ordered, list.start + first, list.items.subList(first, list.items.size).toList())
    return out
}

/** Column widths in characters: the longest cell of each column. */
internal fun tableWidths(table: MdBlock.Table): List<Int> = table.header.indices.map { c ->
    maxOf(table.header[c].text.length, table.rows.maxOfOrNull { it[c].text.length } ?: 0)
}

private fun tableSlices(table: MdBlock.Table): List<DisplayChunk> {
    val widths = tableWidths(table)
    val n = table.rows.size
    if (n == 0) return listOf(DisplayChunk.TableSlice(table, 0, 0, widths, first = true, last = true))
    val step = (SLICE_CELLS / maxOf(1, widths.size)).coerceIn(1, TABLE_ROWS_PER_CHUNK)
    return (0 until n step step).map { from ->
        val to = minOf(n, from + step)
        DisplayChunk.TableSlice(table, from, to, widths, first = from == 0, last = to == n)
    }
}
