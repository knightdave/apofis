package xyz.rycerz.apofis.ui.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import java.net.URI
import java.net.URISyntaxException
import org.commonmark.parser.Parser

enum class SpanKind { STRONG, EMPHASIS, STRIKE, CODE, LINK }

/** A style over text[start, end); [url] only for LINK, always accepted by [safeUrl]. */
data class MdSpan(val start: Int, val end: Int, val kind: SpanKind, val url: String? = null)
data class MdText(val text: String, val spans: List<MdSpan>)
enum class Align { START, CENTER, END }
data class MdListItem(val task: Boolean, val checked: Boolean, val blocks: List<MdBlock>)

sealed interface MdBlock {
    data class Paragraph(val text: MdText) : MdBlock
    data class Heading(val level: Int, val text: MdText) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** [tight]: no blank line between its items or their blocks, so blocks inside an item sit close. */
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>, val tight: Boolean = true) : MdBlock
    data class Table(val header: List<MdText>, val align: List<Align>, val rows: List<List<MdText>>) : MdBlock

    /** A paragraph that is only an image; [url] is empty unless [safeUrl] accepts it. Nothing is ever fetched. */
    data class Image(val alt: String, val url: String) : MdBlock
    data object Rule : MdBlock
}

/** Quotes and lists nest at most this deep; anything deeper is flattened into the deepest container. */
const val MAX_NESTING = 8

// Code is cut out before parsing (fences) or is not markdown at all (indentation). HTML blocks are recognised only
// so that their source shows as it is, never interpreted.
private val blockTypes = setOf(org.commonmark.node.Heading::class.java, BlockQuote::class.java, ListBlock::class.java, ThematicBreak::class.java, HtmlBlock::class.java)

// One tilde is common in prose ("~5 min"), so only ~~text~~ strikes through.
private fun parser(tables: Boolean): Parser = Parser.builder()
    .extensions(
        listOfNotNull(
            if (tables) TablesExtension.builder().maxCells(TABLE_MAX_CELLS).build() else null,
            StrikethroughExtension.builder().requireTwoTildes(true).build(),
            TaskListItemsExtension.create(),
        ),
    )
    .enabledBlockTypes(blockTypes)
    // Deeper structure becomes plain text in the parser; the tree is flattened further to MAX_NESTING below.
    .maxOpenBlockParsers(4 * MAX_NESTING)
    .maxInlineNesting(4 * MAX_NESTING)
    .build()

private val withTables = parser(tables = true)
private val withoutTables = parser(tables = false)

/**
 * The markdown blocks of [text]. Tables are parsed only when [tables] is set: the row builder sets it only for a block
 * its scanner accepted as a table, within the table budgets. Raw HTML stays literal text and nothing is fetched.
 */
fun parseMarkdown(text: String, tables: Boolean = true): List<MdBlock> {
    val document = try {
        (if (tables) withTables else withoutTables).parse(text)
    } catch (_: IllegalArgumentException) { // over the cell limit: unreachable from the row builder, which checks first
        return listOf(MdBlock.Paragraph(MdText(text, emptyList())))
    }
    return blocks(document, 0)
}

/**
 * [url] when it is an absolute http or https URL (scheme in any case) with a well-formed, non-empty host, with the
 * scheme in lower case (Android matches intent schemes case-sensitively); else null.
 */
fun safeUrl(url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme ?: return null
    if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
    if (uri.isOpaque || uri.host.isNullOrEmpty() || uri.rawAuthority == null) return null
    return scheme.lowercase() + url.substring(scheme.length)
}

/** The blocks under [parent], which sits [depth] containers deep. */
private fun blocks(parent: Node, depth: Int): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    var node = parent.firstChild
    while (node != null) {
        addBlock(node, depth, out)
        node = node.next
    }
    return out
}

