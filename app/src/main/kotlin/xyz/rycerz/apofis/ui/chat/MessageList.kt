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

/** Where the parser stands at a block's [Block.origin]: between blocks, inside a paragraph, or inside a fence. */
internal enum class Resume { NORMAL, PARAGRAPH, CODE }

/** Longest chunk of text laid out as one row, so one streamed token never re-lays out a whole long block. */
internal const val CHUNK = 1500

/**
 * One chunk of a paragraph or of a fenced code block, as offsets into the reply text. Parsing can restart at
 * [origin] in state [resume] (with [lang] inside a fence); [first] marks the first chunk of its paragraph or block.
 */
internal data class Block(
    val origin: Int,
    val resume: Resume,
    val start: Int,
    val end: Int,
    val code: Boolean,
    val lang: String,
    val first: Boolean,
)

private const val FENCE = "```"

/**
 * Appends the blocks of [text] from offset [from] to [out], starting in state [resume]. Paragraphs end at blank
 * lines; every paragraph and code block is cut into chunks of at most [CHUNK] characters, at line ends where
 * possible, else at a fixed length.
 */
internal fun splitBlocks(text: String, from: Int, out: MutableList<Block>, resume: Resume = Resume.NORMAL, lang: String = "") {
    var i = from
    var paraStart = if (resume == Resume.PARAGRAPH) from else -1
    var paraEnd = from
    var paraFirst = resume != Resume.PARAGRAPH
    var skipFence = resume == Resume.PARAGRAPH // a resumed paragraph chunk may start mid-line
    fun emitPara(end: Int) {
        if (end > paraStart) out += Block(paraStart, if (paraFirst) Resume.NORMAL else Resume.PARAGRAPH, paraStart, end, false, "", paraFirst)
    }
    fun flushPara() {
        if (paraStart >= 0) emitPara(paraEnd)
        paraStart = -1
        paraFirst = true
    }
    if (resume == Resume.CODE) i = splitCode(text, from, from, lang, first = false, midLine = true, out)
    while (i < text.length) {
        val nl = lineEnd(text, i)
        val first = firstNonSpace(text, i, nl)
        if (first == nl && !skipFence) {
            flushPara()
            i = nl + 1
            continue
        }
        if (!skipFence && text.startsWith(FENCE, first)) {
            flushPara()
            i = splitCode(text, i, minOf(nl + 1, text.length), text.substring(first + FENCE.length, nl).trim(), first = true, midLine = false, out)
            continue
        }
        skipFence = false
        if (paraStart < 0) paraStart = i
        if (i > paraStart && nl - paraStart > CHUNK) {
            emitPara(i - 1) // the paragraph so far, without the line break before this line
            paraStart = i
            paraFirst = false
        }
        while (nl - paraStart > CHUNK) { // one line longer than a chunk: cut at a fixed length
            val cut = safeCut(text, paraStart + CHUNK)
            emitPara(cut)
            paraStart = cut
            paraFirst = false
        }
        paraEnd = nl
        i = nl + 1
    }
    flushPara()
}

/** Splits one fence body starting at [bodyStart] (the fence line at [origin]); returns where parsing continues. */
private fun splitCode(text: String, origin: Int, bodyStart: Int, lang: String, first: Boolean, midLine: Boolean, out: MutableList<Block>): Int {
    var chunkStart = bodyStart
    var chunkOrigin = origin
    var isFirst = first
    var skipClose = midLine
    fun emit(end: Int) {
        out += Block(chunkOrigin, if (isFirst) Resume.NORMAL else Resume.CODE, chunkStart, maxOf(chunkStart, end), true, lang, isFirst)
    }
    var j = bodyStart
    while (j < text.length) {
        val e = lineEnd(text, j)
        val f = firstNonSpace(text, j, e)
        if (!skipClose && text.startsWith(FENCE, f) && firstNonSpace(text, f + FENCE.length, e) == e) {
            emit(j - 1)
            return e + 1
        }
        skipClose = false
        if (e - chunkStart > CHUNK && j > chunkStart) {
            emit(j - 1)
            chunkStart = j
            chunkOrigin = j
            isFirst = false
        }
        while (e - chunkStart > CHUNK) {
            val cut = safeCut(text, chunkStart + CHUNK)
            emit(cut)
            chunkStart = cut
            chunkOrigin = cut
            isFirst = false
        }
        j = e + 1
    }
    emit(text.length) // an unclosed fence (still streaming) runs to the end
    return text.length
}

