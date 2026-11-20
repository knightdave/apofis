package xyz.rycerz.apofis.ui.markdown

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.ui.chat.CHUNK
import xyz.rycerz.apofis.ui.chat.ChatRows
import xyz.rycerz.apofis.ui.chat.ReplyText
import xyz.rycerz.apofis.ui.chat.TextRow
import xyz.rycerz.apofis.ui.chat.Work
import xyz.rycerz.apofis.ui.chat.parseBlocks
import xyz.rycerz.apofis.ui.chat.replyRows

class MarkdownRowsTest {
    private fun chunksOf(text: String) = displayChunks(text, parseBlocks(text).single())

    private fun assertUniqueKeys(rows: List<TextRow>) = assertEquals(rows.size, rows.map { it.key }.toSet().size)

    /** Every MdBlock shown by [rows], in order (rows of paragraph and list blocks only). */
    private fun shownBlocks(rows: List<TextRow>) = rows.flatMap { (it.chunk as DisplayChunk.Blocks).blocks }

    @Test
    fun spansSurviveDisplayCuts() {
        val text = "word ".repeat(290) + "**strong across the cut " + "more ".repeat(10) + "end**" + " tail".repeat(480)
        assertTrue(text.length in 3900..4100)
        val whole = (parseMarkdown(text).single() as MdBlock.Paragraph).text
        val strong = whole.spans.single { it.kind == SpanKind.STRONG }
        assertTrue(strong.start < CHUNK && strong.end > CHUNK)

        val slices = chunksOf(text).map { ((it as DisplayChunk.Blocks).blocks.single() as MdBlock.Paragraph).text }
        assertTrue(slices.size >= 3)
        var offset = 0
        val joined = ArrayList<MdSpan>()
        for (slice in slices) {
            assertTrue(slice.text.length <= CHUNK)
            for (span in slice.spans) {
                assertTrue(span.start >= 0 && span.end <= slice.text.length && span.start < span.end)
                val shifted = span.copy(start = span.start + offset, end = span.end + offset)
                val previous = joined.lastOrNull()
                // A span cut in two continues across the cut: join the halves.
                if (previous != null && previous.end == offset && shifted.start == offset && previous.kind == shifted.kind && previous.url == shifted.url) {
                    joined[joined.size - 1] = previous.copy(end = shifted.end)
                } else {
                    joined += shifted
                }
            }
            offset += slice.text.length
        }
        assertEquals(whole.text, slices.joinToString("") { it.text })
        assertEquals(whole.spans, joined)
        assertTrue("the strong span is really cut", slices.first().spans.any { it.kind == SpanKind.STRONG && it.end == slices.first().text.length })
    }

    @Test
    fun listNumberingSurvivesCuts() {
        val text = (7 until 207).joinToString("\n") { "$it. item number $it with a few words" } + "\n\n"
        val lists = chunksOf(text).map { (it as DisplayChunk.Blocks).blocks.single() as MdBlock.ListBlock }
        assertTrue(lists.size > 2)
        var next = 7
        for (list in lists) {
            assertEquals(next, list.start)
            assertEquals("item number $next with a few words", (list.items.first().blocks.single() as MdBlock.Paragraph).text.text)
            next += list.items.size
        }
        assertEquals(207, next)
    }

    @Test
    fun looseAndNestedListsMatchFreshParse() {
        val sample = "1. first\n\n   more about first\n\n2. second\n   - nested one\n     - nested two\n       - nested three\n\n" +
            "   back in second\n\n3. third\n\n- [ ] task\n\n- [x] done\n"
        val live = ReplyText()
        for (end in 1..sample.length) {
            val text = sample.substring(0, end)
            val rows = live.update("r1", text)
            assertEquals("at $end", parseMarkdown(text), shownBlocks(rows))
        }
        val blocks = parseMarkdown(sample)
        val ordered = blocks[0] as MdBlock.ListBlock
        assertEquals(3, ordered.items.size) // one loose list, not three lists of one item
        assertEquals(2, ordered.items[0].blocks.size) // the indented paragraph belongs to the first item
        val nested = ordered.items[1].blocks[1] as MdBlock.ListBlock
        assertTrue(((nested.items[0].blocks[1] as MdBlock.ListBlock).items[0].blocks[1]) is MdBlock.ListBlock) // three levels
        assertEquals(listOf(true, true), (blocks[1] as MdBlock.ListBlock).items.map { it.task })
    }