private fun addBlock(node: Node, depth: Int, out: MutableList<MdBlock>) {
    when (node) {
        is Paragraph -> out += paragraph(node)
        is Heading -> out += MdBlock.Heading(node.level, inline(node))
        is ThematicBreak -> out += MdBlock.Rule
        is HtmlBlock -> out += MdBlock.Paragraph(MdText(node.literal.trimEnd('\n', '\r'), emptyList()))
        is TableBlock -> out += table(node)
        is BlockQuote ->
            if (depth >= MAX_NESTING) out += blocks(node, depth) else out += MdBlock.Quote(blocks(node, depth + 1))
        is ListBlock -> {
            if (depth >= MAX_NESTING) {
                var item = node.firstChild
                while (item != null) {
                    out += blocks(item, depth)
                    item = item.next
                }
            } else {
                val items = ArrayList<MdListItem>()
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) items += listItem(item, depth + 1)
                    item = item.next
                }
                val start = (node as? OrderedList)?.markerStartNumber ?: 1
                out += MdBlock.ListBlock(node is OrderedList, start, items, node.isTight)
            }
        }
        else -> Unit // link reference definitions and anything unknown add nothing visible
    }
}

private fun listItem(item: ListItem, depth: Int): MdListItem {
    val marker = item.firstChild as? TaskListItemMarker
    return MdListItem(task = marker != null, checked = marker?.isChecked == true, blocks = blocks(item, depth))
}

private fun paragraph(node: Paragraph): MdBlock {
    val only = node.firstChild
    if (only is Image && only.next == null) {
        return MdBlock.Image(inline(only).text, safeUrl(only.destination) ?: "")
    }
    return MdBlock.Paragraph(inline(node))
}

private fun table(node: TableBlock): MdBlock.Table {
    val header = ArrayList<MdText>()
    val align = ArrayList<Align>()
    val rows = ArrayList<List<MdText>>()
    var section = node.firstChild
    while (section != null) {
        var row = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = ArrayList<MdText>()
                var cell = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) {
                        cells += inline(cell)
                        if (section is TableHead) {
                            align += when (cell.alignment) {
                                TableCell.Alignment.CENTER -> Align.CENTER
                                TableCell.Alignment.RIGHT -> Align.END
                                else -> Align.START
                            }
                        }
                    }
                    cell = cell.next
                }
                if (section is TableHead) header += cells else rows += cells
            }
            row = row.next
        }
        section = section.next
    }
    // The parser already pads short rows and cuts long ones to the header; this keeps that true whatever it does.
    val padded = rows.map { r -> List(header.size) { r.getOrNull(it) ?: MdText("", emptyList()) } }
    return MdBlock.Table(header, align, padded)
}

/** The text and spans of [node]'s inline children. */
private fun inline(node: Node): MdText {
    val sb = StringBuilder()
    val spans = ArrayList<MdSpan>()
    appendInlines(node, sb, spans)
    return MdText(sb.toString(), spans)
}

private fun appendInlines(parent: Node, sb: StringBuilder, spans: MutableList<MdSpan>, inLink: Boolean = false) {
    var node = parent.firstChild
    while (node != null) {
        appendInline(node, sb, spans, inLink)
        node = node.next
    }
}

private fun appendInline(node: Node, sb: StringBuilder, spans: MutableList<MdSpan>, inLink: Boolean) {
    when (node) {
        is Text -> sb.append(node.literal)
        is HtmlInline -> sb.append(node.literal)
        is SoftLineBreak, is HardLineBreak -> sb.append('\n')
        is Code -> span(sb, spans, SpanKind.CODE, null) { sb.append(node.literal) }
        is Emphasis -> span(sb, spans, SpanKind.EMPHASIS, null) { appendInlines(node, sb, spans, inLink) }
        is StrongEmphasis -> span(sb, spans, SpanKind.STRONG, null) { appendInlines(node, sb, spans, inLink) }
        is Strikethrough -> span(sb, spans, SpanKind.STRIKE, null) { appendInlines(node, sb, spans, inLink) }
        // An inline image is its alt text; inside a link it takes the link's target, so links never overlap.
        is Link, is Image -> {
            val url = if (inLink) null else (if (node is Link) node.destination else (node as Image).destination)?.let(::safeUrl)
            if (url == null) appendInlines(node, sb, spans, inLink) else span(sb, spans, SpanKind.LINK, url) { appendInlines(node, sb, spans, true) }
        }
        else -> appendInlines(node, sb, spans, inLink)
    }
}

/** Appends [body] and a [kind] span over it, placed before any spans inside it so spans stay ordered by start. */
private inline fun span(sb: StringBuilder, spans: MutableList<MdSpan>, kind: SpanKind, url: String?, body: () -> Unit) {
    val start = sb.length
    val at = spans.size
    body()
    if (sb.length > start) spans.add(at, MdSpan(start, sb.length, kind, url))
}
