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
import xyz.rycerz.apofis.ui.markdown.TABLE_MAX_ROWS
import xyz.rycerz.apofis.ui.markdown.displayChunks
import xyz.rycerz.apofis.ui.markdown.rawChunk
import xyz.rycerz.apofis.ui.markdown.rawPieces

/** Longest chunk of text laid out as one row, so one streamed token never re-lays out a whole long block. */
internal const val CHUNK = 1500

private const val FENCE = "```"

/**
 * The logical blocks of [text] from [from], which must be a line start between blocks; indices count from 0 there.
 * Paragraph groups end at blank lines and fence lines, except that a group holding a list item goes on across blank
 * lines while the next line is indented or another list item (a loose list stays one block). Correct at any length.
 */
internal fun parseBlocks(text: String, from: Int = 0): List<LogicalBlock> {
    val out = ArrayList<LogicalBlock>()
    var i = from
    var paraStart = -1
    var paraEnd = -1
    var list = false // the group holds a list item line
    var pendingBlank = false // blank lines after the group, which a list may still continue across
    var table = false
    var lastHasPipe = false
    var rows = 0
    var pipes = 0
    fun endPara(stable: Boolean, next: Int) {
        if (paraStart >= 0) {
            val kind = if (table) BlockKind.TABLE else BlockKind.TEXT
            val length = paraEnd - paraStart
            val literal = (!stable && length > OPEN_PARSE_LIMIT) ||
                (table && (length > TABLE_MAX_CHARS || rows > TABLE_MAX_ROWS || pipes > TABLE_MAX_CELLS))
            out += LogicalBlock(out.size, paraStart, paraEnd, kind, "", stable, false, if (literal) BlockMode.LITERAL else BlockMode.PARSED, next)
        }
        paraStart = -1
        list = false
        pendingBlank = false
        table = false
        lastHasPipe = false
        rows = 0
        pipes = 0
    }
    while (i < text.length) {
        val nl = lineEnd(text, i)
        val first = firstNonSpace(text, i, nl)
        if (first == nl) { // a blank line; it ends the paragraph for good only once the line itself is finished
            if (paraStart >= 0 && list) pendingBlank = true else endPara(stable = nl < text.length, next = nl + 1)
            lastHasPipe = false
            i = nl + 1
            continue
        }
        if (text.startsWith(FENCE, first)) {
            endPara(stable = true, next = i)
            i = parseFence(text, nl, first, out)
            continue
        }
        // A line that may still turn into a list item (only while unfinished) counts as continuing the list for now.
        if (pendingBlank && !(first > i || isListItem(text, i, nl) || (nl == text.length && mayBecomeListItem(text, i, nl)))) {
            endPara(stable = true, next = i)
        }
        pendingBlank = false
        if (paraStart < 0) paraStart = i
        if (!list && isListItem(text, i, nl)) list = true
        var linePipes = 0
        for (k in i until nl) if (text[k] == '|') linePipes++
        if (!table && lastHasPipe && nl < text.length && DELIMITER_ROW.matches(text.subSequence(i, nl))) table = true
        lastHasPipe = linePipes > 0
        pipes += linePipes
        rows++
        paraEnd = nl
        i = nl + 1
    }
    endPara(stable = false, next = text.length)
    return out
}

/** A GFM table delimiter row, such as `|:--|:-:|--:|` or `---`. */
private val DELIMITER_ROW = Regex("""\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*""")

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

/** Adds the fence opened on the line ending at [openEnd] (backticks at [first]); returns where parsing continues. */
private fun parseFence(text: String, openEnd: Int, first: Int, out: MutableList<LogicalBlock>): Int {
    val lang = text.substring(first + FENCE.length, openEnd).trim()
    val body = minOf(openEnd + 1, text.length)
    var j = body
    while (j < text.length) {
        val e = lineEnd(text, j)
        val f = firstNonSpace(text, j, e)
        if (text.startsWith(FENCE, f) && firstNonSpace(text, f + FENCE.length, e) == e) {
            out += LogicalBlock(out.size, body, maxOf(body, j - 1), BlockKind.CODE, lang, stable = e < text.length, fenceClosed = true, BlockMode.PARSED, next = e + 1)
            return e + 1
        }
        j = e + 1
    }
    // Still streaming: runs to the end.
    out += LogicalBlock(out.size, body, text.length, BlockKind.CODE, lang, stable = false, fenceClosed = false, BlockMode.PARSED, next = text.length)
    return text.length
}

/** Work counters for tests: characters inspected by [chunkRanges], [TextRow]s built and markdown parses. */
internal object Work {
    var chunkSteps = 0L
    var rowsBuilt = 0L
    var markdownParses = 0L
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

/** Every text row of a reply, parsed from scratch. */
internal fun replyRows(key: String, text: String): List<TextRow> = parseBlocks(text).flatMap { blockRows(key, it, text) }

/**
 * The text rows of one streamed reply. While the same key's text grows at its end, stable blocks keep their rows and
 * are never parsed again; an open block whose source is unchanged keeps its rows; a changed PARSED block is parsed
 * again whole (it is at most [OPEN_PARSE_LIMIT] long); a changed LITERAL or CODE block keeps the rows of every piece
 * whose cut is unchanged. A block whose kind or mode changes is rebuilt from scratch. An append without a line break
 * to an open LITERAL paragraph or a fence is not even split into blocks again. Any other change (another key, or the
 * text replaced) starts from scratch and shares nothing with the previous parse.
 */
internal class ReplyText {
    /** An open block, its rows, and where each row's piece starts and up to where its cut was decided (raw pieces only). */
    private class Open(val block: LogicalBlock, val rows: List<TextRow>, val starts: List<Int>, val decided: List<Int>)