    @Test
    fun tableSlicesShareWidths() {
        val text = "| n | name |\n|---|---|\n" + (1..120).joinToString("") { "| $it | item $it |\n" } + "\n"
        val block = parseBlocks(text).single()
        assertEquals(BlockKind.TABLE, block.kind)
        assertEquals(BlockMode.PARSED, block.mode)
        val slices = displayChunks(text, block).map { it as DisplayChunk.TableSlice }
        assertEquals(3, slices.size)
        assertEquals(listOf(0 to 40, 40 to 80, 80 to 120), slices.map { it.from to it.to })
        assertEquals(listOf(3, 8), slices[0].widths)
        for (s in slices) assertSame(slices[0].widths, s.widths)
        assertEquals(listOf(true, false, false), slices.map { it.first })
        assertEquals(listOf(false, false, true), slices.map { it.last })
    }

    @Test
    fun sparseWideTableIsBounded() {
        val wide = "|" + "h|".repeat(300) + "\n|" + "-|".repeat(300) + "\n" + ("|" + "c|".repeat(300) + "\n").repeat(20) + "\n"
        val tall = "| a | b |\n|---|---|\n" + "| 1 | 2 |\n".repeat(600) + "\n"
        val long = "| a | b |\n|---|---|\n" + ("| " + "y".repeat(100) + " | z |\n").repeat(250) + "\n"
        for (text in listOf(wide, tall, long)) {
            val block = parseBlocks(text).single()
            assertTrue(block.stable)
            assertEquals(BlockKind.TABLE, block.kind)
            assertEquals(BlockMode.LITERAL, block.mode)
            val parses = Work.markdownParses
            val chunks = displayChunks(text, block)
            assertEquals(parses, Work.markdownParses) // never handed to the table parser
            assertTrue(chunks.isNotEmpty() && chunks.all { it is DisplayChunk.Literal })
        }
        val small = parseBlocks("| a | b |\n|---|---|\n| 1 | 2 |\n\n").single()
        assertEquals(BlockKind.TABLE to BlockMode.PARSED, small.kind to small.mode)
    }

    @Test
    fun openBlockOverLimitIsLiteralUntilStable() {
        val para = "word ".repeat(1800) // 9000 characters
        assertEquals(BlockMode.LITERAL, parseBlocks(para).single().mode)
        val live = ReplyText()
        val open = live.update("r1", para)
        assertTrue(open.size > 1 && open.all { it.chunk is DisplayChunk.Literal })
        val closed = live.update("r1", "$para\n\n")
        val block = parseBlocks("$para\n\n").single()
        assertTrue(block.stable)
        assertEquals(BlockMode.PARSED, block.mode)
        assertTrue(closed.size > 1 && closed.all { it.chunk is DisplayChunk.Blocks })
        assertTrue(closed.none { c -> open.any { it === c } })
        assertUniqueKeys(closed)
        assertEquals(replyRows("r1", "$para\n\n"), closed)
        // Growing past the limit without a line break turns the open block LITERAL, as a fresh parse does.
        val growing = ReplyText()
        val start = "word ".repeat(1598) // 7990 characters
        for (n in 0..20) {
            val text = start + "y".repeat(n)
            assertEquals("at ${text.length}", replyRows("r1", text), growing.update("r1", text))
        }
        assertEquals(BlockMode.LITERAL, parseBlocks(start + "y".repeat(20)).single().mode)
    }

    @Test
    fun kindChangeRebuilds() {
        val live = ReplyText()
        val before = live.update("r1", "| a |\n|---|")
        assertEquals(listOf(BlockKind.TEXT), before.map { it.kind })
        val after = live.update("r1", "| a |\n|---|\n| 1 |\n")
        assertEquals(listOf(BlockKind.TABLE), after.map { it.kind })
        assertTrue(after.none { a -> before.any { it === a } })
        assertNotEquals(before, after)
        assertEquals(replyRows("r1", "| a |\n|---|\n| 1 |\n"), after)
    }

