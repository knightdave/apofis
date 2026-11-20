package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.IdentityHashMap
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Notice
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.markdown.BlockKind
import xyz.rycerz.apofis.ui.markdown.BlockMode
import xyz.rycerz.apofis.ui.markdown.DisplayChunk
import xyz.rycerz.apofis.ui.markdown.LogicalBlock
import xyz.rycerz.apofis.ui.markdown.OPEN_PARSE_LIMIT
import xyz.rycerz.apofis.ui.markdown.TABLE_MAX_CELLS
import xyz.rycerz.apofis.ui.markdown.TABLE_MAX_CHARS
import xyz.rycerz.apofis.ui.markdown.TABLE_MAX_COLUMNS
import xyz.rycerz.apofis.ui.markdown.TABLE_MAX_ROWS
import xyz.rycerz.apofis.ui.markdown.displayChunks
import xyz.rycerz.apofis.ui.markdown.rawChunk
import xyz.rycerz.apofis.ui.markdown.rawPieces

/** Longest chunk of text laid out as one row, so one streamed token never re-lays out a whole long block. */
internal const val CHUNK = 1500

private const val FENCE = "```"

/**
 * Where the block scanner stands at a line start. A plain value: a copy is a checkpoint that a later update resumes
 * from, so a line that was already scanned in full is never looked at again.
 */
internal data class ScanState(
    var pos: Int = 0, // the next line start
    var fenceBody: Int = -1, // inside a fence: where its content starts
    var fenceLang: String = "",
    var paraStart: Int = -1, // the open paragraph group
    var paraEnd: Int = -1,
    var list: Boolean = false, // the group holds a list item, so it may go on across blank lines
    var paraOpen: Boolean = false, // the group's last line left a paragraph open (a list item numbered 3 cannot start a list then)
    var noTable: Boolean = false, // the group holds a quote or HTML line, so no table starts in it
    var htmlEnd: String? = null, // inside an HTML block that runs, across blank lines, to the line holding this
    var pendingBlank: Boolean = false, // blank lines after a list group, which the next line may still continue
    var headStart: Int = -1, // the previous line, when it can be a table's header row
    var headCells: Int = 0,
    var tableStart: Int = -1, // the open table, from its header line
    var tableEnd: Int = -1,
    var tableRows: Int = 0, // body rows
    var tableCols: Int = 0,
    var tableCells: Long = 0, // body cells, each row counted at least as wide as the header, as the parser splits it
)

/**
 * The logical blocks of [text] (line breaks LF only, see [lf]) from [from], which must be a line start between blocks;
 * indices count from 0 there.
 */
internal fun parseBlocks(text: String, from: Int = 0): List<LogicalBlock> {
    val out = ArrayList<LogicalBlock>()
    val s = ScanState(pos = from)
    while (s.pos < text.length) scanLine(s, text, out)
    openBlocks(s, text, out)
    return out.mapIndexed { i, b -> b.copy(index = i) }
}

/**
 * Scans the line at [s].pos and adds the blocks it ends to [out] (with index 0). Paragraph groups end at blank lines
 * and fence lines, except that a group holding a list item goes on across blank lines while the next line is indented
 * or another list item (a loose list stays one block). A table (header line, delimiter row ended by a line break, then
 * every line with a pipe) is a block of its own; only this scanner decides what is a table. Work and stack depth are
 * linear in the line and constant respectively, whatever the line holds.
 */
