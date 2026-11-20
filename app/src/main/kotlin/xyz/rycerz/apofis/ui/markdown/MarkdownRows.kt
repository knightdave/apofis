package xyz.rycerz.apofis.ui.markdown

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
    return sliceBlocks(parseMarkdown(source.substring(block.start, block.end)))
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
            emit(s, lastBreak, limit, false)
            s = lastBreak + 1
            continue
        }
        val far = minOf(end, s + TABLE_MAX_CHARS)
        while (i < far && text[i] != '\n') {
            i++
            Work.chunkSteps++
        }
        if (i < far) { // the long line ends here
            emit(s, i, i, false)
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

/** Packs whole blocks into chunks of about [CHUNK] characters; a long paragraph, list or any table is sliced. */
internal fun sliceBlocks(blocks: List<MdBlock>): List<DisplayChunk> {
    val out = ArrayList<DisplayChunk>()
    val pending = ArrayList<MdBlock>()
    var pendingSize = 0
    fun flush() {
        if (pending.isEmpty()) return
        out += DisplayChunk.Blocks(pending.toList())
        pending.clear()
        pendingSize = 0
    }
    for (b in blocks) {
        val size = size(b)
        when {
            b is MdBlock.Table -> {
                flush()
                out += tableSlices(b)
            }
            b is MdBlock.Paragraph && size > CHUNK -> {
                flush()
                sliceText(b.text).mapTo(out) { DisplayChunk.Blocks(listOf(MdBlock.Paragraph(it))) }
            }
            b is MdBlock.ListBlock && size > CHUNK && b.items.size > 1 -> {
                flush()
                sliceList(b).mapTo(out) { DisplayChunk.Blocks(listOf(it)) }
            }
            else -> {
                if (pendingSize + size > CHUNK) flush()
                pending += b
                pendingSize += size
            }
        }
    }
    flush()
    return out
}

/** Characters a block shows, roughly its source size. */
private fun size(b: MdBlock): Int = when (b) {
    is MdBlock.Paragraph -> b.text.text.length
    is MdBlock.Heading -> b.text.text.length
    is MdBlock.Quote -> b.blocks.sumOf(::size)
    is MdBlock.ListBlock -> b.items.sumOf(::size)
    is MdBlock.Table -> b.header.sumOf { it.text.length } + b.rows.sumOf { r -> r.sumOf { it.text.length } }
    is MdBlock.Image -> b.alt.length
    MdBlock.Rule -> 1
}

private fun size(item: MdListItem) = item.blocks.sumOf(::size)

/** [text] in slices of at most [CHUNK] characters, cut after the last space or line break before the bound. */
internal fun sliceText(text: MdText): List<MdText> {
    val s = text.text
    val out = ArrayList<MdText>()
    var a = 0
    while (s.length - a > CHUNK) {
        val bound = a + CHUNK
        var cut = bound
        while (cut > a && s[cut - 1] != ' ' && s[cut - 1] != '\n') cut--
        if (cut == a) cut = if (Character.isHighSurrogate(s[bound - 1])) bound - 1 else bound
        out += clip(text, a, cut)
        a = cut
    }
    out += clip(text, a, s.length)
    return out
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
        val s = size(item)
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
    return (0 until n step TABLE_ROWS_PER_CHUNK).map { from ->
        val to = minOf(n, from + TABLE_ROWS_PER_CHUNK)
        DisplayChunk.TableSlice(table, from, to, widths, first = from == 0, last = to == n)
    }
}
