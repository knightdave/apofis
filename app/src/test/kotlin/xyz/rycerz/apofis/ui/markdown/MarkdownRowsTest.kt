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
}