internal fun scanLine(s: ScanState, text: String, out: MutableList<LogicalBlock>) {
    val i = s.pos
    val nl = lineEnd(text, i)
    val end = nl // the line's content
    val finished = nl < text.length
    Work.scanSteps += nl - i + 1
    s.pos = nl + 1
    val first = firstNonSpace(text, i, end)
    val fence = text.startsWith(FENCE, first)
    if (s.fenceBody >= 0) {
        if (fence && firstNonSpace(text, first + FENCE.length, end) == end) {
            out += LogicalBlock(0, s.fenceBody, maxOf(s.fenceBody, i - 1), BlockKind.CODE, s.fenceLang, stable = finished, fenceClosed = true, BlockMode.PARSED, next = nl + 1)
            s.fenceBody = -1
        }
        return
    }
    val html = s.htmlEnd
    if (html != null) { // HTML source: no cut, list or table until its end marker
        if (text.substring(i, end).contains(html, ignoreCase = true)) s.htmlEnd = null
        s.paraEnd = nl
        return
    }
    val blank = first == end
    if (s.tableStart >= 0) {
        when {
            blank -> {
                endTable(s, out, finished, nl + 1)
                return
            }
            fence -> endTable(s, out, true, i)
            hasPipe(text, i, end) && !onlyPipe(text, first, end) -> {
                s.tableRows++
                s.tableCells += maxOf(rowCells(text, first, end), s.tableCols)
                s.tableEnd = nl
                return
            }
            else -> endTable(s, out, finished, i) // an unfinished line may still get a pipe and join the table
        }
    }
    if (blank) { // it ends the paragraph for good only once the line itself is finished
        if (s.paraStart >= 0 && s.list) s.pendingBlank = true else endPara(s, out, finished, nl + 1)
        s.headStart = -1
        return
    }
    if (fence) {
        endPara(s, out, true, i)
        s.fenceLang = text.substring(first + FENCE.length, end).trim()
        s.fenceBody = minOf(nl + 1, text.length)
        return
    }
    val item = isListItem(text, i, end)
    if (s.pendingBlank) {
        // A line that may still turn into a list item (only while unfinished) counts as continuing the list for now.
        if (!(first > i || item || (!finished && mayBecomeListItem(text, i, end)))) endPara(s, out, true, i)
        s.pendingBlank = false
    }
    if (finished && s.headStart >= 0 && delimiterColumns(text, i, end) >= s.headCells) {
        startTable(s, out, nl)
        return
    }
    if (s.paraStart < 0) s.paraStart = i
    // An empty item, or one numbered other than 1, cannot interrupt a paragraph: "text\n-" underlines a heading.
    if (item && (s.paraStart == i || !s.paraOpen || canInterrupt(text, i, end))) s.list = true
    val heading = isAtxHeading(text, first, end)
    s.paraOpen = !heading && !isRuleOrUnderline(text, first, end)
    if (text[first] == '>' || text[first] == '<') s.noTable = true
    if (text[first] == '<' && first - i < 4) s.htmlEnd = htmlEnd(text, first, end)
    s.paraEnd = nl
    // A header row must start a paragraph: not in a list, quote or HTML, not a list item or heading.
    val cells = if (s.list || s.noTable || item || heading) 0 else rowCells(text, first, end)
    s.headStart = if (cells > 0) i else -1
    s.headCells = cells
}

/** `#` to `######` then a space, a tab or the line end. */
private fun isAtxHeading(text: String, first: Int, end: Int): Boolean {
    var k = first
    while (k < end && k - first < 7 && text[k] == '#') k++
    return k > first && k - first <= 6 && (k == end || text[k] == ' ' || text[k] == '\t')
}

/** Only `-`, `=`, `*` or `_` and spaces: a rule or a setext underline, which closes a paragraph. */
private fun isRuleOrUnderline(text: String, first: Int, end: Int): Boolean {
    for (k in first until end) if (text[k] !in "-=*_ \t") return false
    return true
}

private val rawHtmlTags = listOf("script", "pre", "style", "textarea")

/**
 * The end marker of an HTML block starting at [first] that may run across blank lines (comment, processing
 * instruction, declaration, CDATA, or script, pre, style and textarea), when it does not end on this line; else null.
 */
private fun htmlEnd(text: String, first: Int, end: Int): String? {
    val marker = when {
        text.startsWith("<!--", first) -> "-->"
        text.startsWith("<?", first) -> "?>"
        text.startsWith("<![CDATA[", first) -> "]]>"
        text.startsWith("<!", first) && first + 2 < end && text[first + 2].isLetter() -> ">"
        else -> rawHtmlTags.firstOrNull { tag ->
            val after = first + 1 + tag.length
            text.regionMatches(first + 1, tag, 0, tag.length, ignoreCase = true) && (after == end || text[after] in " \t>")
        }?.let { "</$it>" }
    } ?: return null
    return if (text.substring(first + 2, end).contains(marker, ignoreCase = true)) null else marker
}