    private val sample = "# Title\n\nIntro with **bold** and [a link](https://example.com).\n\n" +
        "word ".repeat(290) + "*emphasis across the cut " + "more ".repeat(10) + "end*" + " tail".repeat(60) + "\n\n" +
        "1. first\n\n   more about first\n\n2. second\n   - nested\n     - deeper\n\n- other\n\n" +
        "| a | b |\n|:-|-:|\n| 1 | 2 |\n| 3 | 4 |\n\n" +
        "> quoted\n> text\n\n" +
        "x".repeat(2000) + "\n\n" +
        "## Code\n\n```kotlin\nval x = 1\n```"

    @Test
    fun liveMatchesFreshAtEveryLength() {
        val live = ReplyText()
        for (end in 1..sample.length) {
            val text = sample.substring(0, end)
            val rows = live.update("r1", text)
            assertEquals("at $end", replyRows("r1", text), rows)
            assertUniqueKeys(rows)
        }
        val rows = live.update("r1", sample)
        val code = rows.last()
        assertEquals(BlockKind.CODE to true, code.kind to code.fenceClosed)
        assertTrue(rows.any { it.chunk is DisplayChunk.TableSlice })
        assertTrue(rows.count { (it.chunk as? DisplayChunk.Blocks)?.blocks?.singleOrNull()?.let { b -> b is MdBlock.Paragraph && b.text.text.all { c -> c == 'x' } } == true } == 2)
    }

    @Test
    fun storedPathMatchesLive() {
        val live = ReplyText()
        for (end in 1..sample.length step 7) live.update("r1", sample.substring(0, end))
        val liveRows = live.update("r1", sample)
        val stored = ChatRows(ZoneId.of("UTC")).rows(listOf(AssistantReply("r1", emptyList(), sample, 0.0))).filterIsInstance<TextRow>()
        assertEquals(liveRows, stored)
    }

    // ---- fix round 1: expected outputs, not only agreement between paths ----

    private fun allRows(text: String) =
        ChatRows(ZoneId.of("UTC")).rows(listOf(AssistantReply("r1", emptyList(), text, 0.0))).filterIsInstance<TextRow>()

    private fun plain(text: String) = MdText(text, emptyList())
    private fun para(text: String) = MdBlock.Paragraph(plain(text))
    private fun cells(vararg t: String) = t.map(::plain)

    @Test
    fun pathologicalTablesNeverOverflowTheStack() {
        val wide = "|" + "h|".repeat(1000) + "\n|" + "-|".repeat(1000) + "\n"
        val almost = "|" + "h|".repeat(1000) + "\n|" + "-|".repeat(1000) + "x\n"
        val overBudget = wide + ("|" + "c|".repeat(1000) + "\n").repeat(10)
        val pipes = "|".repeat(5000) + "\n" + "|".repeat(5000) + "\n"
        val longCell = "| " + "y".repeat(30_000) + " |\n|---|\n| z |\n"
        val dashes = "a|b\n" + "-".repeat(50_000) + "|-\n"
        for (text in listOf(wide, almost, overBudget, pipes, longCell, dashes)) {
            for (t in listOf(text, "$text\n")) {
                allRows(t) // the stored path
                val live = ReplyText()
                for (end in listOf(t.length / 2, t.length - 1, t.length)) live.update("r1", t.substring(0, end)) // the live path
            }
        }
        assertEquals(listOf(BlockKind.TABLE to BlockMode.LITERAL), parseBlocks("$wide\n").map { it.kind to it.mode })
        assertEquals(listOf(BlockKind.TEXT), parseBlocks(almost).map { it.kind })
        assertEquals(listOf(BlockKind.TEXT), parseBlocks(pipes).map { it.kind }) // "||" is no delimiter row
        assertTrue(allRows("$longCell\n").all { it.chunk is DisplayChunk.Literal })
    }

