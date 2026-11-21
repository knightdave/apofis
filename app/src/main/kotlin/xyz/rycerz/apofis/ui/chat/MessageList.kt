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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import xyz.rycerz.apofis.ui.markdown.highlighted
import xyz.rycerz.apofis.ui.markdown.infoWord
import xyz.rycerz.apofis.ui.markdown.rawChunk
import xyz.rycerz.apofis.ui.markdown.rawPieces

/** Longest chunk of text laid out as one row, so one streamed token never re-lays out a whole long block. */
internal const val CHUNK = 1500

/**
 * While a line is unfinished, the block scanner decides what it is from its first this many characters (the most a
 * table may hold) and treats the rest as more of the same line, so a growing line costs at most this per update. A
 * finished line is decided from all of it, once.
 */
private const val LINE_LOOK = TABLE_MAX_CHARS

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
internal fun parseBlocks(text: CharSequence, from: Int = 0): List<LogicalBlock> {
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
internal fun scanLine(s: ScanState, text: CharSequence, out: MutableList<LogicalBlock>, noBreakBefore: Int = 0) {
    val i = s.pos
    val nl = lineEnd(text, maxOf(i, noBreakBefore))
    val finished = nl < text.length
    // What decides the line: all of it once finished (read once, as the next update starts after it), else its first
    // LINE_LOOK characters, from which nothing that depends on the line's end is decided.
    val end = if (finished) nl else minOf(nl, i + LINE_LOOK)
    val whole = end == nl
    Work.scanSteps += nl - maxOf(i, noBreakBefore) + end - i + 1
    s.pos = nl + 1
    val first = firstNonSpace(text, i, end)
    val shallow = first - i < 4 && (i until first).all { text[it] == ' ' } // indented under four columns, by spaces only
    val fence = first + FENCE.length <= end && text.startsWith(FENCE, first)
    if (s.fenceBody >= 0) {
        if (fence && whole && firstNonSpace(text, first + FENCE.length, end) == end) {
            out += LogicalBlock(0, s.fenceBody, maxOf(s.fenceBody, i - 1), BlockKind.CODE, s.fenceLang, stable = finished, fenceClosed = true, BlockMode.PARSED, next = nl + 1)
            s.fenceBody = -1
        }
        return
    }
    val blank = first == nl // a line with more than LINE_LOOK leading spaces is text, not blank
    val inHtml = s.htmlEnd
    // HTML source: no fence, cut, list or table until its end, the line holding its marker or else a blank line
    if (inHtml != null && !(inHtml.isEmpty() && blank)) {
        if (inHtml.isNotEmpty() && text.substring(i, end).contains(inHtml, ignoreCase = true)) s.htmlEnd = null
        s.paraEnd = nl
        return
    }
    s.htmlEnd = null
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
        s.fenceLang = infoWord(text, first + FENCE.length, end)
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
    val inParagraph = s.paraStart < i && s.paraOpen
    // An empty item, or one numbered other than 1, cannot interrupt a paragraph: "text\n-" underlines a heading.
    if (item && (!inParagraph || canInterrupt(text, i, end))) s.list = true
    val heading = shallow && isAtxHeading(text, first, end)
    val html = if (shallow && first < end && text[first] == '<') htmlMarker(text, first, end, inParagraph || !whole) else null
    if (html != null) {
        val closed = html.isNotEmpty() && text.substring(first + 2, end).contains(html, ignoreCase = true)
        s.htmlEnd = if (closed) null else html
    }
    s.paraOpen = !heading && html == null && !(shallow && whole && isRuleOrUnderline(text, first, end))
    if (shallow && first < end && text[first] == '>') s.noTable = true
    s.paraEnd = nl
    // A header row must start a paragraph: not in a list or quote, not a list item, heading or HTML block.
    val cells = if (s.list || s.noTable || item || heading || html != null) 0 else rowCells(text, first, end)
    s.headStart = if (cells > 0) i else -1
    s.headCells = cells
}

/** `#` to `######` then a space, a tab or the line end. */
private fun isAtxHeading(text: CharSequence, first: Int, end: Int): Boolean {
    var k = first
    while (k < end && k - first < 7 && text[k] == '#') k++
    return k > first && k - first <= 6 && (k == end || text[k] == ' ' || text[k] == '\t')
}

/** Only `-`, `=`, `*` or `_` and spaces: a rule or a setext underline, which closes a paragraph. */
private fun isRuleOrUnderline(text: CharSequence, first: Int, end: Int): Boolean {
    if (first == end) return false
    for (k in first until end) if (text[k] !in "-=*_ \t") return false
    return true
}

private val rawHtmlTags = listOf("script", "pre", "style", "textarea")

private val blockHtmlTags = (
    "address article aside base basefont blockquote body caption center col colgroup dd details dialog dir div dl dt " +
        "fieldset figcaption figure footer form frame frameset h1 h2 h3 h4 h5 h6 head header hr html iframe legend li " +
        "link main menu menuitem nav noframes ol optgroup option p param search section summary table tbody td tfoot th " +
        "thead title tr track ul"
    ).split(' ').toSet()

/**
 * Whether an HTML block starts at [first] (a `<`), as the parser decides it, and how it ends: at the line holding the
 * returned marker (comment, processing instruction, declaration, CDATA, or script, pre, style and textarea), or at a
 * blank line when it is "" (a block-level tag, or any other complete tag alone on its line, which cannot interrupt a
 * paragraph). Null when no HTML block starts here.
 */
private fun htmlMarker(text: CharSequence, first: Int, end: Int, noLoneTag: Boolean): String? {
    fun at(prefix: String) = text.startsWith(prefix, first)
    fun ends(k: Int, slash: Boolean) = k == end || text[k].isHtmlSpace() || text[k] == '>' || (slash && text.startsWith("/>", k))
    rawHtmlTags.firstOrNull { tag -> text.regionMatches(first + 1, tag, 0, tag.length, ignoreCase = true) && ends(first + 1 + tag.length, false) }
        ?.let { return "</$it>" }
    if (at("<!--")) return "-->"
    if (at("<?")) return "?>"
    if (at("<!") && first + 2 < end && text[first + 2].isAsciiLetter()) return ">"
    if (at("<![CDATA[")) return "]]>"
    val nameStart = if (at("</")) first + 2 else first + 1
    var k = nameStart
    while (k < end && (text[k].isAsciiLetter() || text[k].isAsciiDigit() || text[k] == '-')) k++
    if (k > nameStart && text.substring(nameStart, k).lowercase() in blockHtmlTags && ends(k, true)) return ""
    if (noLoneTag) return null // in a paragraph, or the line is cut off (the rest of it must be blank)
    val tag = tagEnd(text, first, end)
    return if (tag > 0 && firstNonSpace(text, tag, end) == end) "" else null
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
private fun Char.isAsciiDigit() = this in '0'..'9'
private fun Char.isHtmlSpace() = this == ' ' || this == '\t' || this == '\u000B' || this == '\u000C'

private fun skipHtmlSpace(text: CharSequence, from: Int, end: Int): Int {
    var k = from
    while (k < end && text[k].isHtmlSpace()) k++
    return k
}

/**
 * The index after a complete open or closing tag at [start] (a `<`), read as the parser reads it (tag name, then
 * attributes each after a space, with optional unquoted or quoted values); else -1. One forward pass, constant stack.
 */
private fun tagEnd(text: CharSequence, start: Int, end: Int): Int {
    var k = start + 1
    val closing = k < end && text[k] == '/'
    if (closing) k++
    if (k >= end || !text[k].isAsciiLetter()) return -1
    while (k < end && (text[k].isAsciiLetter() || text[k].isAsciiDigit() || text[k] == '-')) k++
    if (closing) {
        k = skipHtmlSpace(text, k, end)
        return if (k < end && text[k] == '>') k + 1 else -1
    }
    while (true) {
        val space = skipHtmlSpace(text, k, end)
        if (space < end && text[space] == '>') return space + 1
        if (text.startsWith("/>", space)) return space + 2
        if (space == k || space >= end) return -1 // an attribute needs a space before it
        k = space
        val c = text[k]
        if (!(c.isAsciiLetter() || c == '_' || c == ':')) return -1
        while (k < end && (text[k].isAsciiLetter() || text[k].isAsciiDigit() || text[k] in "_.:-")) k++
        val eq = skipHtmlSpace(text, k, end)
        if (eq < end && text[eq] == '=') {
            k = skipHtmlSpace(text, eq + 1, end)
            if (k >= end) return -1
            val q = text[k]
            if (q == '"' || q == '\'') {
                k++
                while (k < end && text[k] != q) k++
                if (k >= end) return -1
                k++
            } else {
                val v = k
                while (k < end && text[k] > ' ' && text[k] !in "\"'=<>`") k++
                if (k == v) return -1
            }
        }
    }
}

/** Adds the open blocks at the end of the text (none of them stable) to [out], leaving [s] as it was. */
internal fun openBlocks(s: ScanState, text: CharSequence, out: MutableList<LogicalBlock>) {
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

private fun hasPipe(text: CharSequence, start: Int, end: Int): Boolean {
    for (k in start until end) if (text[k] == '|') return true
    return false
}

private fun onlyPipe(text: CharSequence, first: Int, end: Int) = text[first] == '|' && firstNonSpace(text, first + 1, end) == end

/** Columns of a GFM delimiter row such as `|:--|:-:|--:|` (at least one pipe), else -1. One pass, as the parser does. */
private fun delimiterColumns(text: CharSequence, start: Int, end: Int): Int {
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
private fun rowCells(text: CharSequence, first: Int, end: Int): Int {
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
private fun canInterrupt(text: CharSequence, start: Int, end: Int): Boolean {
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
private fun isListItem(text: CharSequence, start: Int, end: Int): Boolean {
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
private fun mayBecomeListItem(text: CharSequence, start: Int, end: Int): Boolean {
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
    var lfChars = 0L // characters written while making line endings LF
    var parseFailures = 0L // blocks shown literally because parsing or slicing threw: a bug, never expected
    var highlights = 0L // code blocks handed to the highlighter
    var highlightSteps = 0L // characters the highlighter looked at
    var largestHighlight = 0 // the longest code handed to the highlighter
    var highlightFailures = 0L // blocks shown plain because highlighting failed: a bug, never expected
    var timelineRows = 0L // timeline step rows composed
}

/**
 * Cuts text[start, end) into pieces of at most [CHUNK] characters, after the last line break within the bound (the
 * break itself is dropped) or else at the bound, and calls [emit] for each with whether it is the last. One forward
 * pass: every character is inspected at most once. Pieces before the last depend only on characters before their
 * cut, so text appended at [end] never moves them.
 */
internal fun chunkRanges(text: CharSequence, start: Int, end: Int, emit: (start: Int, end: Int, last: Boolean) -> Unit) {
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

private fun lineEnd(text: CharSequence, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun firstNonSpace(text: CharSequence, from: Int, end: Int): Int {
    var k = from
    while (k < end && text[k].isWhitespace()) k++
    return k
}

/** The row for chunk number [c] of [chunks] of block [b] of reply [key]. */
private fun textRow(key: String, b: LogicalBlock, c: Int, chunk: DisplayChunk, last: Boolean): TextRow {
    Work.rowsBuilt++
    return TextRow("$key/b${b.index}/c$c", chunk, b.kind, b.lang, first = c == 0, joinBelow = !last, fenceClosed = b.fenceClosed && last)
}

private fun blockRows(key: String, b: LogicalBlock, text: CharSequence): List<TextRow> {
    val chunks = displayChunks(text, b)
    return chunks.mapIndexed { c, chunk -> textRow(key, b, c, chunk, c == chunks.size - 1) }
}

/** [text] with CRLF and bare CR as LF: done once where text comes in, so every later stage sees one line ending. */
internal fun lf(text: String): String = if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

/**
 * Every text row of a reply, parsed from scratch. [closeFences]: the reply is no longer live, so an unclosed fence is
 * shown closed (highlighted, with its bottom edge); a live reply's open fence stays plain until it closes.
 */
internal fun replyRows(key: String, text: String, closeFences: Boolean = false): List<TextRow> = lf(text).let { t ->
    parseBlocks(t).flatMap { b -> blockRows(key, if (closeFences && b.kind == BlockKind.CODE) b.copy(fenceClosed = true) else b, t) }
}

/** The raw source (line endings LF) of logical block [blockIndex] of [replyText], from a fresh parse; null if none. */
// Ruled: Copy gives the app's canonical text, line endings LF (normalized once at the boundary, as for display), which
// is what a terminal or editor expects; the original CR characters are not restored.
internal fun blockSource(replyText: String, blockIndex: Int): String? {
    val t = lf(replyText)
    return parseBlocks(t).getOrNull(blockIndex)?.let { t.substring(it.start, it.end) }
}

/** What Copy puts on the clipboard for text row [rowKey]: the raw source of its whole block in the canonical reply. */
internal fun copySource(items: List<ChatItem>, rowKey: String): String? {
    val key = replyKeyOf(rowKey)
    val reply = items.lastOrNull { it is AssistantReply && it.key == key } as AssistantReply? ?: return null
    return blockSource(reply.text, blockIndexOf(rowKey))
}

/** The reply key of a text row key `<reply>/b<i>/c<j>`. */
internal fun replyKeyOf(rowKey: String): String = rowKey.substringBeforeLast("/b")

/** The logical block index of a text row key `<reply>/b<i>/c<j>`, or -1. */
internal fun blockIndexOf(rowKey: String): Int = rowKey.substringAfterLast("/b").substringBefore("/c").toIntOrNull() ?: -1

/** The first [size] elements of [backing], which is only ever appended to: a view, never a copy. */
private class Prefix<T>(private val backing: List<T>, override val size: Int) : AbstractList<T>() {
    override fun get(index: Int): T {
        if (index >= size) throw IndexOutOfBoundsException("$index of $size")
        return backing[index]
    }
}

/** The first [count] of [parts] read as one list; [ends] holds each part's end. Both are only ever appended to: a view. */
private class Segments<T>(private val parts: List<List<T>>, private val ends: List<Int>, private val count: Int) : AbstractList<T>() {
    override val size: Int = if (count == 0) 0 else ends[count - 1]

    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("$index of $size")
        var lo = 0
        var hi = count - 1
        while (lo < hi) { // the first part ending after index
            val mid = (lo + hi) ushr 1
            if (ends[mid] > index) hi = mid else lo = mid + 1
        }
        return parts[lo][index - (if (lo == 0) 0 else ends[lo - 1])]
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
    private var norm: CharSequence = "" // [text] through [lf]: [text] itself while it holds no CR, else a builder only appended to
    private var blankTo = 0 // the last line holds only spaces from its start up to here (read once, not per token)
    // Rows of stable blocks, one segment per block, never copied; both lists are only appended to, so earlier views stay valid.
    private var closedParts = ArrayList<List<TextRow>>()
    private var closedEnds = ArrayList<Int>()
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
            closedParts = ArrayList()
            closedEnds = ArrayList()
            closedBlocks = 0
            checkpoint = ScanState()
            tail = emptyList()
            blankTo = 0
        }
        val oldLength = if (appended) norm.length else 0
        val new: CharSequence = when {
            !appended -> lf(raw).let { if (it === raw) it else StringBuilder(it) }
            norm === text && raw.indexOf('\r', text.length) < 0 -> raw
            else -> {
                // Only the appended part is made LF. A CR that ended the old text already stands for the line break,
                // so an LF right after it adds nothing.
                val from = if (text.endsWith('\r') && raw.getOrNull(text.length) == '\n') text.length + 1 else text.length
                val builder = norm as? StringBuilder ?: StringBuilder(norm).also { Work.lfChars += norm.length } // first CR in an append
                Work.lfChars += raw.length - from
                builder.append(lf(raw.substring(from)))
            }
        }
        val grown = if (appended && new.indexOf('\n', oldLength) < 0) grown(new, oldLength) else null
        var finished = 0 // blocks ended on finished lines: stable, and before the checkpoint
        val blocks = grown ?: ArrayList<LogicalBlock>().also { out ->
            val s = checkpoint.copy()
            var next: ScanState? = null
            while (s.pos < new.length) {
                if (lineEnd(new, maxOf(s.pos, oldLength)) == new.length) { // the old last line has no break before oldLength
                    next = s.copy()
                    finished = out.size
                }
                scanLine(s, new, out, oldLength)
            }
            if (next == null) {
                next = s.copy()
                finished = out.size
            }
            openBlocks(s, new, out)
            // How far the last line is only spaces; read on from what is known when the line is the same one.
            val known = if (next.pos == checkpoint.pos) maxOf(blankTo, next.pos) else next.pos
            blankTo = firstNonSpace(new, known, minOf(new.length, next.pos + LINE_LOOK + 1))
            Work.scanSteps += blankTo - known
            checkpoint = next
            parsed = out.size
        }
        // The last line may still become a closing fence (blank so far or starting with a backtick, and short enough for
        // the scanner to judge whole): no code piece whose cut depends on it is settled, so the closing drops none.
        val mayClose = (blankTo >= new.length || new[blankTo] == '`') && new.length - checkpoint.pos <= LINE_LOOK + 1
        val settleBefore = if (mayClose) checkpoint.pos - 1 else Int.MAX_VALUE
        val firstIndex = closedBlocks
        val nextTail = ArrayList<Open>()
        blocks.forEachIndexed { n, scanned ->
            val b = scanned.copy(index = firstIndex + n)
            val open = open(key, tail.firstOrNull { it.block.start == b.start }, b, new, settleBefore)
            if (n < finished) {
                val r = open.rows
                if (r.isNotEmpty()) {
                    closedEnds += (closedEnds.lastOrNull() ?: 0) + r.size
                    closedParts += r
                }
                closedBlocks++
            } else {
                nextTail += open
            }
        }
        tail = nextTail
        var view: List<TextRow> = Segments(closedParts, closedEnds, closedParts.size)
        for (o in nextTail) view = Joined(view, o.rows)
        rows = view
        this.key = key
        text = raw
        norm = new
        return rows
    }

    /** The rows of [b], reusing what [prev] (the block at the same start in the last update) still has right. */
    /**
     * The rows of [b], reusing what [prev] (the block at the same start in the last update) still has right. A code
     * piece is settled only when its cut was decided before [settleBefore] (just before a last line that may still close
     * the fence): so a fence closing or reopening never drops a settled piece, and only the last row, which alone carries
     * the closed state, is rebuilt.
     */
    private fun open(key: String, prev: Open?, b: LogicalBlock, text: CharSequence, settleBefore: Int): Open {
        val p = prev?.block
        val same = p != null && p.start == b.start && p.kind == b.kind && p.mode == b.mode && p.lang == b.lang
        if (same && p!!.end == b.end && p.fenceClosed == b.fenceClosed) {
            return Open(b, prev!!.settled, prev.starts, prev.decided, prev.size, prev.loose, prev.looseStart)
        }
        // A closed fence that is highlighted is built whole once (highlighting runs over the whole block, then slices).
        if ((b.kind != BlockKind.CODE && b.mode == BlockMode.PARSED) || highlighted(b)) return Open(b, ArrayList(), ArrayList(), ArrayList(), 0, blockRows(key, b, text), b.start)
        var settled = ArrayList<TextRow>()
        var starts = ArrayList<Int>()
        var decided = ArrayList<Int>()
        var from = b.start
        if (same) {
            // A settled piece stays while its cut was decided before the new end too; usually all of them do.
            var k = prev!!.size
            while (k > 0 && prev.decided[k - 1] >= b.end) k--
            if (k == prev.size) {
                settled = prev.settled
                starts = prev.starts
                decided = prev.decided
                from = prev.looseStart
            } else { // the block got shorter than a settled cut: copy what stays (not expected, see above)
                for (r in 0 until k) settled += prev.settled[r]
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
            if (d < b.end && (b.kind != BlockKind.CODE || d < settleBefore) && loose.isEmpty()) {
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
     * The tail after characters without a line break were appended to text[0, old), when that provably changes no
     * block but lengthening one open LITERAL paragraph or table or a fence: its last line is that block's, no appended
     * character could turn the line into a fence opening (paragraph) or closing (fence), and the line does not follow a
     * blank line in a list (where it could still end or continue the list). A last line of only spaces that stays so
     * changes nothing outside a fence and lengthens the code inside one. Else null. The line's leading spaces are read
     * once, not again on each token.
     */
    private fun grown(text: CharSequence, old: Int): List<LogicalBlock>? {
        val lineStart = checkpoint.pos
        // Leading spaces matter only as far as the scanner looks (LINE_LOOK), so they are never read further.
        val from = maxOf(blankTo, lineStart)
        val f = firstNonSpace(text, from, maxOf(from, minOf(old, lineStart + LINE_LOOK + 1)))
        blankTo = f
        val g = firstNonSpace(text, old, text.length)
        Work.scanSteps += f - from + g - old
        val spaces = g == text.length // only spaces were appended
        val b = tail.singleOrNull()?.block
        val longer = if (b != null && !b.stable && b.end == old && b.mode == BlockMode.LITERAL) listOf(b.copy(end = text.length)) else null
        // Inside a comment-like HTML block only its end marker matters, and spaces cannot complete it.
        if (!checkpoint.htmlEnd.isNullOrEmpty() && spaces) return longer
        val blank = f == old && old - lineStart <= LINE_LOOK // the line so far is blank (see scanLine)
        if (blank && spaces && text.length - lineStart <= LINE_LOOK) { // and stays blank
            if (checkpoint.fenceBody < 0) return tail.map { it.block }
            return if (b != null && b.kind == BlockKind.CODE && !b.stable && b.end == old) listOf(b.copy(end = text.length)) else null
        }
        if (b == null) return null
        val code = b.kind == BlockKind.CODE
        if (b.stable || b.end != old || lineStart < b.start || (!code && b.mode != BlockMode.LITERAL)) return null
        // Backticks could still make the line a fence line (only within what the scanner looks at).
        val ticks = old - f <= FENCE.length && f + FENCE.length <= lineStart + LINE_LOOK && (f until old).all { text[it] == '`' }
        if (if (code) ticks else (blank || ticks)) return null
        // After a blank line in a list, the first characters decide whether the line goes on with the list.
        if (!code && checkpoint.pendingBlank && old - lineStart < 16) return null
        return listOf(b.copy(end = text.length))
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
                addAll(replyRows(item.key, item.text, closeFences = !item.live))
                add(FooterRow(item)) // every reply: live indicators, notices, Copy and Select
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
    onSelect: (String) -> Unit = {},
    current: (() -> List<ChatItem>)? = null,
) {
    val builder = remember(zone) { ChatRows(zone) }
    val latest by rememberUpdatedState(items) // Copy reads the reply as it is when tapped
    // One sideways scroll per table, shared by its slices (separate list items), by "<reply>/b<index>".
    val tableScrolls = remember { HashMap<String, ScrollState>() }
    val pendingId = live?.pending?.let { RequestRef(it.requestId, it.executionGeneration) }
    // An answer that came too late shows under the reply, live or stored, unless that request is somehow drawn again.
    val gone = notes.any { (ref, note) -> note.result == AnswerResult.GONE && ref != pendingId }
    val rows = builder.rows(items).let { if (gone) Joined(it, listOf(GoneRow)) else it }
    // Copy and Select check the transcript as it is at action time: the owner's current state when given.
    val onSelectNow by rememberUpdatedState(onSelect)
    val currentNow by rememberUpdatedState(current)
    val rowActions = remember { RowActions({ currentNow?.invoke() ?: latest }, { onSelectNow(it) }) }
    // Replies of a turn whose run is still pending are not history yet: once per transcript, not per token.
    @Suppress("UNCHECKED_CAST") val stored = (items as? Joined<ChatItem>)?.head ?: items
    val active = remember(stored) { pendingReplies(stored) }
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
                        is UserRow -> UserBubble(row.message, onRetry = actions::onRetry, onCancel = onCancel, actions = rowActions)
                        is StepsRow -> TimelineCard(row.reply, tag = "step:${row.reply.key}", onStep = { i ->
                            // Read at tap time: a step that has since changed kind or gone opens nothing.
                            (row.reply.steps.getOrNull(i) as? ToolStep)?.let { onStep(StepSubject(row.reply.key, i, it.callId.ifEmpty { null }, it.name)) }
                        })
                        is TextRow -> {
                            val source = { copySource(latest, row.key) }
                            if (row.kind == BlockKind.TABLE) {
                                TextBlock(row, onLink, tableScrolls.getOrPut(row.key.substringBeforeLast("/c")) { ScrollState(0) }, source)
                            } else {
                                TextBlock(row, onLink, source = source)
                            }
                        }
                        is FooterRow -> ReplyFooter(
                            row.reply, live.takeIf { row.reply.live }, notes, answering, actions,
                            rowActions, active = row.reply.key in active,
                        )
                        is NoticeRow -> NoticeLine(row.notice.text)
                        GoneRow -> Notice(R.string.chat_answer_gone)
                    }
                }
            }
        }
    }
}

/** The end-to-end tests' id of a row: the item kind and its opaque key (steps are tagged inside [TimelineCard]). */
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