/** Adds the open blocks at the end of the text (none of them stable) to [out], leaving [s] as it was. */
internal fun openBlocks(s: ScanState, text: String, out: MutableList<LogicalBlock>) {
    when {
        s.fenceBody >= 0 -> out += LogicalBlock(0, s.fenceBody, text.length, BlockKind.CODE, s.fenceLang, stable = false, fenceClosed = false, BlockMode.PARSED, next = text.length)
        s.tableStart >= 0 -> endTable(s.copy(), out, false, text.length)
        else -> endPara(s.copy(), out, false, text.length)
    }
}

private fun endPara(s: ScanState, out: MutableList<LogicalBlock>, stable: Boolean, next: Int) {
    if (s.paraStart >= 0) {
        val literal = !stable && s.paraEnd - s.paraStart > OPEN_PARSE_LIMIT
        out += LogicalBlock(0, s.paraStart, s.paraEnd, BlockKind.TEXT, "", stable, false, if (literal) BlockMode.LITERAL else BlockMode.PARSED, next)
    }
    s.paraStart = -1
    s.list = false
    s.paraOpen = false
    s.noTable = false
    s.htmlEnd = null
    s.pendingBlank = false
    s.headStart = -1
}

/** The delimiter row ending at [nl] makes the previous line a table's header; the group's earlier lines end before it. */
private fun startTable(s: ScanState, out: MutableList<LogicalBlock>, nl: Int) {
    val head = s.headStart
    val cols = s.headCells
    if (s.paraStart < head) s.paraEnd = head - 1 else s.paraStart = -1 // the header was the group's first line
    endPara(s, out, true, head)
    s.tableStart = head
    s.tableEnd = nl
    s.tableRows = 0
    s.tableCols = cols
    s.tableCells = 0L
}

/** Ends the open table; over any budget (rows and cells as the parser splits and pads them) it is LITERAL. */
private fun endTable(s: ScanState, out: MutableList<LogicalBlock>, stable: Boolean, next: Int) {
    val chars = s.tableEnd - s.tableStart
    val cells = s.tableCols + s.tableCells // Long: rows times a wide header can pass Int
    val literal = (!stable && chars > OPEN_PARSE_LIMIT) || chars > TABLE_MAX_CHARS || s.tableRows > TABLE_MAX_ROWS ||
        cells > TABLE_MAX_CELLS || s.tableCols > TABLE_MAX_COLUMNS
    out += LogicalBlock(0, s.tableStart, s.tableEnd, BlockKind.TABLE, "", stable, false, if (literal) BlockMode.LITERAL else BlockMode.PARSED, next)
    s.tableStart = -1
}

private fun hasPipe(text: String, start: Int, end: Int): Boolean {
    for (k in start until end) if (text[k] == '|') return true
    return false
}

private fun onlyPipe(text: String, first: Int, end: Int) = text[first] == '|' && firstNonSpace(text, first + 1, end) == end

/** Columns of a GFM delimiter row such as `|:--|:-:|--:|` (at least one pipe), else -1. One pass, as the parser does. */
private fun delimiterColumns(text: String, start: Int, end: Int): Int {
    var pipes = 0
    var valid = false
    var columns = 0
    var k = start
    while (k < end) {
        when (text[k]) {
            '|' -> {
                k++
                if (++pipes > 1) return -1
                valid = true
            }
            '-', ':' -> {
                if (pipes == 0 && columns > 0) return -1
                if (text[k] == ':') k++
                val dashes = k
                while (k < end && text[k] == '-') k++
                if (k == dashes) return -1
                if (k < end && text[k] == ':') k++
                columns++
                pipes = 0
            }
            ' ', '\t' -> k++
            else -> return -1
        }
    }
    return if (valid) columns else -1
}