private fun safeCut(text: String, at: Int) = if (Character.isHighSurrogate(text[at - 1])) at - 1 else at

private fun lineEnd(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun firstNonSpace(text: String, from: Int, end: Int): Int {
    var k = from
    while (k < end && text[k].isWhitespace()) k++
    return k
}

/**
 * The parsed blocks of one reply, cached by its key. While the same key's text only grows at its end, an update
 * re-parses from the last block's origin and keeps every earlier block object; another key, a shorter text or a
 * change inside the last block starts over. Changes before the last block are not detected: a live reply's text
 * only ever grows at its end.
 */
internal class LiveBlocks {
    private var key: String? = null
    private var text = ""
    private val list = ArrayList<Block>()
    private val texts = ArrayList<String>()
    val blocks: List<Block> get() = list

    /** Blocks created by the last [update]; for tests. */
    var parsed = 0
        private set

    fun text(index: Int): String = texts[index]

    fun update(key: String, new: String) {
        parsed = 0
        if (key == this.key && new === text) return
        val last = list.lastOrNull()
        val appended = key == this.key && last != null && new.length >= text.length &&
            new.regionMatches(last.origin, text, last.origin, text.length - last.origin)
        if (appended) {
            list.removeAt(list.size - 1)
            texts.removeAt(texts.size - 1)
        } else {
            list.clear()
            texts.clear()
        }
        val kept = list.size
        if (appended) splitBlocks(new, last!!.origin, list, last.resume, last.lang) else splitBlocks(new, 0, list)
        for (k in kept until list.size) texts += new.substring(list[k].start, list[k].end)
        parsed = list.size - kept
        this.key = key
        text = new
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

/** One chunk of text; [first] and [joinBelow] tell whether it starts its block and whether the block goes on below. */
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

/**
 * Turns chat items into rows. Stored items are mapped once and reused while the same instances come back; the live
 * reply at the end (see [LiveMerger]) is re-parsed incrementally. Per streamed token the work is that reply's last
 * block and the list of its row references, never the history.
 */
internal class ChatRows(private val zone: ZoneId) {
    private var head: List<ChatItem>? = null
    private var headRows: List<Row> = emptyList()
    private var headDay: LocalDate? = null
    private var cache = IdentityHashMap<ChatItem, List<Row>>()
    private val live = LiveBlocks()

    /** Items mapped to rows so far; for tests. */
    var mapped = 0
        private set

    /** Blocks parsed for the live reply by the last call; for tests. */
    val liveParsed: Int get() = live.parsed

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
                val rows = cache[item] ?: rowsOf(item)
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
        var day = headDay
        for (item in tail) {
            day = separate(item, day, out)
            if (item is AssistantReply && item.live) {
                live.update(item.key, item.text)
                if (item.steps.isNotEmpty()) out += StepsRow(item)
                val blocks = live.blocks
                for (i in blocks.indices) out += textRow(item.key, i, blocks, live.text(i))
                out += FooterRow(item)
            } else {
                out += rowsOf(item)
            }
        }
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
                val blocks = ArrayList<Block>().also { splitBlocks(item.text, 0, it) }
                for (i in blocks.indices) add(textRow(item.key, i, blocks, item.text.substring(blocks[i].start, blocks[i].end)))
                if (item.live || item.outcome != null) add(FooterRow(item))
            }
        }
    }

    private fun textRow(key: String, i: Int, blocks: List<Block>, text: String): TextRow {
        val b = blocks[i]
        return TextRow("$key/b$i", text, b.code, b.lang, b.first, joinBelow = i + 1 < blocks.size && !blocks[i + 1].first)
    }
}

/** The transcript, newest at the bottom, in a reversed list so a growing reply stays pinned without scroll calls. */
@Composable
internal fun MessageList(
    items: List<ChatItem>,
    live: LiveReply?,
    notes: Map<String, AnswerResult>,
    answering: Boolean,
    actions: ChatActions,
    onCancel: (UserMessage) -> Unit,
    onStep: (ToolStep) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val builder = remember(zone) { ChatRows(zone) }
    val rows = builder.rows(items)
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
                        is StepsRow -> StepList(row.reply.steps, onStep)
                        is TextRow -> TextBlock(row)
                        is FooterRow -> ReplyFooter(row.reply, live.takeIf { row.reply.live }, notes, answering, actions)
                        is NoticeRow -> NoticeLine(row.notice.text)
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
