package xyz.rycerz.apofis.ui.chat

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

/** Longest chunk of text laid out as one row, so one streamed token never re-lays out a whole long block. */
internal const val CHUNK = 1500

/**
 * A paragraph or a fenced code block; [start] and [end] bound its content in the reply text. [closed]: nothing
 * appended to the text can change it (a paragraph ended by a finished blank line or a fence, a fence closed by a
 * finished line); parsing then resumes at [next] between blocks.
 */
internal data class Block(val start: Int, val end: Int, val code: Boolean, val lang: String, val closed: Boolean, val next: Int)

private const val FENCE = "```"

/** The blocks of [text] from [from], which must be a line start between blocks. Correct at any length. */
internal fun parseBlocks(text: String, from: Int = 0): List<Block> {
    val out = ArrayList<Block>()
    var i = from
    var paraStart = -1
    var paraEnd = -1
    fun endPara(closed: Boolean, next: Int) {
        if (paraStart >= 0) out += Block(paraStart, paraEnd, false, "", closed, next)
        paraStart = -1
    }
    while (i < text.length) {
        val nl = lineEnd(text, i)
        val first = firstNonSpace(text, i, nl)
        if (first == nl) { // a blank line; it ends the paragraph for good only once the line itself is finished
            endPara(closed = nl < text.length, next = nl + 1)
            i = nl + 1
            continue
        }
        if (text.startsWith(FENCE, first)) {
            endPara(closed = true, next = i)
            i = parseFence(text, nl, first, out)
            continue
        }
        if (paraStart < 0) paraStart = i
        paraEnd = nl
        i = nl + 1
    }
    endPara(closed = false, next = text.length)
    return out
}

/** Adds the fence opened on the line ending at [openEnd] (backticks at [first]); returns where parsing continues. */
private fun parseFence(text: String, openEnd: Int, first: Int, out: MutableList<Block>): Int {
    val lang = text.substring(first + FENCE.length, openEnd).trim()
    val body = minOf(openEnd + 1, text.length)
    var j = body
    while (j < text.length) {
        val e = lineEnd(text, j)
        val f = firstNonSpace(text, j, e)
        if (text.startsWith(FENCE, f) && firstNonSpace(text, f + FENCE.length, e) == e) {
            out += Block(body, maxOf(body, j - 1), true, lang, closed = e < text.length, next = e + 1)
            return e + 1
        }
        j = e + 1
    }
    out += Block(body, text.length, true, lang, closed = false, next = text.length) // still streaming: runs to the end
    return text.length
}

/** Work counters for tests: characters inspected by [chunkRanges] and [TextRow]s built. */
internal object Work {
    var chunkSteps = 0L
    var rowsBuilt = 0L
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

/**
 * Adds to [out] the rows of block number [index] of reply [key], from its content offset [from] (a piece start),
 * numbering pieces from [firstPiece]. Returns where the last piece starts.
 */
private fun blockRows(key: String, index: Int, text: String, b: Block, from: Int, firstPiece: Int, out: MutableList<TextRow>): Int {
    var c = firstPiece
    var lastStart = from
    chunkRanges(text, from, b.end) { s, e, last ->
        Work.rowsBuilt++
        out += TextRow("$key/b$index/c$c", text.substring(s, e), b.code, b.lang, first = c == 0, joinBelow = !last)
        c++
        lastStart = s
    }
    return lastStart
}

/** Every text row of a reply, parsed from scratch. */
internal fun replyRows(key: String, text: String): List<TextRow> {
    val out = ArrayList<TextRow>()
    parseBlocks(text).forEachIndexed { i, b -> blockRows(key, i, text, b, b.start, 0, out) }
    return out
}

/**
 * The text rows of one streamed reply. While the same key's text grows at its end, closed blocks keep their rows,
 * an open block keeps the rows of every piece but its last, and only the rest is built; an append without a line
 * break to an open paragraph or fence is not even parsed again. Any other change (another key, or the text
 * replaced) starts from scratch and shares nothing with the previous parse.
 */
internal class ReplyText {
    /** An open block and its rows; [lastPiece] is where its last row's text starts. Mutated only here. */
    private class Open(var block: Block, val rows: ArrayList<TextRow>, var lastPiece: Int)

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
        blocks.forEachIndexed { n, b ->
            val prev = tail.getOrNull(n)
            val open = if (prev != null && prev.block.start == b.start && prev.block.code == b.code && prev.block.lang == b.lang && b.end >= prev.block.end) {
                prev.rows.removeAt(prev.rows.size - 1)
                prev.lastPiece = blockRows(key, firstIndex + n, new, b, prev.lastPiece, prev.rows.size, prev.rows)
                prev.block = b
                prev
            } else {
                val made = ArrayList<TextRow>()
                Open(b, made, blockRows(key, firstIndex + n, new, b, b.start, 0, made))
            }
            if (b.closed && nextTail.isEmpty()) {
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

    /**
     * The tail after characters without a line break were appended to [old], when that provably only lengthens one
     * open paragraph or fence to [length]: its last line is that block's, and no appended character could turn the
     * line into a fence opening (paragraph) or closing (fence). Else null.
     */
    private fun grown(old: String, length: Int): List<Block>? {
        val b = tail.singleOrNull()?.block ?: return null
        if (b.closed || b.end != old.length || lineStart < b.start) return null
        val f = firstNonSpace(old, lineStart, old.length)
        val ticks = old.length - f <= FENCE.length && (f until old.length).all { old[it] == '`' }
        if (if (b.code) ticks else (f == old.length || ticks)) return null
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

/** One chunk of a block; [first] and [joinBelow] tell whether it starts its block and whether the block goes on below. */
internal data class TextRow(
    override val key: String,
    val text: String,
    val code: Boolean,
    val lang: String,
    val first: Boolean = true,
    val joinBelow: Boolean = false,
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
    onStep: (StepRef) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val builder = remember(zone) { ChatRows(zone) }
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
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 860.dp).fillMaxWidth()) {
                    when (val row = rows[rows.size - 1 - index]) {
                        is DateRow -> DateSeparator(row.day, zone)
                        is UserRow -> UserBubble(row.message, onRetry = actions::onRetry, onCancel = onCancel)
                        is StepsRow -> StepList(row.reply.steps) { i ->
                            val step = row.reply.steps[i] as ToolStep
                            onStep(StepRef(row.reply.key, i, step.callId, row.reply.live))
                        }
                        is TextRow -> TextBlock(row)
                        is FooterRow -> ReplyFooter(row.reply, live.takeIf { row.reply.live }, notes, answering, actions)
                        is NoticeRow -> NoticeLine(row.notice.text)
                        GoneRow -> Notice(R.string.chat_answer_gone)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateSeparator(day: LocalDate, zone: ZoneId) {
    val today = LocalDate.now(zone)
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