/** Cells of a table row, split as the table parser splits it (`\|` does not split); 0 without a pipe. */
private fun rowCells(text: String, first: Int, end: Int): Int {
    if (!hasPipe(text, first, end)) return 0
    var start = first
    var cellEnd = end
    if (text[first] == '|') {
        start = first + 1
        while (cellEnd > start && (text[cellEnd - 1] == ' ' || text[cellEnd - 1] == '\t')) cellEnd--
    }
    var cells = 0
    var content = false
    var k = start
    while (k < cellEnd) {
        if (text[k] == '\\' && k + 1 < cellEnd && text[k + 1] == '|') {
            content = true
            k += 2
            continue
        }
        if (text[k] == '|') {
            cells++
            content = false
        } else {
            content = true
        }
        k++
    }
    return if (content) cells + 1 else cells
}

/** A list item line (see [isListItem]) that may interrupt a paragraph: a bullet or `1.` with content after it. */
private fun canInterrupt(text: String, start: Int, end: Int): Boolean {
    var k = start
    while (k < end && k - start < 3 && text[k] == ' ') k++
    var m = k + 1
    if (text[k].isDigit()) {
        var d = k
        var n = 0
        while (d < end && text[d].isDigit()) n = n * 10 + (text[d++] - '0')
        if (n != 1) return false
        m = d + 1
    }
    return firstNonSpace(text, minOf(m, end), end) < end
}

/** Up to three spaces, then `-`, `+`, `*` or 1 to 9 digits with `.` or `)`, then a space, a tab or the line end. */
private fun isListItem(text: String, start: Int, end: Int): Boolean {
    var k = start
    while (k < end && k - start < 3 && text[k] == ' ') k++
    if (k == end) return false
    val m = if (text[k] == '-' || text[k] == '+' || text[k] == '*') {
        k + 1
    } else {
        var d = k
        while (d < end && d - k < 9 && text[d].isDigit()) d++
        if (d == k || d == end || (text[d] != '.' && text[d] != ')')) return false
        d + 1
    }
    return m == end || text[m] == ' ' || text[m] == '\t'
}

/** The unfinished line [start, end) could still become a list item: `-`, `+`, `*`, or digits with an optional `.` or `)`. */
private fun mayBecomeListItem(text: String, start: Int, end: Int): Boolean {
    if (end - start == 1 && text[start] in "-+*") return true
    var d = start
    while (d < end && d - start < 9 && text[d].isDigit()) d++
    return d > start && (d == end || (d == end - 1 && (text[d] == '.' || text[d] == ')')))
}

/** Work counters for tests: characters inspected by [chunkRanges], [TextRow]s built and markdown parses. */
internal object Work {
    var chunkSteps = 0L
    var rowsBuilt = 0L
    var markdownParses = 0L
    var scanSteps = 0L // characters looked at by the block scanner
    var rowCopies = 0L // row references copied while assembling rows
    var largestParse = 0 // the longest source handed to the markdown parser
    var parseFailures = 0L // blocks shown literally because parsing or slicing threw: a bug, never expected
}

/**
 * Cuts text[start, end) into pieces of at most [CHUNK] characters, after the last line break within the bound (the
 * break itself is dropped) or else at the bound, and calls [emit] for each with whether it is the last. One forward
 * pass: every character is inspected at most once. Pieces before the last depend only on characters before their
 * cut, so text appended at [end] never moves them.
 */
internal fun chunkRanges(text: String, start: Int, end: Int, emit: (start: Int, end: Int, last: Boolean) -> Unit) {
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
            emit(s, lastBreak, false)
            s = lastBreak + 1
        } else {
            val cut = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
            emit(s, cut, false)
            s = cut
        }
    }
    emit(s, end, true)
}

/** [content] cut as by [chunkRanges]. */
internal fun chunks(content: String): List<String> =
    ArrayList<String>().also { out -> chunkRanges(content, 0, content.length) { s, e, _ -> out += content.substring(s, e) } }

