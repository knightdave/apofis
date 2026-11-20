package xyz.rycerz.apofis.ui.markdown

import androidx.compose.ui.graphics.Color
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
import xyz.rycerz.apofis.ui.chat.blockIndexOf
import xyz.rycerz.apofis.ui.chat.blockSource
import xyz.rycerz.apofis.ui.chat.copySource
import xyz.rycerz.apofis.ui.chat.replyKeyOf
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
        // The long underlined heading: see aLongSetextHeadingShowsAsAHeading, which checks what is shown.
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

    // ---- fix round 2 ----

    /** The text a run of Literal rows shows (cuts fall on dropped line breaks in these inputs). */
    private fun literalText(rows: List<TextRow>) = rows.joinToString("\n") { (it.chunk as DisplayChunk.Literal).text }

    /** Rows of [text] on the live path, fed in steps of [step] characters; checks each step against the stored path. */
    private fun streamed(text: String, step: Int): List<TextRow> {
        val live = ReplyText()
        var rows = emptyList<TextRow>()
        for (end in (step until text.length step step) + text.length) {
            rows = live.update("r1", text.substring(0, end))
            assertEquals("at $end", replyRows("r1", text.substring(0, end)), rows)
        }
        return rows
    }

    @Test
    fun parserInputIsBounded() {
        // One stable loose list of about 60,000 characters: no parser call gets more than 20,000 characters of it.
        val long = ("- item " + "word ".repeat(30) + "\n\n").repeat(400) + "end\n\n"
        Work.largestParse = 0
        val rows = allRows(long)
        assertEquals(streamed(long, 997), rows)
        assertTrue("parsed ${Work.largestParse} characters at once", Work.largestParse <= 20_000)
        assertEquals(long.removeSuffix("\n\nend\n\n"), literalText(rows.dropLast(1)))
        assertEquals(DisplayChunk.Blocks(listOf(para("end"))), rows.last().chunk)

        // Unmatched brackets before many links: the parser would walk every pending bracket per link, so it never runs.
        val brackets = "[".repeat(3000) + "[a](https://x.y) ".repeat(300) + "\n\n"
        val parses = Work.markdownParses
        val bracketRows = allRows(brackets)
        assertEquals(parses, Work.markdownParses)
        assertEquals(brackets.removeSuffix("\n\n"), bracketRows.joinToString("") { (it.chunk as DisplayChunk.Literal).text })
        assertEquals(streamed(brackets, 501), bracketRows)
    }

    /** Inputs that once broke a path, or look like they could. */
    private val adversarial = listOf(
        // Mixed line endings: one scanned table line that a parser splitting on CR reads as a table and a quoted table.
        "| a | b |\n|---|---|\n| 1 | 2 |\r\r> | c | d |\r> |---|---|\r" + "> | 3333333333 | 4444444444 |\r".repeat(200) + "\n\n",
        "| a | b |\r\n|---|---|\r\n| 1 | 2 |\r| 3 | 4 |\n",
        "| a | b |\n|---|---|\n" + "| 1 | 2 |\r".repeat(600) + "\n\n",
        "| a |\n|---|\n" + "|" + "c|".repeat(4100) + "\n\n",
        "a\rb\r\rc\r\n\r\n- x\r\r- y\r",
        "\r\r\r\n\n\r",
        "# Items\n3. first\n\n1. next",
        "<!--\n\n**b** [l](https://x.y)\n-->\nafter",
        "<!-- unterminated\n\n# not a heading\n\n| a |\n|---|\n",
        "<div>\n| a | b |\n|---|---|\n| 1 | 2 |\n</div>",
        "<script>\n\nx **y**\n```\n</script>\n\nafter *e*",
        "`name` | Value\n---|---\n`a` | 1\n",
        "> " + "> ".repeat(300) + "| a |\n|---|\n| 1 |\n",
        "- | a |\n  |---|\n" + "  | 1 |\n".repeat(300),
        "x".repeat(9000) + "\n-\n\n",
        "```\r\n" + "y".repeat(20_000) + "\r\nshort\r\n```\r\n",
        "😀".repeat(800) + " ́" + "é".repeat(800),
        "\uD800 \uDC00 [x](https://\uD800.a/) \u0000 \t|\t|\n|-|-|\n",
        "[".repeat(2000) + "](https://x.y)".repeat(2000),
        "![".repeat(500) + "x" + "](https://u.v)".repeat(500),
        "1. a\n\n   > q\n   > | t |\n   > |---|\n\n2. b",
        "|" + "h|".repeat(150) + "\n|" + "-|".repeat(150) + "\n|c|\n",
        "<div>\n```\nthis stays literal\n```\n[docs](https://example.org)\n</div>",
        "<details>\n<summary>Run it</summary>\n\n```sh\nls\n```\n</details>",
        "<!-- generated -->\n| Name | Status |\n| --- | --- |\n| API | OK |\n",
        "<https://example.org> | Status\n---|---\nx | y\n",
        "text\n<span>x</span>\n```\ncode\n```\n",
        "<a href=\"x\" title='y' data-z=w>\n```\nnot code\n```\n\n```\ncode\n```",
        // Lines of spaces growing token by token in every kind of block.
        "| a |\n|---|\n| 1 |\n      \n     x", "- a\n\n      \n  b\n      ", "<!--\n      \n-->\n    ", "<div>\n     \n<p>\n   ",
        "```\n     \n   ```\n    ", "para\n      \t  \r\n   \r   x",
        "<!--\n" + " ".repeat(2000), "\t<div>\n```sh\necho ok\n```", "\t# a | b\n|---|---|\n| 1 | 2 |\n", "\t> a | b\n|---|---|\n",
        "text\n\t---\n3. a\n\n4. b", "\u00A0<!--\n\n**b**\n-->",
        // Lines longer than the scanner looks at.
        "para\n" + " ".repeat(20_005) + "x\n\nnext", "<!--\n" + "x".repeat(25_000) + "-->\n```\ncode\n```", "| a |\n|---|\n|" + " ".repeat(20_010) + "x\n",
        "<!--\n" + "x".repeat(20_000) + "-->\n\n```sh\necho ok\n```\n\n**bold**",
        "```sh\nkeep\n```" + " ".repeat(20_000) + "not a closer\nstill code\n```",
        "```\nkeep\n```" + " ".repeat(25_000) + "\nafter *e*", "a\n" + " ".repeat(25_000) + "\n# heading",
        "| a |\n|---|\n| 1 |\n" + "x".repeat(20_005) + "| 2 |\n| 3 |\n\n# after", "| a |\n" + "|---".repeat(6_000) + "x\n| 1 |\n\n# after",
        "<b>\n```\nnot\n```\n" + "y".repeat(25_000), "text\n" + "-".repeat(25_000) + "x\n3. a\n\n# after",
    )

    @Test
    fun adversarialInputsNeverThrow() {
        val failures = Work.parseFailures
        for (text in adversarial) {
            val stored = allRows(text)
            val step = maxOf(1, text.length / 300)
            assertEquals(text.take(40), stored, streamed(text, step))
        }
        assertEquals("the literal fallback for a throwing parse was never needed", failures, Work.parseFailures)
        // The mixed line endings case: a table, then a quote whose table is plain text (tables in quotes are text).
        val mixed = allRows(adversarial[0])
        assertEquals(BlockKind.TABLE, mixed[0].kind)
        val quotes = mixed.drop(1).flatMap { (it.chunk as DisplayChunk.Blocks).blocks }
        assertTrue(quotes.size > 1 && quotes.all { q -> q is MdBlock.Quote && q.blocks.all { it is MdBlock.Paragraph } })
    }

    @Test
    fun tableBudgetCountsRowsAsTheParserSplitsThem() {
        // Bare CR ends a line for the parser too: 600 rows are over the 500-row budget, shown literally.
        assertTrue(allRows("| a | b |\n|---|---|\n" + "| 1 | 2 |\r".repeat(600) + "\n\n").all { it.chunk is DisplayChunk.Literal })
        // A body row wider than its header is split whole by the parser before it is cut: its cells count.
        assertTrue(allRows("| a |\n|---|\n" + "|" + "c|".repeat(4100) + "\n\n").all { it.chunk is DisplayChunk.Literal })
        // Within the budgets a wide row is cut to the header, as before.
        val cut = (allRows("| a |\n|---|\n| 1 | 2 | 3 |\n\n").single().chunk as DisplayChunk.TableSlice).table
        assertEquals(listOf(cells("1")), cut.rows)
    }

    @Test
    fun anOrderedListAfterAHeadingContinues() {
        val blocks = shownBlocks(allRows("# Items\n3. first\n\n1. next"))
        assertEquals(MdBlock.Heading(1, plain("Items")), blocks[0])
        val list = blocks[1] as MdBlock.ListBlock
        assertEquals(3 to listOf(listOf(para("first")), listOf(para("next"))), list.start to list.items.map { it.blocks })
        assertEquals(2, blocks.size)
    }

    @Test
    fun aLongSetextHeadingShowsAsAHeading() {
        val rows = allRows("x".repeat(9000) + "\n-\n\n")
        val headings = shownBlocks(rows).map { it as MdBlock.Heading }
        assertTrue(headings.size > 1 && headings.all { it.level == 2 })
        assertEquals("x".repeat(9000), headings.joinToString("") { it.text.text })
    }

    @Test
    fun htmlStaysLiteralAcrossBlankLines() {
        val comment = "<!--\n\n**b** [l](https://x.y)\n-->"
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para(comment)))), allRows(comment).map { it.chunk })
        assertEquals(allRows(comment), streamed(comment, 1))
        val script = "<script>\n\nx **y**\n```\n</script>\n\nafter *e*"
        assertEquals(
            listOf(para("<script>\n\nx **y**\n```\n</script>"), MdBlock.Paragraph(MdText("after e", listOf(MdSpan(6, 7, SpanKind.EMPHASIS))))),
            shownBlocks(allRows(script)),
        )
        val div = "<div>\n| a | b |\n|---|---|\n| 1 | 2 |\n</div>"
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para(div)))), allRows(div).map { it.chunk })
    }

    @Test
    fun aHeaderStartingWithInlineCodeIsATable() {
        val table = (allRows("`name` | Value\n---|---\n`a` | 1\n").single().chunk as DisplayChunk.TableSlice).table
        assertEquals(listOf(MdText("name", listOf(MdSpan(0, 4, SpanKind.CODE))), plain("Value")), table.header)
        assertEquals(listOf(listOf(MdText("a", listOf(MdSpan(0, 1, SpanKind.CODE))), plain("1"))), table.rows)
    }

    @Test
    fun aCutAfterASpaceNeverStrandsACombiningMark() {
        val text = MdText("x".repeat(CHUNK - 2) + " ́" + "y".repeat(800), emptyList())
        val slices = sliceText(text)
        assertEquals(text.text, slices.joinToString("") { it.text })
        for (slice in slices.drop(1)) assertTrue(Character.getType(slice.text[0]) != Character.NON_SPACING_MARK.toInt())
    }

    @Test
    fun aFenceDoesNotOpenInsideAnHtmlBlock() {
        val div = "<div>\n```\nthis stays literal\n```\n[docs](https://example.org)\n</div>"
        assertEquals(listOf(DisplayChunk.Blocks(listOf(para(div)))), allRows(div).map { it.chunk })
        assertEquals(allRows(div), streamed(div, 1))
        // A type 7 tag alone on a line starts an HTML block too, up to the blank line.
        val tag = "<a href=\"x\" title='y' data-z=w>\n```\nnot code\n```\n\n```\ncode\n```"
        assertEquals(
            listOf(DisplayChunk.Blocks(listOf(para("<a href=\"x\" title='y' data-z=w>\n```\nnot code\n```"))), DisplayChunk.Code("code", tag.length - 8, first = true, last = true)),
            allRows(tag).map { it.chunk },
        )
        // But it cannot interrupt a paragraph: there the fence opens as usual.
        val inline = allRows("text\n<span>x</span>\n```\ncode\n```\n")
        assertEquals(listOf(BlockKind.TEXT, BlockKind.CODE), inline.map { it.kind })
        assertEquals(DisplayChunk.Blocks(listOf(para("text\n<span>x</span>"))), inline[0].chunk)
    }

    @Test
    fun aTableMayFollowAnHtmlBlockThatEnded() {
        val rows = allRows("<!-- generated -->\n| Name | Status |\n| --- | --- |\n| API | OK |\n")
        assertEquals(DisplayChunk.Blocks(listOf(para("<!-- generated -->"))), rows[0].chunk)
        assertEquals(MdBlock.Table(cells("Name", "Status"), listOf(Align.START, Align.START), listOf(cells("API", "OK"))), (rows[1].chunk as DisplayChunk.TableSlice).table)
        assertEquals(2, rows.size)
        // An autolink is no HTML block: its line can be a header.
        val link = (allRows("<https://example.org> | Status\n---|---\nx | y\n").single().chunk as DisplayChunk.TableSlice).table
        assertEquals(MdText("https://example.org", listOf(MdSpan(0, 19, SpanKind.LINK, "https://example.org"))), link.header[0])
    }

    @Test
    fun indentedOrTabbedLinesStartNoBlock() {
        // A tab (four columns) before <div> makes it paragraph text, so the fence after it is code.
        for (indent in listOf("\t", "    ", "\u00A0")) {
            val text = "$indent<div>\n```sh\necho ok\n```"
            val rows = allRows(text)
            assertEquals(listOf(BlockKind.TEXT, BlockKind.CODE), rows.map { it.kind })
            assertEquals("sh" to "echo ok", rows[1].lang to (rows[1].chunk as DisplayChunk.Code).text)
            assertEquals(rows, streamed(text, 1))
        }
        // Nor is a tabbed "#" or ">" a heading or quote: the line is a paragraph, and here a table's header.
        for (marker in listOf("#", ">")) {
            val table = (allRows("\t$marker a | b\n|---|---|\n| 1 | 2 |\n").single().chunk as DisplayChunk.TableSlice).table
            assertEquals(cells("$marker a", "b"), table.header)
        }
        // Fences are the app's own cut at any indentation (milestone 1), with a tab as with spaces.
        assertEquals(listOf(BlockKind.CODE), allRows("\t```sh\nls\n\t```\n").map { it.kind })
    }

    @Test
    fun aLongLineDecidesNothingFromItsFirstPartAlone() {
        // An end marker past 20,000 characters still ends the comment: the fence and the bold text after it render.
        val comment = allRows("<!--\n" + "x".repeat(20_000) + "-->\n\n```sh\necho ok\n```\n\n**bold**")
        assertEquals(DisplayChunk.Code("echo ok", 20_016, first = true, last = true, tokens("echo ok", Lang.SHELL)), comment.single { it.kind == BlockKind.CODE }.chunk)
        assertEquals(DisplayChunk.Blocks(listOf(MdBlock.Paragraph(MdText("bold", listOf(MdSpan(0, 4, SpanKind.STRONG)))))), comment.last().chunk)
        // Backticks, 20,000 spaces and text do not close a fence: the line stays code, verbatim, in the one block.
        val text = "```sh\nkeep\n```" + " ".repeat(20_000) + "not a closer\nstill code\n```"
        val code = parseBlocks(text)
        assertEquals(listOf(BlockKind.CODE), code.map { it.kind })
        assertEquals("keep\n```" + " ".repeat(20_000) + "not a closer\nstill code", text.substring(code[0].start, code[0].end))
        assertEquals("sh" to true, code[0].lang to code[0].fenceClosed)
        assertEquals(allRows(text), streamed(text, 997))
    }

    /** The source lines of [text] that no block covers: blank lines and fence lines only. */
    private fun assertNothingDropped(text: String) {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = parseBlocks(t)
        var lineStart = 0
        while (lineStart <= t.length) {
            val nl = t.indexOf('\n', lineStart).let { if (it < 0) t.length else it }
            val covered = blocks.any { it.start <= lineStart && nl <= it.end && it.start < it.end }
            val line = t.substring(lineStart, nl)
            val trimmed = line.trim()
            val opening = trimmed.startsWith("```") && blocks.any { it.kind == BlockKind.CODE && it.start == minOf(nl + 1, t.length) }
            assertTrue("dropped line at $lineStart: ${line.take(60)}", covered || trimmed.isEmpty() || trimmed == "```" || opening)
            lineStart = nl + 1
        }
        // Literal and code rows show their block's source exactly (line breaks at cuts aside).
        val rows = allRows(text)
        for (b in blocks) {
            if (b.kind != BlockKind.CODE && b.mode != BlockMode.LITERAL) continue
            val shown = rows.filter { it.key.startsWith("r1/b${b.index}/") }.map { (it.chunk as? DisplayChunk.Literal)?.text ?: (it.chunk as? DisplayChunk.Code)?.text }
            if (shown.any { it == null }) continue // shown parsed after all (within the parser's limits)
            assertEquals(t.substring(b.start, b.end).replace("\n", ""), shown.joinToString("").replace("\n", ""))
        }
    }

    /** After a block holding a line over 20,000 characters, the rows are those of a fresh parse of what follows. */
    private fun assertLongLinesStayContained(text: String) {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = parseBlocks(t)
        val rows = allRows(text)
        for (b in blocks) {
            if (!b.stable || t.substring(b.start, b.end).split('\n').none { it.length > 20_000 }) continue
            val after = rows.filter { it.key.substringAfter("/b").substringBefore('/').toInt() > b.index }.map { it.kind to shift(it.chunk, b.next) }
            assertEquals(replyRows("r1", t.substring(b.next)).map { it.kind to it.chunk }, after)
        }
    }

    private fun shift(c: DisplayChunk, by: Int) = if (c is DisplayChunk.Code) c.copy(start = c.start - by) else c

    @Test
    fun noSourceIsDroppedAndLongLinesStayContained() {
        for (text in adversarial) {
            assertNothingDropped(text)
            assertLongLinesStayContained(text)
        }
    }
    // ---- Task 3: code blocks, highlighting and Copy ----

    private val lightColors = syntaxColors(false, Color(0xFFF9F9F9), Color(0xFF202020))

    private fun codeChunks(rows: List<TextRow>) = rows.filter { it.kind == BlockKind.CODE }.map { it.chunk as DisplayChunk.Code }

    /** The color [c] shows at each of its characters (null: the base code color). */
    private fun colorsOf(c: DisplayChunk.Code): Array<Color?> {
        val shown = styled(c.highlighted!!, lightColors)
        val out = arrayOfNulls<Color>(c.text.length)
        for (r in shown.spanStyles) for (k in r.start until r.end) out[k] = r.item.color
        return out
    }

    @Test
    fun multilineCommentKeepsColorAcrossChunks() {
        val pre = "val a = 1\n".repeat(130)
        val comment = "/*\n" + "comment line\n".repeat(40) + "*/"
        val body = (pre + comment + "\nval b = 2".repeat(400)).take(4000)
        val text = "```kotlin\n$body\n```"
        val bodyStart = text.indexOf('\n') + 1
        val cs = bodyStart + pre.length
        val ce = cs + comment.length
        val chunks = codeChunks(replyRows("r1", text))
        assertEquals(4000, body.length)
        var crossing = 0
        for (c in chunks) {
            val from = maxOf(cs, c.start)
            val to = minOf(ce, c.start + c.text.length)
            if (from >= to) continue
            crossing++
            val colors = colorsOf(c)
            for (k in from until to) assertEquals("at $k", lightColors[Tone.COMMENT.ordinal], colors[k - c.start])
        }
        assertTrue("the comment crosses a cut: $crossing chunks", crossing >= 2)
        // The live path slices the same whole-block highlight.
        assertEquals(replyRows("r1", text), streamed(text, 97))
    }

    @Test
    fun openFenceIsPlainThenHighlighted() {
        val text = "Intro\n\n```py\n" + "x = 'a' # c\n".repeat(300) + "```\n\nafter " + "word ".repeat(200)
        val close = text.indexOf("```\n\nafter") + 3 // the closing fence is seen from here
        val live = ReplyText()
        var highlights = 0L
        for (end in 1..text.length) {
            val before = Work.highlights
            val rows = live.update("r1", text.substring(0, end))
            highlights += Work.highlights - before
            assertEquals("at $end", replyRows("r1", text.substring(0, end)), rows)
            val code = codeChunks(rows)
            if (end < close) {
                assertTrue("at $end", code.all { it.highlighted == null })
                assertEquals("at $end", 0L, highlights)
            } else {
                assertTrue("at $end", code.size > 1 && code.all { it.highlighted?.text == it.text })
                assertEquals("highlighted once, at $end", 1L, highlights)
            }
        }
        // A stored reply whose fence never closed is shown closed: highlighted, with its bottom edge.
        val stored = ChatRows(ZoneId.of("UTC")).rows(listOf(AssistantReply("r1", emptyList(), text.substring(0, close - 3), 0.0)))
            .filterIsInstance<TextRow>().filter { it.kind == BlockKind.CODE }
        assertTrue(stored.last().fenceClosed && stored.all { (it.chunk as DisplayChunk.Code).highlighted != null })
    }

    @Test
    fun longUnbrokenCodeLineStaysOneRow() {
        for (lang in listOf("", "kotlin")) {
            val line = "x".repeat(5000)
            val code = codeChunks(replyRows("r1", "```$lang\n$line\n```"))
            assertEquals(listOf(line), code.map { it.text })
            assertEquals(lang.isNotEmpty(), code.single().highlighted != null)
        }
    }

    @Test
    fun copySourceIsExact() {
        val body = (1..400).joinToString("\n") { "val v$it = \"s$it\" // $it" }.take(5000)
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        val text = "Para one\nline **two**\n\n```kotlin\n$body\n```\n\n$table\n\nend"
        assertEquals(5000, body.length)
        assertEquals("Para one\nline **two**", blockSource(text, 0))
        assertEquals(body, blockSource(text, 1))
        assertEquals(table, blockSource(text, 2))
        assertEquals("end", blockSource(text, 3))
        assertEquals(null, blockSource(text, 4))
        // Every row of the split code block copies the whole block, through the row key as MessageList does.
        val items = listOf(AssistantReply("r/b1/c0", emptyList(), text, 0.0))
        val rows = ChatRows(ZoneId.of("UTC")).rows(items).filterIsInstance<TextRow>()
        val codeRows = rows.filter { it.kind == BlockKind.CODE }
        assertTrue(codeRows.size > 1)
        for (r in codeRows) assertEquals(body, copySource(items, r.key))
        assertEquals(table, copySource(items, rows.first { it.kind == BlockKind.TABLE }.key))
        assertEquals("r/b1/c0", replyKeyOf(codeRows[1].key))
        assertEquals(1, blockIndexOf(codeRows[1].key))
        assertEquals(-1, blockIndexOf("junk"))
        // Line endings are LF in the source a block copies.
        assertEquals("a\nb", blockSource("```\r\na\r\nb\r\n```", 0))
    }

    /** Code bodies the code path must survive (see HighlightTest for the highlighter alone). */
    private val codeCorpus: List<String> = listOf(
        "", " ", "\t \t", "\n\n", "x".repeat(100_000), "ab\n".repeat(50_000), "Zażółć; Привет; 漢字; مرحبا; שלום",
        "😀👩‍👩‍👧 val 🇵🇱 \"😀\" // 😀", "é vaĺ \"á\"", "\t\tval\tx\t=\t1", "a\u0000b \"\u0000\" // \u0000",
        ("x".repeat(1498) + "😀\n").repeat(4), "x".repeat(19_999) + "😀" + "y".repeat(10), ("é".repeat(749) + "é\n").repeat(3),
        "\"", "'", "`", "\"\"\"", "'''", "/*", "#", "\\", "\"a\\\nb\"", "/* /* */ */", "'''\"\"\"'''", "`\${`}`",
        "/*\"*/\"/*", "#\"\n\"#", "// \"\n\"", "\"a\";".repeat(4000), ";".repeat(19_000), "'a".repeat(5000),
        "/*\n" + "comment\n".repeat(500), "\"\"\"\n" + "text\n".repeat(500),
    )

    private val labels = listOf("kt", "py", "sh", "rust", "js", "c", "rb", "php", "nonsense", "", "😀", "a".repeat(40))

    /** The code rows of [rows] join to their block's source exactly, highlighted text equals it, and Copy is exact. */
    private fun assertCodeExact(text: String, items: List<AssistantReply>, rows: List<TextRow>) {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        for (b in parseBlocks(t).filter { it.kind == BlockKind.CODE }) {
            val mine = rows.filter { it.key.startsWith("r1/b${b.index}/") }
            val chunks = mine.map { it.chunk as DisplayChunk.Code }
            assertTrue(chunks.isNotEmpty())
            var at = b.start
            for (c in chunks) {
                if (c.start == at + 1) assertEquals('\n', t[at]) else assertEquals(at, c.start) // a cut drops one LF, or none
                assertEquals(t.substring(c.start, c.start + c.text.length), c.text)
                c.highlighted?.let { assertEquals(c.text, it.text) }
                at = c.start + c.text.length
            }
            assertEquals(b.end, at)
            for (r in mine) assertEquals(t.substring(b.start, b.end), copySource(items, r.key))
        }
    }

    @Test
    fun codeRowsShowAndCopyTheirSourceExactly() {
        val failures = Work.highlightFailures
        for (body in codeCorpus) for (lang in if (body.length > 30_000) listOf("kt") else labels) {
            for (closer in listOf("\n```", "")) { // closed, and a fence that never closes
                val text = "before\n\n```$lang\n$body$closer\n\nafter"
                val stored = listOf(AssistantReply("r1", emptyList(), text, 0.0))
                assertCodeExact(text, stored, ChatRows(ZoneId.of("UTC")).rows(stored).filterIsInstance<TextRow>())
                val live = listOf(AssistantReply("r1", emptyList(), text, 0.0, live = true))
                assertCodeExact(text, live, ChatRows(ZoneId.of("UTC")).rows(live).filterIsInstance<TextRow>())
            }
        }
        assertEquals("the plain fallback for a failing highlight was never needed", failures, Work.highlightFailures)
    }

    @Test
    fun streamedCodeEqualsFreshAtEveryLength() {
        val text = "Start\n\n```kotlin\nval s = \"\"\"\n/* not a comment */\n\"\"\" // c\n```\n" +
            "```py\n# c\n'''doc\n```\n\n```nonsense\nx\n```\n```sh\necho 'a\n``` not a closer\n```x\n```\n" +
            "```rust title=x.rs\nfn f<'a>() {}\n```\n\nend ```kt\n```c\nint x = 1;"
        val live = ReplyText()
        for (end in 1..text.length) assertEquals("at $end", replyRows("r1", text.substring(0, end)), live.update("r1", text.substring(0, end)))
        val code = codeChunks(live.update("r1", text))
        assertEquals(listOf(true, true, false, true, true, false), code.map { it.highlighted != null })
    }

    @Test
    fun highlightingWorkIsBoundedPerUpdate() {
        val failures = Work.highlightFailures
        for ((size, expected) in listOf(15_000 to 1L, HIGHLIGHT_MAX_CHARS + 5_000 to 0L)) {
            val body = "val x = \"s\" // c\n".repeat(size / 17)
            val text = "```kotlin\n$body```\n\n" + "tail ".repeat(400)
            val live = ReplyText()
            val highlights = Work.highlights
            val steps = Work.highlightSteps
            var end = 0
            while (end < text.length) {
                end = minOf(text.length, end + 7)
                live.update("r1", text.substring(0, end))
                assertTrue("at $end", Work.highlights - highlights <= expected)
            }
            assertEquals(expected, Work.highlights - highlights)
            assertTrue(Work.highlightSteps - steps <= 13L * HIGHLIGHT_MAX_CHARS)
            assertTrue(Work.largestHighlight <= HIGHLIGHT_MAX_CHARS)
        }
        // Each closing costs at most one highlight of at most the limit, however often a closer turns out not to be one.
        val live = ReplyText()
        val highlights = Work.highlights
        var text = "```js\nlet a = 1\n"
        for (k in 1..200) {
            text += "```"
            live.update("r1", text)
            text += "x\nlet b = $k\n"
            live.update("r1", text)
        }
        assertEquals(200L, Work.highlights - highlights)
        assertEquals(failures, Work.highlightFailures)
    }

    // ---- fix round 1 ----

    @Test
    fun copyUsesTheCanonicalLfText() {
        // Ruled: Copy gives the block in the app's canonical text, line endings LF (Task 2 normalizes at the boundary),
        // which is what a terminal or editor expects. Offsets are found in that text, so a CRLF before the block is fine.
        val text = "intro\r\n\r\n```sh\r\na\r\nb\rc\r\n```\r\n\r\n| x | y |\r\n|---|---|\r\n| 1 | 2 |\r\n\r\nend"
        assertEquals("a\nb\nc", blockSource(text, 1))
        assertEquals("| x | y |\n|---|---|\n| 1 | 2 |", blockSource(text, 2))
        val items = listOf(AssistantReply("r1", emptyList(), text, 0.0))
        val rows = ChatRows(ZoneId.of("UTC")).rows(items).filterIsInstance<TextRow>()
        assertEquals("a\nb\nc", copySource(items, rows.first { it.kind == BlockKind.CODE }.key))
        assertEquals("| x | y |\n|---|---|\n| 1 | 2 |", copySource(items, rows.first { it.kind == BlockKind.TABLE }.key))
    }

    /** Row work of the reviewer's four fence transitions after [lines] lines of a fence; checks every result against fresh. */
    private fun transitionWork(lines: Int): List<Triple<Long, Long, Long>> {
        val live = ReplyText()
        var text = "```kotlin\n" + "0\n".repeat(lines)
        live.update("r1", text)
        val snapshots = ArrayList<Pair<String, List<TextRow>>>()
        val work = ArrayList<Triple<Long, Long, Long>>()
        for (append in listOf("```", "x", "\n```", "\n")) {
            text += append
            val copies = Work.rowCopies
            val built = Work.rowsBuilt
            val chunk = Work.chunkSteps
            val rows = live.update("r1", text)
            work += Triple(Work.rowCopies - copies, Work.rowsBuilt - built, Work.chunkSteps - chunk)
            snapshots += text to rows
        }
        for ((t, rows) in snapshots) assertEquals("snapshot of ${t.length}", replyRows("r1", t), rows.toList())
        return work
    }

    @Test
    fun fenceTransitionsCostTheSameAtAnyBlockLength() {
        val small = transitionWork(50_000)
        val large = transitionWork(500_000)
        assertEquals(small, large)
        assertTrue("$small", small.all { it.first <= 2 })
    }
}