    @Test
    fun tableAppearsOnlyOnceTheScannerAcceptsIt() {
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para("|a|\n|-|")))), allRows("|a|\n|-|").map { it.chunk })
        val table = MdBlock.Table(cells("a"), listOf(Align.START), emptyList())
        assertEquals(listOf(DisplayChunk.TableSlice(table, 0, 0, listOf(1), first = true, last = true)), allRows("|a|\n|-|\n").map { it.chunk })

        val rows = allRows("Intro\n| a | b |\n|:-|-:|\n| 1 | 2 |\nafter")
        assertEquals(listOf(BlockKind.TEXT, BlockKind.TABLE, BlockKind.TEXT), rows.map { it.kind })
        assertEquals(listOf("r1/b0/c0", "r1/b1/c0", "r1/b2/c0"), rows.map { it.key })
        assertEquals(DisplayChunk.Blocks(listOf(para("Intro"))), rows[0].chunk)
        assertEquals(MdBlock.Table(cells("a", "b"), listOf(Align.START, Align.END), listOf(cells("1", "2"))), (rows[1].chunk as DisplayChunk.TableSlice).table)
        assertEquals(DisplayChunk.Blocks(listOf(para("after"))), rows[2].chunk)
    }

    @Test
    fun tablesInsideContainersStayText() {
        val quoted = "> | a | b |\n> |---|---|\n" + "> | 1 | 2 |\n".repeat(700) + "\n"
        val rows = allRows(quoted)
        assertEquals(setOf(BlockKind.TEXT), rows.map { it.kind }.toSet())
        assertTrue(rows.none { it.chunk is DisplayChunk.TableSlice })
        assertBounded(rows)
        assertTrue(allRows("- item\n  | a |\n  |---|\n  | 1 |\n").none { it.chunk is DisplayChunk.TableSlice })
        assertEquals(
            listOf(DisplayChunk.Blocks(listOf(MdBlock.Quote(listOf(para("| a |\n|---|\n| 1 |")))))),
            allRows("> | a |\n> |---|\n> | 1 |\n").map { it.chunk },
        )
    }

    private fun table(rows: Int, header: String = "| a |", delimiter: String = "|---|", row: String = "| 1 |") =
        "$header\n$delimiter\n" + "$row\n".repeat(rows) + "\n"

    @Test
    fun tableBudgetsCountRealRowsAndCells() {
        fun mode(t: String) = parseBlocks(t).single().let { assertEquals(BlockKind.TABLE, it.kind); it.mode }
        assertEquals(BlockMode.PARSED, mode(table(500)))
        assertEquals(BlockMode.LITERAL, mode(table(501)))
        // Ten columns without outer pipes, rows of one cell: every row is padded to ten cells.
        val header = (1..10).joinToString("|") { "h$it" }
        val delimiter = (1..10).joinToString("|") { "-" }
        assertEquals(BlockMode.PARSED, mode(table(399, header, delimiter, "1|"))) // 400 x 10 = 4000 cells
        assertEquals(BlockMode.LITERAL, mode(table(400, header, delimiter, "1|"))) // 4010 cells
        assertTrue(allRows(table(400, header, delimiter, "1|")).all { it.chunk is DisplayChunk.Literal })
    }

    private fun nodes(b: MdBlock): Int = 1 + when (b) {
        is MdBlock.Quote -> b.blocks.sumOf(::nodes)
        is MdBlock.ListBlock -> b.items.sumOf { 1 + it.blocks.sumOf(::nodes) }
        else -> 0
    }

    private fun chars(b: MdBlock): Int = when (b) {
        is MdBlock.Paragraph -> b.text.text.length
        is MdBlock.Heading -> b.text.text.length
        is MdBlock.Quote -> b.blocks.sumOf(::chars)
        is MdBlock.ListBlock -> b.items.sumOf { it.blocks.sumOf(::chars) }
        is MdBlock.Table -> b.header.sumOf { it.text.length } + b.rows.sumOf { r -> r.sumOf { it.text.length } }
        is MdBlock.Image -> b.alt.length
        MdBlock.Rule -> 0
    }

    /** What one lazy row lays out stays small: few blocks and items, few characters, few table cells. */
    private fun assertBounded(rows: List<TextRow>) {
        for (r in rows) {
            when (val c = r.chunk) {
                is DisplayChunk.Blocks -> {
                    assertTrue("${r.key}: ${c.blocks.sumOf(::nodes)} nodes", c.blocks.sumOf(::nodes) <= 120)
                    assertTrue("${r.key}: ${c.blocks.sumOf(::chars)} characters", c.blocks.sumOf(::chars) <= 2 * CHUNK)
                }
                is DisplayChunk.Literal -> assertTrue(c.text.length <= CHUNK)
                is DisplayChunk.TableSlice -> assertTrue((c.to - c.from + 1) * c.widths.size <= 500)
                is DisplayChunk.Code -> Unit
            }
        }
    }

    @Test
    fun shortItemsAndHugeChildrenAreBoundedPerRow() {
        val inputs = listOf(
            "-\n".repeat(3000) + "\ntext",
            "1.\n".repeat(3000) + "\ntext",
            "- " + "word ".repeat(2000) + "\n\n",
            "> " + "quoted line\n> ".repeat(800) + "\n\n",
            "- a\n" + "  -\n".repeat(3000) + "\n",
            "|" + "h|".repeat(90) + "\n|" + "-|".repeat(90) + "\n" + ("|" + "c|".repeat(90) + "\n").repeat(40) + "\n",
        )
        for (text in inputs) assertBounded(allRows(text))
        // Numbering continues across the cuts of a list of empty items.
        val lists = allRows("1.\n".repeat(3000) + "\ntext").mapNotNull { (it.chunk as? DisplayChunk.Blocks)?.blocks?.singleOrNull() as? MdBlock.ListBlock }
        assertTrue(lists.size > 1)
        var next = 1
        for (list in lists) {
            assertEquals(next, list.start)
            next += list.items.size
        }
        assertEquals(3001, next)
    }

    @Test
    fun crlfListsAndSetextHeadingsMatchExpected() {
        val crlf = "1. first\r\n\r\n1.\r\n\r\n1. next"
        val live = ReplyText()
        for (end in 1..crlf.length) {
            val text = crlf.substring(0, end)
            val rows = live.update("r1", text)
            assertEquals("at $end", parseMarkdown(text), shownBlocks(rows))
            assertEquals("at $end", replyRows("r1", text), rows)
        }
        val list = shownBlocks(allRows(crlf)).single() as MdBlock.ListBlock
        assertEquals(1 to 3, list.start to list.items.size)
        assertEquals(listOf(listOf(para("first")), emptyList(), listOf(para("next"))), list.items.map { it.blocks })

        // A setext underline ends a heading; it does not start a list.
        assertEquals(
            listOf(DisplayChunk.Blocks(listOf(MdBlock.Heading(2, plain("Title")))), DisplayChunk.Blocks(listOf(para("next")))),
            allRows("Title\n-\n\nnext").map { it.chunk },
        )
        val long = parseBlocks("x".repeat(9000) + "\n-\n\n").single()
        assertTrue(long.stable)
        assertEquals(BlockMode.PARSED, long.mode)
    }

    @Test
    fun rawHtmlBlocksShowTheirSource() {
        val html = "<div>\n**hi** [site](https://x.y)\n</div>"
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para(html)))), allRows(html).map { it.chunk })
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para("<!-- a [b](https://c.d) -->")))), allRows("<!-- a [b](https://c.d) -->").map { it.chunk })
    }

    @Test
    fun aCutNeverSplitsACharacterFromItsCombiningMark() {
        val text = MdText("x".repeat(CHUNK - 1) + "á" + "y".repeat(800), emptyList())
        val slices = sliceText(text)
        assertEquals(text.text, slices.joinToString("") { it.text })
        for (slice in slices.drop(1)) assertTrue(Character.getType(slice.text[0]) != Character.NON_SPACING_MARK.toInt())
    }
}