private fun lineEnd(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun firstNonSpace(text: String, from: Int, end: Int): Int {
    var k = from
    while (k < end && text[k].isWhitespace()) k++
    return k
}

/** The row for chunk number [c] of [chunks] of block [b] of reply [key]. */
private fun textRow(key: String, b: LogicalBlock, c: Int, chunk: DisplayChunk, last: Boolean): TextRow {
    Work.rowsBuilt++
    return TextRow("$key/b${b.index}/c$c", chunk, b.kind, b.lang, first = c == 0, joinBelow = !last, fenceClosed = b.fenceClosed)
}

private fun blockRows(key: String, b: LogicalBlock, text: String): List<TextRow> {
    val chunks = displayChunks(text, b)
    return chunks.mapIndexed { c, chunk -> textRow(key, b, c, chunk, c == chunks.size - 1) }
}

/** [text] with CRLF and bare CR as LF: done once where text comes in, so every later stage sees one line ending. */
internal fun lf(text: String): String = if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

/** Every text row of a reply, parsed from scratch. */
internal fun replyRows(key: String, text: String): List<TextRow> = lf(text).let { t -> parseBlocks(t).flatMap { blockRows(key, it, t) } }

/** The first [size] elements of [backing], which is only ever appended to: a view, never a copy. */
private class Prefix<T>(private val backing: List<T>, override val size: Int) : AbstractList<T>() {
    override fun get(index: Int): T {
        if (index >= size) throw IndexOutOfBoundsException("$index of $size")
        return backing[index]
    }
}

/**
 * The text rows of one streamed reply. While the same key's text grows at its end, the block scanner resumes from a
 * checkpoint at the start of the last line, so finished lines are never scanned again; stable blocks keep their rows
 * and are never parsed again; an open block whose source is unchanged keeps its rows; a changed PARSED block is parsed
 * again whole (it is at most [OPEN_PARSE_LIMIT] long); a changed LITERAL or CODE block keeps the rows of every piece
 * whose cut is settled, without copying them. A block whose kind or mode changes is rebuilt from scratch. An append
 * without a line break to an open LITERAL paragraph or table or a fence is not even scanned. The result is a view of
 * the stable rows, the settled rows and the few rows built for this update. Any other change (another key, or the text
 * replaced) starts from scratch and shares nothing with the previous parse.
 *
 * Telling an append from a replacement compares the old text with the new one's start: one vectorised comparison per
 * update. The producer already copies the whole text for every token (`text + delta`); a cheaper test needs a revision
 * from the run follower, which this milestone does not change. Line endings are made LF here, for the appended part
 * only; a reply that holds a CR costs one more copy of the text per update.
 */
internal class ReplyText {
    /**
     * An open block. Its rows are the first [size] of [settled] (pieces whose cut no appended text can move; the lists
     * are only appended to, so the next update of the same block shares them) and then [loose], which start at
     * [looseStart]. [starts] and [decided] go with [settled]: where each piece starts and up to where its cut was
     * decided (see [rawPieces]). A PARSED block has only [loose] rows.
     */
    private class Open(
        val block: LogicalBlock,
        val settled: ArrayList<TextRow>,
        val starts: ArrayList<Int>,
        val decided: ArrayList<Int>,
        val size: Int,
        val loose: List<TextRow>,
        val looseStart: Int,
    ) {
        val rows: List<TextRow> get() = if (size == 0) loose else Joined(Prefix(settled, size), loose)
    }

    private var key: String? = null
    private var text = "" // as given
    private var norm = "" // [text] through [lf]; the same instance while it holds no CR
    private var closed = ArrayList<TextRow>() // rows of stable blocks; only appended to, so earlier views stay valid
    private var closedBlocks = 0
    private var checkpoint = ScanState() // the scanner at the start of the text's last line
    private var tail: List<Open> = emptyList()
    private var rows: List<TextRow> = emptyList()

    /** Blocks produced by the scanner in the last [update]; for tests. */
    var parsed = 0
        private set

    fun update(key: String, raw: String): List<TextRow> {
        parsed = 0
        if (key == this.key && (raw === text || raw == text)) return rows
        val appended = key == this.key && raw.startsWith(text)
        if (!appended) {
            closed = ArrayList()
            closedBlocks = 0
            checkpoint = ScanState()
            tail = emptyList()
        }
        val new = when {
            !appended -> lf(raw)
            norm === text && raw.indexOf('\r', text.length) < 0 -> raw
            else -> {
                // A CR that ended the old text already stands for the line break; an LF right after it adds nothing.
                val from = if (text.endsWith('\r') && raw.getOrNull(text.length) == '\n') text.length + 1 else text.length
                norm + lf(raw.substring(from))
            }
        }
        val old = if (appended) norm else ""
        val grown = if (appended && new.indexOf('\n', old.length) < 0) grown(old, new.length) else null
        var finished = 0 // blocks ended on finished lines: stable, and before the checkpoint
        val blocks = grown ?: ArrayList<LogicalBlock>().also { out ->
            val s = checkpoint.copy()
            var next: ScanState? = null
            while (s.pos < new.length) {
                if (lineEnd(new, s.pos) == new.length) {
                    next = s.copy()
                    finished = out.size
                }
                scanLine(s, new, out)
            }
            if (next == null) {
                next = s.copy()
                finished = out.size
            }
            openBlocks(s, new, out)
            checkpoint = next
            parsed = out.size
        }
        val firstIndex = closedBlocks
        val nextTail = ArrayList<Open>()
        blocks.forEachIndexed { n, scanned ->
            val b = scanned.copy(index = firstIndex + n)
            val open = open(key, tail.firstOrNull { it.block.start == b.start }, b, new)
            if (n < finished) {
                val r = open.rows
                closed.addAll(r)
                Work.rowCopies += r.size
                closedBlocks++
            } else {
                nextTail += open
            }
        }
        tail = nextTail
        var view: List<TextRow> = Prefix(closed, closed.size)
        for (o in nextTail) view = Joined(view, o.rows)
        rows = view
        this.key = key
        text = raw
        norm = new
        return rows
    }

    /** The rows of [b], reusing what [prev] (the block at the same start in the last update) still has right. */
    private fun open(key: String, prev: Open?, b: LogicalBlock, text: String): Open {
        val p = prev?.block
        val same = p != null && p.start == b.start && p.kind == b.kind && p.mode == b.mode && p.lang == b.lang
        if (same && p!!.end == b.end && p.fenceClosed == b.fenceClosed) {
            return Open(b, prev!!.settled, prev.starts, prev.decided, prev.size, prev.loose, prev.looseStart)
        }
        if (b.kind != BlockKind.CODE && b.mode == BlockMode.PARSED) return Open(b, ArrayList(), ArrayList(), ArrayList(), 0, blockRows(key, b, text), b.start)
        var settled = ArrayList<TextRow>()
        var starts = ArrayList<Int>()
        var decided = ArrayList<Int>()
        var from = b.start
        if (same) {
            // A settled piece stays while its cut was decided before the new end too; usually all of them do.
            var k = prev!!.size
            while (k > 0 && prev.decided[k - 1] >= b.end) k--
            if (k == prev.size && p!!.fenceClosed == b.fenceClosed) {
                settled = prev.settled
                starts = prev.starts
                decided = prev.decided
                from = prev.looseStart
            } else { // the block got shorter or its fence opened or closed: copy what stays (rare)
                for (r in 0 until k) settled += prev.settled[r].let { if (it.fenceClosed == b.fenceClosed) it else it.copy(fenceClosed = b.fenceClosed) }
                starts.addAll(prev.starts.subList(0, k))
                decided.addAll(prev.decided.subList(0, k))
                Work.rowCopies += k
                from = if (k == prev.size) prev.looseStart else prev.starts[k]
            }
        }
        var size = settled.size
        val loose = ArrayList<TextRow>()
        var looseStart = -1
        rawPieces(text, b, from) { s, e, d, last ->
            val row = textRow(key, b, size + loose.size, rawChunk(text, b, s, e, last), last)
            if (d < b.end && loose.isEmpty()) {
                settled += row
                starts += s
                decided += d
                size++
            } else {
                if (loose.isEmpty()) looseStart = s
                loose += row
            }
        }
        return Open(b, settled, starts, decided, size, loose, looseStart)
    }

    /**
     * The tail after characters without a line break were appended to [old], when that provably only lengthens one
     * open LITERAL paragraph or table or a fence to [length]: its last line is that block's, no appended character
     * could turn the line into a fence opening (paragraph) or closing (fence), and the line does not follow a blank
     * line in a list (where it could still end or continue the list). Else null.
     */
    private fun grown(old: String, length: Int): List<LogicalBlock>? {
        val b = tail.singleOrNull()?.block ?: return null
        val lineStart = checkpoint.pos
        val code = b.kind == BlockKind.CODE
        if (b.stable || b.end != old.length || lineStart < b.start || (!code && b.mode != BlockMode.LITERAL)) return null
        val f = firstNonSpace(old, lineStart, old.length)
        val ticks = old.length - f <= FENCE.length && (f until old.length).all { old[it] == '`' }
        if (if (code) ticks else (f == old.length || ticks)) return null
        if (!code && checkpoint.pendingBlank) return null
        return listOf(b.copy(end = length))
    }
}

/** One lazy list row. Keys derive from the item's key, so a reply keeps its rows' identity between updates. */
internal sealed interface Row {
    val key: String
}

internal data class DateRow(override val key: String, val day: LocalDate) : Row
internal data class UserRow(val message: UserMessage) : Row {
    override val key get() = message.key
}
internal data class StepsRow(val reply: AssistantReply) : Row {
    override val key get() = reply.key
}

/**
 * One display chunk of a logical block; [first] and [joinBelow] tell whether it starts its block and whether the block
 * goes on below; [fenceClosed] as in [LogicalBlock].
 */
internal data class TextRow(
    override val key: String,
    val chunk: DisplayChunk,
    val kind: BlockKind,
    val lang: String,
    val first: Boolean,
    val joinBelow: Boolean,
    val fenceClosed: Boolean,
) : Row
internal data class FooterRow(val reply: AssistantReply) : Row {
    override val key get() = reply.key + "/end"
}
internal data class NoticeRow(val notice: Notice) : Row {
    override val key get() = notice.key
}

/** At the very bottom: answers that came too late, kept until the next message is sent. */
internal data object GoneRow : Row {
    override val key get() = "notes/gone"
}

/**
 * Turns chat items into rows. Stored items are mapped once and reused while the same instances come back; the live
 * reply (see [LiveMerger]) goes through [ReplyText]. Per streamed token the work is that reply's open tail and a copy
 * of the references to its rows, never the history.
 */
internal class ChatRows(private val zone: ZoneId) {
    private var head: List<ChatItem>? = null
    private var headRows: List<Row> = emptyList()
    private var headDay: LocalDate? = null
    private var cache = IdentityHashMap<ChatItem, List<Row>>()
    private var tailCache = IdentityHashMap<ChatItem, List<Row>>()
    private val live = ReplyText()

    /** Items mapped to rows so far; for tests. */
    var mapped = 0
        private set

    fun rows(items: List<ChatItem>): List<Row> {
        val joined = items as? Joined<*>
        @Suppress("UNCHECKED_CAST")
        val stored = joined?.head as List<ChatItem>? ?: items
        if (stored !== head) {
            head = stored
            val next = IdentityHashMap<ChatItem, List<Row>>()
            val out = ArrayList<Row>()
            var day: LocalDate? = null
            for (item in stored) {
                day = separate(item, day, out)
                val rows = cache[item] ?: tailCache[item] ?: rowsOf(item)
                next[item] = rows
                out += rows
            }
            cache = next
            headRows = out
            headDay = day
        }
        @Suppress("UNCHECKED_CAST")
        val tail = joined?.tail as List<ChatItem>? ?: emptyList()
        if (tail.isEmpty()) return headRows
        val parts = ArrayList<List<Row>>() // the live reply's rows are joined in as a view, never copied
        var out = ArrayList<Row>()
        val nextTail = IdentityHashMap<ChatItem, List<Row>>()
        var day = headDay
        for (item in tail) {
            day = separate(item, day, out)
            if (item is AssistantReply && item.live) {
                if (item.steps.isNotEmpty()) out += StepsRow(item)
                parts += out
                parts += live.update(item.key, item.text)
                out = arrayListOf<Row>(FooterRow(item))
            } else {
                val rows = tailCache[item] ?: cache[item] ?: rowsOf(item)
                nextTail[item] = rows
                out += rows
            }
        }
        parts += out
        tailCache = nextTail
        return Joined(headRows, parts.reduce { a, b -> Joined(a, b) })
    }

    private fun separate(item: ChatItem, previous: LocalDate?, out: MutableList<Row>): LocalDate? {
        val ts = when (item) {
            is UserMessage -> item.timestamp
            is AssistantReply -> item.timestamp
            is Notice -> return previous
        }
        val day = Instant.ofEpochMilli((ts * 1000).toLong()).atZone(zone).toLocalDate()
        if (day != previous) out += DateRow("d/${item.key}", day)
        return day
    }

    private fun rowsOf(item: ChatItem): List<Row> {
        mapped++
        return when (item) {
            is UserMessage -> listOf(UserRow(item))
            is Notice -> listOf(NoticeRow(item))
            is AssistantReply -> buildList {
                if (item.steps.isNotEmpty()) add(StepsRow(item))
                addAll(replyRows(item.key, item.text))
                if (item.live || item.outcome != null) add(FooterRow(item))
            }
        }
    }
}

/** The transcript, newest at the bottom, in a reversed list so a growing reply stays pinned without scroll calls. */
@Composable
internal fun MessageList(
    items: List<ChatItem>,
    live: LiveReply?,
    notes: Map<RequestRef, AnswerNote>,
    answering: Boolean,
    actions: ChatActions,
    onCancel: (UserMessage) -> Unit,
    onStep: (StepSubject) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    zone: ZoneId,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = {},
) {
    val builder = remember(zone) { ChatRows(zone) }
    // One sideways scroll per table, shared by its slices (separate list items), by "<reply>/b<index>".
    val tableScrolls = remember { HashMap<String, ScrollState>() }
    val pendingId = live?.pending?.let { RequestRef(it.requestId, it.executionGeneration) }
    // An answer that came too late shows under the reply, live or stored, unless that request is somehow drawn again.
    val gone = notes.any { (ref, note) -> note.result == AnswerResult.GONE && ref != pendingId }
    val rows = builder.rows(items).let { if (gone) Joined(it, listOf(GoneRow)) else it }
    LazyColumn(modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = contentPadding) {
        items(
            count = rows.size,
            key = { rows[rows.size - 1 - it].key },
            contentType = { rows[rows.size - 1 - it]::class },
        ) { index ->
            val row = rows[rows.size - 1 - index]
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxWidth().then(rowTag(row))) {
                    when (row) {
                        is DateRow -> DateSeparator(row.day, today)
                        is UserRow -> UserBubble(row.message, onRetry = actions::onRetry, onCancel = onCancel)
                        is StepsRow -> StepList(row.reply.steps, tag = "step:${row.reply.key}") { i ->
                            val step = row.reply.steps[i] as ToolStep
                            onStep(StepSubject(row.reply.key, i, step.callId.ifEmpty { null }, step.name))
                        }
                        is TextRow ->
                            if (row.kind == BlockKind.TABLE) {
                                TextBlock(row, onLink, tableScrolls.getOrPut(row.key.substringBeforeLast("/c")) { ScrollState(0) })
                            } else {
                                TextBlock(row, onLink)
                            }
                        is FooterRow -> ReplyFooter(row.reply, live.takeIf { row.reply.live }, notes, answering, actions)
                        is NoticeRow -> NoticeLine(row.notice.text)
                        GoneRow -> Notice(R.string.chat_answer_gone)
                    }
                }
            }
        }
    }
}

/** The end-to-end tests' id of a row: the item kind and its opaque key (steps are tagged inside [StepList]). */
private fun rowTag(row: Row): Modifier = when (row) {
    is UserRow -> Modifier.testTag("user:${row.key}")
    is TextRow -> Modifier.testTag("reply:${row.key}")
    is NoticeRow -> Modifier.testTag("notice:${row.key}")
    else -> Modifier
}

@Composable
private fun DateSeparator(day: LocalDate, today: LocalDate) {
    val label = when (day) {
        today -> stringResource(R.string.chat_today)
        today.minusDays(1) -> stringResource(R.string.chat_yesterday)
        else -> remember(day) { day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    }
    Text(
        label,
        Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
    )
}
