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

/** A paragraph or a fenced code block of a reply's text, as offsets; [origin] is where its first line starts. */
internal data class Block(val origin: Int, val start: Int, val end: Int, val code: Boolean, val lang: String = "")

private const val FENCE = "```"

/** Appends the blocks of [text] from offset [from] (a line start) to [out]. Paragraphs are split at blank lines. */
internal fun splitBlocks(text: String, from: Int, out: MutableList<Block>) {
    var i = from
    var para = -1
    var paraEnd = -1
    fun closePara() {
        if (para >= 0) out += Block(para, para, paraEnd, code = false)
        para = -1
    }
    while (i < text.length) {
        val nl = lineEnd(text, i)
        val first = firstNonSpace(text, i, nl)
        when {
            first == nl -> closePara()
            text.startsWith(FENCE, first) -> {
                closePara()
                val bodyStart = minOf(nl + 1, text.length)
                var j = bodyStart
                var bodyEnd = text.length // an unclosed fence (still streaming) runs to the end
                var next = text.length
                while (j < text.length) {
                    val e = lineEnd(text, j)
                    val f = firstNonSpace(text, j, e)
                    if (text.startsWith(FENCE, f) && firstNonSpace(text, f + FENCE.length, e) == e) {
                        bodyEnd = maxOf(bodyStart, j - 1)
                        next = e
                        break
                    }
                    j = e + 1
                }
                out += Block(i, bodyStart, bodyEnd, code = true, lang = text.substring(first + FENCE.length, nl).trim())
                i = next + 1
                continue
            }
            else -> {
                if (para < 0) para = i
                paraEnd = nl
            }
        }
        i = nl + 1
    }
    closePara()
}

private fun lineEnd(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun firstNonSpace(text: String, from: Int, end: Int): Int {
    var k = from
    while (k < end && text[k].isWhitespace()) k++
    return k
}

/**
 * The blocks of a growing text. While the text only grows at its end, each update re-splits only from the start of
 * the last block, so a streamed token costs the length of that block, not of the whole reply.
 */
internal class LiveBlocks {
    private var text = ""
    private val list = ArrayList<Block>()
    private val texts = ArrayList<String>()
    val blocks: List<Block> get() = list

    fun text(index: Int): String = texts[index]

    fun update(new: String) {
        if (new === text) return
        val last = list.lastOrNull()
        val from = last?.origin ?: 0
        if (last != null && new.length >= text.length && new.regionMatches(from, text, from, text.length - from)) {
            list.removeAt(list.size - 1)
            texts.removeAt(texts.size - 1)
        } else {
            list.clear()
            texts.clear()
        }
        val kept = list.size
        splitBlocks(new, if (kept == 0) 0 else from, list)
        for (k in kept until list.size) texts += new.substring(list[k].start, list[k].end)
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
internal data class TextRow(override val key: String, val text: String, val code: Boolean, val lang: String) : Row
internal data class FooterRow(val reply: AssistantReply) : Row {
    override val key get() = reply.key + "/end"
}
internal data class NoticeRow(val notice: Notice) : Row {
    override val key get() = notice.key
}

/**
 * Turns chat items into rows. Stored items are mapped once and reused while the same instances come back; the live
 * reply at the end (see [withLive]) is re-split incrementally. Per streamed token the work is that reply's blocks only.
 */
internal class ChatRows(private val zone: ZoneId) {
    private var head: List<ChatItem>? = null
    private var headRows: List<Row> = emptyList()
    private var headDay: LocalDate? = null
    private var cache = IdentityHashMap<ChatItem, List<Row>>()
    private val live = LiveBlocks()

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
                val mapped = cache[item] ?: rowsOf(item)
                next[item] = mapped
                out += mapped
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
            if (item is AssistantReply) {
                live.update(item.text)
                if (item.steps.isNotEmpty()) out += StepsRow(item)
                live.blocks.forEachIndexed { i, b -> out += TextRow("${item.key}/b$i", live.text(i), b.code, b.lang) }
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

    private fun rowsOf(item: ChatItem): List<Row> = when (item) {
        is UserMessage -> listOf(UserRow(item))
        is Notice -> listOf(NoticeRow(item))
        is AssistantReply -> buildList {
            if (item.steps.isNotEmpty()) add(StepsRow(item))
            val blocks = ArrayList<Block>().also { splitBlocks(item.text, 0, it) }
            blocks.forEachIndexed { i, b -> add(TextRow("${item.key}/b$i", item.text.substring(b.start, b.end), b.code, b.lang)) }
            if (item.live || item.outcome != null) add(FooterRow(item))
        }
    }
}

/** The transcript, newest at the bottom, in a reversed list so a growing reply stays pinned without scroll calls. */
@Composable
internal fun MessageList(
    items: List<ChatItem>,
    live: LiveReply?,
    answerNote: AnswerResult?,
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
                        is FooterRow -> ReplyFooter(row.reply, live.takeIf { row.reply.live }, answerNote, answering, actions)
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