    private var key: String? = null
    private var text = ""
    private var closedRows = ArrayList<TextRow>()
    private var closedBlocks = 0
    private var stable = 0 // where the open tail starts
    private var tail: List<Open> = emptyList()
    private var lineStart = 0 // where the text's last line starts
    private var rows: List<TextRow> = emptyList()

    /** Blocks parsed by the last [update]; for tests. */
    var parsed = 0
        private set

    fun update(key: String, new: String): List<TextRow> {
        parsed = 0
        if (key == this.key && new == text) return rows
        val appended = key == this.key && new.startsWith(text)
        if (!appended) {
            closedRows = ArrayList()
            closedBlocks = 0
            stable = 0
            tail = emptyList()
            lineStart = 0
        }
        val old = if (appended) text else ""
        var newLineStart = lineStart
        for (i in new.length - 1 downTo old.length) {
            if (new[i] == '\n') {
                newLineStart = i + 1
                break
            }
        }
        val grown = if (appended && newLineStart == lineStart) grown(old, new.length) else null
        val blocks = grown ?: parseBlocks(new, stable).also { parsed = it.size }
        lineStart = newLineStart
        val nextTail = ArrayList<Open>()
        val firstIndex = closedBlocks
        blocks.forEachIndexed { n, parsedBlock ->
            val b = parsedBlock.copy(index = firstIndex + n)
            val open = open(key, tail.getOrNull(n), b, new)
            if (b.stable && nextTail.isEmpty()) {
                closedRows.addAll(open.rows)
                closedBlocks++
                stable = b.next
            } else {
                nextTail += open
            }
        }
        tail = nextTail
        rows = ArrayList<TextRow>(closedRows.size + nextTail.sumOf { it.rows.size }).apply {
            addAll(closedRows)
            nextTail.forEach { addAll(it.rows) }
        }
        this.key = key
        text = new
        return rows
    }

    /** The rows of [b], reusing what [prev] (the block at the same place in the last update) still has right. */
    private fun open(key: String, prev: Open?, b: LogicalBlock, text: String): Open {
        val p = prev?.block
        val same = p != null && p.start == b.start && p.kind == b.kind && p.mode == b.mode && p.lang == b.lang
        if (same && p!!.end == b.end && p.fenceClosed == b.fenceClosed) return Open(b, prev!!.rows, prev.starts, prev.decided)
        if (b.kind != BlockKind.CODE && b.mode == BlockMode.PARSED) return Open(b, blockRows(key, b, text), emptyList(), emptyList())
        val rows = ArrayList<TextRow>()
        val starts = ArrayList<Int>()
        val decided = ArrayList<Int>()
        var from = b.start
        if (same) {
            // A piece is unchanged when its cut was decided before both the old and the new end (see rawPieces); the
            // last piece never is, as it was decided by the old end.
            val end = minOf(p!!.end, b.end)
            var k = 0
            while (k < prev!!.rows.size && prev.decided[k] < end) k++
            for (r in 0 until k) {
                rows += prev.rows[r].let { if (it.fenceClosed == b.fenceClosed) it else it.copy(fenceClosed = b.fenceClosed) }
                starts += prev.starts[r]
                decided += prev.decided[r]
            }
            from = prev.starts[k]
        }
        var c = rows.size
        rawPieces(text, b, from) { s, e, d, last ->
            rows += textRow(key, b, c++, rawChunk(text, b, s, e, last), last)
            starts += s
            decided += d
        }
        return Open(b, rows, starts, decided)
    }

    /**
     * The tail after characters without a line break were appended to [old], when that provably only lengthens one
     * open LITERAL paragraph or fence to [length]: its last line is that block's, no appended character could turn the
     * line into a fence opening (paragraph) or closing (fence), and the line does not follow a blank line (where it
     * could still end or continue a list). Else null.
     */
    private fun grown(old: String, length: Int): List<LogicalBlock>? {
        val b = tail.singleOrNull()?.block ?: return null
        val code = b.kind == BlockKind.CODE
        if (b.stable || b.end != old.length || lineStart < b.start || (!code && b.mode != BlockMode.LITERAL)) return null
        val f = firstNonSpace(old, lineStart, old.length)
        val ticks = old.length - f <= FENCE.length && (f until old.length).all { old[it] == '`' }
        if (if (code) ticks else (f == old.length || ticks)) return null
        if (!code && lineStart > b.start) {
            val prevStart = old.lastIndexOf('\n', lineStart - 2) + 1
            if (firstNonSpace(old, prevStart, lineStart - 1) == lineStart - 1) return null
        }
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
        val out = ArrayList<Row>()
        val nextTail = IdentityHashMap<ChatItem, List<Row>>()
        var day = headDay
        for (item in tail) {
            day = separate(item, day, out)
            if (item is AssistantReply && item.live) {
                if (item.steps.isNotEmpty()) out += StepsRow(item)
                out += live.update(item.key, item.text)
                out += FooterRow(item)
            } else {
                val rows = tailCache[item] ?: cache[item] ?: rowsOf(item)
                nextTail[item] = rows
                out += rows
            }
        }
        tailCache = nextTail
        return Joined(headRows, out)
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
